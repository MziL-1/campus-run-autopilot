package dev.campusrun.app.login

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.campusrun.core.session.Token
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Login state machine (brief Step 2 / ruling 2):
 *
 * ```text
 * Idle ──► NeedCaptcha ──► Submitting ──► Success(Token)
 *   │           │              │
 *   └───────────┴──────────────┴──────► Error(msg)
 * ```
 *
 * Transitions are driven by [CaptchaHandler]: `solve()` publishes `NeedCaptcha` and
 * blocks the login worker until the UI delivers a proof (then `Submitting`), `skip()`
 * publishes `Submitting` directly for whitelisted attempts.
 *
 * [state] is Compose snapshot state so [LoginScreen] recomposes on every transition;
 * it is written only from the login worker thread (or the test's direct executor).
 */
sealed class LoginState {
    data object Idle : LoginState()
    data object NeedCaptcha : LoginState()
    data object Submitting : LoginState()
    data class Success(val token: Token) : LoginState()
    data class Error(val message: String) : LoginState()
}

/**
 * Owns the login flow: one attempt at a time, on [executor] (single daemon thread by
 * default; tests inject a direct executor for the deterministic legs).
 *
 * The captcha handshake with the UI is two public callbacks — [onCaptchaSolved] and
 * [onCaptchaCancelled] — which release the blocked worker inside [CaptchaHandler.solve].
 * The blocking wait is bounded by [CAPTCHA_TIMEOUT_SECONDS] so an abandoned WebView can
 * never pin the worker forever.
 */
class LoginViewModel(
    private val auth: Authenticator,
    private val executor: Executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "login-worker").apply { isDaemon = true }
    },
) {

    var state: LoginState by mutableStateOf(LoginState.Idle)
        private set

    /** True while an attempt owns the worker — drives button/field enabled state. */
    var busy: Boolean by mutableStateOf(false)
        private set

    /** One attempt at a time: re-entry while `NeedCaptcha`/`Submitting` is ignored. */
    @Volatile
    private var attemptInFlight = false

    /** Handshake with the blocked worker inside [CaptchaHandler.solve]. */
    private val solveLock = Any()

    @Volatile
    private var pendingProof: CaptchaResult? = null

    @Volatile
    private var cancelReason: String? = null

    private var solveLatch: CountDownLatch? = null

    private val captcha = object : CaptchaHandler {
        override fun solve(request: CaptchaRequest): CaptchaResult {
            val latch = CountDownLatch(1)
            synchronized(solveLock) {
                pendingProof = null
                cancelReason = null
                solveLatch = latch
            }
            set(LoginState.NeedCaptcha)
            return try {
                if (!latch.await(CAPTCHA_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    throw TimeoutException("人机校验超时（${CAPTCHA_TIMEOUT_SECONDS}s 未完成）")
                }
                val proof = synchronized(solveLock) { pendingProof }
                if (proof == null) {
                    val reason = synchronized(solveLock) { cancelReason }
                    throw LoginException(reason ?: "人机校验未完成")
                }
                set(LoginState.Submitting)
                proof
            } finally {
                synchronized(solveLock) { solveLatch = null }
            }
        }

        override fun skip() = set(LoginState.Submitting)
    }

    /**
     * Starts one login attempt. [password] is held only for the duration of the call:
     * it is never stored in [state], and any exception message is scrubbed of it before
     * becoming an `Error` (pinned by the unit test).
     */
    fun submit(phone: String, password: String) {
        if (attemptInFlight) return
        attemptInFlight = true
        busy = true
        executor.execute {
            try {
                val token = auth.login(phone, password, captcha)
                set(LoginState.Success(token))
            } catch (e: Exception) {
                set(LoginState.Error(readableMessage(e, password)))
            } finally {
                attemptInFlight = false
                busy = false
            }
        }
    }

    /** UI → worker: the WebView gt4 flow produced a proof; releases [CaptchaHandler.solve]. */
    fun onCaptchaSolved(result: CaptchaResult) {
        synchronized(solveLock) {
            pendingProof = result
            cancelReason = null
            solveLatch?.countDown()
        }
    }

    /**
     * UI → worker: the challenge ended without a proof — the user dismissed it, or gt4
     * reported an error. The attempt ends as `Error([reason])`.
     */
    fun onCaptchaCancelled(reason: String = "人机校验已取消") {
        synchronized(solveLock) {
            cancelReason = reason
            solveLatch?.countDown()
        }
    }

    /**
     * Back to [LoginState.Idle] after a logout: the persisted session was dropped, so
     * the next screen must be the credential form — leaving the stale `Success` in
     * place would re-render the logged-in placeholder and re-save the session.
     * Only reachable from Home, where no login attempt is in flight.
     */
    fun resetToIdle() {
        set(LoginState.Idle)
        busy = false
    }

    private fun set(next: LoginState) {
        state = next
    }

    /**
     * A user-readable message for [Error]: falls back to the exception type when the
     * message is blank, and never contains the password — even if some transport layer
     * echoed it back (defense in depth beyond not interpolating it ourselves).
     */
    private fun readableMessage(e: Exception, password: String): String {
        val raw = e.message?.takeIf { it.isNotBlank() } ?: e::class.simpleName ?: "登录失败"
        return if (password.isEmpty()) raw else raw.replace(password, "***")
    }

    private companion object {
        const val CAPTCHA_TIMEOUT_SECONDS = 120L
    }
}
