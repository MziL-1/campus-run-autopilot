package dev.campusrun.app.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.campusrun.app.update.UpdatePromptState
import dev.campusrun.core.api.ApiClient
import dev.campusrun.core.campus.CampusClient
import dev.campusrun.core.campus.CampusOption
import dev.campusrun.core.campus.CampusSelectionStore
import dev.campusrun.core.campus.InMemoryCampusSelectionStore
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
import dev.campusrun.core.upload.SemesterSummaryClient
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
 * - [cumulativeLine] is the **counted** client-side sum of the fetched page — only the
 *   official 达标 rows (`officialResult == true`, 计入成绩), scope-labeled `近N条` —
 *   the records response carries no server-side semester summary (findings §3.1 D),
 *   so it must never claim to be a semester total; rows without a verdict field are
 *   disclosed (`已知 N 条计入…未知`) rather than guessed.
 * - [historyRows] renders the fetched rows (up to 20) as date/distance/duration lines
 *   plus the **server-sourced** 达标/未达标 badge ([HistoryEntry.officialResult],
 *   correlated 5/5 vs the official app); rows without the field carry no badge —
 *   our divergent `valid` is never displayed.
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
    /**
     * The account's campuses from `GET /api/v61101/campus/zonelist` (`CampusClient`) —
     * empty when the leg failed or the platform offers none, in which case the header
     * stays the plain profile line (no fake affordance).
     */
    val campuses: List<CampusOption> = emptyList(),
    /** The campus Home currently reads the rule for (persisted choice, else the platform's `isSelected`); `null` = session default. */
    val selectedCampusUnid: Int? = null,
    /** 当前已完成的里程 — counted (计入成绩) client-side sum of the fetched page, scope-labeled. */
    val cumulativeLine: String = "",
    /** Newest record, objective fields + the server-sourced badge; `""` when there is no record. */
    val latestLine: String = "",
    /** 历史跑步记录 — one line per fetched row (date · distance · duration · 达标/未达标 badge). */
    val historyRows: List<String> = emptyList(),
    val error: String? = null,
)

/** Read-only seam for Home: the three core fetches, fakeable in [HomeViewModelTest]. */
interface HomeData {
    fun fetchPolicy(token: Token): RunPolicy

    /** Best-effort: `null` (or a thrown transport error) leaves the school name blank. */
    fun fetchProfile(token: Token): UserProfile?

    /** Page 1 of history rows (objective fields + official verdict); empty list when there is no history. */
    fun fetchHistory(token: Token): List<HistoryEntry>

    /**
     * Server-side counted semester total in meters (官方 计入成绩); `null` when the
     * server offers none — best-effort, the caller falls back to the page sum.
     */
    fun fetchCountedSemester(token: Token): Int?

    /**
     * The account's campuses (read-only `zonelist` leg); `null` = no answer (business
     * error / transport), an empty list = the platform reports none. Defaulted so the
     * pre-campus call sites keep compiling against the unchanged contract.
     */
    fun fetchCampuses(token: Token): List<CampusOption>? = null
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
    /**
     * Persisted campus choice (which campus's rule Home reads). Session-only by default
     * (unit tests); MainActivity passes the [dev.campusrun.app.data.AndroidCampusSelectionStore]
     * so a refresh or an app restart keeps the selection. Never a credential — it only
     * decides the `selectUnid` the policy leg sends, so the session token (and therefore
     * settlement's `selectedUnid`) is untouched.
     */
    private val campusSelection: CampusSelectionStore = InMemoryCampusSelectionStore(),
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
                // Campus leg (best-effort like the profile leg): the persisted choice is
                // local, the list is the platform's — either may be absent without
                // failing the risk-control facts below.
                val campuses = runCatching { data.fetchCampuses(token) }.getOrNull()
                if (loggedOut) return@execute
                val stored = campusSelection.load()?.toIntOrNull()
                // Self-heal: a choice the platform no longer lists is dropped, but only
                // when we actually got a list (`null` = no answer → keep the choice).
                if (stored != null && campuses != null &&
                    campuses.isNotEmpty() && campuses.none { it.unid == stored }
                ) {
                    campusSelection.clear()
                }
                val effective = effectiveCampusUnid(stored, campuses)
                if (loggedOut) return@execute
                // The campus choice reaches the wire ONLY as the policy leg's unid
                // (upstream `selectUnid`/`selectedUnid`); the session token is not
                // rewritten, so settlement keeps the login unid (no Settlement change).
                val policyToken = if (effective == null || effective == token.unid.toIntOrNull()) {
                    token
                } else {
                    token.copy(unid = effective.toString())
                }
                val policy = data.fetchPolicy(policyToken)
                if (loggedOut) return@execute
                val profile = runCatching { data.fetchProfile(token) }.getOrNull()
                if (loggedOut) return@execute
                val history = data.fetchHistory(token)
                if (loggedOut) return@execute
                // Best-effort like the profile leg: a missing/failing server total
                // degrades to the counted page sum, never fails the refresh.
                val serverCountedM = runCatching { data.fetchCountedSemester(token) }.getOrNull()
                if (loggedOut) return@execute
                state = HomeUiState(
                    loading = false,
                    rules = ruleLines(policy),
                    requirementLine = requirementText(policy),
                    schoolName = profile?.schoolName.orEmpty(),
                    nickname = profile?.displayName.orEmpty(),
                    campuses = campuses.orEmpty(),
                    selectedCampusUnid = effective,
                    cumulativeLine = cumulativeText(history, serverCountedM),
                    latestLine = latestText(history.firstOrNull()),
                    historyRows = history.map(::historyRowText),
                )
            } catch (e: Exception) {
                state = state.copy(loading = false, error = readable(e))
            }
        }
    }

    /**
     * 校区切换 (campus switcher): the dropdown only ever offers the account's OWN
     * campuses from the last refresh, so an out-of-list id is ignored (no blind write).
     *
     * The choice is persisted first (a refresh or an app restart keeps it) and then the
     * whole Home reload runs — policy first, so the new campus's rule/points are what
     * render. Only the policy leg's `selectUnid` changes; the session token, and with it
     * settlement's `selectedUnid`, is untouched.
     */
    fun selectCampus(unid: Int) {
        if (loggedOut) return
        if (!campusSwitchable(state.campuses) || state.campuses.none { it.unid == unid }) return
        campusSelection.save(unid.toString())
        refresh()
    }

    /**
     * Logout (spec §5.5-3): drops the persisted session through core's [TokenStore] —
     * the session-keys-only contract — so the device-identity salt/snapshot, the install
     * keys and ParamsStore stay untouched and the device id never churns. Idempotent:
     * a second call while [loggedOut] is a no-op (no double clear, no crash).
     *
     * The [loggedOut] signal is raised BEFORE the drop (fix: invalidate in-flight work
     * on logout): the refresh worker checks it before each token-taking leg, so no leg
     * can start once the session is being cleared. The persisted campus choice is
     * dropped with the session (per-account, see [selectCampus]).
     */
    fun logout() {
        if (loggedOut) return
        loggedOut = true
        // The campus choice is per-account: a later login on this device must not
        // inherit it (same rule as the session keys).
        campusSelection.clear()
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
 * 当前已完成的里程 (brief item 3) — **counted only (计入成绩)**:
 *
 * - **preferred**: [serverCountedM] — the server-side semester total
 *   (`SemesterSummaryClient` → `semesterDis`, the official 计入成绩 number; live probe
 *   4039 = exactly the two 达标 rows while the naive page sums to 10129). Labeled with
 *   its source (`服务端学期口径`) because it IS a semester total — the only case where
 *   claiming 学期 is honest.
 * - **fallback** (server leg absent/failed): the fetched page's official 达标 rows
 *   ([HistoryEntry.officialResult] `true`, correlated 5/5 against the official app —
 *   see `rowOfficialResult`), scope-labeled `近N条` because the records API exposes no
 *   semester summary (findings §3.1 D — `data` is a bare row array), so presenting
 *   this as a semester total would fabricate completeness we do not have.
 *
 * An empty page is the empty-state copy, not a zero total.
 *
 * Honest fallbacks when the verdict field is absent (older/legacy rows, client-side
 * path only):
 * - some rows unknown → the counted sum plus `已知 N 条计入，M 条计分状态未知` —
 *   unknown rows are disclosed, never silently counted or dropped;
 * - all rows unknown → `未知` instead of a fabricated `0` total.
 */
internal fun cumulativeText(rows: List<HistoryEntry>, serverCountedM: Int? = null): String {
    // The server total outranks the page entirely: an empty/failed page must not
    // mask a real server-side counted total (and vice versa — the server number is
    // the official 计入成绩 fact, the page sum is only the labeled fallback).
    serverCountedM?.let { meters ->
        return "已完成（计入成绩，服务端学期口径）：${formatDistance(meters)}"
    }
    if (rows.isEmpty()) return "暂无跑步记录"
    val counted = rows.filter { it.officialResult == true }
    val unknown = rows.count { it.officialResult == null }
    val distance = formatDistance(counted.sumOf { it.distanceM })
    val scope = "近 ${rows.size} 条"
    val head = "已完成（计入成绩，$scope）"
    return when {
        unknown == 0 -> "$head：$distance"
        unknown == rows.size -> "$head：未知（全部 $unknown 条计分状态未知）"
        else -> "$head：$distance（已知 ${counted.size} 条计入，$unknown 条计分状态未知）"
    }
}

/** `totalM` → `2.03 km` at ≥1000 m, otherwise `800 m` (sub-km stays in meters). */
private fun formatDistance(totalM: Int): String =
    if (totalM >= 1000) String.format(Locale.ROOT, "%.2f km", totalM / 1000.0) else "$totalM m"

/**
 * One 历史跑步记录 row (brief item 4): date · distance · duration, plus the
 * **server-sourced** 达标/未达标 badge when the row carries an official verdict
 * ([HistoryEntry.officialResult]); rows without the field carry no badge — a verdict
 * we do not have must never be stated. Unknown `startTime`/`totalTime` degrade to
 * explicit placeholders — printing epoch 0 or a 0 s duration would state a fact we do
 * not have.
 */
internal fun historyRowText(row: HistoryEntry): String =
    "${rowDate(row.startTimeMs)} · ${row.distanceM} m · ${formatDuration(row.durationSec)}${verdictBadge(row.officialResult)}"

/**
 * The official verdict badge: ` · 达标` / ` · 未达标` from the server's scoring field,
 * `""` when the row carries no verdict (never a guess, never our divergent `valid`).
 */
internal fun verdictBadge(officialResult: Boolean?): String = when (officialResult) {
    true -> " · 达标"
    false -> " · 未达标"
    null -> ""
}

/** Newest record as one objective line + the server-sourced badge; `""` when there is no record at all. */
internal fun latestText(row: HistoryEntry?): String {
    if (row == null) return ""
    return "最近记录：id=${row.id} · ${rowDate(row.startTimeMs)} · " +
        "${row.distanceM} m · ${formatDuration(row.durationSec)}${verdictBadge(row.officialResult)}"
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

/** Production [HomeData]: the read-only core clients over the shared [ApiClient]. */
class CoreHomeData(private val api: ApiClient) : HomeData {
    override fun fetchPolicy(token: Token): RunPolicy = PolicyClient(api).fetch(token, mode = 1)

    override fun fetchProfile(token: Token): UserProfile? = ProfileClient(api).fetch(token)

    override fun fetchHistory(token: Token): List<HistoryEntry> = HistoryClient(api).list(token)

    override fun fetchCountedSemester(token: Token): Int? = SemesterSummaryClient(api).countedMeters(token)

    override fun fetchCampuses(token: Token): List<CampusOption>? = CampusClient(api).fetch(token)
}

/**
 * The dropdown is offered **only** for a real choice (brief: keep it honest — a single
 * campus renders the plain profile line, never a menu that cannot go anywhere).
 */
internal fun campusSwitchable(campuses: List<CampusOption>): Boolean = campuses.size > 1

/**
 * Which campus's rule the policy leg should read:
 *
 * 1. the persisted choice while the platform still lists it (survives refresh/restart);
 * 2. otherwise the platform's own `isSelected` campus;
 * 3. `null` = no override — the policy keeps the session's `unid`.
 *
 * [campuses] is `null` when the list leg gave no answer: the persisted choice is then
 * kept (a transient failure must not silently move the user back to another campus),
 * while a real empty list yields `null` (the platform reports no campuses at all).
 */
internal fun effectiveCampusUnid(stored: Int?, campuses: List<CampusOption>?): Int? {
    campuses ?: return stored
    if (stored != null && campuses.any { it.unid == stored }) return stored
    return campuses.firstOrNull { it.isSelected }?.unid
}
