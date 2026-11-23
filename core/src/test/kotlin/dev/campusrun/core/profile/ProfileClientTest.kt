package dev.campusrun.core.profile

import dev.campusrun.core.api.ApiClient
import dev.campusrun.core.config.ProtocolParams
import dev.campusrun.core.session.Token
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Offline tests for the Home profile seam (brief: 当前校园名 after login).
 *
 * The fixture `profile/user_info.json` mirrors the live 7.4.x `GET /api/v70390/user/info`
 * response shape captured in `docs/protocol-7.4-findings.md` §3.1 A (52-key `data`,
 * `campusName` present, `name` = 真名, `nickname` separate) with **PII dummied** —
 * no real uid/phone/name values are stored in the repository.
 */
class ProfileClientTest {

    private lateinit var server: MockWebServer
    private lateinit var params: ProtocolParams

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        params = ProtocolParams(
            aesKey = "0123456789abcdef".toByteArray(),
            kdfSalt = "user12345:".repeat(4).toByteArray(),
            rsaPublicKey = REQUEST_RSA_PUBLIC_KEY,
            endpointBase = server.url("/").toString().trimEnd('/'),
            signSalt = "2slhe02lsfiwowlcixisla_sls-_slaor",
            versionTag = 1,
        )
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `profile fetch parses school name and display name from the user info fixture`() {
        server.enqueue(MockResponse().setBody(fixtureText("user_info.json")))

        val profile = ProfileClient(ApiClient(params = params)).fetch(TOKEN)

        assertEquals("示例大学", profile!!.schoolName, "campusName is the school name")
        assertEquals("示例昵称", profile.displayName, "nickname is preferred over the real name")

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/v70390/user/info", request.path)
        assertEquals(TOKEN.value, request.getHeader("token"), "the profile leg carries the session")
        assertEquals(TOKEN.uid, request.getHeader("uid"))
    }

    @Test
    fun `profile display name stays blank when nickname is absent - no real name leaks to Home`() {
        server.enqueue(
            MockResponse().setBody(
                """{"error":10000,"message":"成功","data":{"name":"真名示例","nickname":"","campusName":"示例大学"}}""",
            ),
        )

        val profile = ProfileClient(ApiClient(params = params)).fetch(TOKEN)

        // Home may show the nickname (user's own, on-device) but NOT the 真名 —
        // the brief blesses school name + nickname only.
        assertEquals("", profile!!.displayName, "a blank nickname must not fall back to the real name")
        assertEquals("示例大学", profile.schoolName)
    }

    @Test
    fun `profile business error or missing data returns null not a crash`() {
        server.enqueue(MockResponse().setBody("""{"error":-10000,"message":"ERROR","data":null}"""))
        server.enqueue(MockResponse().setBody("""{"error":10000,"message":"成功"}"""))

        val client = ProfileClient(ApiClient(params = params))

        assertNull(client.fetch(TOKEN), "a business error reads as 'no profile', like upstream")
        assertNull(client.fetch(TOKEN), "a success envelope without data reads as 'no profile'")
    }

    @Test
    fun `profile response that is not an object is structurally wrong and throws`() {
        server.enqueue(MockResponse().setBody("""[1,2,3]"""))

        assertThrows(IllegalArgumentException::class.java) {
            ProfileClient(ApiClient(params = params)).fetch(TOKEN)
        }
    }

    @Test
    fun `profile parse tolerates the documented field renames`() {
        // Findings §3.1 A: telephone replaced phone/mobile, major is gone — neither is
        // needed for the Home display, and their absence must not break the parse.
        val payload = Json.parseToJsonElement(
            """{"error":10000,"message":"成功","data":{"uid":1,"name":"真名示例",""" +
                """"nickname":"昵称","campusName":"示例大学","telephone":"13800000000"}}""",
        )

        val profile = parseUserProfile(payload)

        assertEquals("示例大学", profile!!.schoolName)
        assertTrue(profile.displayName.isNotBlank())
    }

    private fun fixtureText(name: String): String =
        checkNotNull(javaClass.classLoader.getResourceAsStream("profile/$name")) {
            "missing profile/$name fixture"
        }.use { it.readBytes().toString(Charsets.UTF_8) }

    private companion object {
        val TOKEN = Token("session-value", "90001", "55001", null)

        // Upstream core/crypto.py REQ_RSA_PKCS1_B64 (public request key, 1024-bit).
        const val REQUEST_RSA_PUBLIC_KEY =
            "MIGJAoGBALmmqVPOwY1mTVHGEfg7jHck1MNXsVtUfrqY99bm/W2cjLi3LvG/wMYw" +
                "bhmf9O+y3CUlZ5g4AMd2Ly4XbWUloG/O8+1USGJ8ddmrzbl8j2EZi0OhpVCYI381" +
                "+zZ0qa3z1cOuG2XdJnE7pO6Y4JNC37miqMXYmjejVi2QDIbONomfAgMBAAE="
    }
}
