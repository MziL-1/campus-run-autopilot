package dev.campusrun.core.upload

import dev.campusrun.core.api.ApiClient
import dev.campusrun.core.config.ProtocolParams
import dev.campusrun.core.session.Token
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Official scoring status per record — the field the Home history badge and the
 * counted progress line read ([HistoryEntry.officialResult] / [RunRecord.officialResult]).
 *
 * Correlation evidence (2026-11-24 live read-only dump, reference/out/rows/, five
 * records with OFFICIAL app verdicts known from the grayscale daily official check):
 *
 * | rrid | 官方 verdict | row `status` | row `complete` |
 * |---|---|---|---|
 * | 1367719089 | 不达标 | 1 | true |
 * | 1369631995 | 不达标 | 1 | true |
 * | 1370876678 | 不达标 | 1 | true |
 * | 1371833451 | 达标 (2.00 km) | 0 | true |
 * | 1373345979 | 达标 (2.03 km) | 0 | true |
 *
 * `status` separates the 5/5 with no overlap; every other list-row key is constant
 * across verdicts (`complete=true`, `validDis==totalDis`, `validTime==totalTime`,
 * `stepFreqStatus=0`, `appealStatus=0`, `type/prize/recordUrl/prizeName=null`).
 * The record-detail endpoint corroborates the polarity: `status=1` rows carry
 * `statusInfo="检测到跑步数据异常，本次成绩无效"` + a failing speed rule
 * (`reasonList[type=12].completeStatus=0`), `status=0` rows carry
 * `statusInfo="太棒了，恭喜您完成了跑步"` + all rules passing.
 *
 * The polarity is INVERTED vs upstream 7.3.92 `history_record_status`
 * (`status==1 && complete==true` → 达标) — that flip is exactly the documented
 * three-way divergence of our `valid` flag (11-17 ours true/官方 不达标;
 * 11-21 & 11-23 ours false/官方 达标).
 */
class HistoryOfficialResultTest {

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
    fun `five real-shaped rows - official verdicts map exactly onto the known grayscale verdicts`() {
        server.enqueue(MockResponse().setBody(FIVE_REAL_ROWS))

        val entries = HistoryClient(api()).list(TOKEN)

        assertEquals(5, entries.size, "the whole page must come back")
        // PII-dummied fixture: ids, distances, times and `status` preserved from the
        // live read-only dump; uid/phone/token never present.
        assertEquals(
            listOf(true, true, false, false, false),
            entries.map { it.officialResult },
            "status=0 rows are 官方 达标, status=1 rows are 官方 不达标 (5/5 correlation)",
        )
        assertEquals(
            listOf("1373345979", "1371833451", "1370876678", "1369631995", "1367719089"),
            entries.map { it.id },
            "fixture row order must be preserved (newest first, as served)",
        )
    }

    @Test
    fun `latest carries the same official result for the newest row`() {
        server.enqueue(MockResponse().setBody(FIVE_REAL_ROWS))

        val record = HistoryClient(api()).latest(TOKEN)

        assertEquals("1373345979", record?.id)
        assertEquals(true, record?.officialResult, "newest row is status=0 -> 官方 达标")
    }

    @Test
    fun `counted distance - only official 达标 rows sum, the three 不达标 rows contribute nothing`() {
        server.enqueue(MockResponse().setBody(FIVE_REAL_ROWS))

        val entries = HistoryClient(api()).list(TOKEN)

        val countedM = entries.filter { it.officialResult == true }.sumOf { it.distanceM }
        assertEquals(4039, countedM, "counted = 2009 + 2030 (the two 官方 达标 rows only)")
        val allM = entries.sumOf { it.distanceM }
        assertEquals(10129, allM, "sanity: the full page sums to the documented 10.13 km")
        assertTrue(countedM < allM, "counted must be strictly below the naive total")
    }

    @Test
    fun `row without status - official result stays unknown, never a guess`() {
        server.enqueue(
            MockResponse().setBody(
                """{"error":10000,"data":[{"rrid":44,"totalDis":2000,"complete":true}]}""",
            ),
        )
        // complete=false with no status: incomplete -> not counted (upstream parity),
        // still not an official verdict claim about scoring.
        server.enqueue(
            MockResponse().setBody(
                """{"error":10000,"data":[{"rrid":45,"totalDis":100,"complete":false}]}""",
            ),
        )
        // Legacy 7.3.92-era shape: isValid present, status absent -> unknown here
        // (no 7.4.x official evidence ties isValid to the verdict).
        server.enqueue(
            MockResponse().setBody(
                """{"error":10000,"data":[{"rrid":46,"totalDis":100,"isValid":true}]}""",
            ),
        )
        val client = HistoryClient(api())

        assertNull(client.latest(TOKEN)?.officialResult, "no status -> unknown, not a verdict")
        assertEquals(false, client.latest(TOKEN)?.officialResult, "complete=false -> not counted")
        assertNull(client.latest(TOKEN)?.officialResult, "legacy isValid alone -> unknown")
    }

    @Test
    fun `status polarity - 1 is 不达标 and 0 is 达标 regardless of complete`() {
        server.enqueue(
            MockResponse().setBody("""{"error":10000,"data":[{"rrid":51,"status":1,"complete":true}]}"""),
        )
        server.enqueue(
            MockResponse().setBody("""{"error":10000,"data":[{"rrid":52,"status":0,"complete":true}]}"""),
        )
        server.enqueue(
            MockResponse().setBody("""{"error":10000,"data":[{"rrid":53,"status":1,"complete":false}]}"""),
        )
        server.enqueue(
            MockResponse().setBody("""{"error":10000,"data":[{"rrid":54,"status":0,"complete":false}]}"""),
        )
        // Unknown status value -> unknown (never silently coerced to a verdict).
        server.enqueue(
            MockResponse().setBody("""{"error":10000,"data":[{"rrid":55,"status":7,"complete":true}]}"""),
        )
        val client = HistoryClient(api())

        assertEquals(false, client.latest(TOKEN)?.officialResult, "status=1 is the 官方 anomaly verdict")
        assertEquals(true, client.latest(TOKEN)?.officialResult, "status=0 is the 官方 normal verdict")
        assertEquals(false, client.latest(TOKEN)?.officialResult, "incomplete is never counted")
        assertEquals(false, client.latest(TOKEN)?.officialResult, "incomplete is never counted, even with status=0")
        assertNull(client.latest(TOKEN)?.officialResult, "an unrecognised status must stay unknown")
    }

    @Test
    fun `the divergent valid flag and officialResult are independent fields`() {
        // The exact 11-17 divergence row: our `valid` says true (status==1 && complete),
        // the official verdict is 不达标 -> officialResult=false.
        server.enqueue(
            MockResponse().setBody(
                """{"error":10000,"data":[{"rrid":1367719089,"totalDis":2030,"status":1,"complete":true}]}""",
            ),
        )
        // The exact 11-21/11-23 divergence row: our `valid` says false (status!=1),
        // the official verdict is 达标 -> officialResult=true.
        server.enqueue(
            MockResponse().setBody(
                """{"error":10000,"data":[{"rrid":1373345979,"totalDis":2030,"status":0,"complete":true}]}""",
            ),
        )
        val client = HistoryClient(api())

        val overestimate = client.latest(TOKEN)!!
        assertTrue(overestimate.valid, "our divergent flag keeps its old semantics")
        assertEquals(false, overestimate.officialResult, "official verdict comes from status, not from valid")

        val underestimate = client.latest(TOKEN)!!
        assertFalse(underestimate.valid, "our divergent flag keeps its old semantics")
        assertEquals(true, underestimate.officialResult, "official verdict comes from status, not from valid")
    }

    // --- fixtures and helpers ---

    /**
     * The five real rows as served 2026-11-24 (read-only dump, reference/out/rows/
     * list_rows.json), PII-dummied: `uuid` replaced by fixed dummies, `themeImg`
     * blanked; ids, distances, timestamps, `status`/`complete` and every other
     * scoring-relevant value preserved verbatim.
     */
    private fun api(): ApiClient = ApiClient(params = params)

    @org.junit.jupiter.api.Test
    fun `row still parses the objective fields alongside officialResult`() {
        server.enqueue(MockResponse().setBody(FIVE_REAL_ROWS))

        val first = HistoryClient(api()).list(TOKEN).first()

        assertEquals("1373345979", first.id)
        assertEquals(2030, first.distanceM)
        assertEquals(760, first.durationSec)
        assertEquals(1795410776000L, first.startTimeMs)
        assertEquals(true, first.officialResult)
    }

    private companion object {
        val TOKEN = Token("session-value", "90001", "55001", null)

        const val REQUEST_RSA_PUBLIC_KEY =
            "MIGJAoGBALmmqVPOwY1mTVHGEfg7jHck1MNXsVtUfrqY99bm/W2cjLi3LvG/wMYw" +
                "bhmf9O+y3CUlZ5g4AMd2Ly4XbWUloG/O8+1USGJ8ddmrzbl8j2EZi0OhpVCYI381" +
                "+zZ0qa3z1cOuG2XdJnE7pO6Y4JNC37miqMXYmjejVi2QDIbONomfAgMBAAE="

        /** See class KDoc — the correlation table this fixture pins. */
        const val FIVE_REAL_ROWS =
            """{"error":10000,"data":[""" +
                """{"rrid":1373345979,"uuid":"dummy-uuid-0001","sportType":1,"totalTime":760,"totalDis":2030.0,""" +
                """"startTime":1795410776000,"stopTime":1795411536000,"complete":true,"recordUrl":null,""" +
                """"prizeName":null,"status":0,"avgStepFreq":166,"appealStatus":0,"stepFreqStatus":0,""" +
                """"validDis":2030.0,"validTime":760,"themeImg":"","type":null,"prize":null},""" +
                """{"rrid":1371833451,"uuid":"dummy-uuid-0002","sportType":1,"totalTime":700,"totalDis":2009.0,""" +
                """"startTime":1795250746000,"stopTime":1795251446000,"complete":true,"recordUrl":null,""" +
                """"prizeName":null,"status":0,"avgStepFreq":166,"appealStatus":0,"stepFreqStatus":0,""" +
                """"validDis":2009.0,"validTime":700,"themeImg":"","type":null,"prize":null},""" +
                """{"rrid":1370876678,"uuid":"dummy-uuid-0003","sportType":1,"totalTime":760,"totalDis":2030.0,""" +
                """"startTime":1795152386000,"stopTime":1795153146000,"complete":true,"recordUrl":null,""" +
                """"prizeName":null,"status":1,"avgStepFreq":167,"appealStatus":0,"stepFreqStatus":0,""" +
                """"validDis":2030.0,"validTime":760,"themeImg":"","type":null,"prize":null},""" +
                """{"rrid":1369631995,"uuid":"dummy-uuid-0004","sportType":1,"totalTime":760,"totalDis":2030.0,""" +
                """"startTime":1795056737000,"stopTime":1795057497000,"complete":true,"recordUrl":null,""" +
                """"prizeName":null,"status":1,"avgStepFreq":166,"appealStatus":0,"stepFreqStatus":0,""" +
                """"validDis":2030.0,"validTime":760,"themeImg":"","type":null,"prize":null},""" +
                """{"rrid":1367719089,"uuid":"dummy-uuid-0005","sportType":1,"totalTime":760,"totalDis":2030.0,""" +
                """"startTime":1794908694000,"stopTime":1794909454000,"complete":true,"recordUrl":null,""" +
                """"prizeName":null,"status":1,"avgStepFreq":165,"appealStatus":0,"stepFreqStatus":0,""" +
                """"validDis":2030.0,"validTime":760,"themeImg":"","type":null,"prize":null}]}"""
    }
}
