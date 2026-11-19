package dev.campusrun.cli

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Per-install identity (review fix, release blocker): the CLI used to pin both its wire
 * keys (`aesKey`/`kdfSalt`) and its `DeviceId` salt to source constants, so every clone of
 * the binary presented as ONE device carrying ONE set of wire keys. These tests pin the
 * replacement offline, in a temp dir so nothing is written to the real home directory:
 *
 * - first run generates ASCII-hex wire keys (T12 printable rule) and persists them;
 * - a second store over the same file reloads the identical bytes (no mid-run churn);
 * - two installs never share keys or a device salt;
 * - the device salt is stable across loads and is not the old shared constant.
 */
class InstallIdentityTest {

    @TempDir
    lateinit var dir: File

    @Test
    fun `fresh install generates ascii-hex wire keys and persists them`() {
        val first = paramsStoreFor(dir).current()

        assertTrue(
            first.aesKey.size == 16 && first.aesKey.all { it in HEX_CHARS },
            "aesKey must be 16 ASCII-hex bytes (T12 gateway rule), got ${first.aesKey.size}",
        )
        assertTrue(
            first.kdfSalt.size == 40 && first.kdfSalt.all { it in HEX_CHARS },
            "kdfSalt must be 40 ASCII-hex bytes (four 10-byte keyData segments)",
        )
        assertFalse(
            first.aesKey.contentEquals(BUNDLED_AES_KEY),
            "aesKey must be per-install random, not the bundled shared constant",
        )
        assertTrue(File(dir, "params.bin").isFile, "keys must be persisted for the next run")

        val second = paramsStoreFor(dir).current()
        assertArrayEquals(first.aesKey, second.aesKey, "a later run must reload the same aesKey")
        assertArrayEquals(first.kdfSalt, second.kdfSalt, "a later run must reload the same kdfSalt")
    }

    @Test
    fun `two installs never share wire keys`() {
        val other = File(dir, "other-install")
        val mine = paramsStoreFor(dir).current()
        val theirs = paramsStoreFor(other).current()

        assertFalse(
            mine.aesKey.contentEquals(theirs.aesKey),
            "distinct installs must not share the wire aesKey",
        )
        assertFalse(
            mine.kdfSalt.contentEquals(theirs.kdfSalt),
            "distinct installs must not share the wire kdfSalt",
        )
    }

    @Test
    fun `device salt persists per install and is never the shared constant`() {
        val first = loadDeviceSalt(dir)

        assertTrue(first.isNotEmpty(), "a fresh install must generate a device salt")
        assertFalse(
            first.contentEquals(SHARED_CONSTANT_SALT),
            "the salt must be random, not the pre-review shared constant",
        )
        assertTrue(File(dir, "device-salt.bin").isFile, "the salt must be persisted for the next run")

        assertArrayEquals(first, loadDeviceSalt(dir), "a later run must reload the same salt")
        assertNotEquals(
            first.toList(),
            loadDeviceSalt(File(dir, "other-install")).toList(),
            "distinct installs must derive distinct DeviceIds",
        )
    }

    private companion object {
        val HEX_CHARS: Set<Byte> = ("0123456789abcdef".toByteArray()).toSet()

        /** The bundled fallback values — asserted against so per-install randomness is proven. */
        val BUNDLED_AES_KEY = "0123456789abcdef".toByteArray(Charsets.US_ASCII)

        val SHARED_CONSTANT_SALT = "campusrun/cli/device-identity/v1".toByteArray(Charsets.UTF_8)
    }
}
