package dev.campusrun.core.config

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException

/**
 * Offline tests for the Task 10 hot-reloadable params store, including the T5 review
 * ruling carried into this task: **per-install random `aesKey`/`kdfSalt` (16 B / 40 B),
 * generated on first use, persisted through a seam, and never shipped or overwritten by
 * remote config** (upstream unwraps the RSA-wrapped key per request — any valid-length
 * key is wire-compatible, pinned shared keys would correlate all installs).
 *
 * `applyRemote` decides "remote newer" by numeric `versionTag` comparison — never data
 * class equality, because `ProtocolParams`' ByteArray fields make `equals` identity-based
 * (T3 watch item). Bundled params stay the fallback whenever persistence is unavailable,
 * so first-run generation can never brick a fresh install.
 */
class ParamsStoreTest {

    @TempDir
    lateinit var tempDir: File

    private val bundled = ProtocolParams(
        aesKey = "bundled-aes-key!".toByteArray(Charsets.US_ASCII), // 16 bytes
        kdfSalt = "bundled-kdf-salt-0123456789abcdefghijklm".toByteArray(Charsets.US_ASCII), // 40 bytes
        rsaPublicKey = "bundled-rsa-public-key",
        endpointBase = "https://bundled.invalid",
        signSalt = "bundled-salt",
        versionTag = 1,
    )

    @Test
    fun `first use generates per-install random keys of 16 and 40 bytes`() {
        val store = ParamsStore(bundled, FileParamsKeyStore(File(tempDir, "keys.bin")))

        val params = store.current()

        assertEquals(16, params.aesKey.size, "aesKey must be AES-128 length")
        assertEquals(40, params.kdfSalt.size, "kdfSalt must be four 10-byte keyData segments")
        assertFalse(params.aesKey.contentEquals(bundled.aesKey), "install keys must not be the bundled pin")
        assertFalse(params.kdfSalt.contentEquals(bundled.kdfSalt), "install keys must not be the bundled pin")
        // Non-key hot-updatable fields start at their bundled values.
        assertEquals(bundled.signSalt, params.signSalt)
        assertEquals(bundled.endpointBase, params.endpointBase)
        assertEquals(bundled.versionTag, params.versionTag)
    }

    @Test
    fun `fresh installs never share keys`() {
        val a = ParamsStore(bundled, FileParamsKeyStore(File(tempDir, "install-a.bin"))).current()
        val b = ParamsStore(bundled, FileParamsKeyStore(File(tempDir, "install-b.bin"))).current()

        assertFalse(a.aesKey.contentEquals(b.aesKey), "per-install randomness, not one global pin")
        assertFalse(a.kdfSalt.contentEquals(b.kdfSalt), "per-install randomness, not one global pin")
    }

    @Test
    fun `keys are deterministic per persistence and stable across store instances`() {
        val file = File(tempDir, "keys.bin")
        val first = ParamsStore(bundled, FileParamsKeyStore(file)).current()
        val second = ParamsStore(bundled, FileParamsKeyStore(file)).current() // fresh instance, same store

        assertArrayEquals(first.aesKey, second.aesKey, "a second store on the same persistence must reload the keys")
        assertArrayEquals(first.kdfSalt, second.kdfSalt, "a second store on the same persistence must reload the keys")
    }

    @Test
    fun `install keys are printable ASCII hex - the gateway rejects non-ASCII keys (T12 live finding)`() {
        val store = ParamsStore(bundled, FileParamsKeyStore(File(tempDir, "ascii.bin")))

        val params = store.current()

        val hexDomain = { b: Byte -> b in 0x30..0x39 || b in 0x61..0x66 }
        assertTrue(params.aesKey.all(hexDomain), "aesKey must stay in [0-9a-f]: ${params.aesKey.decodeToString()}")
        assertTrue(params.kdfSalt.all(hexDomain), "kdfSalt must stay in [0-9a-f]: ${params.kdfSalt.decodeToString()}")
        assertEquals(16, params.aesKey.size)
        assertEquals(40, params.kdfSalt.size)
    }

    @Test
    fun `persisted binary keys from a pre-T12 build are regenerated as ASCII and re-saved`() {
        val file = File(tempDir, "legacy.bin")
        file.writeBytes(ByteArray(56) { 1 }) // right size, every byte non-ASCII
        val logs = mutableListOf<String>()

        val params = ParamsStore(bundled, FileParamsKeyStore(file), log = logs::add).current()

        val hexDomain = { b: Byte -> b in 0x30..0x39 || b in 0x61..0x66 }
        assertTrue(params.aesKey.all(hexDomain) && params.kdfSalt.all(hexDomain), "must not keep binary keys")
        assertTrue(logs.any { it.contains("non-ASCII") }, "migration must be observable: $logs")

        // The regenerated keys were re-saved: a fresh instance reloads them unchanged.
        val second = ParamsStore(bundled, FileParamsKeyStore(file)).current()
        assertArrayEquals(params.aesKey, second.aesKey)
        assertArrayEquals(params.kdfSalt, second.kdfSalt)
    }

    @Test
    fun `remote params with higher version applied without restart`() {
        val store = ParamsStore(bundled, FileParamsKeyStore(File(tempDir, "keys.bin")))
        val before = store.current()

        val applied = store.applyRemote(
            """
            {"versionTag":2,
             "endpointBase":"https://remote.invalid",
             "signSalt":"remote-salt",
             "aesKey":"REMOTE-NEVER-THIS",
             "kdfSalt":"REMOTE-NEVER-THIS-EITHER"}
            """.trimIndent().replace("\n", ""),
        )

        assertTrue(applied, "versionTag 2 > bundled 1 must hot-apply on the same instance")
        val after = store.current()
        assertEquals(2, after.versionTag)
        assertEquals("https://remote.invalid", after.endpointBase)
        assertEquals("remote-salt", after.signSalt)
        assertEquals(
            bundled.rsaPublicKey,
            after.rsaPublicKey,
            "fields the remote doc does not carry keep their current value",
        )
        assertArrayEquals(before.aesKey, after.aesKey, "remote config must NEVER overwrite the install aesKey")
        assertArrayEquals(before.kdfSalt, after.kdfSalt, "remote config must NEVER overwrite the install kdfSalt")
        assertEquals(16, after.aesKey.size)
        assertEquals(40, after.kdfSalt.size)
    }

    @Test
    fun `remote params at or below the current version and malformed docs are rejected`() {
        val store = ParamsStore(bundled, FileParamsKeyStore(File(tempDir, "keys.bin")))

        assertFalse(store.applyRemote("""{"versionTag":1,"signSalt":"same-version"}"""), "equal is not newer")
        assertFalse(store.applyRemote("""{"versionTag":0,"signSalt":"older"}"""), "older is not newer")
        assertFalse(store.applyRemote("""{"signSalt":"no-version-tag"}"""), "versionTag is the gate")
        assertFalse(store.applyRemote("this is not json"), "malformed input must not throw")

        val params = store.current()
        assertEquals(bundled.versionTag, params.versionTag)
        assertEquals(bundled.signSalt, params.signSalt)
    }

    @Test
    fun `persistence failure falls back to bundled keys without bricking`() {
        val broken = object : ParamsKeyStore {
            override fun load(): ByteArray = throw IOException("storage unavailable")
            override fun save(keys: ByteArray): Unit = throw IOException("storage unavailable")
        }

        // No-op log sink: the default stderr sink would only repeat what the dedicated
        // observability test below asserts.
        val params = ParamsStore(bundled, broken, log = {}).current()

        assertArrayEquals(bundled.aesKey, params.aesKey, "bundled values are the fallback when persistence is down")
        assertArrayEquals(bundled.kdfSalt, params.kdfSalt, "bundled values are the fallback when persistence is down")
        assertTrue(params.aesKey.isNotEmpty() && params.kdfSalt.isNotEmpty())
    }

    @Test
    fun `key store failures are logged through the seam instead of failing silently`() {
        // A permanently failing store pins this install to the SHARED bundled key — the
        // exact cross-install correlation risk the per-install ruling exists to prevent,
        // so both failure paths must be observable to callers (UpdateChecker's log seam).
        val logs = mutableListOf<String>()
        val broken = object : ParamsKeyStore {
            override fun load(): ByteArray = throw IOException("storage unavailable")
            override fun save(keys: ByteArray): Unit = throw IOException("storage unavailable")
        }

        val params = ParamsStore(bundled, broken, log = logs::add).current()

        assertArrayEquals(bundled.aesKey, params.aesKey, "fallback behaviour is unchanged by logging")
        assertTrue(logs.any { message -> message.contains("load") }, "load failure must be logged: $logs")
        assertTrue(logs.any { message -> message.contains("save") }, "save failure must be logged: $logs")
    }
}
