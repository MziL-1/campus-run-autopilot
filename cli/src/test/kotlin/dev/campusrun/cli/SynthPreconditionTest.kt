package dev.campusrun.cli

import dev.campusrun.core.policy.LatLng
import dev.campusrun.core.policy.RunPolicy
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The 11-25 empty-geometry guard: a school with NO server polygon (geoFences absent)
 * derives synthesis geometry from the issued checkpoints, so a run whose five-point
 * answer came back empty has nothing to inscribe a loop in. The precondition must refuse
 * with an actionable Chinese message BEFORE the synthesizer's English `require`
 * (`fence must be a polygon of at least 3 points`) or any submission leg can surface.
 *
 * [executeRun] itself needs the live protocol (sync/policy over the network), so the
 * gate lives in the pure [synthGeometryRefusal] seam — the same offline shape as
 * [DailyLimitWiringTest]'s `dailyLimitAdmission`.
 */
class SynthPreconditionTest {

    private val checkpoint = LatLng(34.7546, 113.6208)

    private fun policy(fence: List<LatLng>, checkpoints: List<LatLng>): RunPolicy = RunPolicy(
        requiredPoints = checkpoints.take(3),
        optionalPoints = checkpoints.drop(3),
        fence = fence,
        paceMinSecPerKm = 180,
        paceMaxSecPerKm = 600,
        validFromHour = 10,
        validToHour = 22,
        maxDistancePerRunM = 2000,
        dailyRunLimit = 0,
        faceWindow = null,
        minDistancePerRunM = 2000,
    )

    @Test
    fun `no fence and no checkpoints refuses with a friendly message`() {
        val refusal = synthGeometryRefusal(policy(fence = emptyList(), checkpoints = emptyList()))

        assertNotNull(refusal, "empty geometry must refuse before synthesis")
        assertTrue(refusal!!.contains("点位数据未获取到"), refusal)
        assertTrue(refusal.contains("至少 3 个点位"), refusal)
        assertTrue(refusal.contains("重试"), "the refusal must say how to retry: $refusal")
        assertFalse(
            refusal.contains("fence must be a polygon"),
            "raw synthesizer text must never surface: $refusal",
        )
    }

    @Test
    fun `a fence below three points without checkpoints also refuses`() {
        // A degenerate 1-2 point polygon is not synthesis geometry either — same refusal,
        // not the synthesizer's raw require.
        val degenerate = listOf(checkpoint, checkpoint.copy(lat = 34.7550))

        val refusal = synthGeometryRefusal(policy(fence = degenerate, checkpoints = emptyList()))

        assertNotNull(refusal, "a sub-polygon fence with no checkpoints must refuse")
        assertTrue(refusal!!.contains("点位数据未获取到"), refusal)
    }

    @Test
    fun `no fence with issued checkpoints proceeds to the derived-fence path`() {
        // The live school: no polygon, but the five-point answer returned checkpoints.
        assertNull(
            synthGeometryRefusal(policy(fence = emptyList(), checkpoints = List(5) { checkpoint })),
            "issued checkpoints are synthesis geometry — the derived path must proceed",
        )
    }

    @Test
    fun `a real polygon with no checkpoints proceeds`() {
        val polygon = listOf(
            LatLng(34.7540, 113.6200),
            LatLng(34.7540, 113.6220),
            LatLng(34.7560, 113.6220),
            LatLng(34.7560, 113.6200),
        )

        assertNull(
            synthGeometryRefusal(policy(fence = polygon, checkpoints = emptyList())),
            "a server polygon needs no checkpoints",
        )
    }
}
