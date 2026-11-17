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
 */
class ApiClient(
    private val params: ProtocolParams,
    baseUrl: String = params.endpointBase,
    private val client: OkHttpClient = OkHttpClient(),
    private val deviceHeaders: Map<String, String> = emptyMap(),
) {

    /** Upstream `BaseNetSecClient.uid`; `null` omits the header (upstream: `None` or `"-1"`). */
    var uid: String? = null

    /** Upstream `BaseNetSecClient.token`; `null` omits the header. */
    var token: String? = null

    private val baseUrl = baseUrl.trimEnd('/')

    fun get(path: String): JsonElement = execute(path, body = null)

    fun post(path: String, body: JsonElement): JsonElement = execute(path, body = body.toString())

    private fun execute(path: String, body: String?): JsonElement {
        val timestampMs = System.currentTimeMillis()
        val headers = LinkedHashMap<String, String>(deviceHeaders)
        headers["timeStamp"] = timestampMs.toString()
        headers["nonce"] = UUID.randomUUID().toString()
        uid?.let { headers["uid"] = it }
        token?.let { headers["token"] = it }
        headers["tokenSign"] = Signer.tokenSign(
            linkedMapOf(
                "timeStamp" to timestampMs.toString(),
                "token" to (token ?: ""),
                "uid" to (uid ?: ""),
            ),
            params,
        )
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
