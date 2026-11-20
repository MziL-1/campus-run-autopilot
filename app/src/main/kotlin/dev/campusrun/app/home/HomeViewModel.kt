package dev.campusrun.app.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.campusrun.core.api.ApiClient
import dev.campusrun.core.config.RemoteVersionInfo
import dev.campusrun.core.config.UpdateChecker
import dev.campusrun.core.diag.RedactedLog
import dev.campusrun.core.policy.PolicyClient
import dev.campusrun.core.policy.RunPolicy
import dev.campusrun.core.session.Token
import dev.campusrun.core.session.TokenStore
import dev.campusrun.core.upload.HistoryClient
import dev.campusrun.core.upload.RunRecord
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Home screen state (brief: 进度/历史). [rules] and [historyLine] come from the two read-only
 * core clients (spec §5.5-6: 本校规则 + 当前进度 visible so users judge for themselves);
 * [error] carries a sanitized load failure. The update prompt lives in
 * [HomeViewModel.updatePrompt] — a separate state so a refresh can never clobber it.
 */
data class HomeUiState(
    val loading: Boolean = true,
    val rules: List<String> = emptyList(),
    val historyLine: String = "",
    val error: String? = null,
)

/** Read-only seam for Home: the two core fetches, fakeable in [HomeViewModelTest]. */
interface HomeData {
    fun fetchPolicy(token: Token): RunPolicy

    fun latest(token: Token): RunRecord?
}

/**
 * The non-blocking update prompt (spec G4/R5): the human-readable [message] plus the
 * first `apkMirrors` URL so the user can actually reach the download ([downloadUrl] is
 * null only when the document carried no mirror).
 */
data class UpdatePrompt(val message: String, val downloadUrl: String?)

/**
 * Home state owner: loads the school rules + latest history record on [executor], and
 * turns the startup `version.json` result ([onVersionCheck]) into a **non-blocking**
 * prompt state — `null` document (every mirror failed) stays silent, `minApkVersionCode >
 * current` raises [updatePrompt] (spec G4/R5, ruling: fail-silent + dismissible).
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
) {

    var state: HomeUiState by mutableStateOf(HomeUiState())
        private set

    /**
     * True once [logout] dropped the persisted session — [MainActivity] navigates back
     * to the login screen on it. Idempotent: further logouts/refreshes are no-ops.
     */
    var loggedOut: Boolean by mutableStateOf(false)
        private set

    /** Update-required prompt (提示态); `null` = no prompt. Dismissible, never blocking. */
    var updatePrompt: UpdatePrompt? by mutableStateOf(null)
        private set

    /**
     * The core update rule itself ([UpdateChecker.updateRequired]); no network happens here —
     * [MainActivity] runs [UpdateChecker.check] off the main thread and hands the document over.
     */
    private val updateChecker = UpdateChecker(log = {})

    /** Loads rules + progress (brief: 首页显示本校规则与进度). Safe to call repeatedly. */
    fun refresh() {
        if (loggedOut) return // already logged out: the Home is gone, nothing to load.
        state = state.copy(loading = true, error = null)
        executor.execute {
            try {
                val policy = data.fetchPolicy(token)
                val record = data.latest(token)
                state = HomeUiState(
                    loading = false,
                    rules = ruleLines(policy),
                    historyLine = historyText(record),
                )
            } catch (e: Exception) {
                state = state.copy(loading = false, error = readable(e))
            }
        }
    }

    /** Startup update check result (ruling: `null` fails silently to "no update"). */
    fun onVersionCheck(info: RemoteVersionInfo?) {
        if (info == null) return
        if (updateChecker.updateRequired(info, currentApkVersionCode)) {
            updatePrompt = UpdatePrompt(
                message = "发现新版本：最低要求版本号 ${info.minApkVersionCode}（当前 $currentApkVersionCode）。" +
                    "请下载更新最新 APK 后继续使用。",
                // First mirror only (spec §8-6 下载多镜像 URL): the dialog offers one link.
                downloadUrl = info.apkMirrors.firstOrNull(),
            )
        }
    }

    fun dismissUpdatePrompt() {
        updatePrompt = null
    }

    /**
     * Logout (spec §5.5-3): drops the persisted session through core's [TokenStore] —
     * the session-keys-only contract — so the device-identity salt/snapshot, the install
     * keys and ParamsStore stay untouched and the device id never churns. Idempotent:
     * a second call while [loggedOut] is a no-op (no double clear, no crash).
     */
    fun logout() {
        if (loggedOut) return
        tokenStore.clear()
        loggedOut = true
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
        if (policy.requiredPoints.isNotEmpty() || policy.optionalPoints.isNotEmpty()) {
            add("点位：必经 ${policy.requiredPoints.size} 个，可选 ${policy.optionalPoints.size} 个")
        }
    }

    /** 当前进度 from HistoryClient: the newest record, or the empty-state copy. */
    private fun historyText(record: RunRecord?): String = when (record) {
        null -> "暂无跑步记录"
        else -> "最近记录：id=${record.id} · ${record.distanceM} m · " +
            if (record.valid) "达标" else "未达标"
    }

    /** Seconds-per-km → `m:ss`. */
    private fun minuteSec(seconds: Int): String = "%d:%02d".format(seconds / 60, seconds % 60)

    /** Sanitized so a transport echo can never surface uid/phone/token in the UI (PII rule). */
    private fun readable(e: Exception): String {
        val raw = e.message?.takeIf { it.isNotBlank() } ?: e::class.simpleName ?: "加载失败"
        return RedactedLog.sanitize(raw)
    }
}

/** Production [HomeData]: the two read-only core clients over the shared [ApiClient]. */
class CoreHomeData(private val api: ApiClient) : HomeData {
    override fun fetchPolicy(token: Token): RunPolicy = PolicyClient(api).fetch(token, mode = 1)

    override fun latest(token: Token): RunRecord? = HistoryClient(api).latest(token)
}
