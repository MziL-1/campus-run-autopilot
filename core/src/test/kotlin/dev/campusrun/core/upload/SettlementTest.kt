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

    /**
     * Longitude step (degrees) of the canonical 2030 m / 760 s straight track used by the
     * pace regression test: 760 haversine segments at lat 34.705 sum to exactly 2030 m
     * (`2 * 6371000 * atan2(sqrt(h), sqrt(1-h))` per segment, h = cos²lat · sin²(Δλ/2)).
     */
    private val canonicalLngStep = 2.9219703506642138e-05

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

    /**
     * Regression for the 不达标 defect (.superpowers/sdd/pace-diagnosis.md): the platform
     * reads the wire `speed` as 平均配速 — it renders `speed / 1000` in 分/km and checks it
     * against `speedTop 3.0 … speedBottom 10.0` (= [RunPolicy.paceMinSecPerKm] …
     * [RunPolicy.paceMaxSecPerKm] / 60). Upstream `builder.py:162` computes `speed` as
     * average SPEED (m/s ×1000) → 2671 → 2.671 分/km < 3.0 → 配速 rule fails → 成绩无效
     * for every real record, while the true pace 6'14" would pass.
     */
    @Test
    fun `speed field is pace in min per km so the platform pace window accepts it`() {
        // Canonical live run shape: the default 2030 m / 760 s track the three不达标
        // records used (RunScreen default; diagnosis §2). Straight east line whose
        // 760 haversine segments of canonicalLngStep degrees sum to exactly 2030 m.
        val track = List(761) { i ->
            TrackPoint(34.705, 113.7 + i * canonicalLngStep, i, 2.73, 156.0)
        }
        val startMs = 1_750_123_456_000L
        val canonicalHandle = UploadHandle(
            uid = "90001",
            unid = "55001",
            uuid = "0b9c2f8e-4b6a-4f1e-9d2c-7a5e3f1b8c4d",
            startTimeMs = startMs,
            stopTimeMs = startMs + 760_000L,
        )
        val policy = paceWindowPolicy()

        val totals = deriveTotals(track)
        assertEquals(2030, totals.totalDis, "canonical track must measure exactly 2030 m")
        assertEquals(760, totals.durationSec, "canonical track must span 760 s")

        val body = parseBody(Settlement.buildRequest(policy, track, canonicalHandle, params()))
        val speed = body.getValue("speed").jsonPrimitive.int

        // Platform window in the same units the wire value is read in: 分/km.
        val paceWindowMinPerKm = policy.paceMinSecPerKm / 60.0..policy.paceMaxSecPerKm / 60.0
        assertTrue(
            speed / 1000.0 in paceWindowMinPerKm,
            "speed=$speed renders as 平均配速 ${speed / 1000.0} 分/km, outside the platform " +
                "window $paceWindowMinPerKm 分/km — the 配速 rule would fail (不达标)",
        )
        // 760 s / 2030 m = 6.240 分/km = 6'14" → 6240 (old speed formula emitted 2671 here).
        assertEquals(6240, speed, "pace must be min/km x1000 for the canonical 2030 m / 760 s run")
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

    /**
     * The live 配速 window: `speedTop 3.0` / `speedBottom 10.0` 分/km from
     * `run_mode_policy.json` mapped to 180…600 s/km exactly like `PolicyClient`.
     */
    private fun paceWindowPolicy(): RunPolicy =
        policy().copy(paceMinSecPerKm = 180, paceMaxSecPerKm = 600)

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
