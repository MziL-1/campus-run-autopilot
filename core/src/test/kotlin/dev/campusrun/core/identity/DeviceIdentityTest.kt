package dev.campusrun.core.identity

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DeviceIdentityTest {

    private val salt = "0f3c2a91b7d84e56a1c0d9e8f7a6b5c4".toByteArray()
    private val hints = mapOf(
        "oaid" to "6f1b2c3d-4e5f-6789-abcd-ef0123456789",
        "androidId" to "9774d56d682e549c",
        "model" to "M2101K9C",
    )

    @Test
    fun `same salt and hints produce the same id across instances`() {
        val first = DeviceIdentity(salt, hints).stableId()
        val second = DeviceIdentity(salt, hints).stableId()
        assertEquals(first, second)
        assertTrue(first.matches(Regex("[0-9a-f]{32}")), "id must be a 32-char lower-hex DeviceId: $first")
    }

    @Test
    fun `different salt produces a different id`() {
        val otherSalt = "9a8b7c6d5e4f3a2b1c0d9e8f7a6b5c4d".toByteArray()
        assertNotEquals(
            DeviceIdentity(salt, hints).stableId(),
            DeviceIdentity(otherSalt, hints).stableId(),
        )
    }

    @Test
    fun `empty hints are stable and do not throw`() {
        val first = DeviceIdentity(salt, emptyMap()).stableId()
        val second = DeviceIdentity(salt, emptyMap()).stableId()
        assertEquals(first, second)
        assertNotEquals(first, DeviceIdentity("9a8b7c6d5e4f3a2b1c0d9e8f7a6b5c4d".toByteArray(), emptyMap()).stableId())
    }

    @Test
    fun `hint iteration order does not change the id`() {
        val reversed = hints.entries.reversed().associate { it.key to it.value }
        assertEquals(
            DeviceIdentity(salt, hints).stableId(),
            DeviceIdentity(salt, reversed).stableId(),
        )
    }

    @Test
    fun `empty salt is rejected instead of colliding all installs`() {
        assertThrows(IllegalArgumentException::class.java) {
            DeviceIdentity(ByteArray(0), hints)
        }
    }
}
