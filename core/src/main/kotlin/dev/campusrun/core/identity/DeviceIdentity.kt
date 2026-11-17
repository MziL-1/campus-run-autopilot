package dev.campusrun.core.identity

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * Stable tool-side device identity (spec §5.5-3: "另生成一个本地持久化的工具侧稳定 ID；
 * 所有设备指纹一经生成本地持久化，不频繁变更").
 *
 * The id is a keyed digest of the install salt plus the hardware hints the App can observe
 * (OAID, Android ID, ...). Requirements this encodes:
 *
 *  - **Same inputs, same id** — deterministic across instances and processes, so the tool
 *    never looks like a new device to the server's device counter (churn triggers risk
 *    control).
 *  - **Salt participates** — a different persisted salt yields a different id, so two
 *    installs never collide even with identical or empty hardware hints.
 *  - **Hint order is irrelevant** — keys are canonicalised (sorted, UTF-8, length-prefixed
 *    `key`/`value` fields), so a caller's map iteration order cannot change the id and
 *    `{"ab"="c"}` cannot collide with `{"a"="bc"}`.
 *  - **No hints is valid** — the salt alone still yields a stable id (App may lack OAID
 *    permission); with no hints every install still gets its own id via its own salt.
 *
 * Output format is the upstream `DeviceId` shape: 32 lower-case hex characters
 * (upstream uses `uuid.uuid4().hex`, 16 bytes). The hash is SHA-256 over the canonical
 * encoding, truncated to 16 bytes. The scheme is versioned by the `campusrun/device-
 * identity/v1` domain tag so a future change cannot silently re-derive existing ids.
 *
 * [persistedSalt] must be a non-empty random value generated once and persisted by the
 * caller; it is rejected when empty because an empty salt would make every install share
 * one device id. Hardware hints must be the most stable form the platform exposes and must
 * never be reformatted between runs (any change re-derives the id).
 */
class DeviceIdentity(
    private val persistedSalt: ByteArray,
    private val hardwareHints: Map<String, String>,
) {

    init {
        require(persistedSalt.isNotEmpty()) {
            "persistedSalt must be a non-empty persisted random value"
        }
    }

    fun stableId(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(canonicalEncoding())
        val out = StringBuilder(ID_BYTES * 2)
        for (i in 0 until ID_BYTES) {
            val value = digest[i].toInt() and 0xFF
            out.append(HEX[value ushr 4]).append(HEX[value and 0x0F])
        }
        return out.toString()
    }

    private fun canonicalEncoding(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(DOMAIN)
        writeField(out, persistedSalt)
        hardwareHints.keys.sorted().forEach { key ->
            writeField(out, key.toByteArray(Charsets.UTF_8))
            writeField(out, hardwareHints.getValue(key).toByteArray(Charsets.UTF_8))
        }
        return out.toByteArray()
    }

    /** 4-byte big-endian length prefix, then the UTF-8 bytes (unambiguous concatenation). */
    private fun writeField(out: ByteArrayOutputStream, bytes: ByteArray) {
        out.write(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
        out.write(bytes)
    }

    private companion object {
        const val ID_BYTES = 16
        const val HEX = "0123456789abcdef"
        val DOMAIN = "campusrun/device-identity/v1".toByteArray(Charsets.UTF_8)
    }
}
