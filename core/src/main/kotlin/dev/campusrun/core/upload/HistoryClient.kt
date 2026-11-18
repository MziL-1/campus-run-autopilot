package dev.campusrun.core.upload

import dev.campusrun.core.api.ApiClient
import dev.campusrun.core.session.Token
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** One row of the run-history list, flattened like upstream `history/models.py`. */
data class RunRecord(val id: String, val distanceM: Int, val valid: Boolean)

/**
 * History read-back, ported from upstream `协议/7.3.92/history/client.py`
 * (`get_run_records` -> `POST /api/v70230/runnings/records`) +
 * `history/models.py` (`parse_history_records`, `history_record_status`).
 *
 * [latest] fetches page 1 (`pageNum=1, pageSize=20, sid=0, type=1`, the upstream
 * defaults) and returns the first row — the newest record. Response tolerance mirrors
 * `parse_history_records`: `data` may be a row list, a `{recordList: [...]}` envelope,
 * or a JSON string of either; `null`/empty means "no records" -> `null`. A dict without
 * `recordList` is structurally wrong and throws [IllegalArgumentException] (upstream
 * raises `ValueError` rather than silently reporting an empty history).
 *
 * `valid` follows upstream `history_record_status` strictly: `isValid:false` /
 * `complete:false` is 未达标; `isValid:true`, or `status==1` **and** `complete:true`,
 * is 达标; a bare `status==1` alone does **not** establish validity (upstream
 * `models.py` comment) and maps to `false`. `distanceM` uses `totalDis` with the
 * upstream `totalDis or distance` fallback, rounded Python-style ([pyRound]).
 */
class HistoryClient(val api: ApiClient) {

    fun latest(token: Token): RunRecord? {
        // The token is the request identity (same contract as PolicyClient/RunUploader).
        api.setCredentials(token.uid, token.value)

        val payload = api.post(
            RECORDS_PATH,
            buildJsonObject {
                put("pageNum", 1)
                put("pageSize", 20)
                put("sid", 0)
                put("type", 1)
            },
        )
        val root = payload as? JsonObject
            ?: throw IllegalArgumentException("history response must be a JSON object")
        if ((root["error"]?.jsonPrimitive?.contentOrNull)?.toDoubleOrNull()?.toLong() != BUSINESS_SUCCESS) {
            return null
        }
        val row = parseHistoryRows(root["data"]).firstOrNull() ?: return null
        return RunRecord(
            id = rowId(row).toString(),
            distanceM = pyRound(rowDistanceM(row)).toInt(),
            valid = rowValid(row),
        )
    }

    private companion object {
        const val RECORDS_PATH = "/api/v70230/runnings/records"
        const val BUSINESS_SUCCESS = 10000L
    }
}

/** Upstream `parse_history_records`: list | {recordList} | JSON string of either. */
private fun parseHistoryRows(data: JsonElement?): List<JsonObject> = when {
    data == null || data is JsonNull -> emptyList()
    data is JsonPrimitive -> {
        val text = data.contentOrNull
            ?: throw IllegalArgumentException("history rows must be JSON, got null")
        val parsed = runCatching { Json.parseToJsonElement(text) }.getOrElse {
            throw IllegalArgumentException("history rows are not valid JSON: $text")
        }
        parseHistoryRows(parsed)
    }
    data is JsonArray -> data.map { element ->
        element as? JsonObject
            ?: throw IllegalArgumentException("history rows must be JSON objects")
    }
    data is JsonObject -> {
        if ("recordList" !in data) {
            throw IllegalArgumentException("history response object lacks recordList")
        }
        parseHistoryRows(data["recordList"])
    }
    else -> throw IllegalArgumentException("history rows must be a list, got $data")
}

/** Upstream `int(rrid or id or 0)`. */
private fun rowId(row: JsonObject): Long =
    row.longField("rrid")?.takeIf { it != 0L }
        ?: row.longField("id")
        ?: 0L

/** Upstream `float(totalDis or distance or 0.0)`. */
private fun rowDistanceM(row: JsonObject): Double =
    row.doubleField("totalDis")?.takeIf { it != 0.0 }
        ?: row.doubleField("distance")
        ?: 0.0

/** Upstream `history_record_status`: only an explicit pass is `true`. */
private fun rowValid(row: JsonObject): Boolean {
    val complete = row.boolField("complete")
    val valid = row.boolField("isValid")
    if (complete == false || valid == false) return false
    val status = row.longField("status")
    return valid == true || (status == 1L && complete == true)
}

private fun JsonObject.longField(key: String): Long? =
    primitive(key)?.let { it.content.toLongOrNull() ?: it.content.toDoubleOrNull()?.toLong() }

private fun JsonObject.doubleField(key: String): Double? =
    primitive(key)?.content?.toDoubleOrNull()

private fun JsonObject.boolField(key: String): Boolean? =
    (primitive(key) as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toBooleanStrictOrNull()

private fun JsonObject.primitive(key: String): JsonPrimitive? =
    this[key]?.let { if (it is JsonNull) null else it as? JsonPrimitive }
