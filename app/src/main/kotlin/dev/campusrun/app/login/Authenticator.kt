package dev.campusrun.app.login

import dev.campusrun.core.api.ApiClient
import dev.campusrun.core.session.Token
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.util.Base64
import java.util.UUID

/**
 * Credential login seam (brief interface): the whole upstream chain —
 * `checkGeeUse → (geevalidate) → login` — behind one synchronous call.
 *
 * The implementation runs on a background thread owned by [LoginViewModel]; it never
 * touches the UI directly, it reaches it through [CaptchaHandler].
 */
interface Authenticator {
    fun login(phone: String, password: String, captcha: CaptchaHandler): Token
}

/**
 * Phase bridge between [Authenticator.login] and the UI — and the shape the R2 spike
 * settled on (`docs/spikes/geetest-android.md` §4): invoked **exactly once** per login
 * attempt, after `checkGeeUse` returns.
 *
 * Both signals drive the `LoginViewModel` state machine:
 *
 *  - [solve] — the server demands human verification: publish `NeedCaptcha`, block the
 *    login worker until the UI (WebView gt4 flow) hands back the proof, then publish
 *    `Submitting` and return it;
 *  - [skip] — the server whitelisted the attempt: publish `Submitting` directly.
 *
 * Blocking is inherent: [Authenticator.login] is a single synchronous call per the brief,
 * so the captcha leg can only be a blocking callback resolved by the UI thread.
 */
interface CaptchaHandler {

    /** checkGeeUse demands verification; blocks until the user solves the challenge. */
    fun solve(request: CaptchaRequest): CaptchaResult

    /** checkGeeUse skipped verification (whitelist); no challenge is shown. */
    fun skip()
}

/** What the WebView challenge must solve: the fixed upstream login captcha id. */
data class CaptchaRequest(val captchaId: String)

/**
 * GeeTest v4 proof as `captchaObj.getValidate()` returns it (snake_case on the wire),
 * mapped onto the `geevalidate` payload field names from upstream `login/models.py`.
 */
data class CaptchaResult(
    val lotNumber: String,
    val passToken: String,
    val genTime: String,
    val captchaOutput: String,
)

/** Business-level login failure; [message] is server-readable and never carries secrets. */
class LoginException(message: String) : Exception(message)

/** Upstream `login/config.py LOGIN_CAPTCHA_ID` — also used by the WebView challenge UI. */
const val LOGIN_CAPTCHA_ID = "3b02ad39bd099fd3a8336d9347a189ab"

/**
 * Faithful port of upstream `login/client.py` (`LoginClient.login`) over [ApiClient]'s
 * tokenSign-free [ApiClient.postLogin] seam:
 *
 * ```text
 * checkGeeUse ── data==true(白名单) ──► skip() ─────────────────────► login(Basic) ► Token
 *      │ 需要校验
 *      ▼
 * solve(CaptchaRequest)  ► WebView gt4 流程（用户滑一次）► geevalidate(10000) ► login(Basic) ► Token
 * ```
 *
 * Headers mirror upstream `execute_request`: static identity placeholders (`uid=-1`,
 * `token=`, `tokenSign=` from `DEFAULT_DEVICE_CONFIG`) plus, on the login call only,
 * `Authorization: Basic base64(phone:password)` — all signed by `headerSign`, none of
 * them a computed `tokenSign` (upstream login never computes one).
 *
 * @param deviceModel ASCII-sanitized `Build.MODEL`, sent as `device_model` (upstream reads
 *   `DEFAULT_DEVICE_CONFIG.deviceName`).
 * @param osVersion ASCII-sanitized `Build.VERSION.RELEASE`, sent as `os_version`.
 */
class ApiAuthenticator(
    private val api: ApiClient,
    private val deviceModel: String,
    private val osVersion: String,
) : Authenticator {

    override fun login(phone: String, password: String, captcha: CaptchaHandler): Token {
        val sessionUuid = UUID.randomUUID().toString()

        // 1. account security check — upstream login/client.py:93-98.
        val check = api.postLogin(
            URL_CHECK_GEE_USE,
            buildJsonObject {
                put("username", phone)
                put("uuid", sessionUuid)
                put("unid", 0)
                put("type", 2)
            },
            staticIdentity,
        )
        // data == true → whitelist, no challenge (upstream: `chk_res.get("data") is True`).
        val whitelisted = (check as? JsonObject)
            ?.get("data")
            ?.let { (it as? JsonPrimitive)?.booleanOrNull } == true

        // 2. captcha phase — exactly one signal per attempt (drives NeedCaptcha/Submitting).
        val proof = if (whitelisted) {
            captcha.skip()
            null
        } else {
            captcha.solve(CaptchaRequest(LOGIN_CAPTCHA_ID))
        }

        // 3. upload the proof for server-side validation — upstream login/client.py:105-119.
        if (proof != null) {
            val validated = api.postLogin(
                URL_GEE_VALIDATE,
                buildJsonObject {
                    put("lotNumber", proof.lotNumber)
                    put("captchaOutput", proof.captchaOutput)
                    put("passToken", proof.passToken)
                    put("genTime", proof.genTime)
                    put("isOffline", false)
                    put("osType", 0)
                    put("businessType", 0)
                    put("uuid", sessionUuid)
                    put("username", phone)
                },
                staticIdentity,
            )
            requireSuccess(validated, "geevalidate")
        }

        // 4. credential login — HTTP Basic carries the password, body stays NetSec-wrapped
        //    (upstream login/client.py:122-137).
        val authorization = "Basic " + Base64.getEncoder().encodeToString(
            "$phone:$password".toByteArray(Charsets.UTF_8),
        )
        val login = api.postLogin(
            URL_LOGIN,
            buildJsonObject {
                put("loginType", 1)
                put("device_model", deviceModel)
                put("os_version", osVersion)
                put("username", phone)
                put("uuid", sessionUuid)
            },
            staticIdentity + ("Authorization" to authorization),
        )

        val loginObj = login as? JsonObject ?: throw LoginException("登录响应格式异常")
        if (loginObj.intOf("error") != BUSINESS_OK) {
            // e.g. 10121 单设备登录保护 — server message, never the password (T12 ruling).
            throw LoginException("登录失败(${loginObj.intOf("error") ?: "?"}): ${loginObj.stringOf("message") ?: "未知错误"}")
        }
        val data = loginObj.get("data") as? JsonObject ?: throw LoginException("登录失败: 响应缺少 data")
        val token = data.stringOf("token") ?: throw LoginException("登录失败: 响应缺少 token")
        val uid = data.stringOf("uid") ?: throw LoginException("登录失败: 响应缺少 uid")
        val unid = data.stringOf("unid") ?: ""
        // Upstream never derives an expiry from the login payload → null (persist until replaced).
        return Token(value = token, uid = uid, unid = unid, expiresAtMs = null)
    }

    /** Throws [LoginException] with the server's message unless the business code is 10000. */
    private fun requireSuccess(payload: JsonElement, stage: String) {
        val obj = payload as? JsonObject ?: throw LoginException("$stage 响应格式异常")
        val code = obj.intOf("error")
        if (code != BUSINESS_OK) {
            throw LoginException("$stage 失败(${code ?: "?"}): ${obj.stringOf("message") ?: "未知错误"}")
        }
    }

    private fun JsonObject.intOf(key: String): Int? =
        (get(key) as? JsonPrimitive)?.contentOrNull?.toIntOrNull()

    private fun JsonObject.stringOf(key: String): String? =
        (get(key) as? JsonPrimitive)?.contentOrNull

    private companion object {
        const val BUSINESS_OK = 10000

        // Upstream DEFAULT_DEVICE_CONFIG identity placeholders: the login envelope carries
        // them verbatim and headerSign covers them (login/client.py never computes tokenSign).
        val staticIdentity = mapOf("uid" to "-1", "token" to "", "tokenSign" to "")

        // Endpoints from upstream login/config.py (probe-verified on 7.4.x, findings §2).
        const val URL_CHECK_GEE_USE = "/api/v65/security/checkGeeUse"
        const val URL_GEE_VALIDATE = "/api/v70270/security/geevalidate"
        const val URL_LOGIN = "/api/v70100/login"
    }
}
