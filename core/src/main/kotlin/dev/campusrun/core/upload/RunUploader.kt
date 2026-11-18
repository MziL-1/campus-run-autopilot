package dev.campusrun.core.upload

import dev.campusrun.core.api.ApiClient
import dev.campusrun.core.crypto.NetSecKit
import dev.campusrun.core.crypto.Signer
import dev.campusrun.core.session.TimeSync
import dev.campusrun.core.session.Token
import dev.campusrun.core.track.TrackPoint
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

/**
 * Result of the OBS push that [RunUploader.uploadTrack] performs; the settlement request
 * ([Settlement.buildRequest]) is assembled from it.
 *
 * @param uid `uid` request header / settlement `uid` field (upstream `BaseNetSecClient.uid`).
 * @param unid campus/organisation id -> settlement `selectedUnid` (upstream
 *   `int(self.unid or 0)` in `running/client.py`).
 * @param uuid run uuid (`str(uuid.uuid4())` upstream), also the OBS object key stem.
 * @param startTimeMs epoch ms of the first track sample (upstream `start_time_ms`).
 * @param stopTimeMs epoch ms of the last track sample (upstream `stop_time_ms`); always
 *   `startTimeMs + durationSec * 1000` (upstream `consistency.validate_totals`).
 */
data class UploadHandle(
    val uid: String,
    val unid: String,
    val uuid: String,
    val startTimeMs: Long,
    val stopTimeMs: Long,
)

/**
 * OBS upload pipeline, ported from upstream `协议/7.3.92/running/client.py`
 * (`request_obs_upload_url` + `upload_to_obs`, called by `execute_full_run` step 4):
 *
 * ```text
 * 1. sample server time (TimeSync)      -> stopTime = now+skew, startTime = stop - duration*1000
 * 2. uuid = uuid4(); objectKey = run_data/{yyyyMMddHH}/{uuid}.json   (builder.py:291-292)
 * 3. POST /api/obs/temporary/url {bucketName, objectKey, method:"Put", contentType:"text/plain"}
 *      -> {error:10000, data:{signedUrl, actualSignedRequestHeaders}} (data may be a JSON string)
 * 4. PUT signedUrl with the signed headers and the plain gzip-base64 obs_payload JSON
 * ```
 *
 * The gateway call goes through [api] (NetSecKit request envelope, [Signer] headers —
 * [netsec]/[signer] are the wire-layer dependencies of this pipeline, held as required by
 * the Task 8 interface); the signed PUT is a raw HTTPS call to the presigned OBS URL,
 * exactly like upstream's `urllib` PUT — OBS's own auth scheme lives entirely inside the
 * presigned URL + `actualSignedRequestHeaders`, which is the seam Task 9 verifies live.
 *
 * Upstream's `fixed_point_json` is gzip `"[]"`: the checkpoint envelope is only known to
 * [Settlement.buildRequest] (it takes the policy), and the upload happens before the
 * settlement body is built — see the Task 8 report for the live-verification note.
 */
class RunUploader(
    val api: ApiClient,
    val netsec: NetSecKit,
    val signer: Signer,
    val timeSync: TimeSync,
) {

    private val obsClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS) // upstream urlopen timeout=30
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    fun uploadTrack(token: Token, track: List<TrackPoint>): UploadHandle {
        // The token is the request identity (same contract as PolicyClient/HistoryClient).
        api.setCredentials(token.uid, token.value)

        val totals = deriveTotals(track)
        val stopTimeMs = System.currentTimeMillis() + timeSync.serverOffsetMs()
        val startTimeMs = stopTimeMs - totals.durationSec * 1000L
        val uuid = UUID.randomUUID().toString()
        val objectKey = "run_data/${hourOf(startTimeMs)}/$uuid.json"

        val credential = api.post(
            OBS_TEMPORARY_PATH,
            buildJsonObject {
                put("bucketName", OBS_BUCKET_NAME)
                put("objectKey", objectKey)
                put("method", "Put")
                put("contentType", "text/plain")
            },
        )
        val info = obsCredential(credential)
        val signedUrl = (info["signedUrl"] as? JsonPrimitive)?.contentOrNull
            ?: throw IOException("OBS credential response has no signedUrl: $info")
        val signedHeaders = signedHeaders(info)

        putToObs(signedUrl, signedHeaders, obsPayload(token.uid, uuid, track, totals, startTimeMs))

        return UploadHandle(token.uid, token.unid, uuid, startTimeMs, stopTimeMs)
    }

    /** Upstream `request_obs_upload_url` success check + `data` string tolerance. */
    private fun obsCredential(payload: JsonElement): JsonObject {
        val root = payload as? JsonObject
            ?: throw IOException("OBS credential response must be a JSON object, got $payload")
        val error = (root["error"]?.jsonPrimitive?.contentOrNull)?.toLongOrNull()
            ?: (root["error"]?.jsonPrimitive?.contentOrNull)?.toDoubleOrNull()?.toLong()
        if (error != BUSINESS_SUCCESS) {
            val message = (root["message"] as? JsonPrimitive)?.contentOrNull ?: "no message"
            throw IOException("OBS credential request failed: error=$error message=$message")
        }
        return when (val data = root["data"]) {
            null, JsonNull -> throw IOException("OBS credential response has no data")
            is JsonObject -> data
            is JsonPrimitive -> runCatching {
                Json.parseToJsonElement(data.content).jsonObject
            }.getOrElse { throw IOException("OBS credential data is not a JSON object: ${data.content}") }
            else -> throw IOException("OBS credential data must be an object, got $data")
        }
    }

    /** `actualSignedRequestHeaders or {"Content-Type": "text/plain"}` (upstream truthiness). */
    private fun signedHeaders(info: JsonObject): Map<String, String> {
        val signed = info["actualSignedRequestHeaders"] as? JsonObject ?: return DEFAULT_OBS_HEADERS
        val headers = signed.entries
            .mapNotNull { (name, value) -> (value as? JsonPrimitive)?.contentOrNull?.let { name to it } }
            .toMap()
        return headers.ifEmpty { DEFAULT_OBS_HEADERS }
    }

    /** Upstream `upload_to_obs`: plain compact JSON, signed headers, 2xx = success. */
    private fun putToObs(signedUrl: String, signedHeaders: Map<String, String>, payload: JsonObject) {
        val contentType = signedHeaders.entries
            .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }?.value
            ?: "text/plain"
        val request = Request.Builder().url(signedUrl)
        signedHeaders.forEach { (name, value) ->
            // Content-Type travels as the body's media type; transport headers are OkHttp's.
            if (name.equals("Content-Type", ignoreCase = true) || name in TRANSPORT_HEADERS) {
                return@forEach
            }
            request.addHeader(name, value)
        }
        request.put(payload.toString().toByteArray(Charsets.UTF_8).toRequestBody(contentType.toMediaType()))
        obsClient.newCall(request.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("OBS upload failed: HTTP ${response.code} for $signedUrl")
            }
        }
    }

    /** Upstream `obs_payload` (builder.py:276-288): gzip+base64 of every run artefact. */
    private fun obsPayload(
        uid: String,
        uuid: String,
        track: List<TrackPoint>,
        totals: TrackTotals,
        startTimeMs: Long,
    ): JsonObject = buildJsonObject {
        put("rrid", gzipBase64(""))
        put("uid", gzipBase64(uid))
        put("uuid", gzipBase64(uuid))
        put("run_data", gzipBase64(allLocJson(track, startTimeMs)))
        put("step_freq_json", gzipBase64(stepBuckets(totals, startTimeMs).toString()))
        put("speed_json", gzipBase64(speedBuckets(totals, startTimeMs).toString()))
        put("segment_json", gzipBase64("[]"))
        put("fixed_point_json", gzipBase64("[]"))
        put("runFaceCheck", gzipBase64(""))
        put("extension_json", gzipBase64(""))
        put("laps_json", gzipBase64(""))
    }

    /** Upstream `gzip_base64_encode`. */
    private fun gzipBase64(text: String): String {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        return Base64.getEncoder().encodeToString(out.toByteArray())
    }

    /** Upstream `time.strftime("%Y%m%d%H", localtime(start_time_ms / 1000))`. */
    private fun hourOf(startTimeMs: Long): String =
        SimpleDateFormat("yyyyMMddHH", Locale.US).format(Date(startTimeMs))

    private companion object {
        const val OBS_TEMPORARY_PATH = "/api/obs/temporary/url"
        const val OBS_BUCKET_NAME = "iydsj-hbase-hot" // upstream running/config.py
        const val BUSINESS_SUCCESS = 10000L

        val DEFAULT_OBS_HEADERS = mapOf("Content-Type" to "text/plain")

        /** OkHttp-owned hop headers; upstream never presigns them. */
        val TRANSPORT_HEADERS = setOf(
            "Host", "Content-Length", "Connection", "Keep-Alive", "Transfer-Encoding",
            "Upgrade", "TE", "Trailer", "Proxy-Authenticate", "Proxy-Authorization",
        )
    }
}
