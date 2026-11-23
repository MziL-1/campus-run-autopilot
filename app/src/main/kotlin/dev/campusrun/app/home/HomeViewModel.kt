package dev.campusrun.app.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.campusrun.app.update.UpdatePromptState
import dev.campusrun.core.api.ApiClient
import dev.campusrun.core.config.RemoteVersionInfo
import dev.campusrun.core.diag.RedactedLog
import dev.campusrun.core.policy.PolicyClient
import dev.campusrun.core.policy.RunPolicy
import dev.campusrun.core.policy.RunPolicyValidator
import dev.campusrun.core.profile.ProfileClient
import dev.campusrun.core.profile.UserProfile
import dev.campusrun.core.session.Token
import dev.campusrun.core.session.TokenStore
import dev.campusrun.core.upload.HistoryClient
import dev.campusrun.core.upload.HistoryEntry
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Home screen state (brief: 进度/历史/校园).
 *
 * - [rules] and [requirementLine] come from the read-only [PolicyClient] leg (spec §5.5-6:
 *   本校规则 + the school's required mileage, surfaced prominently so users judge for
 *   themselves). [requirementLine] is its own field so the requirement reads as a fact
 *   even when the full rule card is scanned past.
 * - [schoolName] / [nickname] come from the new read-only [ProfileClient] leg — the
 *   user's OWN school name and nickname, shown on-device only (PII rule: never the 真名).
 * - [cumulativeLine] is the **client-side sum of the fetched history page**, labeled
 *   `近N条累计` — the records response carries no server-side semester summary
 *   (findings §3.1 D), so it must never claim to be a semester total.
 * - [historyRows] renders the fetched rows (up to 20) as objective date/distance/duration
 *   lines — **no 达标/未达标 verdict**: our `valid` derivation diverges from the official
 *   scoring in both directions, so it must not be presented as fact.
 * - [error] carries a sanitized load failure.
 *
 * The update prompt is NOT owned here — it lives in the shared
 * [dev.campusrun.app.update.UpdatePromptState] (MainActivity-owned, see its KDoc), so a
 * refresh can never clobber it and the dialog renders once, globally.
 */
data class HomeUiState(
    val loading: Boolean = true,
    val rules: List<String> = emptyList(),
    /** 学校要求的里程 — prominent single line; `""` until the policy leg lands. */
    val requirementLine: String = "",
    /** 当前校园名 from `user/info`; `""` until the profile leg lands (or on its failure). */
    val schoolName: String = "",
    /** Optional nickname (user's own, on-device); `""` when the server sends none. */
    val nickname: String = "",
    /** 当前已完成的里程 — client-side sum of the fetched page, scope-labeled. */
    val cumulativeLine: String = "",
    /** Newest record, objective fields only; `""` when there is no record. */
    val latestLine: String = "",
    /** 历史跑步记录 — one objective line per fetched row (date · distance · duration). */
    val historyRows: List<String> = emptyList(),
    val error: String? = null,
)

/** Read-only seam for Home: the three core fetches, fakeable in [HomeViewModelTest]. */
interface HomeData {
    fun fetchPolicy(token: Token): RunPolicy

    /** Best-effort: `null` (or a thrown transport error) leaves the school name blank. */
    fun fetchProfile(token: Token): UserProfile?

    /** Page 1 of history as objective rows; empty list when there is no history. */
    fun fetchHistory(token: Token): List<HistoryEntry>
}

/**
 * The non-blocking update prompt (spec G4/R5): the human-readable [message] plus the
 * first `apkMirrors` URL so the user can actually reach the download ([downloadUrl] is
 * null only when the document carried no mirror).
 */
data class UpdatePrompt(val message: String, val downloadUrl: String?)

/**
 * Home state owner: loads the school rules + required mileage, the profile (school
 * name/nickname) and the history page on [executor], and **views** the startup
 * `version.json` result ([onVersionCheck]) through the shared [updatePromptState] — the
 * prompt itself is owned by MainActivity's global layer (fix: show regardless of auth
 * state) so it can raise before login too; this VM only ever reads/delegates to the same
 * instance (single source of truth → no double dialog).
 *
 * The profile leg is **cosmetic and best-effort**: a failed `user/info` call degrades to
 * a blank school name and never fails the refresh, because rules + history are the
 * risk-control facts and must still render.
 *
 * [logout] is the session-drop seam (spec §5.5-3): it reaches ONLY core's session-key
 * [TokenStore] contract, so device identity, install keys and ParamsStore are
 * structurally out of reach — a logout can never re-derive the device id.
 */
class HomeViewModel(
    private val data: HomeData,
    private val token: Token,
    private val currentApkVersionCode: Int,
    private val executor: Executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "home-loader").apply { isDaemon = true }
    },
    private val tokenStore: TokenStore,
    /**
     * The shared prompt state ([dev.campusrun.app.update.UpdatePromptState]) — MainActivity
     * passes the one instance it created before the auth branch; the default (a fresh,
     * Home-local holder) exists only for unit tests. Delegating to a shared instance is
     * what keeps "show + dismissed" in one place: dismissing from the global dialog clears
     * [updatePrompt] here too, so nothing can render the dialog a second time.
     */
    private val updatePromptState: UpdatePromptState = UpdatePromptState(currentApkVersionCode),
) {

    var state: HomeUiState by mutableStateOf(HomeUiState())
        private set

    /**
     * True once [logout] dropped the persisted session — [MainActivity] navigates back
     * to the login screen on it. Idempotent: further logouts/refreshes are no-ops.
     */
    var loggedOut: Boolean by mutableStateOf(false)
        private set

    /**
     * Update-required prompt (提示态); `null` = no prompt. A **view** over the shared
     * [updatePromptState] (see the constructor KDoc for ownership) — kept under this name
     * so the existing Home tests and call sites read unchanged.
     */
    val updatePrompt: UpdatePrompt? get() = updatePromptState.prompt

    /** Startup update check result — delegates to the shared holder (fail-silent: `null` → no prompt). */
    fun onVersionCheck(info: RemoteVersionInfo?) = updatePromptState.onVersionCheck(info)

    /** Dismisses the prompt — clears the shared holder, i.e. the global dialog too. */
    fun dismissUpdatePrompt() = updatePromptState.dismiss()

    /** Loads rules + profile + history (brief: 首页显示校园名、要求里程与进度历史). Safe to call repeatedly. */
    fun refresh() {
        if (loggedOut) return // already logged out: the Home is gone, nothing to load.
        state = state.copy(loading = true, error = null)
        executor.execute {
            try {
                // Fix (invalidate in-flight work on logout): every token-taking leg
                // re-checks — PolicyClient/ProfileClient/HistoryClient re-install their
                // token into the shared ApiClient at leg entry, so a leg started after
                // the drop would resurrect the cleared credentials.
                if (loggedOut) return@execute
                val policy = data.fetchPolicy(token)
                if (loggedOut) return@execute
                val profile = runCatching { data.fetchProfile(token) }.getOrNull()
                if (loggedOut) return@execute
                val history = data.fetchHistory(token)
                if (loggedOut) return@execute
                state = HomeUiState(
                    loading = false,
                    rules = ruleLines(policy),
                    requirementLine = requirementText(policy),
                    schoolName = profile?.schoolName.orEmpty(),
                    nickname = profile?.displayName.orEmpty(),
                    cumulativeLine = cumulativeText(history),
                    latestLine = latestText(history.firstOrNull()),
                    historyRows = history.map(::historyRowText),
                )
            } catch (e: Exception) {
                state = state.copy(loading = false, error = readable(e))
            }
        }
    }

    /**
     * Logout (spec §5.5-3): drops the persisted session through core's [TokenStore] —
     * the session-keys-only contract — so the device-identity salt/snapshot, the install
     * keys and ParamsStore stay untouched and the device id never churns. Idempotent:
     * a second call while [loggedOut] is a no-op (no double clear, no crash).
     *
     * The [loggedOut] signal is raised BEFORE the drop (fix: invalidate in-flight work
     * on logout): the refresh worker checks it before each token-taking leg, so no leg
     * can start once the session is being cleared.
     */
    fun logout() {
        if (loggedOut) return
        loggedOut = true
        tokenStore.clear()
    }

    /** Spec §5.5-6 规则卡: 次数 / 里程 / 配速 / 时段 (+必经点), "未公布" when not advertised. */
    private fun ruleLines(policy: RunPolicy): List<String> = buildList {
        add(
            if (policy.validFromHour == 0 && policy.validToHour == 0) {
                "时段：未公布"
            } else {
                "时段：${policy.validFromHour}:00-${policy.validToHour}:00"
            },
        )
        add(
            if (policy.paceMinSecPerKm == 0 && policy.paceMaxSecPerKm == 0) {
                "配速：未公布"
            } else {
                "配速：${minuteSec(policy.paceMinSecPerKm)}-${minuteSec(policy.paceMaxSecPerKm)} 分/公里"
            },
        )
        val min = if (policy.minDistancePerRunM > 0) {
            "最低 ${policy.minDistancePerRunM} m"
        } else {
            "最低未公布（按 2000 m 结算下限）"
        }
        val cap = if (policy.maxDistancePerRunM > 0) {
            "单日上限 ${policy.maxDistancePerRunM} m"
        } else {
            "单日上限未公布"
        }
        add("单次里程：$min，$cap")
        add(
            if (policy.dailyRunLimit > 0) {
                "每日次数：上限 ${policy.dailyRunLimit} 次"
            } else {
                "每日次数：未公布（建议每日 1 次自限）"
            },
        )
        // 学期/每周要求次数 (guidance #5): 7.4.x advertises weekCountGoal only — shown
        // when present, omitted otherwise (the weekly goal is not a daily cap, so it must
        // not masquerade as one).
        if (policy.weekCountGoal > 0) {
            add("每周要求：${policy.weekCountGoal} 次")
        }
        if (policy.requiredPoints.isNotEmpty() || policy.optionalPoints.isNotEmpty()) {
            add("点位：必经 ${policy.requiredPoints.size} 个，可选 ${policy.optionalPoints.size} 个")
        }
    }

    /** Seconds-per-km → `m:ss`. */
    private fun minuteSec(seconds: Int): String = "%d:%02d".format(seconds / 60, seconds % 60)

    /** Sanitized so a transport echo can never surface uid/phone/token in the UI (PII rule). */
    private fun readable(e: Exception): String {
        val raw = e.message?.takeIf { it.isNotBlank() } ?: e::class.simpleName ?: "加载失败"
        return RedactedLog.sanitize(raw)
    }
}

/**
 * 学校要求的里程 (brief item 2): the rule's per-run floor and daily cap as one prominent
 * line. An out-of-window/empty policy (7.4.x returns no `runRuleModel` before 10:00,
 * findings §3.5) reads `未公布` — never a made-up number.
 */
internal fun requirementText(policy: RunPolicy): String {
    val min = policy.minDistancePerRunM
    val cap = policy.maxDistancePerRunM
    if (min <= 0 && cap <= 0) return "学校要求：未公布"
    val parts = buildList {
        if (min > 0) add("单次 ≥ $min m") else add("单次未公布")
        if (cap > 0) add("单日 ≤ $cap m") else add("单日未公布")
    }
    return "学校要求：" + parts.joinToString("，")
}

/**
 * 当前已完成的里程 (brief item 3): the sum of the **fetched page's** rows, labeled with
 * that exact scope (`近N条累计`). The records API exposes no semester summary
 * (findings §3.1 D — `data` is a bare row array), so presenting this as a semester total
 * would fabricate completeness we do not have; the label keeps the claim honest.
 * An empty page is the empty-state copy, not a zero total.
 */
internal fun cumulativeText(rows: List<HistoryEntry>): String {
    if (rows.isEmpty()) return "暂无跑步记录"
    val totalM = rows.sumOf { it.distanceM }
    val distance = if (totalM >= 1000) {
        String.format(Locale.ROOT, "%.2f km", totalM / 1000.0)
    } else {
        "$totalM m"
    }
    return "已完成：近 ${rows.size} 条累计 $distance"
}

/**
 * One 历史跑步记录 row (brief item 4): date · distance · duration, objective fields
 * only. Unknown `startTime`/`totalTime` degrade to explicit placeholders — printing
 * epoch 0 or a 0 s duration would state a fact we do not have.
 */
internal fun historyRowText(row: HistoryEntry): String =
    "${rowDate(row.startTimeMs)} · ${row.distanceM} m · ${formatDuration(row.durationSec)}"

/** Newest record as one objective line; `""` when there is no record at all. */
internal fun latestText(row: HistoryEntry?): String {
    if (row == null) return ""
    return "最近记录：id=${row.id} · ${rowDate(row.startTimeMs)} · " +
        "${row.distanceM} m · ${formatDuration(row.durationSec)}"
}

/** `totalTime` seconds → `m:ss` under an hour, `h:mm:ss` above; unknown stays unknown. */
internal fun formatDuration(seconds: Int?): String = when {
    seconds == null || seconds <= 0 -> "时长未知"
    seconds < 3600 -> "%d:%02d".format(seconds / 60, seconds % 60)
    else -> "%d:%02d:%02d".format(seconds / 3600, (seconds % 3600) / 60, seconds % 60)
}

/**
 * `startTime` epoch ms → `MM-dd HH:mm` in the fixed `Asia/Shanghai` rule zone
 * ([RunPolicyValidator.RULE_ZONE]); unknown stays unknown.
 */
private fun rowDate(startTimeMs: Long?): String {
    if (startTimeMs == null) return "日期未知"
    return ROW_DATE_FORMAT.format(Instant.ofEpochMilli(startTimeMs).atZone(RunPolicyValidator.RULE_ZONE))
}

private val ROW_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

/** Production [HomeData]: the three read-only core clients over the shared [ApiClient]. */
class CoreHomeData(private val api: ApiClient) : HomeData {
    override fun fetchPolicy(token: Token): RunPolicy = PolicyClient(api).fetch(token, mode = 1)

    override fun fetchProfile(token: Token): UserProfile? = ProfileClient(api).fetch(token)

    override fun fetchHistory(token: Token): List<HistoryEntry> = HistoryClient(api).list(token)
}
