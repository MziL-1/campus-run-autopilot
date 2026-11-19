package dev.campusrun.cli

import dev.campusrun.core.config.ProtocolParams
import dev.campusrun.core.policy.LatLng
import dev.campusrun.core.policy.RunPolicy
import dev.campusrun.core.track.TrackPoint
import dev.campusrun.core.upload.Settlement
import dev.campusrun.core.upload.UploadHandle
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Review Focus #5 (plan risk 5: 提交时序异常): the settlement body must carry the run as
 * a **durationSec-paced timeline**, not one instantaneous dump. The assertions walk the
 * two 10 s bucket arrays (`speedPerTenSec` / `stepsPerTenSec`, Task 8's upstream
 * `time_buckets`) plus the 1 Hz `allLocJson` timeline and require progress to advance
 * bucket-by-bucket across the whole duration: contiguous windows anchored at
 * `startTime`, positive distance/steps in *every* window, strictly growing cumulative
 * totals, and the final window ending exactly at `stopTime = startTime + durationSec·1000`.
 */
class PacingTest {

    private val startMs = 1_760_000_000_000L
    private val durationSec = 600
    private val speedMps = 3.5
    private val stepRatePerMin = 156.0

    /** Straight eastbound line at lat 30: 3.5 m per 1 Hz sample (~2100 m total). */
    private val track: List<TrackPoint> = (0..durationSec).map { t ->
        TrackPoint(
            lat = 30.0,
            lng = 120.0 + t * speedMps / METRES_PER_DEGREE_LNG_AT_LAT30,
            elapsedSec = t,
            speedMps = speedMps,
            stepRateHz = stepRatePerMin,
        )
    }

    private val handle = UploadHandle(
        uid = "100001",
        unid = "90001",
        uuid = "0b9c2f8e-4b6a-4f1e-9d2c-7a5e3f1b8c4d",
        startTimeMs = startMs,
        stopTimeMs = startMs + durationSec * 1000L,
    )

    @Test
    fun `settlement pacing respects durationSec`() {
        val body = Json.parseToJsonElement(
            String(Settlement.buildRequest(policy(), track, handle, params()), Charsets.UTF_8),
        ).jsonObject

        val totalDis = body.getValue("totalDis").jsonPrimitive.int
        val totalSteps = body.getValue("totalSteps").jsonPrimitive.int
        assertEquals(durationSec, body.getValue("totalTime").jsonPrimitive.int)

        // --- speed buckets: one 10 s window per durationSec/10, anchored at startTime ---
        val speedBuckets = body.getValue("speedPerTenSec").jsonArray
        assertEquals(durationSec / 10, speedBuckets.size, "bucket count must follow durationSec")

        var cumulativeDistance = 0.0
        speedBuckets.forEachIndexed { index, element ->
            val bucket = element.jsonObject
            assertEquals(startMs + index * 10_000L, bucket.getValue("beginTime").jsonPrimitive.long)
            assertEquals(startMs + (index + 1) * 10_000L, bucket.getValue("endTime").jsonPrimitive.long)
            assertEquals(index + 1, bucket.getValue("queueNum").jsonPrimitive.int)
            val distance = bucket.getValue("distance").jsonPrimitive.double
            assertTrue(distance > 0.0, "bucket $index carries no progress — submission is not paced")
            cumulativeDistance += distance
        }
        // Last window closes exactly at stopTime — the timeline, not an instant.
        assertEquals(handle.stopTimeMs, speedBuckets.last().jsonObject.getValue("endTime").jsonPrimitive.long)
        assertTrue(
            cumulativeDistance > 0.9 * totalDis && cumulativeDistance <= totalDis + 1.0,
            "bucketed distance $cumulativeDistance must telescope to totalDis $totalDis",
        )
        // Not instantaneous: no single 10 s slice may hold the whole run.
        val firstBucketDistance = speedBuckets.first().jsonObject.getValue("distance").jsonPrimitive.double
        assertTrue(firstBucketDistance < totalDis / 10.0, "front bucket holds the entire distance")

        // --- step buckets: same windows, progress in every window ---
        val stepBuckets = body.getValue("stepsPerTenSec").jsonArray
        assertEquals(speedBuckets.size, stepBuckets.size)
        var cumulativeSteps = 0L
        stepBuckets.forEachIndexed { index, element ->
            val bucket = element.jsonObject
            assertEquals(startMs + index * 10_000L, bucket.getValue("beginTime").jsonPrimitive.long)
            val steps = bucket.getValue("stepsNum").jsonPrimitive.long
            assertTrue(steps > 0, "step bucket $index carries no cadence")
            cumulativeSteps += steps
        }
        assertEquals(totalSteps.toLong(), cumulativeSteps, "step buckets must telescope to totalSteps")

        // --- allLocJson: the 1 Hz timeline itself spans durationSec ---
        val samples = Json.parseToJsonElement(body.getValue("allLocJson").jsonPrimitive.content).jsonArray
        assertEquals(durationSec + 1, samples.size)
        val times = samples.map { it.jsonObject.getValue("time").jsonPrimitive.long }
        assertEquals(startMs, times.first())
        assertEquals(handle.stopTimeMs, times.last())
        times.zipWithNext().forEach { (previous, next) ->
            assertEquals(1000L, next - previous, "allLocJson samples must advance 1 s at a time")
        }
    }

    private fun policy(): RunPolicy = RunPolicy(
        requiredPoints = emptyList(),
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

    private fun params(): ProtocolParams = ProtocolParams(
        aesKey = ByteArray(16) { 1 },
        kdfSalt = ByteArray(40) { 2 },
        rsaPublicKey = "",
        endpointBase = "https://offline.invalid",
        signSalt = "2slhe02lsfiwowlcixisla_sls-_slaor",
        versionTag = 1,
    )

    private companion object {
        /** 6371000 · cos(30°) · π/180 — metres per degree of longitude at lat 30. */
        const val METRES_PER_DEGREE_LNG_AT_LAT30 = 96_411.0
    }
}
