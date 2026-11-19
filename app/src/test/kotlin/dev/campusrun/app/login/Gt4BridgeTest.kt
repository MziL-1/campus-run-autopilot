package dev.campusrun.app.login

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * gt4 bridge dispatch (T12 device fix): the official `gt4-index.html` keeps
 * `.geetest_captcha` at `display:none` until the host reveals it — CDP on the real
 * device proved `window.jsBridge.callback('showBox')` flips it to `display:block`,
 * and the official SDK performs that call natively. `ready` must therefore trigger
 * the reveal; every other notify type keeps its existing mapping.
 */
class Gt4BridgeTest {

    @Test
    fun `ready - triggers the showBox reveal of the hidden captcha card`() {
        var reveals = 0
        val bridge = bridge(onReady = { reveals++ })

        bridge.handleEvent("ready", null)

        assertEquals(1, reveals, "ready must call showBox exactly once")
    }

    @Test
    fun `get - no native reaction (GeeToken is an optional extra, spike B4)`() {
        var reveals = 0
        var cancelled = false
        val bridge = bridge(onReady = { reveals++ }, onCancel = { cancelled = true })

        bridge.handleEvent("get", null)

        assertEquals(0, reveals, "get must not reveal the box")
        assertEquals(false, cancelled, "get must not cancel the challenge")
    }

    @Test
    fun `close - cancels the challenge`() {
        var cancelled = false
        val bridge = bridge(onCancel = { cancelled = true })

        bridge.handleEvent("close", null)

        assertEquals(true, cancelled, "close must cancel")
    }

    @Test
    fun `error without data - readable default message, no crash`() {
        var message: String? = null
        val bridge = bridge(onError = { message = it })

        bridge.handleEvent("error", null)

        assertEquals("验证失败: 请重试", message, "error must stay readable without data")
    }

    private fun bridge(
        onSolved: (CaptchaResult) -> Unit = { error("unexpected solve") },
        onCancel: () -> Unit = {},
        onError: (String) -> Unit = {},
        onReady: () -> Unit = {},
    ) = Gt4Bridge(onSolved, onCancel, onError, onReady)
}
