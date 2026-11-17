package dev.campusrun.cli

import dev.campusrun.core.policy.LatLng
import dev.campusrun.core.policy.RunPolicy
import dev.campusrun.core.policy.RunPolicyValidator
import dev.campusrun.core.track.TrackPoint
import dev.campusrun.core.track.TrackSynthesizer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Negative gate tests for [RunPolicyValidator] (Task 9 brief Step 1). Every case builds
 * a real synthesized track over a synthetic rectangular fence — the same material the
 * CLI feeds the gate — then perturbs exactly one dimension (position, checkpoint
 * coverage, duration, clock, face window) and asserts the mandated Chinese keyword
 * appears in the returned violation list.
 *
 * The clean baseline is deliberately constructed to pass every rule, so a green
 * `clean track passes the gate` proves the gate has no false positives before the
 * negatives prove it has true ones.
 */
class ValidatorTest {

    private val fence = listOf(
        LatLng(30.0000, 120.0000),
        LatLng(30.0000, 120.0300),
        LatLng(30.0300, 120.0300),
        LatLng(30.0300, 120.0000),
    )

    /** Submission instant: 2026-11-17 12:00 Asia/Shanghai — inside the 10–22 window. */
    private val nowNoon: Long = ZonedDateTime.of(2026, 11, 17, 12, 0, 0, 0, ZoneId.of("Asia/Shanghai"))
        .toInstant().toEpochMilli()

    /** 08:00 the same day — before the advertised 10:00 window opens. */
    private val nowMorning: Long = ZonedDateTime.of(2026, 11, 17, 8, 0, 0, 0, ZoneId.of("Asia/Shanghai"))
        .toInstant().toEpochMilli()

    private fun policy(): RunPolicy = RunPolicy(
        requiredPoints = emptyList(),
        optionalPoints = emptyList(),
        fence = fence,
        paceMinSecPerKm = 180,
        paceMaxSecPerKm = 600,
        validFromHour = 10,
        validToHour = 22,
        maxDistancePerRunM = 5000,
        dailyRunLimit = 0,
        faceWindow = null,
        minDistancePerRunM = 2000,
    )

    /** 2080 m target / 700 s: mean pace 337 s/km sits mid-window; emitted length lands
     *  just below target (synthesizer bisects from below), comfortably over the 2000 m
     *  floor. Halving the elapsed times doubles the pace to 168 s/km — below the fast
     *  bound — which is what the brief's `too fast pace rejected` case exploits. */
    private val cleanTrack: List<TrackPoint> by lazy {
        TrackSynthesizer.synthesize(policy(), targetDistanceM = 2080, durationSec = 700, seed = 7L)
    }

    /** 1900 m track — under the settlement completeness floor of 2000 m. */
    private val shortTrack: List<TrackPoint> by lazy {
        TrackSynthesizer.synthesize(policy(), targetDistanceM = 1900, durationSec = 640, seed = 7L)
    }

    private fun assertKeyword(violations: List<String>, keyword: String) {
        assertTrue(violations.any { it.contains(keyword) }, "expected a \"$keyword\" violation, got: $violations")
    }

    @Test
    fun `clean track passes the gate`() {
        assertEquals(emptyList<String>(), RunPolicyValidator.validate(cleanTrack, policy(), nowNoon))
    }

    @Test
    fun `track outside fence rejected`() {
        // Rigid northward shift: every sample leaves the polygon, geometry otherwise intact.
        val shifted = cleanTrack.map { it.copy(lat = it.lat + 0.05) }

        val violations = RunPolicyValidator.validate(shifted, policy(), nowNoon)

        assertKeyword(violations, "出围栏")
    }

    @Test
    fun `missing required point rejected`() {
        // Synthesize without pinning, then demand the fence centre: the ring-shaped route
        // stays ~300 m from the centre, far outside the 50 m acceptance radius.
        val withPoint = policy().copy(requiredPoints = listOf(LatLng(30.0150, 120.0150)))

        val violations = RunPolicyValidator.validate(cleanTrack, withPoint, nowNoon)

        assertKeyword(violations, "缺必经点")
        assertTrue(violations.none { it.contains("出围栏") }, "fence itself must stay satisfied: $violations")
    }

    @Test
    fun `synthesized track through the required point passes the gate`() {
        val withPoint = policy().copy(requiredPoints = listOf(LatLng(30.0150, 120.0150)))
        val pinned = TrackSynthesizer.synthesize(withPoint, targetDistanceM = 2080, durationSec = 700, seed = 11L)

        assertEquals(emptyList<String>(), RunPolicyValidator.validate(pinned, withPoint, nowNoon))
    }

    @Test
    fun `too fast pace rejected`() {
        // Same route, halved clock: mean pace drops below the fast bound (speedTop).
        val halved = cleanTrack.map { it.copy(elapsedSec = it.elapsedSec / 2) }

        val violations = RunPolicyValidator.validate(halved, policy(), nowNoon)

        assertKeyword(violations, "配速")
    }

    @Test
    fun `face window blocked`() {
        // Synthetic face window around "now" (spec R6); production faceWindow is null.
        val inFaceWindow = policy().copy(faceWindow = (nowNoon - 60_000)..(nowNoon + 60_000))

        val violations = RunPolicyValidator.validate(cleanTrack, inFaceWindow, nowNoon)

        assertKeyword(violations, "人脸")
    }

    @Test
    fun `submission outside school window rejected`() {
        val violations = RunPolicyValidator.validate(cleanTrack, policy(), nowMorning)

        assertKeyword(violations, "时段外")
    }

    @Test
    fun `run below settlement completeness floor rejected`() {
        val violations = RunPolicyValidator.validate(shortTrack, policy(), nowNoon)

        assertKeyword(violations, "距离不足")
    }

    @Test
    fun `school floor above settlement floor is enforced`() {
        val violations = RunPolicyValidator.validate(cleanTrack, policy().copy(minDistancePerRunM = 2500), nowNoon)

        assertKeyword(violations, "距离不足")
    }

    @Test
    fun `settlement floor applies even when school floor is lower`() {
        // minDistance 1500 would pass the school rule but still goes out complete=false.
        val violations = RunPolicyValidator.validate(
            shortTrack,
            policy().copy(minDistancePerRunM = 1500),
            nowNoon,
        )

        assertKeyword(violations, "距离不足")
    }

    @Test
    fun `daily run limit blocks the next submission`() {
        val limited = policy().copy(dailyRunLimit = 1)

        assertKeyword(RunPolicyValidator.validateDailyLimit(1, limited), "每日次数上限")
        assertEquals(emptyList<String>(), RunPolicyValidator.validateDailyLimit(0, limited))
        // dailyRunLimit == 0 means "not advertised" (7.4.x has no daily field), not zero runs.
        assertEquals(emptyList<String>(), RunPolicyValidator.validateDailyLimit(3, policy()))
    }
}
