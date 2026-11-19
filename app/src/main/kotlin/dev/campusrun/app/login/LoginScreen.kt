package dev.campusrun.app.login

import android.webkit.JavascriptInterface
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.webkit.WebViewAssetLoader
import dev.campusrun.core.session.Token
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Login UI (brief Step 3): credential form, the WebView gt4 challenge while the state
 * machine is in `NeedCaptcha`, and the logged-in placeholder after `Success` (the real
 * home screen is Task 13).
 */
@Composable
fun LoginScreen(vm: LoginViewModel, onLoggedIn: (Token) -> Unit) {
    val state = vm.state
    LaunchedEffect(state) {
        if (state is LoginState.Success) onLoggedIn(state.token)
    }
    when (state) {
        is LoginState.Success -> LoggedInScreen(state.token)
        is LoginState.NeedCaptcha -> CaptchaChallenge(
            onSolved = vm::onCaptchaSolved,
            onCancel = { vm.onCaptchaCancelled() },
            onError = { message -> vm.onCaptchaCancelled(message) },
        )

        else -> CredentialForm(vm)
    }
}

/** Post-login placeholder (Task 13 replaces this with the real home screen). */
@Composable
fun LoggedInScreen(token: Token) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("登录成功", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        Text("uid=${token.uid}  unid=${token.unid}", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "会话已加密保存在本机",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CredentialForm(vm: LoginViewModel) {
    var phone by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val busy = vm.busy

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("校园跑登录", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = phone,
            onValueChange = { phone = it },
            label = { Text("手机号") },
            singleLine = true,
            enabled = !busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("密码") },
            singleLine = true,
            enabled = !busy,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                // Device-run finding (T12): a third-party IME can cover the 登录
                // button, so submit straight from the keyboard's Go action.
                imeAction = ImeAction.Go,
            ),
            keyboardActions = KeyboardActions(
                onGo = { vm.submit(phone.trim(), password) },
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = { vm.submit(phone.trim(), password) },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.height(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text("登录")
            }
        }
        val state = vm.state
        if (state is LoginState.Error) {
            Spacer(Modifier.height(16.dp))
            Text(
                state.message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/**
 * The gt4 challenge from the spike decision (`docs/spikes/geetest-android.md`): the
 * official `gt4-index.html` bridge page (bundled as an asset, exactly as the upstream
 * app and GeeTest's own SDK load it) runs the v4 JS flow; `window.JSInterface.gt4Notify`
 * is the page's documented Android callback and delivers `ready / result / error / close`.
 */
@Composable
private fun CaptchaChallenge(
    onSolved: (CaptchaResult) -> Unit,
    onCancel: () -> Unit,
    onError: (String) -> Unit,
) {
    Box(Modifier.fillMaxSize().statusBarsPadding()) {
        AndroidView(
            factory = { context ->
                // T12 device-run finding (spike §3 risk #1, resolved): gt4.js builds its
                // core-bundle URLs protocol-relative (`//gcaptcha4.geetest.com/...`), so a
                // `file://` page resolves them to `file://gcaptcha4...` (ERR_INVALID_URL)
                // and script loads are CORS-blocked outright. The page must live on an
                // https origin — WebViewAssetLoader serves the bundled assets under
                // https://appassets.androidplatform.net/ (never touches the network), and
                // protocol-relative URLs then resolve to https://gcaptcha4.geetest.com.
                // This is the spike's documented fallback for the file:// risk.
                val assetLoader = WebViewAssetLoader.Builder()
                    .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
                    .build()
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    // T12 device-run diagnostics: which resource fails and what the page
                    // logs — the bare WebViewClient() above swallowed all of it.
                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView,
                            request: WebResourceRequest,
                        ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

                        override fun onReceivedError(
                            view: WebView,
                            request: WebResourceRequest,
                            error: WebResourceError,
                        ) {
                            Log.e("gt4", "net-error ${request.url} :: ${error.errorCode} ${error.description}")
                        }

                        override fun onReceivedHttpError(
                            view: WebView,
                            request: WebResourceRequest,
                            response: WebResourceResponse,
                        ) {
                            Log.e("gt4", "http-error ${request.url} :: ${response.statusCode}")
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onLoadResource(view: WebView, url: String) {
                            Log.i("gt4", "load $url")
                        }

                        override fun onPageFinished(view: WebView, url: String) {
                            Log.i("gt4", "page-finished $url")
                        }
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                            Log.i(
                                "gt4",
                                "console[${consoleMessage.messageLevel()}] ${consoleMessage.message()} " +
                                    "@${consoleMessage.sourceId()}:${consoleMessage.lineNumber()}",
                            )
                            return true
                        }
                    }
                    addJavascriptInterface(
                        Gt4Bridge(
                            onSolved = onSolved,
                            onCancel = onCancel,
                            onError = onError,
                            // T12 device fix: the official page hides `.geetest_captcha`
                            // until the host reveals it (CDP-proven); `gt4Notify` runs on
                            // the JS bridge thread, so hop to the WebView's own thread.
                            onReady = { post { evaluateJavascript(SHOW_BOX_JS, null) } },
                        ),
                        "JSInterface",
                    )
                    loadUrl(captchaAssetUrl())
                }
            },
            onRelease = { it.destroy() },
            modifier = Modifier.fillMaxSize(),
        )
        TextButton(
            onClick = onCancel,
            modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
        ) {
            Text("取消")
        }
    }
}

/**
 * `https://appassets.androidplatform.net/assets/geetest/gt4-index.html?params=<urlencoded json>` —
 * served locally by [WebViewAssetLoader] (no network), and the https origin makes gt4.js's
 * protocol-relative API URLs resolve correctly. The page parses the query with
 * `decodeURIComponent(query.split('=')[1])`, so the value must be URL-encoded and free of
 * raw `=` (URLEncoder guarantees both; compact JSON has no spaces, so no `+`-vs-`%20`
 * mismatch either).
 */
private fun captchaAssetUrl(): String {
    val args = buildJsonObject {
        put("captchaId", LOGIN_CAPTCHA_ID)
        put("product", "bind")
        put("language", "zh")
        put("title", "完成安全验证")
    }.toString()
    val encoded = URLEncoder.encode(args, Charsets.UTF_8.name()).replace("+", "%20")
    return "https://appassets.androidplatform.net/assets/geetest/gt4-index.html?params=$encoded"
}

/**
 * Reveals the challenge card. CDP on the real device proved the official page keeps
 * `.geetest_captcha` at `display:none` until the host calls `showBox` (the official
 * SDK does this natively; we must do it from the WebView side). `captchaObj` is
 * assigned before `ready` is emitted, so the call is safe at that point.
 */
private const val SHOW_BOX_JS = "window.jsBridge && window.jsBridge.callback('showBox')"

/** Receives `JSInterface.gt4Notify(json)` callbacks from the gt4 bridge page. */
internal class Gt4Bridge(
    private val onSolved: (CaptchaResult) -> Unit,
    private val onCancel: () -> Unit,
    private val onError: (String) -> Unit,
    private val onReady: () -> Unit = {},
) {

    @JavascriptInterface
    fun gt4Notify(message: String) {
        // Runs on the WebView's JS thread; the targets are thread-safe (the ViewModel
        // handshake is synchronised, and `close`/`error` only release a latch).
        val event = runCatching { JSONObject(message) }.getOrNull() ?: return
        Log.i("gt4", "notify type=${event.optString("type")}")
        handleEvent(event.optString("type"), event.optJSONObject("data"))
    }

    /** Dispatch on an already-parsed notify — the unit-testable seam (Gt4BridgeTest). */
    internal fun handleEvent(type: String, data: JSONObject?) {
        when (type) {
            "result" -> {
                val payload = data ?: return
                onSolved(
                    CaptchaResult(
                        lotNumber = payload.optString("lot_number"),
                        passToken = payload.optString("pass_token"),
                        genTime = payload.optString("gen_time"),
                        captchaOutput = payload.optString("captcha_output"),
                    ),
                )
            }

            "ready" -> onReady()
            "close" -> onCancel()
            "error", "fail" -> onError("验证失败: ${data?.optString("msg") ?: "请重试"}")
            // "get" (GeeToken) needs no reaction — see spike doc §3/B4.
        }
    }
}
