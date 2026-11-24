package dev.campusrun.core.upload

import dev.campusrun.core.api.ApiClient
import dev.campusrun.core.config.ProtocolParams
import dev.campusrun.core.crypto.NetSecKit
import dev.campusrun.core.session.Token
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Base64

/**
 * Server-side counted semester total (官方 header "计入成绩 X 公里") — the leg the Home
 * progress line prefers over the client-side page sum when available.
 *
 * Live probe evidence (2026-11-24, read-only):
 * - `getPersonalSemesterInfo(runMode=1)` → `{sid:9497, sname:"2026-2027-1", …}`
 * - `recordssummary/semester(sid=9497)` → `{semesterDis:4039, semesterValidDis:4000, …}`
 *   where **4039 = the counted sum of the account's two 官方 达标 rows (2009+2030)**
 *   while the naive five-row page sums to 10129 — i.e. `semesterDis` is the official
 *   计入成绩 total, not a naive distance sum. The official app header corroborates:
 *   on 11-21 (four records, naive 8099 m) it showed "学期累计总里程 2.00 · 计入成绩 2.00"
 *   — counted-only there too.
 * - `recordssummary/semester(sid=0)` → all zeros: the real `sid` from the info leg is
 *   required (sid is semester-scoped, so it must be fetched, never hard-coded).
 */
class SemesterSummaryClientTest {

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
    fun `counted - info leg yields the sid, summary leg yields the counted semesterDis`() {
        // PII-dummied shapes exactly as served (reference/out/rows/*.json).
        server.enqueue(
            MockResponse().setBody(
                """{"error":10000,"message":"成功","data":{"sid":9497,"sname":"2026-2027-1",""" +
                    """"startDate":1788710400000,"endDate":1799596799000,"semesterGoal":0,""" +
                    """"semesterValid":4000,"goalMethod":2}}""",
            ),
        )
        server.enqueue(
            MockResponse().setBody(
                """{"error":10000,"message":"成功","data":{"semesterDis":4039,""" +
                    """"semesterValidDis":4000,"sname":"2026-2027-1","semesterCount":0,""" +
                    """"semesterValidCount":0}}""",
            ),
        )
        val token = Token("session-value", "90001", "55001", null)

        val counted = SemesterSummaryClient(api()).countedMeters(token)

        assertEquals(4039, counted, "semesterDis is the official counted total")
        val info = server.takeRequest()
        assertEquals("/api/v41/running/getPersonalSemesterInfo", info.path)
        assertEquals(token.value, info.getHeader("token"), "credentials are installed at leg entry")
        assertEquals(token.uid, info.getHeader("uid"))
        assertEquals(1, decryptPostBody(info).getValue("runMode").jsonPrimitive.content.toInt())
        val summary = server.takeRequest()
        assertEquals("/api/v55/runnings/recordssummary/semester", summary.path)
        // The REAL sid must be forwarded — sid=0 returns zeros (live probe).
        assertEquals("9497", decryptPostBody(summary).getValue("sid").jsonPrimitive.content)
    }

    @Test
    fun `info leg failure or missing sid - no total, never a guessed zero`() {
        // Business error on the info leg -> null (one request only).
        server.enqueue(MockResponse().setBody("""{"error":-10000,"message":"ERROR","data":null}"""))
        // Success without a sid -> null (the summary leg must not be called with a fake sid).
        server.enqueue(MockResponse().setBody("""{"error":10000,"message":"成功","data":{"sname":"x"}}"""))

        val client = SemesterSummaryClient(api())

        assertNull(client.countedMeters(TOKEN), "business error -> no total")
        assertNull(client.countedMeters(TOKEN), "missing sid -> no total")
        // 2 info calls, 0 summary calls — a missing sid must not trigger a summary call.
        assertEquals(2, server.requestCount, "a missing sid must not trigger a summary call")
    }

    @Test
    fun `summary leg failure or missing semesterDis - null, not zero`() {
        server.enqueue(
            MockResponse().setBody("""{"error":10000,"data":{"sid":9497,"sname":"2026-2027-1"}}"""),
        )
        server.enqueue(MockResponse().setBody("""{"error":-10000,"message":"ERROR","data":null}"""))
        server.enqueue(
            MockResponse().setBody("""{"error":10000,"data":{"sid":9497,"sname":"2026-2027-1"}}"""),
        )
        server.enqueue(MockResponse().setBody("""{"error":10000,"data":{"sname":"2026-2027-1"}}"""))
        val client = SemesterSummaryClient(api())

        assertNull(client.countedMeters(TOKEN), "summary business error -> no total")
        assertNull(client.countedMeters(TOKEN), "missing semesterDis -> no total, never a fabricated 0")
    }

    @Test
    fun `structurally wrong documents throw instead of guessing a total`() {
        server.enqueue(MockResponse().setBody("""[1,2,3]"""))

        assertThrows(IllegalArgumentException::class.java) {
            SemesterSummaryClient(api()).countedMeters(TOKEN)
        }
    }

    // --- fixtures and helpers ---

    private fun api(): ApiClient = ApiClient(params = params)

    /** Reassemble `k || d` (Task 3 envelope) and return the decrypted POST body object. */
    private fun decryptPostBody(request: RecordedRequest): JsonObject {
        val wire = Json.parseToJsonElement(request.body.readByteArray().decodeToString()).jsonObject
        val envelope =
            Base64.getDecoder().decode(wire.getValue("k").jsonPrimitive.content) +
                Base64.getDecoder().decode(wire.getValue("d").jsonPrimitive.content)
        val inner = Json.parseToJsonElement(NetSecKit.decrypt(envelope, params).decodeToString()).jsonObject
        return Json.parseToJsonElement(
            String(Base64.getDecoder().decode(inner.getValue("data").jsonPrimitive.content), Charsets.UTF_8),
        ).jsonObject
    }

    private companion object {
        val TOKEN = Token("session-value", "90001", "55001", null)

        // Upstream core/crypto.py REQ_RSA_PKCS1_B64 (public request key, 1024-bit).
        const val REQUEST_RSA_PUBLIC_KEY =
            "MIGJAoGBALmmqVPOwY1mTVHGEfg7jHck1MNXsVtUfrqY99bm/W2cjLi3LvG/wMYw" +
                "bhmf9O+y3CUlZ5g4AMd2Ly4XbWUloG/O8+1USGJ8ddmrzbl8j2EZi0OhpVCYI381" +
                "+zZ0qa3z1cOuG2XdJnE7pO6Y4JNC37miqMXYmjejVi2QDIbONomfAgMBAAE="
    }
}
