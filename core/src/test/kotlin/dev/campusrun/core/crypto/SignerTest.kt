package dev.campusrun.core.crypto

import dev.campusrun.core.config.ProtocolParams
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SignerTest {

    private data class HeaderCase(
        val name: String,
        val headers: Map<String, String>,
        val aesKey: ByteArray,
        val kdfSalt: ByteArray,
        val rsaPublicKey: String,
        val expected: String,
    )

    private data class TokenCase(
        val name: String,
        val token: String,
        val salt: String,
        val expected: String,
    )

    private data class SettlementCase(
        val name: String,
        val fields: List<String>,
        val salt: String,
        val expected: String,
    )

    @Test
    fun `headerSign matches python`() {
        val cases = headerCases()
        assertTrue(cases.size >= 3, "expected at least 3 header vectors, got ${cases.size}")
        cases.forEach { c ->
            val p = params(c.aesKey, c.kdfSalt, c.rsaPublicKey)
            assertEquals(c.expected, Signer.headerSign(c.headers, p), "headerSign ${c.name}")
        }
    }

    @Test
    fun `tokenSign matches python`() {
        val cases = tokenCases()
        assertTrue(cases.size >= 3, "expected at least 3 token vectors, got ${cases.size}")
        cases.forEach { c ->
            val p = params(signSalt = c.salt)
            assertEquals(c.expected, Signer.tokenSign(c.token, p), "tokenSign ${c.name}")
        }
    }

    @Test
    fun `settlementDigest 31-field golden value`() {
        val cases = settlementCases()
        assertTrue(cases.size >= 3, "expected at least 3 settlement vectors, got ${cases.size}")
        assertEquals(31, cases.first { it.name == "31-fields-complete" }.fields.size, "31 fields")
        cases.forEach { c ->
            assertEquals(c.expected, Signer.settlementDigest(c.fields, c.salt), "settlement ${c.name}")
        }
    }

    private fun headerCases(): List<HeaderCase> {
        val root = loadVectors()
        return root.getValue("header").jsonArray.map { element ->
            val obj = element.jsonObject
            val headersJson = obj.getValue("headers").jsonObject
            val order = obj.getValue("headerOrder").jsonArray.map { it.jsonPrimitive.content }
            val headers = LinkedHashMap<String, String>()
            order.forEach { key -> headers[key] = headersJson.getValue(key).jsonPrimitive.content }
            HeaderCase(
                name = obj.string("name"),
                headers = headers,
                aesKey = obj.string("aesKeyHex").hexToBytes(),
                kdfSalt = obj.string("kdfSaltHex").hexToBytes(),
                rsaPublicKey = obj.string("rsaPublicKey"),
                expected = obj.string("headerSign"),
            )
        }
    }

    private fun tokenCases(): List<TokenCase> {
        val root = loadVectors()
        return root.getValue("token").jsonArray.map { element ->
            val obj = element.jsonObject
            TokenCase(
                name = obj.string("name"),
                token = obj.string("token"),
                salt = obj.string("salt"),
                expected = obj.string("sign"),
            )
        }
    }

    private fun settlementCases(): List<SettlementCase> {
        val root = loadVectors()
        return root.getValue("settlement").jsonArray.map { element ->
            val obj = element.jsonObject
            SettlementCase(
                name = obj.string("name"),
                fields = obj.getValue("fields").jsonArray.map { it.jsonPrimitive.content },
                salt = obj.string("salt"),
                expected = obj.string("signature"),
            )
        }
    }

    private fun loadVectors(): JsonObject {
        val text = checkNotNull(javaClass.classLoader.getResourceAsStream("vectors/sign.json")) {
            "missing vectors/sign.json"
        }.use { it.readBytes().toString(Charsets.UTF_8) }
        return Json.parseToJsonElement(text).jsonObject
    }

    private fun params(
        aesKey: ByteArray = "0123456789abcdef".toByteArray(),
        kdfSalt: ByteArray = "user12345:".repeat(4).toByteArray(),
        rsaPublicKey: String = REQUEST_RSA_PUBLIC_KEY,
        signSalt: String = "2slhe02lsfiwowlcixisla_sls-_slaor",
    ) = ProtocolParams(
        aesKey = aesKey,
        kdfSalt = kdfSalt,
        rsaPublicKey = rsaPublicKey,
        endpointBase = "https://run.gxapp.iydsj.com",
        signSalt = signSalt,
        versionTag = 1,
    )

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

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
