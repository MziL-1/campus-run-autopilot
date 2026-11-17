package dev.campusrun.core.session

import dev.campusrun.core.api.ApiClient
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * GMT clock alignment, ported from upstream `profile/client.py` `get_server_time` +
 * `profile/models.py` `ServerTime`.
 *
 * Upstream samples the local clock **before** the request, fetches
 * `GET /api/v3/getserver/gmttime` (a NetSec envelope whose business object is
 * `{"error":10000,"data":<server epoch ms>}`) and reports
 * `offset = (server_ms - local_ms) / 1000` seconds. This port returns that skew in
 * milliseconds — positive means the server clock is ahead of the local clock. The
 * round-trip is not compensated, exactly like upstream (a real 7.4.x probe measured
 * ≈ −2.8 s of local/server skew on the reference machine).
 *
 * Any unusable answer (non-10 000 business error, missing `data`, transport/HTTP failure)
 * falls back to offset `0`, mirroring upstream's `ServerTime.from_api(t0, t0)`.
 *
 * [nowMs] is the local clock seam; production callers use the default.
 */
class TimeSync(
    val api: ApiClient,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    fun serverOffsetMs(): Long {
        val localMs = nowMs()
        val serverMs = runCatching { readServerMs() }.getOrNull() ?: return 0L
        return serverMs - localMs
    }

    private fun readServerMs(): Long? {
        val response = api.get(GMT_TIME_PATH) as? JsonObject ?: return null
        if (response["error"]?.jsonPrimitive?.let { it.longOrNull ?: it.doubleOrNull?.toLong() } != SUCCESS) {
            return null
        }
        val data = response["data"]?.jsonPrimitive ?: return null
        return data.longOrNull ?: data.doubleOrNull?.toLong()
    }

    private companion object {
        const val GMT_TIME_PATH = "/api/v3/getserver/gmttime"
        const val SUCCESS = 10_000L
    }
}
