package dev.campusrun.app.run

import dev.campusrun.app.session.SessionEpoch
import dev.campusrun.core.campus.CampusSelectionStore
import dev.campusrun.core.campus.InMemoryCampusSelectionStore
import dev.campusrun.core.policy.RunPolicy
import dev.campusrun.core.policy.RunPolicyValidator
import dev.campusrun.core.session.Token
import dev.campusrun.core.track.TrackPoint
import dev.campusrun.core.upload.RunRecord
import dev.campusrun.core.upload.UploadHandle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
            // Dated in the past: the pre-upload cross-session daily gate must let it through.
            record = RunRecord(id = "9001", distanceM = 2030, valid = true, startTimeMs = PAST_MS),
        )
        val vm = RunViewModel(fake, TOKEN, DIRECT, settleWaitMs = 0)

        vm.startRun(distanceM = 2030, durationSec = 760)

        val state = vm.state
        assertTrue(state is RunState.Success, "expected Success, got $state")
        state as RunState.Success
        assertEquals(2030, state.distanceM, "Success must carry the run's distance")
        assertTrue(state.message.contains("2030"), "mileage not displayed: ${state.message}")
        // Verdict-free objective row (same defect class as the Home fix): the message must
        // state id/distance/date facts and must never derive a verdict from `valid`.
        assertTrue(
            state.message.contains("回查记录已写入"),
            "objective record line required: ${state.message}",
        )
        assertTrue(state.message.contains("id=9001"), "record id not displayed: ${state.message}")
        assertFalse(state.message.contains("达标"), "no verdict may render: ${state.message}")
        assertEquals(1, fake.uploadCalls, "a passing gate should upload exactly once")
    }

    @Test
    fun `read-back valid=false - the submission still succeeds with an objective line`() {
        // The 11-23 case: our flag said false while the official app scored 达标 — the
        // divergent flag must never route a settled submission into Error.
        val fake = FakeRunStages(
            policy = policy(),
            track = track(distanceM = 2030, durationSec = 760),
            record = RunRecord(id = "9007", distanceM = 2030, valid = false, startTimeMs = PAST_MS),
        )
        val vm = RunViewModel(fake, TOKEN, DIRECT, settleWaitMs = 0)

        vm.startRun(distanceM = 2030, durationSec = 760)

        val state = vm.state
        assertTrue(state is RunState.Success, "valid=false must not fail a settled run: $state")
        state as RunState.Success
        assertTrue(
            state.message.contains("回查记录已写入：id=9007 · 2030 m"),
            "objective id/distance row required: ${state.message}",
        )
        assertFalse(state.message.contains("未达标"), "no verdict may render: ${state.message}")
        assertFalse(state.message.contains("valid"), "the flag must stay out of the UI: ${state.message}")
    }

    @Test
    fun `cross-session daily gate - a record dated today refuses before upload`() {
        val fake = FakeRunStages(
            policy = policy(),
            track = track(distanceM = 2030, durationSec = 760),
            record = RunRecord(id = "9002", distanceM = 2030, valid = true, startTimeMs = todayNoonMs()),
        )
        val vm = RunViewModel(fake, TOKEN, DIRECT, settleWaitMs = 0)

        vm.startRun(distanceM = 2030, durationSec = 760)

        val state = vm.state
        assertTrue(state is RunState.Error, "today's record must refuse the run, got $state")
        assertTrue(
            (state as RunState.Error).message.contains("今日已有跑步记录"),
            "refusal must name the cross-session daily rule: ${state.message}",
        )
        assertEquals(0, fake.uploadCalls, "the daily gate must stop the pipeline before upload")
    }

    @Test
    fun `cross-session daily gate - an undated record fails closed before upload`() {
        // No parseable startTime -> the gate cannot prove "not today", so it refuses.
        val fake = FakeRunStages(
            policy = policy(),
            track = track(distanceM = 2030, durationSec = 760),
            record = RunRecord(id = "9003", distanceM = 2030, valid = true),
        )
        val vm = RunViewModel(fake, TOKEN, DIRECT, settleWaitMs = 0)

        vm.startRun(distanceM = 2030, durationSec = 760)

        val state = vm.state
        assertTrue(state is RunState.Error, "an undated record must fail closed, got $state")
        assertTrue(
            (state as RunState.Error).message.contains("缺少开始时间"),
            "refusal must explain the missing date: ${state.message}",
        )
        assertEquals(0, fake.uploadCalls, "fail-closed must stop the pipeline before upload")
    }

    @Test
    fun `cross-session daily gate - a record from an earlier day allows the run`() {
        val fake = FakeRunStages(
            policy = policy(),
            track = track(distanceM = 2030, durationSec = 760),
            record = RunRecord(id = "9004", distanceM = 2030, valid = true, startTimeMs = PAST_MS),
        )
        val vm = RunViewModel(fake, TOKEN, DIRECT, settleWaitMs = 0)

        vm.startRun(distanceM = 2030, durationSec = 760)

        assertTrue(vm.state is RunState.Success, "yesterday's record must not block: ${vm.state}")
        assertEquals(1, fake.uploadCalls, "only today's record may refuse the upload")
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
            // Past-dated so the cross-session gate lets the FIRST run through; the second
            // run must then be caught by the in-session guard alone.
            record = RunRecord(id = "9001", distanceM = 2030, valid = true, startTimeMs = PAST_MS),
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

    @Test
    fun `logout during run - the pipeline aborts before upload`() {
        // Gated history read: the pipeline is mid-leg when the logout seam lands.
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val fake = FakeRunStages(
            policy = policy(),
            track = track(distanceM = 2030, durationSec = 760),
            // Past-dated so the cross-session gate would allow the upload — the abort
            // must come from the logout invalidation alone.
            record = RunRecord(id = "9005", distanceM = 2030, valid = true, startTimeMs = PAST_MS),
            beforeLatestHistory = {
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
            },
        )
        val vm = RunViewModel(fake, TOKEN, BACKGROUND, settleWaitMs = 0)

        vm.startRun(distanceM = 2030, durationSec = 760)
        assertTrue(entered.await(5, TimeUnit.SECONDS), "pipeline must reach the history read")
        SessionEpoch.bump() // the logout seam (MainActivity's clear) lands mid-run
        release.countDown()

        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && vm.state !is RunState.Error) {
            Thread.sleep(5)
        }
        val state = vm.state
        assertTrue(state is RunState.Error, "a logged-out pipeline must end non-success, got $state")
        assertTrue(
            (state as RunState.Error).message.contains("已退出登录"),
            "the abort must name the logout: ${state.message}",
        )
        assertEquals(0, fake.uploadCalls, "upload must NEVER be called after logout")
    }

    @Test
    fun `epoch capture - a run started after a logout bump still proceeds`() {
        // Capture-at-start semantics: only work that outlived ITS OWN logout dies, so a
        // fresh session (new pipeline, new capture) is not permanently invalidated.
        SessionEpoch.bump()
        val fake = FakeRunStages(
            policy = policy(),
            track = track(distanceM = 2030, durationSec = 760),
            record = RunRecord(id = "9006", distanceM = 2030, valid = true, startTimeMs = PAST_MS),
        )
        val vm = RunViewModel(fake, TOKEN, DIRECT, settleWaitMs = 0)

        vm.startRun(distanceM = 2030, durationSec = 760)

        assertTrue(vm.state is RunState.Success, "a post-bump run must proceed, got ${vm.state}")
        assertEquals(1, fake.uploadCalls, "the fresh pipeline must upload exactly once")
    }

    @Test
    fun `campus selection - the run policy leg carries the campus Home selected`() {
        // Exactly the value HomeViewModel.selectCampus(35103) persists into the store
        // MainActivity shares with this ViewModel (display/execution agreement).
        val store = object : CampusSelectionStore {
            override fun load(): String? = "35103"
            override fun save(unid: String) = Unit
            override fun clear() = Unit
        }
        val fake = FakeRunStages(
            policy = policy(),
            track = track(distanceM = 2030, durationSec = 760),
        )
        val vm = RunViewModel(fake, TOKEN, DIRECT, settleWaitMs = 0, campusSelection = store)

        vm.startRun(distanceM = 2030, durationSec = 760)

        assertEquals(
            listOf("35103"),
            fake.selectUnids,
            "the run must read the rule for the campus Home displays (selectUnid)",
        )
        assertEquals("90001", TOKEN.unid, "the session token stays untouched — settlement keeps the login unid")
    }

    @Test
    fun `campus selection - with no selection the run keeps the session unid`() {
        val fake = FakeRunStages(
            policy = policy(),
            track = track(distanceM = 2030, durationSec = 760),
        )
        val vm = RunViewModel(
            fake,
            TOKEN,
            DIRECT,
            settleWaitMs = 0,
            campusSelection = InMemoryCampusSelectionStore(),
        )

        vm.startRun(distanceM = 2030, durationSec = 760)

        assertEquals(listOf("90001"), fake.selectUnids, "no persisted choice -> the session unid")
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
        /** Test gate run at the top of every [latestHistory] leg (in-flight window). */
        private val beforeLatestHistory: (() -> Unit)? = null,
    ) : RunStages {
        var uploadCalls = 0
            private set

        override fun syncSkewMs(): Long = 0L

        /** Every `selectUnid` the policy leg was asked to read — pins the campus in effect. */
        val selectUnids = mutableListOf<String>()

        override fun fetchPolicy(token: Token, selectUnid: String): RunPolicy {
            selectUnids += selectUnid
            return policy
        }

        override fun synthesize(policy: RunPolicy, distanceM: Int, durationSec: Int, seed: Long): List<TrackPoint> = track

        override fun upload(token: Token, track: List<TrackPoint>): UploadHandle {
            uploadCalls += 1
            return UploadHandle(
                uid = "12345678",
                unid = "90001",
                uuid = "00000000-0000-0000-0000-000000000000",
                startTimeMs = 1_700_000_000_000L,
                stopTimeMs = 1_700_000_000_000L + 760_000L,
            )
        }

        override fun settle(policy: RunPolicy, track: List<TrackPoint>, handle: UploadHandle): String = "9001"

        override fun latestHistory(token: Token): RunRecord? {
            beforeLatestHistory?.invoke()
            return record
        }
    }

    private companion object {
        /** Dummy session — no credential material (fixtures rule). */
        val TOKEN = Token(value = "unit-test-token", uid = "12345678", unid = "90001", expiresAtMs = null)

        /** Runs the pipeline inline on the calling thread — deterministic legs. */
        val DIRECT = Executor { it.run() }

        /** Real worker thread for the in-flight-logout test (the gate blocks the runner). */
        val BACKGROUND = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "run-test").apply { isDaemon = true }
        }

        const val METRES_PER_DEGREE_LNG = 111_194.9266

        /** 2025-10-09 12:00 +08 — always an earlier day than "now" in these tests. */
        const val PAST_MS = 1_760_000_000_000L

        /** Noon today in the gate's zone, so the date comparison cannot flake around midnight. */
        fun todayNoonMs(): Long =
            LocalDate.now(RunPolicyValidator.RULE_ZONE).atTime(12, 0)
                .atZone(RunPolicyValidator.RULE_ZONE).toInstant().toEpochMilli()
    }
}
