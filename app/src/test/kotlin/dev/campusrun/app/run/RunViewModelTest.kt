package dev.campusrun.app.run

import dev.campusrun.core.policy.RunPolicy
import dev.campusrun.core.session.Token
import dev.campusrun.core.track.TrackPoint
import dev.campusrun.core.upload.RunRecord
import dev.campusrun.core.upload.UploadHandle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.Executor

/**
 * One-shot run pipeline tests (brief Step 1):
 *
 * - `run success → Success 状态且显示里程` (this file)
 * - `validator 违规 → Error 显示首条违规、未调用 upload` (this file — the fake counts
 *   upload calls and asserts they stay at zero when the gate refuses)
 *
 * The stage seam [RunStages] fakes every protocol I/O leg, so these tests exercise the
 * ViewModel's orchestration — including the real, pure [dev.campusrun.core.policy.RunPolicyValidator]
 * gate — with no network.
 */
class RunViewModelTest {

    @Test
    fun `run success - Success state shows the distance`() {
        val fake = FakeRunStages(
            policy = policy(),
            track = track(distanceM = 2030, durationSec = 760),
            record = RunRecord(id = "9001", distanceM = 2030, valid = true),
        )
        val vm = RunViewModel(fake, TOKEN, DIRECT, settleWaitMs = 0)

        vm.startRun(distanceM = 2030, durationSec = 760)

        val state = vm.state
        assertTrue(state is RunState.Success, "expected Success, got $state")
        state as RunState.Success
        assertEquals(2030, state.distanceM, "Success must carry the run's distance")
        assertTrue(state.message.contains("2030"), "mileage not displayed: ${state.message}")
        assertEquals(1, fake.uploadCalls, "a passing gate should upload exactly once")
    }

    @Test
    fun `validator violation - Error shows the first violation and upload is never called`() {
        val fake = FakeRunStages(
            policy = policy(),
            // 100 m track: below the 2000 m completeness floor → 距离不足 (first violation).
            track = track(distanceM = 100, durationSec = 30),
        )
        val vm = RunViewModel(fake, TOKEN, DIRECT, settleWaitMs = 0)

        vm.startRun(distanceM = 100, durationSec = 30)

        val state = vm.state
        assertTrue(state is RunState.Error, "expected Error, got $state")
        state as RunState.Error
        assertTrue(
            state.message.contains("距离不足"),
            "first violation must be shown to the user: ${state.message}",
        )
        assertEquals(
            0,
            fake.uploadCalls,
            "refuse-before-upload: the validator's refusal must stop the pipeline before upload",
        )
    }

    @Test
    fun `second run in one session is refused by the daily self-limit`() {
        val fake = FakeRunStages(
            policy = policy(),
            track = track(distanceM = 2030, durationSec = 760),
            record = RunRecord(id = "9001", distanceM = 2030, valid = true),
        )
        val vm = RunViewModel(fake, TOKEN, DIRECT, settleWaitMs = 0)
        vm.startRun(distanceM = 2030, durationSec = 760)
        assertTrue(vm.state is RunState.Success, "first run must succeed, got ${vm.state}")

        vm.startRun(distanceM = 2030, durationSec = 760)

        val state = vm.state
        assertTrue(state is RunState.Error, "second run must be refused, got $state")
        assertTrue(
            (state as RunState.Error).message.contains("自限"),
            "refusal must state the daily self-limit: ${state.message}",
        )
        assertEquals(1, fake.uploadCalls, "the self-limit must block before any second upload")
    }

    /** No advertised window (both hours 0) so the validator's wall-clock check cannot flake. */
    private fun policy() = RunPolicy(
        requiredPoints = emptyList(),
        optionalPoints = emptyList(),
        fence = emptyList(),
        paceMinSecPerKm = 180,
        paceMaxSecPerKm = 600,
        validFromHour = 0,
        validToHour = 0,
        maxDistancePerRunM = 0,
        dailyRunLimit = 0,
        faceWindow = null,
        minDistancePerRunM = 2000,
    )

    /**
     * Two samples spanning [distanceM] metres of equatorial longitude over [durationSec]
     * seconds — path length ≈ distance, mean pace = duration/distance (374 s/km for the
     * default 2030 m / 760 s, inside the 180–600 window).
     */
    private fun track(distanceM: Int, durationSec: Int): List<TrackPoint> = listOf(
        TrackPoint(lat = 0.0, lng = 0.0, elapsedSec = 0, speedMps = 2.67, stepRateHz = 166.0),
        TrackPoint(
            lat = 0.0,
            lng = distanceM / METRES_PER_DEGREE_LNG,
            elapsedSec = durationSec,
            speedMps = 2.67,
            stepRateHz = 166.0,
        ),
    )

    private class FakeRunStages(
        private val policy: RunPolicy,
        private val track: List<TrackPoint>,
        private val record: RunRecord? = null,
    ) : RunStages {
        var uploadCalls = 0
            private set

        override fun syncSkewMs(): Long = 0L

        override fun fetchPolicy(token: Token): RunPolicy = policy

        override fun synthesize(policy: RunPolicy, distanceM: Int, durationSec: Int, seed: Long): List<TrackPoint> = track

        override fun upload(token: Token, track: List<TrackPoint>): UploadHandle {
            uploadCalls += 1
            return UploadHandle(
                uid = "12345678",
                unid = "35102",
                uuid = "00000000-0000-0000-0000-000000000000",
                startTimeMs = 1_700_000_000_000L,
                stopTimeMs = 1_700_000_000_000L + 760_000L,
            )
        }

        override fun settle(policy: RunPolicy, track: List<TrackPoint>, handle: UploadHandle): String = "9001"

        override fun latestHistory(token: Token): RunRecord? = record
    }

    private companion object {
        /** Dummy session — no credential material (fixtures rule). */
        val TOKEN = Token(value = "unit-test-token", uid = "12345678", unid = "35102", expiresAtMs = null)

        /** Runs the pipeline inline on the calling thread — deterministic legs. */
        val DIRECT = Executor { it.run() }

        const val METRES_PER_DEGREE_LNG = 111_194.9266
    }
}
