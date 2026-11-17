package dev.campusrun.core.upload

import dev.campusrun.core.config.ProtocolParams
import dev.campusrun.core.policy.LatLng
import dev.campusrun.core.policy.RunPolicy
import dev.campusrun.core.track.TrackPoint
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Offline golden tests for [Settlement.buildRequest] (Task 8 brief Step 1).
 *
 * Golden provenance: `tools/gen-vectors.py` `settlement_build_case` calls the *unmodified*
 * upstream `running/builder.py` (`compute_sport_signature` / `compute_original_sign`) and
 * upstream `consistency.time_buckets` over a fixed synthetic track, so every expected
 * value in `vectors/sign.json` -> `settlement["track-derived-760s"]` is upstream-produced.
 * The test reconstructs the same track/handle/policy from the golden `inputs` block and
 * asserts the produced save/record body against it — field set, per-field rendering,
 * MD5 digest, `originalSign`, the `fivePointJson` envelope and the 10 s buckets.
 */
class SettlementTest {

    private val golden: JsonObject = settlementGolden()

    @Test
    fun `buildRequest pins the 31 settlement fields against the generator golden`() {
        val body = parseBody(Settlement.buildRequest(policy(), track(), handle(), params()))

        val fields = golden.getValue("fields").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(31, fields.size, "golden must carry the upstream 31-field set")
        assertEquals(31, golden.getValue("fieldCount").jsonPrimitive.int)

        // 31 fields complete: every sign key is present in the body ...
        fields.forEach { field ->
            val key = field.substringBefore('=')
            assertTrue(key in body, "save/record body is missing settlement key $key")
            // ... and renders to exactly the golden k=v value (Python str() rendering).
            assertEquals(field.substringAfter('='), pythonStr(body.getValue(key)), "field $key")
        }

        // Digest and originalSign byte-pinned by the upstream builder.
        assertEquals(
            golden.getValue("signature").jsonPrimitive.content,
            body.getValue("signature").jsonPrimitive.content,
            "settlement signature must match compute_sport_signature",
        )
        assertEquals(
            golden.getValue("originalSign").jsonPrimitive.content,
            body.getValue("originalSign").jsonPrimitive.content,
            "originalSign must match compute_original_sign",
        )

        // Derived totals must equal what the generator derived from the same track.
        val derived = golden.getValue("inputs").jsonObject.getValue("derived").jsonObject
        listOf("totalDis", "totalSteps", "avgStepFreq", "speed", "unCompleteReason").forEach {
            assertEquals(
                derived.getValue(it).jsonPrimitive.int,
                body.getValue(it).jsonPrimitive.int,
                "derived field $it",
            )
        }
        assertEquals(
            derived.getValue("complete").jsonPrimitive.content,
            body.getValue("complete").jsonPrimitive.content,
            "derived field complete",
        )

        // fivePointJson envelope (upstream builder.py:214-219) byte-pinned.
        assertEquals(
            golden.getValue("fivePointJson").jsonPrimitive.content,
            body.getValue("fivePointJson").jsonPrimitive.content,
            "fivePointJson envelope",
        )

        // 10 s buckets pinned against upstream time_buckets + model to_dict output.
        val speed = body.getValue("speedPerTenSec").jsonArray
        val steps = body.getValue("stepsPerTenSec").jsonArray
        assertEquals(golden.getValue("bucketCount").jsonPrimitive.int, speed.size)
        assertEquals(golden.getValue("bucketCount").jsonPrimitive.int, steps.size)
        assertEquals(golden.getValue("firstSpeedBucket").jsonObject, speed.first().jsonObject)
        assertEquals(golden.getValue("lastSpeedBucket").jsonObject, speed.last().jsonObject)
        assertEquals(golden.getValue("firstStepBucket").jsonObject, steps.first().jsonObject)
        assertEquals(golden.getValue("lastStepBucket").jsonObject, steps.last().jsonObject)
    }

    @Test
    fun `startTime and stopTime differ by exactly durationSec`() {
        val body = parseBody(Settlement.buildRequest(policy(), track(), handle(), params()))
        val durationSec = golden.getValue("inputs").jsonObject.getValue("track").jsonObject
            .getValue("durationSec").jsonPrimitive.int

        val startTime = body.getValue("startTime").jsonPrimitive.long
        val stopTime = body.getValue("stopTime").jsonPrimitive.long

        assertEquals(durationSec * 1000L, stopTime - startTime, "stop-start must span durationSec")
        assertEquals(durationSec, body.getValue("totalTime").jsonPrimitive.int)
        assertEquals(handle().startTimeMs, startTime)
        assertEquals(handle().stopTimeMs, stopTime)
    }

    @Test
    fun `buckets advance over durationSec and sum to the totals`() {
        val body = parseBody(Settlement.buildRequest(policy(), track(), handle(), params()))
        val startTime = body.getValue("startTime").jsonPrimitive.long
        val stopTime = body.getValue("stopTime").jsonPrimitive.long

        val speed = body.getValue("speedPerTenSec").jsonArray
        val steps = body.getValue("stepsPerTenSec").jsonArray
        assertEquals(startTime, speed.first().jsonObject.beginTime())
        assertEquals(stopTime, speed.last().jsonObject.endTime())
        for (i in 1 until speed.size) {
            assertEquals(
                speed[i - 1].jsonObject.endTime(),
                speed[i].jsonObject.beginTime(),
                "speed buckets must be contiguous",
            )
            assertEquals(
                steps[i - 1].jsonObject.endTime(),
                steps[i].jsonObject.beginTime(),
                "step buckets must be contiguous",
            )
        }

        // Telescoping upstream time_buckets arithmetic: sums hit the run totals exactly.
        val totalSteps = body.getValue("totalSteps").jsonPrimitive.int
        val totalDis = body.getValue("totalDis").jsonPrimitive.int
        assertEquals(
            totalSteps.toLong(),
            steps.fold(0L) { acc, e -> acc + e.jsonObject.getValue("stepsNum").jsonPrimitive.long },
            "bucket step counts must sum to totalSteps",
        )
        val bucketDistance = speed.fold(0.0) { acc, e ->
            acc + e.jsonObject.getValue("distance").jsonPrimitive.content.toDouble()
        }
        assertEquals(totalDis.toDouble(), bucketDistance, 0.01, "bucket distances must sum to totalDis")
    }

    @Test
    fun `short run below selDistance is marked incomplete`() {
        // 100 x 0.00001 deg east ≈ 91 m, far below the selDistance floor of 2000 m.
        val shortTrack = List(101) { TrackPoint(34.705, 113.7 + it * 0.00001, it, 0.1, 150.0) }
        val startTime = 1_750_123_456_000L
        val shortHandle = UploadHandle(
            uid = "90001",
            unid = "55001",
            uuid = "0b9c2f8e-4b6a-4f1e-9d2c-7a5e3f1b8c4d",
            startTimeMs = startTime,
            stopTimeMs = startTime + 100_000L,
        )

        val body = parseBody(Settlement.buildRequest(policy(), shortTrack, shortHandle, params()))

        val totalDis = body.getValue("totalDis").jsonPrimitive.int
        assertTrue(totalDis in 1 until 2000, "short track distance $totalDis must be below selDistance")
        assertEquals(2000, body.getValue("selDistance").jsonPrimitive.int)
        assertEquals(0, body.getValue("selRunTime").jsonPrimitive.int)
        assertFalse(body.getValue("complete").jsonPrimitive.content.toBooleanStrict(), "must be incomplete")
        assertEquals(10, body.getValue("unCompleteReason").jsonPrimitive.int, "upstream builder.py:160")
        assertEquals(100, body.getValue("totalTime").jsonPrimitive.int)
    }

    // --- fixtures reconstructed from the golden `inputs` block (single source of truth) ---

    private fun track(): List<TrackPoint> {
        val spec = golden.getValue("inputs").jsonObject.getValue("track").jsonObject
        val duration = spec.getValue("durationSec").jsonPrimitive.int
        val lat = spec.getValue("lat").jsonPrimitive.content.toDouble()
        val lng0 = spec.getValue("lng0").jsonPrimitive.content.toDouble()
        val lngStep = spec.getValue("lngStep").jsonPrimitive.content.toDouble()
        val speed = spec.getValue("speedMps").jsonPrimitive.content.toDouble()
        val cadence = spec.getValue("cadence").jsonPrimitive.content.toDouble()
        return List(duration + 1) { i -> TrackPoint(lat, lng0 + i * lngStep, i, speed, cadence) }
    }

    private fun handle(): UploadHandle {
        val spec = golden.getValue("inputs").jsonObject.getValue("handle").jsonObject
        return UploadHandle(
            uid = spec.getValue("uid").jsonPrimitive.content,
            unid = spec.getValue("unid").jsonPrimitive.content,
            uuid = spec.getValue("uuid").jsonPrimitive.content,
            startTimeMs = spec.getValue("startTimeMs").jsonPrimitive.long,
            stopTimeMs = spec.getValue("stopTimeMs").jsonPrimitive.long,
        )
    }

    private fun policy(): RunPolicy {
        val points = golden.getValue("inputs").jsonObject.getValue("requiredPoints").jsonArray
        return RunPolicy(
            requiredPoints = points.jsonArray.map {
                LatLng(
                    it.jsonArray[0].jsonPrimitive.content.toDouble(),
                    it.jsonArray[1].jsonPrimitive.content.toDouble(),
                )
            },
            optionalPoints = emptyList(),
            fence = emptyList(),
            paceMinSecPerKm = 0,
            paceMaxSecPerKm = 0,
            validFromHour = 0,
            validToHour = 0,
            maxDistancePerRunM = 0,
            dailyRunLimit = 0,
            faceWindow = null,
        )
    }

    private fun params(): ProtocolParams = ProtocolParams(
        aesKey = "0123456789abcdef".toByteArray(),
        kdfSalt = "user12345:".repeat(4).toByteArray(),
        rsaPublicKey = "unused-for-settlement",
        endpointBase = "https://offline.invalid",
        // The settlement salt the golden was signed with (upstream SPORT_STATIC_SALT,
        // identical to the core sign salt).
        signSalt = golden.getValue("salt").jsonPrimitive.content,
        versionTag = 1,
    )

    /** Python `str(v)` rendering of a body value for the sign field list. */
    private fun pythonStr(value: JsonElement): String = when (value) {
        // Upstream builder.py:177 carries goalId as the literal string "null" in the sign
        // map while the body (line 237) carries JSON null.
        is JsonNull -> "null"
        is JsonPrimitive -> value.content
        else -> throw IllegalArgumentException("sign value must be scalar, got $value")
    }

    private fun parseBody(bytes: ByteArray): JsonObject =
        Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject

    private fun JsonObject.beginTime(): Long = getValue("beginTime").jsonPrimitive.long
    private fun JsonObject.endTime(): Long = getValue("endTime").jsonPrimitive.long

    private fun settlementGolden(): JsonObject {
        val text = checkNotNull(javaClass.classLoader.getResourceAsStream("vectors/sign.json")) {
            "missing vectors/sign.json (run python tools/gen-vectors.py)"
        }.use { it.readBytes().toString(Charsets.UTF_8) }
        return Json.parseToJsonElement(text).jsonObject.getValue("settlement").jsonArray
            .map { it.jsonObject }
            .first { it.getValue("name").jsonPrimitive.content == "track-derived-760s" }
    }
}
