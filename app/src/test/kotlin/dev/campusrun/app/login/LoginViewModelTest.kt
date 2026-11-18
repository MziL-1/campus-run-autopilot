package dev.campusrun.app.login

import dev.campusrun.core.session.Token
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.Executor

/**
 * LoginViewModel state machine tests (brief Step 2): `Idle → NeedCaptcha → Submitting →
 * Success(Token) | Error(msg)`, driven by a Fake [Authenticator].
 *
 * The captcha leg is inherently asynchronous (the login worker blocks until the UI
 * delivers a solve), so those assertions await a state transition with a timeout; the
 * Success/Error legs run on a direct executor and are asserted synchronously.
 */
class LoginViewModelTest {

    @Test
    fun `success - fake authenticator returning a token lands in Success`() {
        val token = Token(value = "tok-1", uid = "10549846", unid = "35102", expiresAtMs = null)
        val fake = object : Authenticator {
            override fun login(phone: String, password: String, captcha: CaptchaHandler): Token {
                captcha.skip()
                return token
            }
        }
        val vm = LoginViewModel(fake, DIRECT)

        vm.submit("13800000000", "hunter2")

        assertEquals(LoginState.Success(token), vm.state)
    }

    @Test
    fun `captcha required - state moves to NeedCaptcha then completes after the solve`() {
        val token = Token(value = "tok-2", uid = "10549846", unid = "35102", expiresAtMs = null)
        val fake = object : Authenticator {
            override fun login(phone: String, password: String, captcha: CaptchaHandler): Token {
                captcha.solve(CaptchaRequest(CAPTCHA_ID))
                return token
            }
        }
        // Background executor: solve() blocks the login worker until the test delivers the proof.
        val vm = LoginViewModel(fake)

        vm.submit("13800000000", "hunter2")

        assertEquals(LoginState.NeedCaptcha, vm.await { it is LoginState.NeedCaptcha })
        vm.onCaptchaSolved(CaptchaResult("lot", "pass", "gen", "out"))
        assertEquals(LoginState.Success(token), vm.await { it is LoginState.Success })
    }

    @Test
    fun `error - thrown exception surfaces a readable message and never the password`() {
        val password = "hunter2-secret"
        val fake = object : Authenticator {
            override fun login(phone: String, password: String, captcha: CaptchaHandler): Token =
                throw IllegalStateException("server rejected the credentials (password=$password)")
        }
        val vm = LoginViewModel(fake, DIRECT)

        vm.submit("13800000000", password)

        val state = vm.state
        assertTrue(state is LoginState.Error, "expected Error, got $state")
        state as LoginState.Error
        assertTrue(state.message.contains("server rejected"), "not readable: ${state.message}")
        assertFalse(state.message.contains(password), "password leaked into Error: ${state.message}")
    }

    /** Polls [LoginViewModel.state] until [predicate] holds; fails the test on timeout. */
    private fun LoginViewModel.await(
        timeoutMs: Long = 5_000,
        predicate: (LoginState) -> Boolean,
    ): LoginState {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val current = state
            if (predicate(current)) return current
            Thread.sleep(5)
        }
        throw AssertionError("timed out after ${timeoutMs}ms waiting for state; last=$state")
    }

    private companion object {
        const val CAPTCHA_ID = "3b02ad39bd099fd3a8336d9347a189ab"

        /** Runs login inline on the calling thread — deterministic for the non-captcha legs. */
        val DIRECT = Executor { it.run() }
    }
}
