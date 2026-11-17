package dev.campusrun.core.crypto

import dev.campusrun.core.config.ProtocolParams
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NetSecKitTest {

    private data class VectorCase(
        val name: String,
        val plain: ByteArray,
        val aesKey: ByteArray,
        val kdfSalt: ByteArray,
        val rsaPublicKey: String,
        val wrappedKey: ByteArray,
        val expectedCipher: ByteArray,
        val responsePlain: ByteArray,
        val responseCipher: ByteArray,
    )

    @Test
    fun `netsec roundtrip`() {
        val p = params(
            aesKey = "0123456789abcdef".toByteArray(),
            kdfSalt = "user12345:".repeat(4).toByteArray(),
        )
        val plain = """{"runMode":1,"semesterId":20241}""".toByteArray()
        assertArrayEquals(plain, NetSecKit.decrypt(NetSecKit.encrypt(plain, p), p))
    }

    @Test
    fun `netsec matches python vectors`() {
        val cases = loadVectors("vectors/netsec.json")
        assertTrue(cases.size >= 3, "expected at least 3 vector cases, got ${cases.size}")
        cases.forEach { c ->
            val p = params(c.aesKey, c.kdfSalt, c.rsaPublicKey)
            val envelope = NetSecKit.encrypt(c.plain, p)
            assertArrayEquals(c.wrappedKey, envelope.copyOfRange(0, c.wrappedKey.size), "wrapped key ${c.name}")
            assertArrayEquals(c.expectedCipher, envelope, "case ${c.name}")
            assertArrayEquals(c.plain, NetSecKit.decrypt(c.expectedCipher, p), "decrypt ${c.name}")
            assertArrayEquals(c.responsePlain, NetSecKit.decrypt(c.responseCipher, p), "response ${c.name}")
        }
    }

    private fun params(
        aesKey: ByteArray = "0123456789abcdef".toByteArray(),
        kdfSalt: ByteArray = "user12345:".repeat(4).toByteArray(),
        rsaPublicKey: String = REQUEST_RSA_PUBLIC_KEY,
    ) = ProtocolParams(
        aesKey = aesKey,
        kdfSalt = kdfSalt,
        rsaPublicKey = rsaPublicKey,
        endpointBase = "https://run.gxapp.iydsj.com",
        signSalt = "2slhe02lsfiwowlcixisla_sls-_slaor",
        versionTag = 1,
    )

    private fun loadVectors(resource: String): List<VectorCase> {
        val text = checkNotNull(javaClass.classLoader.getResourceAsStream(resource)) { "missing $resource" }
            .use { it.readBytes().toString(Charsets.UTF_8) }
        val root = Json.parseToJsonElement(text).jsonObject
        return root.getValue("cases").jsonArray.map { element ->
            val obj = element.jsonObject
            VectorCase(
                name = obj.string("name"),
                plain = obj.hex("plainHex"),
                aesKey = obj.hex("aesKeyHex"),
                kdfSalt = obj.hex("kdfSaltHex"),
                rsaPublicKey = obj.string("rsaPublicKey"),
                wrappedKey = obj.hex("wrappedKeyHex"),
                expectedCipher = obj.hex("cipherHex"),
                responsePlain = obj.hex("responsePlainHex"),
                responseCipher = obj.hex("responseCipherHex"),
            )
        }
    }

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

    private fun JsonObject.hex(key: String): ByteArray = string(key).hexToBytes()

    private fun String.hexToBytes(): ByteArray =
        ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private companion object {
        // Upstream core/crypto.py REQ_RSA_PKCS1_B64 (public request key, 1024-bit).
        const val REQUEST_RSA_PUBLIC_KEY =
            "MIGJAoGBALmmqVPOwY1mTVHGEfg7jHck1MNXsVtUfrqY99bm/W2cjLi3LvG/wMYw" +
                "bhmf9O+y3CUlZ5g4AMd2Ly4XbWUloG/O8+1USGJ8ddmrzbl8j2EZi0OhpVCYI381" +
                "+zZ0qa3z1cOuG2XdJnE7pO6Y4JNC37miqMXYmjejVi2QDIbONomfAgMBAAE="
    }
}
