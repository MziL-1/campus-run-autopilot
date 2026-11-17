package dev.campusrun.core.crypto

import dev.campusrun.core.config.ProtocolParams
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale

/**
 * Request/settlement signatures, ported from upstream `协议/7.3.92`.
 *
 * | Kotlin | Upstream |
 * |---|---|
 * | [headerSign] | `core/client.py` `build_headers` step 2 + `core/crypto.py` `NetSecClient.encrypt_request` |
 * | [tokenSign] | `core/crypto.py` `compute_token_sign` |
 * | [settlementDigest] | `running/builder.py` `compute_sport_signature` (31-field settlement MD5) |
 *
 * ### headerSign
 *
 * Upstream builds the standard headers (`timeStamp`, `nonce`, `uid`, `token`, `tokenSign`,
 * ...), serialises them compactly and encrypts that JSON as the NetSecKit request payload;
 * the resulting `{d,h,k,p,t}` dict, in that key order, is the `headerSign` header value:
 *
 * ```text
 * data      = base64(compactJson(headers))
 * keyData*  = the four 10-byte segments of params.kdfSalt (upstream: random UUID slices)
 * inner     = {"data":..,"keyDataFour":..,"keyDataOne":..,"keyDataThree":..,
 *              "keyDataTwo":..,"platform":2,"timeStamp":<sec>} + '\n'
 * h         = md5(inner)                       (hex, lower case)
 * d         = base64(aes128_cbc(inner, params.aesKey))
 * k         = base64(rsa_pkcs1_v15(params.aesKey))
 * headerSign = {"d":..,"h":..,"k":..,"p":"101","t":"0"}
 * ```
 *
 * The caller must already have put its `tokenSign` (and every other signed header) into
 * [headers]; upstream signs the map *including* `tokenSign`.
 *
 * `encrypt_request` draws the request AES key, the `keyData*` segments and `time.time()`
 * from randomness/the clock. The first two are pinned by [ProtocolParams] (aesKey, kdfSalt),
 * and the inner second-granularity timestamp is derived from `headers["timeStamp"]` (the
 * upstream millisecond header) so the digest stays reproducible across languages — the
 * same forced-determinism deviation Task 3 documented for the PKCS#1 padding.
 *
 * `headers["timeStamp"]` is **required**: upstream `build_headers` always inserts
 * `timeStamp = str(now_ms)` before `encrypt_request` reads `int(time.time())`. Passing a
 * header set without it, or a seconds-shaped value, is a caller bug and fails loudly here
 * instead of producing a self-consistent digest that disagrees with the wire header.
 *
 * ### tokenSign
 *
 * Upstream `build_headers` signs `{timeStamp, token, uid}` (plus any per-request
 * `sign_params`). [tokenSign] has two forms: the map form reproduces that macro verbatim
 * by applying upstream `compute_token_sign` to the caller's dict; the string form is the
 * convenience overload for the single `token` entry — `md5("token=" + token +
 * params.signSalt)`.
 *
 * ### settlementDigest
 *
 * `fields` are upstream `k=v` strings. The list is assembled by the caller (Task 8); this
 * function applies upstream's ordering and salting rules: drop `signature`, sort by
 * `(key.lower(), key)`, join with `&`, append `salt`, MD5 over UTF-8, lower-case hex. Values
 * must arrive already rendered the way Python's `str(v)` renders them upstream (`true`/
 * `false` for bools, `3.0` for floats, ...).
 */
object Signer {

    private const val KEY_DATA_SEGMENT_BYTES = 10
    private const val RSA_WRAP_BYTES = 128
    private const val AES_BLOCK_BYTES = 16
    private const val EPOCH_MILLIS_THRESHOLD = 1_000_000_000_000L
    private const val HEX = "0123456789abcdef"

    fun headerSign(headers: Map<String, String>, params: ProtocolParams): String {
        val raw = headers["timeStamp"]
            ?: throw IllegalArgumentException("headerSign requires the millisecond timeStamp header")
        val millis = raw.toLongOrNull()
            ?: throw IllegalArgumentException("timeStamp header must be integer epoch milliseconds: $raw")
        require(millis >= EPOCH_MILLIS_THRESHOLD) {
            "timeStamp header must be epoch milliseconds, got $raw"
        }
        return requestWire(compactJson(headers), params, millis)
    }

    /**
     * Upstream `NetSecClient.encrypt_request` for an arbitrary plaintext: wraps [plainText]
     * (the headers JSON for `headerSign`, the compact body JSON for a POST) into the
     * `{d,h,k,p,t}` wire JSON. [timeStampMs] yields upstream's inner `int(time.time())`;
     * callers pass the request's millisecond timestamp so header and body envelopes of one
     * request agree.
     */
    internal fun requestWire(plainText: String, params: ProtocolParams, timeStampMs: Long): String {
        require(params.kdfSalt.size == 4 * KEY_DATA_SEGMENT_BYTES) {
            "kdfSalt must hold the four 10-byte keyData segments (40 bytes), got ${params.kdfSalt.size}"
        }
        require(timeStampMs >= EPOCH_MILLIS_THRESHOLD) {
            "timeStamp must be epoch milliseconds, got $timeStampMs"
        }
        val timestampSeconds = timeStampMs / 1000
        val data = Base64.getEncoder().encodeToString(plainText.toByteArray(Charsets.UTF_8))
        // Upstream inner_dict order is keyDataFour, One, Three, Two (core/crypto.py).
        val inner = buildString {
            append("{\"data\":").append(jsonString(data))
            append(",\"keyDataFour\":").append(jsonString(keyData(params, 4)))
            append(",\"keyDataOne\":").append(jsonString(keyData(params, 1)))
            append(",\"keyDataThree\":").append(jsonString(keyData(params, 3)))
            append(",\"keyDataTwo\":").append(jsonString(keyData(params, 2)))
            append(",\"platform\":2")
            append(",\"timeStamp\":").append(timestampSeconds)
            append("}\n")
        }
        val innerBytes = inner.toByteArray(Charsets.UTF_8)
        val h = md5Hex(innerBytes)
        // NetSecKit.encrypt lays out `RSA wrap (128 B) || AES-128-CBC ciphertext`.
        val envelope = NetSecKit.encrypt(innerBytes, params)
        require(envelope.size > RSA_WRAP_BYTES && (envelope.size - RSA_WRAP_BYTES) % AES_BLOCK_BYTES == 0) {
            "unexpected NetSecKit envelope (${envelope.size} bytes)"
        }
        val k = Base64.getEncoder().encodeToString(envelope.copyOfRange(0, RSA_WRAP_BYTES))
        val d = Base64.getEncoder().encodeToString(envelope.copyOfRange(RSA_WRAP_BYTES, envelope.size))
        return "{\"d\":${jsonString(d)},\"h\":${jsonString(h)},\"k\":${jsonString(k)}," +
            "\"p\":\"101\",\"t\":\"0\"}"
    }

    fun tokenSign(token: String, params: ProtocolParams): String =
        tokenSign(mapOf("token" to token), params)

    /**
     * Upstream `build_headers` `sign_dict`: `{timeStamp, token, uid}` plus optional
     * per-request `sign_params`, signed with upstream `compute_token_sign` (same salt and
     * ordering rules as the string overload).
     */
    fun tokenSign(signParams: Map<String, String>, params: ProtocolParams): String =
        canonicalSign(signParams, params.signSalt)

    fun settlementDigest(fields: List<String>, salt: String): String {
        val canonical = fields
            .map { field ->
                val separator = field.indexOf('=')
                require(separator > 0) { "settlement field must be key=value: $field" }
                field.substring(0, separator) to field.substring(separator + 1)
            }
            .filter { (key, _) -> key.lowercase(Locale.ROOT) != "signature" }
            .sortedWith(compareBy({ it.first.lowercase(Locale.ROOT) }, { it.first }))
            .joinToString("&") { (key, value) -> "$key=$value" }
        return md5Hex((canonical + salt).toByteArray(Charsets.UTF_8))
    }

    /**
     * Upstream `compute_token_sign`: case-insensitive key sort with the original key as the
     * tie-breaker, `k=v` joined by `&`, salt appended, MD5 over UTF-8, lower-case hex.
     */
    private fun canonicalSign(signParams: Map<String, String>, salt: String): String {
        val canonical = signParams.entries
            .filter { (key, _) -> key.lowercase(Locale.ROOT) != "signature" }
            .sortedWith(compareBy({ it.key.lowercase(Locale.ROOT) }, { it.key }))
            .joinToString("&") { (key, value) -> "$key=$value" }
        return md5Hex((canonical + salt).toByteArray(Charsets.UTF_8))
    }

    /** Upstream `keyDataOne..Four`: `kdfSalt[10*(n-1):10*n]`, upstream UUID alphabet (ASCII). */
    private fun keyData(params: ProtocolParams, index: Int): String {
        val offset = (index - 1) * KEY_DATA_SEGMENT_BYTES
        return String(params.kdfSalt.copyOfRange(offset, offset + KEY_DATA_SEGMENT_BYTES), Charsets.UTF_8)
    }

    /** `json.dumps(value, ensure_ascii=False)` string escaping, byte-exact for UTF-8 text. */
    private fun jsonString(value: String): String {
        val out = StringBuilder(value.length + 2)
        out.append('"')
        for (ch in value) {
            when (ch) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (ch < ' ') out.append("\\u").append(String.format(Locale.ROOT, "%04x", ch.code)) else out.append(ch)
            }
        }
        return out.append('"').toString()
    }

    /** `json.dumps(dict, separators=(",", ":"), ensure_ascii=False)`, iteration order preserved. */
    private fun compactJson(headers: Map<String, String>): String =
        headers.entries.joinToString(",", "{", "}") { (key, value) -> jsonString(key) + ":" + jsonString(value) }

    private fun md5Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("MD5").digest(bytes)
        val out = StringBuilder(digest.size * 2)
        for (byte in digest) {
            val value = byte.toInt() and 0xFF
            out.append(HEX[value ushr 4]).append(HEX[value and 0x0F])
        }
        return out.toString()
    }
}
