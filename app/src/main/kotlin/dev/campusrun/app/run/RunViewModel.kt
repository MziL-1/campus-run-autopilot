package dev.campusrun.app.run

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.campusrun.app.session.SessionEpoch
import dev.campusrun.core.api.ApiClient
import dev.campusrun.core.campus.CampusSelectionStore
import dev.campusrun.core.campus.InMemoryCampusSelectionStore
import dev.campusrun.core.config.ProtocolParams
import dev.campusrun.core.crypto.NetSecKit
import dev.campusrun.core.crypto.Signer
import dev.campusrun.core.diag.RedactedLog
import dev.campusrun.core.policy.LatLng
import dev.campusrun.core.policy.PolicyClient
import dev.campusrun.core.policy.RunPolicy
import dev.campusrun.core.policy.RunPolicyValidator
import dev.campusrun.core.session.TimeSync
import dev.campusrun.core.session.Token
import dev.campusrun.core.track.TrackPoint
import dev.campusrun.core.track.TrackSynthesizer
import dev.campusrun.core.upload.HistoryClient
import dev.campusrun.core.upload.RunRecord
import dev.campusrun.core.upload.RunUploader
import dev.campusrun.core.upload.Settlement
import dev.campusrun.core.upload.UploadHandle
import dev.campusrun.core.upload.dailySelfLimitRefusal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.random.Random

/**
 * One-shot run state machine (brief Step 2):
 *
 * ```text
 * Idle ──► Running ──► Success(distanceM, msg)   gate passed, uploaded, record read back
 *   │         │       └► Error(msg)              gate refusal / stage failure / 回查无记录
 *   │         └──────────► (executor)
 *   └─► Error(msg)      refused before Running (daily self-limit, bad input)
 * ```
 *
 * [state] is Compose snapshot state so [RunScreen] recomposes on every transition; it is
 * written only from the run worker thread (or the test's direct executor).
 */
sealed class RunState {
    data object Idle : RunState()
    data object Running : RunState()

    /**
     * Submitted and read back — [distanceM] is the server-confirmed record distance.
     * Deliberately carries **no 达标/未达标 verdict**: our `RunRecord.valid` diverges from
     * the official scoring in both directions (11-17 ours `true`/官方 不达标; 11-21 &
     * 11-23 ours `false`/官方 达标), so only objective record facts are stated here.
     */
    data class Success(val distanceM: Int, val message: String) : RunState()

    data class Error(val message: String) : RunState()
}

/**
 * Stage seam for the one-shot pipeline: every protocol I/O leg behind one interface so
 * [RunViewModelTest] can fake the core (brief Step 1) while the real [RunPolicyValidator]
 * gate still runs over the fake's track and policy.
 */
interface RunStages {
    /** Server clock skew from [TimeSync] (ms; caller applies it to the submission instant). */
    fun syncSkewMs(): Long

    /**
     * @param selectUnid the campus whose rule to read — the persisted campus choice
     *   (Home's dropdown) resolved by [RunViewModel], defaulting to the session unid.
     *   It reaches the wire as `runModePolicy.selectUnid` and the five-point
     *   `selectedUnid` (PolicyClient), so display and execution use the same campus.
     */
    fun fetchPolicy(token: Token, selectUnid: String = token.unid): RunPolicy

    fun synthesize(policy: RunPolicy, distanceM: Int, durationSec: Int, seed: Long): List<TrackPoint>

    fun upload(token: Token, track: List<TrackPoint>): UploadHandle

    /** Settlement body → `save/record`; returns the server `rrid`/`id` when present. */
    fun settle(policy: RunPolicy, track: List<TrackPoint>, handle: UploadHandle): String?

    fun latestHistory(token: Token): RunRecord?
}

/**
 * One-shot run orchestration (brief Step 2) — the App port of the CLI's `run` pipeline
 * (`cli/Main.kt executeRun`): sync → policy → synth → **validate (refuse on any violation,
 * before any upload)** → OBS upload → settle → history verify. Every protocol decision
 * stays in `core`; this class only sequences the [RunStages] legs and owns UI state.
 *
 * Fail-closed gates ported from the CLI:
 *
 * - **rule body absent** (no pace window AND no time window) → refuse: outside the school
 *   run window or a rejected session, exactly upstream's "未取得学校规则，停止提交".
 * - **每日次数上限 advertised** → refuse: `latest()` returns only the newest history row,
 *   so today's run *count* (which an advertised limit needs) is not derivable here —
 *   the CLI's fail-closed `dailyLimitAdmission` parity (review round 1).
 * - **daily self-limit (spec §5.5-4)**: one submission per process/session, PLUS the
 *   cross-session leg — before any upload the newest history record must not already be
 *   today's ([dailySelfLimitRefusal]); a process restart can no longer re-enable a second
 *   run of the same day. `null` history (no records) is the first-run case and proceeds.
 */
class RunViewModel(
    private val stages: RunStages,
    private val token: Token,
    private val executor: Executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "run-worker").apply { isDaemon = true }
    },
    private val log: (String) -> Unit = {},
    /** Upstream waits before the history read-back; tests pass 0 for determinism. */
    private val settleWaitMs: Long = 1_500,
    /**
     * The persisted campus choice — the SAME store Home writes (MainActivity passes one
     * shared instance), so the campus Home displays and the campus this run executes
     * against are always the one value (display/execution agreement). Not a credential;
     * only the policy leg's `selectUnid` follows it, never the session [Token].
     */
    private val campusSelection: CampusSelectionStore = InMemoryCampusSelectionStore(),
) {

    var state: RunState by mutableStateOf(RunState.Idle)
        private set

    /**
     * Set the moment a submission is *attempted* (before the upload leaves): a half-finished
     * submission must also count against the daily self-limit.
     */
    @Volatile
    private var submissionAttempted = false

    /** Starts one run on the worker thread; re-entry while [RunState.Running] is ignored. */
    fun startRun(distanceM: Int, durationSec: Int) {
        if (state is RunState.Running) return
        if (submissionAttempted) {
            fail("本次会话已提交过一次，按每日 1 次自限不再重复提交（跨会话另有历史记录日期把关）")
            return
        }
        if (distanceM <= 0 || durationSec <= 0) {
            fail("距离/时长必须为正数")
            return
        }
        executor.execute { pipeline(distanceM, durationSec) }
    }

    /** The brief's `run` pipeline; see the class KDoc for the gate ordering. */
    private fun pipeline(distanceM: Int, durationSec: Int) {
        // Fix (invalidate in-flight work on logout): captured at task start; every
        // token-taking leg below re-compares it, so a pipeline that outlived its logout
        // can neither re-install the dead token into the shared ApiClient nor submit.
        val startEpoch = SessionEpoch.current
        set(RunState.Running)
        try {
            if (abortIfLoggedOut(startEpoch, "未获取规则")) return
            val seed = Random.nextLong()
            val skewMs = stages.syncSkewMs()
            log("sync: server clock skew $skewMs ms")

            // Display/execution agreement: the run reads the SAME persisted campus Home
            // displays (Home syncs the store to the effective campus on every refresh),
            // so a selectCampus(B) on Home is what this policy and its five-point query
            // are read for. No selection keeps the session unid. It reaches the wire only
            // as `selectUnid`/`selectedUnid` — the session token is never rewritten, so
            // settlement keeps the login unid (no Settlement change).
            val selectUnid = campusSelection.load()?.takeIf { it.isNotBlank() } ?: token.unid
            val policy = stages.fetchPolicy(token, selectUnid)
            log(
                "rule: campus unid $selectUnid, window ${policy.validFromHour}-${policy.validToHour} h, " +
                    "pace ${policy.paceMinSecPerKm}-${policy.paceMaxSecPerKm} s/km, " +
                    "minDistance ${policy.minDistancePerRunM} m, dayGoal ${policy.maxDistancePerRunM} m, " +
                    "dailyLimit ${policy.dailyRunLimit}",
            )

            // No advertised rule body → outside the window or session rejected (CLI gate).
            if (policy.paceMinSecPerKm == 0 && policy.paceMaxSecPerKm == 0 &&
                policy.validFromHour == 0 && policy.validToHour == 0
            ) {
                fail("未取得学校规则（不在本校跑步时段，或会话已失效），已拒绝提交")
                return
            }

            // 每日次数上限 fail-closed admission: latest() hands back only the newest row,
            // so today's run COUNT (what an advertised limit needs) is not derivable here.
            if (policy.dailyRunLimit > 0) {
                fail(
                    "本校每日次数上限 ${policy.dailyRunLimit} 次，但历史接口只回传最新一条记录、" +
                        "无法核对今日次数 — 已拒绝（fail-closed），请人工确认今日次数后再跑",
                )
                return
            }

            // Sanity fence for issued checkpoints (CLI port): refuse anchor-relative or
            // null-island coordinates before anything is synthesized.
            val checkpoints = policy.requiredPoints + policy.optionalPoints
            val stray = checkpoints
                .firstOrNull { it.lat !in CHINA_MIN_LAT..CHINA_MAX_LAT || it.lng !in CHINA_MIN_LNG..CHINA_MAX_LNG }
            if (stray != null) {
                fail("点位 $stray 超出合理地理范围，已拒绝合成与提交")
                return
            }

            // Friendly precondition gate (11-25 incident): a school with NO server polygon
            // (geoFences absent) derives synthesis geometry from the issued checkpoints, so
            // an EMPTY five-point answer leaves nothing to synthesize. Refuse HERE — in
            // Chinese, with what was seen logged for 导出诊断 — before the synthesizer's
            // English `require` (or any OBS/upload work) can surface. Parity: CLI `Main.kt`.
            if (policy.fence.size < 3 && checkpoints.isEmpty()) {
                refuseNoGeometry(policy, checkpoints)
                return
            }

            // No server-side polygon (geoFences = null, the live school): derive synthesis
            // geometry from the issued checkpoints. Validation still runs against the REAL
            // policy and settlement never sees the derived fence (CLI port).
            val geometryFence = when {
                policy.fence.size >= 3 -> policy.fence
                checkpoints.isNotEmpty() -> deriveFence(checkpoints)
                else -> policy.fence
            }
            if (geometryFence !== policy.fence) {
                log("synth: derived ${geometryFence.size}-point fence around ${checkpoints.size} checkpoints")
            }

            // dayGoal is a *daily* total; a per-run target above it lifts the synthesis cap
            // (the validator's per-run floor still gates the submission) — CLI parity.
            val synthBase = if (policy.maxDistancePerRunM in 1 until distanceM) {
                policy.copy(fence = geometryFence, maxDistancePerRunM = 0)
            } else {
                policy.copy(fence = geometryFence)
            }

            // Route through ALL issued checkpoints when the geometry can host it, falling
            // back to required-only (CLI parity; `check_track` demands every point touched).
            val track = try {
                stages.synthesize(synthBase.copy(requiredPoints = checkpoints), distanceM, durationSec, seed)
            } catch (allPoints: IllegalArgumentException) {
                log("synth: all-checkpoint routing unavailable (${allPoints.message}); falling back to required-only")
                try {
                    stages.synthesize(synthBase, distanceM, durationSec, seed)
                } catch (requiredOnly: IllegalArgumentException) {
                    // Belt and suspenders: the gate above makes the empty-geometry case
                    // unreachable here, so this branch stays a backstop. Detect it
                    // STRUCTURALLY (fence handed to synthesis < 3 points) instead of
                    // pattern-matching the synthesizer's English require text — a wording
                    // change upstream can never re-leak raw English into the UI.
                    if (geometryFence.size < 3) {
                        refuseNoGeometry(policy, checkpoints)
                    } else {
                        fail("轨迹合成失败：${requiredOnly.message ?: "围栏/点位几何无法容纳本次跑步"}")
                    }
                    return
                }
            }
            log("synth: seed $seed, ${track.size} samples at 1 Hz")

            // ── THE GATE (brief: validator 违规 → 拒绝提交, upload never called) ──
            val violations = RunPolicyValidator.validate(track, policy, System.currentTimeMillis() + skewMs)
            if (violations.isNotEmpty()) {
                // No upload, no settle, nothing beyond the read-only policy fetch leaves the device.
                fail("拒绝提交（未通过本校规则校验）：" + violations.joinToString("；"))
                return
            }
            log("gate: passed (fence/checkpoints/pace/distance/window/face)")

            // ── Cross-session 每日 ≤1 (spec §5.5-4 #1): the newest history record must not
            // already be today's. Read-only, before any upload — a process restart can no
            // longer re-enable a second run of the same day.
            if (abortIfLoggedOut(startEpoch, "未核对历史")) return
            dailySelfLimitRefusal(stages.latestHistory(token), System.currentTimeMillis() + skewMs)
                ?.let { message ->
                    fail(message)
                    return
                }

            if (abortIfLoggedOut(startEpoch, "未上传")) return
            submissionAttempted = true
            val handle = stages.upload(token, track)
            log("upload: OBS push accepted, uuid ${handle.uuid}")

            // The record is created here: a logout that landed while the upload was on
            // the wire must still stop the save/record leg (the submission stays inert).
            if (abortIfLoggedOut(startEpoch, "未保存记录")) return
            val rrid = stages.settle(policy, track, handle)
            log("settle: save/record accepted, rrid=$rrid")

            if (settleWaitMs > 0) Thread.sleep(settleWaitMs) // upstream gives the store room
            if (abortIfLoggedOut(startEpoch, "未回查")) return
            val record = stages.latestHistory(token)
            log(
                "verify: " + (record?.let { "id=${it.id}, ${it.distanceM} m, valid=${it.valid}" } ?: "no record returned"),
            )

            // Verdict-free read-back: our `valid` flag diverges from the official scoring
            // in both directions, so it must never choose an error-vs-success message.
            // The submission itself already succeeded at settle (rrid above) either way —
            // only objective record facts are reported below.
            if (record == null) {
                fail("提交已接受 (rrid=$rrid) 但回查无记录")
            } else {
                succeed(record)
            }
        } catch (e: Exception) {
            fail(readable(e))
        }
    }

    /**
     * Terminal success: settlement accepted the record and the history read-back returned
     * it. The message states objective facts only (id · distance · date · official-app
     * pointer) — never a verdict derived from [RunRecord.valid] (see [RunState.Success]).
     */
    private fun succeed(record: RunRecord) {
        val message = "回查记录已写入：${recordFactLine(record)}（以官方 App 回查为准）"
        RecentResults.add("一键跑：$message")
        set(RunState.Success(record.distanceM, message))
    }

    /**
     * One objective read-back row: `id · distance · date` (the fixed [RunPolicyValidator.RULE_ZONE]
     * date, or an explicit placeholder when the row carries no usable `startTime`).
     */
    private fun recordFactLine(record: RunRecord): String {
        val date = record.startTimeMs?.let {
            ROW_DATE_FORMAT.format(Instant.ofEpochMilli(it).atZone(RunPolicyValidator.RULE_ZONE))
        } ?: "日期未知"
        return "id=${record.id} · ${record.distanceM} m · $date"
    }

    /**
     * Fix (invalidate in-flight work on logout): true when [SessionEpoch] moved past the
     * [startEpoch] captured at pipeline start — i.e. this pipeline outlived its own
     * logout. The caller aborts with a terminal non-success state ([at] names the leg
     * that did NOT run), so no dead-token re-install and no submission can follow.
     */
    private fun abortIfLoggedOut(startEpoch: Long, at: String): Boolean {
        if (SessionEpoch.current == startEpoch) return false
        fail("已退出登录，一键跑已中止（$at）")
        return true
    }

    /**
     * Terminal refusal for the empty-geometry case (no server polygon AND no issued
     * checkpoints). Logs **what the pipeline saw** — fence point count, checkpoint count,
     * advertised rule window — through [log] (MainActivity routes it into [RedactedLog],
     * so a later 导出诊断 can tell a transient five-point failure from an out-of-window
     * rule), then surfaces the actionable Chinese message instead of the synthesizer's
     * raw English `require`.
     */
    private fun refuseNoGeometry(policy: RunPolicy, checkpoints: List<LatLng>) {
        log(
            "synth: REFUSED — fence ${policy.fence.size} points, checkpoints ${checkpoints.size}, " +
                "window ${policy.validFromHour}-${policy.validToHour} h (no geometry to synthesize)",
        )
        fail(NO_GEOMETRY_MESSAGE)
    }

    /** Terminal refusal/failure: records the outcome for the diagnostic export, then shows it. */
    private fun fail(message: String) {
        RecentResults.add("一键跑：$message")
        set(RunState.Error(message))
    }

    private fun set(next: RunState) {
        state = next
    }

    /**
     * A user-readable failure message: exception type as fallback, and scrubbed with
     * [RedactedLog.sanitize] so a transport echo can never surface uid/phone/token in the UI
     * or in the exported diagnostics (PII rule; defense in depth beyond not interpolating them).
     */
    private fun readable(e: Exception): String {
        val raw = e.message?.takeIf { it.isNotBlank() } ?: e::class.simpleName ?: "提交失败"
        return RedactedLog.sanitize(raw)
    }

    private companion object {
        /**
         * Friendly refusal when there is no synthesis geometry at all (fence < 3 points
         * AND no issued checkpoints) — the 11-25 incident, where the raw English
         * `fence must be a polygon of at least 3 points, got 0` leaked into the UI.
         * Names the cause (school has no polygon → checkpoints required), the likely
         * reasons (transient five-point failure, or outside the issue window) and the
         * retry action. CLI `Main.kt` carries the same sentence for parity.
         */
        const val NO_GEOMETRY_MESSAGE =
            "点位数据未获取到（学校无围栏，合成需要至少 3 个点位）——" +
                "可能是网络瞬时失败或不在下发时段，请稍后点“刷新规则与进度”重试"

        /** Date shape of the objective read-back row (same as Home's history rows). */
        val ROW_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

        /** Mainland-China bounding box for issued checkpoints (risk control §5.5, CLI port). */
        const val CHINA_MIN_LAT = 5.0
        const val CHINA_MAX_LAT = 55.0
        const val CHINA_MIN_LNG = 70.0
        const val CHINA_MAX_LNG = 140.0

        /** ~110 m of padding in degrees; the floor keeps a degenerate box usable. */
        const val FENCE_PAD_DEG = 0.001
        const val FENCE_MIN_HALF_DEG = 0.0015

        /**
         * Bounding-box polygon (+padding) around the issued checkpoints, for schools that
         * advertise no `geoFences` polygon (the live school). Synthesis geometry only —
         * [RunPolicyValidator] runs against the real policy (CLI `deriveFence` port).
         */
        fun deriveFence(checkpoints: List<LatLng>): List<LatLng> {
            val latMin = checkpoints.minOf { it.lat }
            val latMax = checkpoints.maxOf { it.lat }
            val lngMin = checkpoints.minOf { it.lng }
            val lngMax = checkpoints.maxOf { it.lng }
            val latHalf = maxOf((latMax - latMin) / 2 + FENCE_PAD_DEG, FENCE_MIN_HALF_DEG)
            val lngHalf = maxOf((lngMax - lngMin) / 2 + FENCE_PAD_DEG, FENCE_MIN_HALF_DEG)
            val latCentre = (latMin + latMax) / 2
            val lngCentre = (lngMin + lngMax) / 2
            return listOf(
                LatLng(latCentre - latHalf, lngCentre - lngHalf),
                LatLng(latCentre + latHalf, lngCentre - lngHalf),
                LatLng(latCentre + latHalf, lngCentre + lngHalf),
                LatLng(latCentre - latHalf, lngCentre + lngHalf),
            )
        }
    }
}

/**
 * Production [RunStages]: the core protocol clients wired over one [ApiClient] — the App's
 * equivalent of the CLI's inline `executeRun` wiring (thin orchestration, all protocol
 * logic in `core`; the `save/record` success check mirrors `cli/Main.kt settleRrid`).
 */
class CoreRunStages(
    private val api: ApiClient,
    private val params: ProtocolParams,
) : RunStages {

    private val timeSync = TimeSync(api)

    override fun syncSkewMs(): Long = timeSync.serverOffsetMs()

    /**
     * The campus arrives from [RunViewModel], which resolves the persisted selection
     * Home writes — so the run executes against the campus Home displays. [PolicyClient]
     * sends this ONE value as `runModePolicy.selectUnid` **and** the five-point
     * `selectedUnid`, and the session [Token] is never rewritten (settlement keeps the
     * login unid — no Settlement change).
     */
    override fun fetchPolicy(token: Token, selectUnid: String): RunPolicy =
        PolicyClient(api).fetch(token, mode = 1, selectUnid = selectUnid)

    override fun synthesize(policy: RunPolicy, distanceM: Int, durationSec: Int, seed: Long): List<TrackPoint> =
        TrackSynthesizer.synthesize(policy, distanceM, durationSec, seed)

    override fun upload(token: Token, track: List<TrackPoint>): UploadHandle =
        RunUploader(api, NetSecKit, Signer, timeSync).uploadTrack(token, track)

    override fun settle(policy: RunPolicy, track: List<TrackPoint>, handle: UploadHandle): String? {
        val body = Json.parseToJsonElement(
            String(Settlement.buildRequest(policy, track, handle, params), Charsets.UTF_8),
        )
        return settleRrid(api.post(SAVE_RECORD_PATH, body))
    }

    override fun latestHistory(token: Token): RunRecord? = HistoryClient(api).latest(token)

    /** Upstream `submit_record` success check: `error == 10000`, `data.rrid | data.id`. */
    private fun settleRrid(response: JsonElement): String? {
        val root = response as? JsonObject
            ?: throw IllegalArgumentException("save/record response must be a JSON object")
        val error = (root["error"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toLong()
        if (error != BUSINESS_SUCCESS) {
            val message = (root["message"] as? JsonPrimitive)?.contentOrNull ?: "no message"
            throw IllegalStateException("server rejected the record: error=$error message=$message")
        }
        val data = root["data"] as? JsonObject ?: return null
        return (data["rrid"] as? JsonPrimitive)?.contentOrNull?.takeIf { it != "null" }
            ?: (data["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it != "null" }
    }

    private companion object {
        const val SAVE_RECORD_PATH = "/api/v70260/runnings/save/record"
        const val BUSINESS_SUCCESS = 10_000L
    }
}

/**
 * In-memory 最近提交结果摘要 for the one-tap diagnostic export (spec §6.3: 脱敏日志、配置
 * 版本、最近提交结果摘要). Process-scoped, no PII by construction (record ids, distances,
 * rule strings — never uid/phone/token), capped so a long session cannot grow unbounded.
 */
object RecentResults {
    private const val MAX_LINES = 20
    private val store = mutableListOf<String>()

    @Synchronized
    fun add(line: String) {
        store += RedactedLog.sanitize(line)
        if (store.size > MAX_LINES) store.removeAt(0)
    }

    @Synchronized
    fun lines(): List<String> = store.toList()
}
