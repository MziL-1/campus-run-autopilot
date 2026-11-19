package dev.campusrun.app.run

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.campusrun.core.api.ApiClient
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
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.random.Random

/**
 * One-shot run state machine (brief Step 2):
 *
 * ```text
 * Idle ──► Running ──► Success(distanceM, msg)   gate passed, uploaded, verified 达标
 *   │         │       └► Error(msg)              gate refusal / stage failure / 回查未达标
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

    /** Submitted, read back and 达标 — [distanceM] is the server-confirmed record distance. */
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

    fun fetchPolicy(token: Token): RunPolicy

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
        set(RunState.Running)
        try {
            val seed = Random.nextLong()
            val skewMs = stages.syncSkewMs()
            log("sync: server clock skew $skewMs ms")

            val policy = stages.fetchPolicy(token)
            log(
                "rule: window ${policy.validFromHour}-${policy.validToHour} h, " +
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
            val stray = (policy.requiredPoints + policy.optionalPoints)
                .firstOrNull { it.lat !in CHINA_MIN_LAT..CHINA_MAX_LAT || it.lng !in CHINA_MIN_LNG..CHINA_MAX_LNG }
            if (stray != null) {
                fail("点位 $stray 超出合理地理范围，已拒绝合成与提交")
                return
            }

            // No server-side polygon (geoFences = null, the live school): derive synthesis
            // geometry from the issued checkpoints. Validation still runs against the REAL
            // policy and settlement never sees the derived fence (CLI port).
            val checkpoints = policy.requiredPoints + policy.optionalPoints
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
                    fail("轨迹合成失败：${requiredOnly.message ?: "围栏/点位几何无法容纳本次跑步"}")
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
            dailySelfLimitRefusal(stages.latestHistory(token), System.currentTimeMillis() + skewMs)
                ?.let { message ->
                    fail(message)
                    return
                }

            submissionAttempted = true
            val handle = stages.upload(token, track)
            log("upload: OBS push accepted, uuid ${handle.uuid}")

            val rrid = stages.settle(policy, track, handle)
            log("settle: save/record accepted, rrid=$rrid")

            if (settleWaitMs > 0) Thread.sleep(settleWaitMs) // upstream gives the store room
            val record = stages.latestHistory(token)
            log(
                "verify: " + (record?.let { "id=${it.id}, ${it.distanceM} m, valid=${it.valid}" } ?: "no record returned"),
            )

            when {
                record == null -> fail("提交已接受 (rrid=$rrid) 但回查无记录，无法判定")
                record.valid -> succeed(record)
                else -> fail("回查未达标：${record.distanceM} m，记录 id=${record.id}")
            }
        } catch (e: Exception) {
            fail(readable(e))
        }
    }

    private fun succeed(record: RunRecord) {
        val message = "提交成功：${record.distanceM} m，达标（记录 id=${record.id}）"
        RecentResults.add("一键跑：$message")
        set(RunState.Success(record.distanceM, message))
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

    override fun fetchPolicy(token: Token): RunPolicy = PolicyClient(api).fetch(token, mode = 1)

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
