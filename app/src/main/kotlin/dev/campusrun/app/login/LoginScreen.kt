package dev.campusrun.app.login

import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.foundation.text.KeyboardOptions
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
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
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
    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = true
                    // file:// page → GeeTest API XHR; the official SDK loads its local
                    // bridge page the same way, and gt4 endpoints send CORS `*`.
                    @Suppress("DEPRECATION")
                    settings.allowUniversalAccessFromFileURLs = true
                    webViewClient = WebViewClient()
                    addJavascriptInterface(Gt4Bridge(onSolved, onCancel, onError), "JSInterface")
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
 * `file:///android_asset/geetest/gt4-index.html?params=<urlencoded json>` — the page
 * parses the query with `decodeURIComponent(query.split('=')[1])`, so the value must be
 * URL-encoded and free of raw `=` (URLEncoder guarantees both; compact JSON has no
 * spaces, so no `+`-vs-`%20` mismatch either).
 */
private fun captchaAssetUrl(): String {
    val args = buildJsonObject {
        put("captchaId", LOGIN_CAPTCHA_ID)
        put("product", "bind")
        put("language", "zh")
        put("title", "完成安全验证")
    }.toString()
    val encoded = URLEncoder.encode(args, Charsets.UTF_8.name()).replace("+", "%20")
    return "file:///android_asset/geetest/gt4-index.html?params=$encoded"
}

/** Receives `JSInterface.gt4Notify(json)` callbacks from the gt4 bridge page. */
private class Gt4Bridge(
    private val onSolved: (CaptchaResult) -> Unit,
    private val onCancel: () -> Unit,
    private val onError: (String) -> Unit,
) {

    @JavascriptInterface
    fun gt4Notify(message: String) {
        // Runs on the WebView's JS thread; the targets are thread-safe (the ViewModel
        // handshake is synchronised, and `close`/`error` only release a latch).
        val event = runCatching { JSONObject(message) }.getOrNull() ?: return
        when (event.optString("type")) {
            "result" -> {
                val data = event.optJSONObject("data") ?: return
                onSolved(
                    CaptchaResult(
                        lotNumber = data.optString("lot_number"),
                        passToken = data.optString("pass_token"),
                        genTime = data.optString("gen_time"),
                        captchaOutput = data.optString("captcha_output"),
                    ),
                )
            }

            "close" -> onCancel()
            "error", "fail" -> onError("验证失败: ${event.optJSONObject("data")?.optString("msg") ?: "请重试"}")
            // "ready" and "get" (GeeToken) need no reaction — see spike doc §3/B4.
        }
    }
}
