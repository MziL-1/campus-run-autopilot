package dev.campusrun.core.api

import dev.campusrun.core.config.ProtocolParams
import dev.campusrun.core.crypto.NetSecKit
import dev.campusrun.core.crypto.Signer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Base64
import java.util.UUID

/**
 * Thin OkHttp wrapper for the NetSecKit 4.2.4 wire protocol, ported from upstream
 * `core/client.py` (`BaseNetSecClient` / `execute_request`).
 *
 * Per request this mirrors upstream `build_headers`:
 *
 * ```text
 * headers   = deviceHeaders + {timeStamp: <now ms>, nonce: <uuid4>}
 *           + {uid, token}                          (only when set)
 * tokenSign = compute_token_sign({timeStamp, token, uid})   -> Signer.tokenSign(map, params)
 * headerSign= NetSecKit wire JSON of those headers          -> Signer.headerSign(headers, params)
 * body      = NetSecKit wire JSON of the compact JSON body  (POST only)
 * ```
 *
 * `deviceHeaders` carries the fingerprint headers (`appVersion`, `deviceName`, `DeviceId`,
 * `osVersion`, `Accept`, `Content-Type`, `User-Agent`, ...); values must be visible ASCII
 * because OkHttp rejects control and non-ASCII header octets.
 *
 * Responses are `{"r":..,"s":..,"v":101}` envelopes: `r` is AES-128-CBC under the
 * response-KDF of `params.kdfSalt`, the inner object's `data` is base64 of the business
 * JSON (a non-JSON `data` payload is returned as a [JsonPrimitive] string, upstream's
 * fallback). A response without `r` is returned as-is (upstream login/error paths).
 *
 * One deviation is inherited from [Signer]: the wire envelope's inner `timeStamp` is
 * derived from this request's millisecond sample instead of a separate `time.time()` call,
 * so header and body envelopes of the same request agree. Non-2xx responses throw
 * [IOException]; upstream instead returns an `{"error":..}` object, so callers that mirror
 * upstream's fallbacks (e.g. [dev.campusrun.core.session.TimeSync]) catch it.
 *
 * Session credentials live in one immutable holder (T5 review ruling): [execute] snapshots
 * it **once** per request, so a concurrent session swap can never sign a request from a
 * torn uid/token pair. [postLogin] is the narrow tokenSign-free seam for the upstream login
 * envelope (`login/client.py:60-79`): upstream builds its own headers there — static
 * identity placeholders plus `Authorization` — and computes no `tokenSign`; those headers
 * arrive verbatim through `extraHeaders` and are still covered by `headerSign`. Existing
 * [get]/[post] behavior is byte-identical.
 */
class ApiClient(
    private val params: ProtocolParams,
    baseUrl: String = params.endpointBase,
    private val client: OkHttpClient = OkHttpClient(),
    private val deviceHeaders: Map<String, String> = emptyMap(),
) {

    /** One immutable session snapshot; written only through [setCredentials]. */
    private data class Credentials(val uid: String?, val token: String?)

    @Volatile
    private var credentials: Credentials = Credentials(null, null)

    /** Upstream `BaseNetSecClient.uid`; `null` omits the header (upstream: `None` or `"-1"`). */
    val uid: String? get() = credentials.uid

    /** Upstream `BaseNetSecClient.token`; `null` omits the header. */
    val token: String? get() = credentials.token

    /** Applies uid+token in a single volatile write — never a torn pair (T5 ruling). */
    fun setCredentials(uid: String?, token: String?) {
        credentials = Credentials(uid, token)
    }

    private val baseUrl = baseUrl.trimEnd('/')

    fun get(path: String): JsonElement = execute(path, body = null)

    fun post(path: String, body: JsonElement): JsonElement = execute(path, body = body.toString())

    /**
     * Upstream `LoginClient.execute_request` (`login/client.py:60-79`): the login envelope
     * carries no session headers and no computed `tokenSign` — the static identity
     * placeholders and `Authorization` arrive verbatim through [extraHeaders] (upstream
     * signs them as part of `DEFAULT_DEVICE_CONFIG`), while `headerSign` still covers the
     * final header map exactly as upstream does.
     */
    fun postLogin(
        path: String,
        body: JsonElement,
        extraHeaders: Map<String, String> = emptyMap(),
    ): JsonElement = execute(path, body = body.toString(), extraHeaders = extraHeaders, loginEnvelope = true)

    private fun execute(
        path: String,
        body: String?,
        extraHeaders: Map<String, String> = emptyMap(),
        loginEnvelope: Boolean = false,
    ): JsonElement {
        val session = credentials // single snapshot per request (T5 ruling)
        val timestampMs = System.currentTimeMillis()
        val headers = LinkedHashMap<String, String>(deviceHeaders)
        headers["timeStamp"] = timestampMs.toString()
        headers["nonce"] = UUID.randomUUID().toString()
        if (!loginEnvelope) {
            session.uid?.let { headers["uid"] = it }
            session.token?.let { headers["token"] = it }
            headers["tokenSign"] = Signer.tokenSign(
                linkedMapOf(
                    "timeStamp" to timestampMs.toString(),
                    "token" to (session.token ?: ""),
                    "uid" to (session.uid ?: ""),
                ),
                params,
            )
        }
        // Upstream applies custom headers (Authorization) before computing headerSign.
        extraHeaders.forEach { (name, value) -> headers[name] = value }
        // Upstream adds headerSign only after encrypting the header map (it is not signed).
        val headerSign = Signer.headerSign(headers, params)

        val request = Request.Builder().url(urlFor(path))
        headers.forEach { (name, value) -> request.addHeader(name, value) }
        request.addHeader("headerSign", headerSign)
        if (body == null) {
            request.get()
        } else {
            request.post(Signer.requestWire(body, params, timestampMs).toRequestBody(JSON_MEDIA_TYPE))
        }

        client.newCall(request.build()).execute().use { response ->
            val payload = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code} for ${response.request.url}: ${payload.take(512)}")
            }
            return decode(payload)
        }
    }

    private fun decode(payload: String): JsonElement {
        val root = Json.parseToJsonElement(payload)
        val cipher = (root as? JsonObject)?.get("r").stringContentOrNull() ?: return root
        val innerJson = NetSecKit.decrypt(Base64.getDecoder().decode(cipher), params).toString(Charsets.UTF_8)
        val inner = Json.parseToJsonElement(innerJson)
        val data = (inner as? JsonObject)?.get("data").stringContentOrNull() ?: return inner
        val business = Base64.getDecoder().decode(data).toString(Charsets.UTF_8)
        return runCatching { Json.parseToJsonElement(business) }.getOrElse { JsonPrimitive(business) }
    }

    private fun urlFor(path: String): String = when {
        path.startsWith("http://") || path.startsWith("https://") -> path
        path.startsWith("/") -> baseUrl + path
        else -> "$baseUrl/$path"
    }

    private fun JsonElement?.stringContentOrNull(): String? = (this as? JsonPrimitive)?.contentOrNull

    private companion object {
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
