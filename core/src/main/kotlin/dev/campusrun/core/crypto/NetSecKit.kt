package dev.campusrun.core.crypto

import dev.campusrun.core.config.ProtocolParams
import java.math.BigInteger
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * NetSecKit 4.2.4 crypto, ported from upstream `协议/7.3.92/core/crypto.py`.
 *
 * Envelope produced by [encrypt] and consumed by [decrypt]:
 *
 * ```text
 * [ RSA PKCS#1 v1.5 wrap of params.aesKey (modulus length, 128 B) ][ AES-128-CBC ciphertext ]
 * ```
 *
 * [decrypt] additionally accepts the upstream server-response shape (`decrypt_response`):
 * raw AES-128-CBC bytes keyed by the Feistel KDF, with no wrapped-key prefix.
 *
 * The three upstream segments are used as-is:
 *  - [aesCbcEncrypt] / [aesCbcDecrypt]: AES-128-CBC, zero IV, PKCS#7 padding
 *    (`aes128_cbc_encrypt` / `aes128_cbc_decrypt`, including upstream's non-validating
 *    pad strip on decrypt).
 *  - [responseKdf]: Feistel-variant response KDF over the four 10-byte keyData segments
 *    (`response_kdf` / `_byte_hash` / `_feistel_transform` / `_final_mix`), round logic
 *    unchanged.
 *  - [wrapAesKey]: RSA PKCS#1 v1.5 key wrapping (`rsa_pkcs1_v15_encrypt`).
 *
 * Upstream fills the PKCS#1 v1.5 padding string from `os.urandom`, which cannot be
 * reproduced by another language. So that tools/gen-vectors.py can pin the whole
 * envelope byte-for-byte, this port derives the padding octets from the response-KDF
 * stream (cyclic, zero octets skipped exactly like upstream's own loop). The PKCS#1
 * block layout `00 02 PS 00 M` is unchanged; only the source of PS is deterministic.
 */
object NetSecKit {

    private const val BLOCK_BYTES = 16
    private const val MODULUS_BYTES = 128
    private const val KEY_DATA_SEGMENT_BYTES = 10

    fun encrypt(plain: ByteArray, params: ProtocolParams): ByteArray {
        val wrappedKey = wrapAesKey(params)
        return wrappedKey + aesCbcEncrypt(plain, params.aesKey)
    }

    fun decrypt(payload: ByteArray, params: ProtocolParams): ByteArray {
        val wrappedKey = wrapAesKey(params)
        val hasWrappedKey = payload.size >= wrappedKey.size &&
            payload.copyOfRange(0, wrappedKey.size).contentEquals(wrappedKey)
        if (hasWrappedKey) {
            require(payload.size > wrappedKey.size && (payload.size - wrappedKey.size) % BLOCK_BYTES == 0) {
                "not a NetSecKit envelope: ${payload.size} bytes"
            }
            return aesCbcDecrypt(payload.copyOfRange(wrappedKey.size, payload.size), params.aesKey)
        }
        // Server response (`decrypt_response`): raw AES-CBC under response_kdf(keyDataOne..Four).
        return aesCbcDecrypt(payload, responseKdf(params.kdfSalt))
    }

    // --- RSA PKCS#1 v1.5 key wrapping (upstream rsa_pkcs1_v15_encrypt) ---

    private fun wrapAesKey(params: ProtocolParams): ByteArray {
        require(params.aesKey.size == BLOCK_BYTES) { "aesKey must be 16 bytes" }
        val (modulus, exponent) = parseRsaPublicKey(params.rsaPublicKey)
        val padding = paddingOctets(params.kdfSalt, MODULUS_BYTES - params.aesKey.size - 3)
        val encoded = ByteArray(MODULUS_BYTES)
        encoded[0] = 0
        encoded[1] = 2
        System.arraycopy(padding, 0, encoded, 2, padding.size)
        encoded[2 + padding.size] = 0
        System.arraycopy(params.aesKey, 0, encoded, 3 + padding.size, params.aesKey.size)
        return toFixedLength(BigInteger(1, encoded).modPow(exponent, modulus), MODULUS_BYTES)
    }

    /**
     * Deterministic PKCS#1 v1.5 padding string: cyclic response-KDF stream, zero octets
     * skipped (upstream's `if b != 0: ps.append(b)` loop does the same when driven by
     * `os.urandom`).
     */
    private fun paddingOctets(kdfSalt: ByteArray, count: Int): ByteArray {
        require(count >= 8) { "aesKey too long for the 1024-bit request key" }
        val seed = responseKdf(kdfSalt)
        require(seed.any { it != 0.toByte() }) { "response KDF produced an all-zero padding seed" }
        val padding = ByteArray(count)
        var filled = 0
        var index = 0
        while (filled < count) {
            val next = seed[index % seed.size]
            index++
            if (next != 0.toByte()) {
                padding[filled] = next
                filled++
            }
        }
        return padding
    }

    /**
     * Upstream reads the bare PKCS#1 `RSAPublicKey` DER at fixed offsets:
     * `30 81 89 02 81 81 00 <128-byte modulus> 02 03 01 00 01`.
     */
    private fun parseRsaPublicKey(base64: String): Pair<BigInteger, BigInteger> {
        val der = Base64.getDecoder().decode(base64)
        // 30 81 89 | 02 81 81 00 <modulus> | 02 03 <exponent>: 7-byte header + modulus + 5-byte trailer.
        require(der.size == 7 + MODULUS_BYTES + 5) {
            "unsupported RSA request key (${der.size} bytes, expected 140)"
        }
        val modulus = BigInteger(1, der.copyOfRange(7, 7 + MODULUS_BYTES))
        val exponent = BigInteger(1, der.copyOfRange(7 + MODULUS_BYTES + 2, der.size))
        return modulus to exponent
    }

    private fun toFixedLength(value: BigInteger, length: Int): ByteArray {
        val raw = value.toByteArray()
        val body = if (raw.size == length + 1 && raw[0] == 0.toByte()) raw.copyOfRange(1, raw.size) else raw
        require(body.size <= length) { "RSA output does not fit in $length bytes" }
        if (body.size == length) return body
        val out = ByteArray(length)
        System.arraycopy(body, 0, out, length - body.size, body.size)
        return out
    }

    // --- Feistel-variant response KDF (upstream response_kdf) ---

    private fun responseKdf(kdfSalt: ByteArray): ByteArray {
        require(kdfSalt.size == 4 * KEY_DATA_SEGMENT_BYTES) {
            "kdfSalt must hold the four 10-byte keyData segments (40 bytes), got ${kdfSalt.size}"
        }
        val hashes = IntArray(4) {
            byteHash(kdfSalt.copyOfRange(it * KEY_DATA_SEGMENT_BYTES, (it + 1) * KEY_DATA_SEGMENT_BYTES))
        }

        // _feistel_transform
        val t = hashes[2] xor hashes[0]
        val mix = Integer.rotateLeft(t, 8) xor Integer.rotateRight(t, 8) xor t
        val newS1 = mix xor hashes[1]
        val newS3 = mix xor hashes[3]
        val t2 = newS3 xor newS1
        val mix2 = Integer.rotateLeft(t2, 8) xor Integer.rotateRight(t2, 8) xor t2
        val newS0 = mix2 xor hashes[0]
        val newS2 = mix2 xor hashes[2]

        // _final_mix, packed little-endian as d, a, b, c
        var a = (newS2 or newS3).inv() xor newS1
        var b = (newS2 xor newS3) xor a
        var c = (a and newS2) xor newS0
        b = b xor c
        a = (b or c).inv() xor a
        val d = (a and b) xor newS3

        val key = ByteArray(BLOCK_BYTES)
        writeLittleEndian(d, key, 0)
        writeLittleEndian(a, key, 4)
        writeLittleEndian(b, key, 8)
        writeLittleEndian(c, key, 12)
        return key
    }

    /** `_byte_hash`: `h = ((b | (h << 8)) ^ (h >>> 24)) & 0xFFFFFFFF`. */
    private fun byteHash(data: ByteArray): Int {
        var h = 0
        for (b in data) {
            h = ((b.toInt() and 0xFF) or (h shl 8)) xor (h ushr 24)
        }
        return h
    }

    private fun writeLittleEndian(value: Int, out: ByteArray, offset: Int) {
        out[offset] = value.toByte()
        out[offset + 1] = (value ushr 8).toByte()
        out[offset + 2] = (value ushr 16).toByte()
        out[offset + 3] = (value ushr 24).toByte()
    }

    // --- AES-128-CBC (upstream aes128_cbc_encrypt / aes128_cbc_decrypt) ---

    private fun aesCbcEncrypt(plain: ByteArray, key: ByteArray): ByteArray {
        require(key.size == BLOCK_BYTES) { "aesKey must be 16 bytes" }
        val padLength = BLOCK_BYTES - plain.size % BLOCK_BYTES
        val padded = plain.copyOf(plain.size + padLength)
        for (i in plain.size until padded.size) {
            padded[i] = padLength.toByte()
        }
        return aesCipher(Cipher.ENCRYPT_MODE, key).doFinal(padded)
    }

    /** Upstream strips the pad whenever the last octet is in 1..16, without validating the run. */
    private fun aesCbcDecrypt(cipherText: ByteArray, key: ByteArray): ByteArray {
        require(key.size == BLOCK_BYTES) { "aesKey must be 16 bytes" }
        require(cipherText.isNotEmpty() && cipherText.size % BLOCK_BYTES == 0) {
            "ciphertext length must be a non-zero multiple of 16"
        }
        val padded = aesCipher(Cipher.DECRYPT_MODE, key).doFinal(cipherText)
        val padLength = padded.last().toInt() and 0xFF
        return if (padLength in 1..BLOCK_BYTES) padded.copyOf(padded.size - padLength) else padded
    }

    private fun aesCipher(mode: Int, key: ByteArray): Cipher =
        Cipher.getInstance("AES/CBC/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), IvParameterSpec(ByteArray(BLOCK_BYTES)))
        }
}
