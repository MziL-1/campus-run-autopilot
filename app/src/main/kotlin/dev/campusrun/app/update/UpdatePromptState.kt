package dev.campusrun.app.update

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.campusrun.app.home.UpdatePrompt
import dev.campusrun.core.config.RemoteVersionInfo
import dev.campusrun.core.config.UpdateChecker

/**
 * Single source of truth for the startup update prompt (spec G4/R5): turns the
 * `version.json` result into a **non-blocking** [prompt] state — `null` document (every
 * mirror failed) stays silent, `minApkVersionCode > current` raises the prompt, and a
 * dismissed prompt stays dismissed ([dismissed] keeps a later re-feed from nagging again).
 *
 * **State ownership (fix: prompt must show regardless of auth state):**
 * [dev.campusrun.autopilot.MainActivity] creates exactly ONE instance in `onCreate`,
 * *before* the `token == null` branch, and feeds it from the startup check unconditionally —
 * so an outdated build prompts on the LoginScreen too (pre-login), and the instance
 * survives login/logout transitions for the activity's lifetime. The dialog is rendered
 * ONLY from MainActivity's content (global layer); [dev.campusrun.app.home.HomeViewModel]
 * merely delegates to the same instance, so the dialog can never render twice and a
 * dismissal anywhere sticks everywhere.
 */
class UpdatePromptState(
    private val currentApkVersionCode: Int,
    private val updateChecker: UpdateChecker = UpdateChecker(log = {}),
) {

    /** The prompt to render; `null` = nothing to show (no update yet, or dismissed). */
    var prompt: UpdatePrompt? by mutableStateOf(null)
        private set

    /**
     * True once [dismiss] ran — the global layer's "show + dismissed" latch: a re-feed of
     * the same outdated document (e.g. re-entering Home after a login/logout cycle)
     * cannot re-raise a prompt the user already closed.
     */
    var dismissed: Boolean = false
        private set

    /**
     * Startup update check result (ruling: `null` fails silently to "no update"; no
     * network happens here — [dev.campusrun.autopilot.MainActivity] runs
     * [UpdateChecker.check] off the main thread and hands the document over).
     */
    fun onVersionCheck(info: RemoteVersionInfo?) {
        if (info == null) return
        if (dismissed) return
        if (updateChecker.updateRequired(info, currentApkVersionCode)) {
            prompt = UpdatePrompt(
                message = "发现新版本：最低要求版本号 ${info.minApkVersionCode}（当前 $currentApkVersionCode）。" +
                    "请下载更新最新 APK 后继续使用。",
                // First mirror only (spec §8-6 下载多镜像 URL): the dialog offers one link.
                downloadUrl = info.apkMirrors.firstOrNull(),
            )
        }
    }

    /** Closes the prompt (知道了 / tap-outside): non-blocking, never re-raised this run. */
    fun dismiss() {
        prompt = null
        dismissed = true
    }
}
