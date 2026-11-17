package dev.campusrun.core.track

import dev.campusrun.core.policy.LatLng
import dev.campusrun.core.policy.RunPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * Property tests for [TrackSynthesizer] (spec C2, §5.5).
 *
 * The live Task 6 fixture carries no fence (`geoFences` is null — school not configured),
 * so per the Task 7 ruling the properties run against a synthetic campus policy: a
 * ~667 m x 686 m quadrilateral around the fixture origin (~34.7°N 113.7°E) with two
 * required checkpoints inside it. Fully offline: no network, no fixture geometry.
 *
 * Pace convention: mean pace = total time / total km (the server-side view of a run);
 * the synthetic window is [210, 600] s/km (3.5–10.0 min/km, the live fixture's floor).
 */
class TrackSynthesizerTest {

    private val policy = RunPolicy(
        requiredPoints = listOf(
            LatLng(34.7018, 113.7021), // ~201 m SW of the fence centre, inside
            LatLng(34.7042, 113.7055), // ~208 m NE of the fence centre, inside
        ),
        optionalPoints = emptyList(),
        fence = listOf(
            LatLng(34.7000, 113.7000),
            LatLng(34.7000, 113.7075),
            LatLng(34.7060, 113.7075),
            LatLng(34.7060, 113.7000),
        ),
        paceMinSecPerKm = 210,
        paceMaxSecPerKm = 600,
        validFromHour = 10,
        validToHour = 22,
        maxDistancePerRunM = 5000,
        dailyRunLimit = 1,
        faceWindow = null,
    )

    private val track by lazy {
        TrackSynthesizer.synthesize(policy, targetDistanceM = 2000, durationSec = 900, seed = 42L)
    }

    private fun pathLengthM(): Double =
        Geo.pathLengthM(track.map { LatLng(it.lat, it.lng) })

    @Test
    fun `all points inside fence`() {
        assertTrue(track.isNotEmpty(), "synthesized track must not be empty")
        track.forEach { p ->
            assertTrue(
                Geo.insidePolygon(LatLng(p.lat, p.lng), policy.fence),
                "point outside fence: $p",
            )
        }
    }

    @Test
    fun `visits all required points`() {
        policy.requiredPoints.forEach { required ->
            val nearest = track.minOf { Geo.distanceM(LatLng(it.lat, it.lng), required) }
            assertTrue(nearest <= 20.0, "required $required never visited, nearest=$nearest m")
        }
    }

    @Test
    fun `path length within 3 percent`() {
        val len = pathLengthM()
        assertTrue(abs(len - 2000.0) / 2000.0 <= 0.03, "path length $len m off the 2000 m target")
    }

    @Test
    fun `mean pace within policy window`() {
        val len = pathLengthM()
        val meanPaceSecPerKm = 900.0 / (len / 1000.0)
        assertTrue(meanPaceSecPerKm >= 210.0, "mean pace $meanPaceSecPerKm below 210 s/km")
        assertTrue(
            meanPaceSecPerKm <= policy.paceMaxSecPerKm,
            "mean pace $meanPaceSecPerKm above policy max ${policy.paceMaxSecPerKm} s/km",
        )
    }

    @Test
    fun `elapsed monotonic and ends at durationSec`() {
        assertTrue(track.size >= 2, "track must have samples")
        track.zipWithNext { a, b ->
            assertTrue(b.elapsedSec > a.elapsedSec, "elapsed not strictly increasing: $a -> $b")
        }
        assertEquals(0, track.first().elapsedSec)
        assertEquals(900, track.last().elapsedSec)
    }

    @Test
    fun `deterministic per seed and distinct across seeds`() {
        assertEquals(
            track,
            TrackSynthesizer.synthesize(policy, 2000, 900, seed = 42L),
            "same seed must reproduce the track exactly",
        )
        assertNotEquals(
            track,
            TrackSynthesizer.synthesize(policy, 2000, 900, seed = 43L),
            "different seeds must not produce identical tracks (spec §5.5-2)",
        )
    }

    /** spec C2 步频校验 (70–240) + §5.5-1 natural variation — no constant-speed tell. */
    @Test
    fun `step rate in spec bounds and pace varies naturally`() {
        track.forEach {
            assertTrue(it.stepRateHz in 70.0..240.0, "stepRateHz out of [70, 240]: $it")
        }
        val paces = track.map { 1000.0 / it.speedMps }
        val minPace = paces.min()
        val maxPace = paces.max()
        assertTrue(minPace >= policy.paceMinSecPerKm, "instant pace $minPace below window")
        assertTrue(maxPace <= policy.paceMaxSecPerKm, "instant pace $maxPace above window")
        assertTrue(
            maxPace - minPace >= 10.0,
            "pace is effectively constant: min=$minPace max=$maxPace s/km",
        )
    }
}
