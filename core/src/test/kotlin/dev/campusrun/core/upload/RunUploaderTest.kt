package dev.campusrun.core.upload

import dev.campusrun.core.api.ApiClient
import dev.campusrun.core.config.ProtocolParams
import dev.campusrun.core.crypto.NetSecKit
import dev.campusrun.core.crypto.Signer
import dev.campusrun.core.session.TimeSync
import dev.campusrun.core.session.Token
import dev.campusrun.core.track.TrackPoint
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.zip.GZIPInputStream

/**
 * Offline tests for the Task 8 pipeline: OBS push ([RunUploader.uploadTrack]) and history
 * read-back ([HistoryClient.latest]) against a MockWebServer (no live network).
 *
 * The flow mirrors upstream `running/client.py` `execute_full_run` steps 4 (credential
 * request -> signed PUT) and the history list read of `history/client.py` `get_run_records`.
 * The gateway payload is asserted to decrypt through the Task 3 NetSecKit envelope
 * (`k` = 128-byte RSA wrap, `d` = AES body, reassembled per the NetSecKit KDoc).
 */
class RunUploaderTest {

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
    fun `uploadTrack follows the upstream OBS flow with a decryptable gateway payload`() {
        val serverMs = 1_750_123_456_789L
        server.dispatcher = flowDispatcher(serverMs)
        val uploader = uploader()
        val track = uploadTrackFixture()
        val token = Token("session-value", "90001", "55001", null)

        val handle = uploader.uploadTrack(token, track)

        // Upstream flow order: GMT sync -> credential request -> signed PUT.
        val gmt = server.takeRequest()
        assertEquals("GET", gmt.method)
        assertEquals("/api/v3/getserver/gmttime", gmt.path)

        // 1. Gateway credential request (running/client.py request_obs_upload_url).
        val gateway = server.takeRequest()
        assertEquals("POST", gateway.method)
        assertEquals("/api/obs/temporary/url", gateway.path)
        assertEquals(token.uid, gateway.getHeader("uid"))
        assertEquals(token.value, gateway.getHeader("token"))
        assertTrue(gateway.getHeader("tokenSign")!!.isNotBlank(), "upstream flow signs headers")
        assertTrue(gateway.getHeader("headerSign")!!.isNotBlank(), "upstream flow signs headers")

        // The NetSecKit envelope: k (RSA wrap, 128 B) || d (AES body) -> decryptable.
        val wire = Json.parseToJsonElement(gateway.body.readByteArray().decodeToString()).jsonObject
        val wrap = Base64.getDecoder().decode(wire.getValue("k").jsonPrimitive.content)
        val body = Base64.getDecoder().decode(wire.getValue("d").jsonPrimitive.content)
        assertEquals(128, wrap.size, "k must be the 128-byte RSA-wrapped AES key")
        val envelope = wrap + body
        val inner = Json.parseToJsonElement(NetSecKit.decrypt(envelope, params).decodeToString()).jsonObject
        val credential = Json.parseToJsonElement(
            String(Base64.getDecoder().decode(inner.getValue("data").jsonPrimitive.content), Charsets.UTF_8),
        ).jsonObject
        assertEquals("iydsj-hbase-hot", credential.getValue("bucketName").jsonPrimitive.content)
        assertEquals("Put", credential.getValue("method").jsonPrimitive.content)
        assertEquals("text/plain", credential.getValue("contentType").jsonPrimitive.content)
        val objectKey = credential.getValue("objectKey").jsonPrimitive.content
        assertTrue(
            Regex("""run_data/\d{10}/[0-9a-f-]{36}\.json""").matches(objectKey),
            "objectKey must follow run_data/{yyyyMMddHH}/{uuid}.json, got $objectKey",
        )
        assertTrue(objectKey.contains(handle.uuid), "objectKey must embed the handle uuid")

        // 2. Signed PUT to OBS (running/client.py upload_to_obs): plain JSON, signed headers.
        val put = server.takeRequest()
        assertEquals("PUT", put.method)
        assertEquals("/obs/store", put.path)
        assertEquals("sig-abc", put.getHeader("X-OBS-Signature"))
        assertEquals("text/plain", put.getHeader("Content-Type"))
        val payload = Json.parseToJsonElement(put.body.readByteArray().decodeToString()).jsonObject
        assertEquals(OBS_PAYLOAD_KEYS, payload.keys, "upstream obs_payload key set")

        assertEquals(token.uid, gunzip(payload, "uid"))
        assertEquals(handle.uuid, gunzip(payload, "uuid"))
        assertEquals("", gunzip(payload, "rrid"))
        assertEquals("[]", gunzip(payload, "segment_json"))
        assertEquals("[]", gunzip(payload, "fixed_point_json"))
        assertEquals("", gunzip(payload, "runFaceCheck"))
        assertEquals("", gunzip(payload, "extension_json"))
        assertEquals("", gunzip(payload, "laps_json"))

        // run_data holds the full track with absolute upstream timestamps.
        val runData = Json.parseToJsonElement(gunzip(payload, "run_data")).jsonArray
        assertEquals(track.size, runData.size)
        val first = runData.first().jsonObject
        val last = runData.last().jsonObject
        assertEquals(track.first().lat, first.getValue("lat").jsonPrimitive.content.toDouble(), 0.0)
        assertEquals(track.first().lng, first.getValue("lon").jsonPrimitive.content.toDouble(), 0.0)
        assertEquals(handle.startTimeMs, first.getValue("time").jsonPrimitive.content.toLong())
        assertEquals(handle.stopTimeMs, last.getValue("time").jsonPrimitive.content.toLong())
        assertEquals("true", first.getValue("isValid").jsonPrimitive.content)
        assertEquals("3.0", first.getValue("accuracy").jsonPrimitive.content)

        // Step/speed bucket JSON matches the settlement arrays (10 s buckets for 60 s).
        val steps = Json.parseToJsonElement(gunzip(payload, "step_freq_json")).jsonArray
        val speeds = Json.parseToJsonElement(gunzip(payload, "speed_json")).jsonArray
        assertEquals(6, steps.size)
        assertEquals(6, speeds.size)
        assertEquals(steps.last().jsonObject.getValue("endTime").jsonPrimitive.content,
            handle.stopTimeMs.toString())

        // Handle: identity for settlement + timestamps spanning durationSec exactly.
        assertEquals(token.uid, handle.uid)
        assertEquals(token.unid, handle.unid)
        val hour = SimpleDateFormat("yyyyMMddHH").format(java.util.Date(handle.startTimeMs))
        assertTrue(objectKey.startsWith("run_data/$hour/"), "objectKey hour must follow startTime")
        assertEquals(60_000L, handle.stopTimeMs - handle.startTimeMs)
    }

    @Test
    fun `uploadTrack fails when the gateway rejects the credential request`() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path!!.startsWith("/api/v3/getserver/gmttime") ->
                    MockResponse().setBody("""{"error":10000,"data":1750123456789}""")
                else -> MockResponse().setBody("""{"error":-10000,"message":"ERROR","data":null}""")
            }
        }
        val uploader = uploader()

        assertThrows(java.io.IOException::class.java) {
            uploader.uploadTrack(Token("session-value", "90001", "55001", null), uploadTrackFixture())
        }
    }

    @Test
    fun `history latest reads the newest record with the session credentials`() {
        server.enqueue(
            MockResponse().setBody(
                """{"error":10000,"data":{"recordList":[""" +
                    """{"rrid":987,"totalDis":2150.0,"totalTime":760,"status":1,"complete":true},""" +
                    """{"rrid":986,"totalDis":1500,"status":1,"complete":false}]}}""",
            ),
        )
        val token = Token("session-value", "90001", "55001", null)

        val record = HistoryClient(api()).latest(token)

        assertEquals(RunRecord("987", 2150, true, null, false), record)
        val request = server.takeRequest()
        assertEquals("/api/v70230/runnings/records", request.path)
        assertEquals("POST", request.method)
        assertEquals(token.value, request.getHeader("token"))
        assertEquals(token.uid, request.getHeader("uid"))
        // Upstream history/client.py get_run_records page query, through the NetSecKit wire.
        val body = decryptPostBody(request)
        assertEquals("1", body.getValue("pageNum").jsonPrimitive.content)
        assertEquals("20", body.getValue("pageSize").jsonPrimitive.content)
        assertEquals("0", body.getValue("sid").jsonPrimitive.content)
        assertEquals("1", body.getValue("type").jsonPrimitive.content)
    }

    @Test
    fun `history latest maps validity exactly like upstream history_record_status`() {
        // 未达标: isValid=false wins even with status 1.
        server.enqueue(
            MockResponse().setBody(
                """{"error":10000,"data":{"recordList":[{"rrid":1,"totalDis":100,"status":1,"isValid":false}]}}""",
            ),
        )
        // 达标: status 1 + complete true.
        server.enqueue(
            MockResponse().setBody(
                """{"error":10000,"data":{"recordList":[{"rrid":2,"totalDis":100,"status":1,"complete":true}]}}""",
            ),
        )
        // Not established: status 1 alone is not 达标 (upstream models.py comment).
        server.enqueue(
            MockResponse().setBody(
                """{"error":10000,"data":{"recordList":[{"rrid":3,"totalDis":100,"status":1}]}}""",
            ),
        )
        val client = HistoryClient(api())

        assertEquals(RunRecord("1", 100, false, null, false), client.latest(TOKEN))
        assertEquals(RunRecord("2", 100, true, null, false), client.latest(TOKEN))
        assertEquals(RunRecord("3", 100, false, null, false), client.latest(TOKEN))
    }

    @Test
    fun `history latest accepts bare list and string envelopes and falls back to distance`() {
        // Bare list envelope + `distance` fallback when totalDis is absent/zero
        // (upstream RunRecordDetail: `totalDis or distance`).
        server.enqueue(
            MockResponse().setBody(
                """{"error":10000,"data":[{"rrid":7,"totalDis":0,"distance":1234.4,"status":1}]}""",
            ),
        )
        // data as a JSON string (upstream parse_history_records json.loads path).
        server.enqueue(
            MockResponse().setBody(
                """{"error":10000,"data":"[{\"rrid\":8,\"totalDis\":100,\"isValid\":true}]"}""",
            ),
        )
        val client = HistoryClient(api())

        assertEquals(RunRecord("7", 1234, false, null, false), client.latest(TOKEN))
        // row 8 has isValid but no `status` -> official verdict unknown (null), never guessed.
        assertEquals(RunRecord("8", 100, true, null, null), client.latest(TOKEN))
    }

    @Test
    fun `history latest parses startTime into the record for the daily gate`() {
        // Three shapes seen/anticipated in the wild (history_dates.py): epoch ms, epoch s,
        // and a yyyy-MM-dd date string; absent -> null, which the daily gate fails closed on.
        server.enqueue(
            MockResponse().setBody(
                """{"error":10000,"data":[{"rrid":11,"totalDis":100,"isValid":true,"startTime":1795057647307}]}""",
            ),
        )
        server.enqueue(
            MockResponse().setBody(
                """{"error":10000,"data":[{"rrid":12,"totalDis":100,"isValid":true,"startTime":1795057647}]}""",
            ),
        )
        server.enqueue(
            MockResponse().setBody(
                """{"error":10000,"data":[{"rrid":13,"totalDis":100,"isValid":true,"startTime":"2026-11-19"}]}""",
            ),
        )
        server.enqueue(
            MockResponse().setBody(
                """{"error":10000,"data":[{"rrid":14,"totalDis":100,"isValid":true}]}""",
            ),
        )
        val client = HistoryClient(api())

        assertEquals(1795057647307L, client.latest(TOKEN)?.startTimeMs, "epoch ms must pass through")
        assertEquals(1795057647000L, client.latest(TOKEN)?.startTimeMs, "epoch seconds must be scaled to ms")
        assertNotNull(client.latest(TOKEN)?.startTimeMs, "a yyyy-MM-dd string must be dated")
        assertNull(client.latest(TOKEN)?.startTimeMs, "a row without startTime stays undated")
    }

    @Test
    fun `history latest returns null on business error or empty history`() {
        server.enqueue(MockResponse().setBody("""{"error":-10000,"message":"ERROR","data":null}"""))
        server.enqueue(MockResponse().setBody("""{"error":10000,"data":{"recordList":[]}}"""))
        server.enqueue(MockResponse().setBody("""{"error":10000,"data":{"recordList":null}}"""))
        server.enqueue(MockResponse().setBody("""{"error":10000,"data":null}"""))
        val client = HistoryClient(api())

        repeat(4) {
            assertEquals(null, client.latest(TOKEN), "case $it must yield no record")
        }
    }

    @Test
    fun `history list returns every page row with objective fields and the official verdict`() {
        // The 7.4.x bare-array envelope (findings §3.1 D): 19-key rows, no summary field.
        server.enqueue(
            MockResponse().setBody(
                """{"error":10000,"data":[""" +
                    """{"rrid":30,"totalDis":2084.4,"totalTime":760,"startTime":1795246200000,"status":1,"complete":true},""" +
                    """{"rrid":29,"totalDis":1500,"totalTime":600,"startTime":1795159800000,"status":1,"complete":false},""" +
                    """{"rrid":28,"totalDis":1000,"startTime":1795073400000}]}""",
            ),
        )
        // Business error -> empty list (same contract as latest -> null).
        server.enqueue(MockResponse().setBody("""{"error":-10000,"message":"ERROR","data":null}"""))

        val entries = HistoryClient(api()).list(TOKEN)

        assertEquals(3, entries.size, "the whole page must come back, not just the newest row")
        assertEquals(
            HistoryEntry(
                id = "30",
                startTimeMs = 1795246200000L,
                distanceM = 2084, // pyRound(2084.4)
                durationSec = 760,
                officialResult = false, // status=1 -> 官方 不达标
            ),
            entries[0],
        )
        assertEquals(1500, entries[1].distanceM)
        assertEquals(false, entries[1].officialResult, "complete=false is never counted")
        assertNull(entries[2].durationSec, "a row without totalTime must stay unknown, not 0")
        assertNull(entries[2].officialResult, "a row without status carries no verdict, never a guess")

        assertEquals(emptyList<HistoryEntry>(), HistoryClient(api()).list(TOKEN), "business error -> no rows")
    }

    @Test
    fun `history latest rejects an envelope without recordList`() {
        server.enqueue(MockResponse().setBody("""{"error":10000,"data":{}}"""))

        assertThrows(IllegalArgumentException::class.java) {
            HistoryClient(api()).latest(TOKEN)
        }
    }

    // --- fixtures and helpers ---

    private fun uploader(): RunUploader {
        val api = ApiClient(params = params)
        return RunUploader(api, NetSecKit, Signer, TimeSync(api))
    }

    private fun api(): ApiClient = ApiClient(params = params)

    private fun uploadTrackFixture(): List<TrackPoint> =
        List(61) { i -> TrackPoint(34.705, 113.7 + i * 0.00002, i, 0.5, 160.0) }

    private fun flowDispatcher(serverMs: Long): Dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = when {
            request.path!!.startsWith("/api/v3/getserver/gmttime") ->
                MockResponse().setBody("""{"error":10000,"data":$serverMs}""")
            request.path!!.startsWith("/api/obs/temporary/url") ->
                MockResponse().setBody(
                    """{"error":10000,"data":{"signedUrl":"${server.url("/obs/store")}",""" +
                        """"actualSignedRequestHeaders":{"Content-Type":"text/plain",""" +
                        """"X-OBS-Signature":"sig-abc"}}}""",
                )
            request.path!!.startsWith("/obs/store") -> MockResponse().setResponseCode(200)
            else -> MockResponse().setResponseCode(404).setBody("""{"error":-1,"message":"no route"}""")
        }
    }

    private fun gunzip(payload: JsonObject, key: String): String =
        GZIPInputStream(
            java.io.ByteArrayInputStream(Base64.getDecoder().decode(payload.getValue(key).jsonPrimitive.content)),
        ).use { it.readBytes().decodeToString() }

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

        val OBS_PAYLOAD_KEYS = setOf(
            "rrid", "uid", "uuid", "run_data", "step_freq_json", "speed_json",
            "segment_json", "fixed_point_json", "runFaceCheck", "extension_json", "laps_json",
        )

        // Upstream core/crypto.py REQ_RSA_PKCS1_B64 (public request key, 1024-bit).
        const val REQUEST_RSA_PUBLIC_KEY =
            "MIGJAoGBALmmqVPOwY1mTVHGEfg7jHck1MNXsVtUfrqY99bm/W2cjLi3LvG/wMYw" +
                "bhmf9O+y3CUlZ5g4AMd2Ly4XbWUloG/O8+1USGJ8ddmrzbl8j2EZi0OhpVCYI381" +
                "+zZ0qa3z1cOuG2XdJnE7pO6Y4JNC37miqMXYmjejVi2QDIbONomfAgMBAAE="
    }
}
