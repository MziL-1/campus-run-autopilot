package dev.campusrun.core.upload

import dev.campusrun.core.api.ApiClient
import dev.campusrun.core.policy.RunPolicyValidator
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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * One row of the run-history list, flattened like upstream `history/models.py`.
 *
 * @param startTimeMs epoch ms of the row's `startTime` — the 7.4.x 19-key row carries it
 *   (findings §3.1 D) — normalised from epoch ms, epoch seconds or a `yyyy-MM-dd` date
 *   string. `null` when the row carries no usable timestamp; see [dailySelfLimitRefusal]
 *   for how the daily gate treats that.
 */
data class RunRecord(
    val id: String,
    val distanceM: Int,
    val valid: Boolean,
    val startTimeMs: Long? = null,
)

/**
 * One **objective** history row for the Home list (brief: 历史跑步记录): id, date,
 * distance and duration only.
 *
 * Deliberately carries **no verdict**: the 7.4.x 19-key row (findings §3.1 D) has no
 * field that tracks the official 达标 scoring — our `status`/`complete`-derived
 * `valid` diverges from the official verdict in *both* directions (11-17: ours
 * `true`, official 不达标; 11-21: ours `false`, official 达标) — so a verdict here
 * would be presented as fact while being unreliable. See [HistoryClient.list].
 *
 * @param durationSec the row's `totalTime` (seconds), `null` when absent/unusable.
 */
data class HistoryEntry(
    val id: String,
    val startTimeMs: Long?,
    val distanceM: Int,
    val durationSec: Int?,
)

/**
 * History read-back, ported from upstream `协议/7.3.92/history/client.py`
 * (`get_run_records` -> `POST /api/v70230/runnings/records`) +
 * `history/models.py` (`parse_history_records`, `history_record_status`).
 *
 * [latest] fetches page 1 (`pageNum=1, pageSize=20, sid=0, type=1`, the upstream
 * defaults) and returns the first row — the newest record; [list] returns that same
 * page as objective rows for the Home history list. Response tolerance mirrors
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
        val row = fetchRows(token).firstOrNull() ?: return null
        return RunRecord(
            id = rowId(row).toString(),
            distanceM = pyRound(rowDistanceM(row)).toInt(),
            valid = rowValid(row),
            startTimeMs = rowStartTimeMs(row),
        )
    }

    /**
     * The full first page (up to 20 rows) as **objective** [HistoryEntry] rows for the
     * Home history list (brief: 历史跑步记录 + 当前已完成的里程).
     *
     * The records response carries **no server-side semester/summary field**: 7.4.x
     * `data` is a bare array of the 19-key rows (findings §3.1 D) and the legacy
     * `{recordList}` envelope's siblings are not a summary either — so the caller must
     * sum the fetched rows itself and label the scope honestly (`近N条累计`), never as a
     * semester total. No verdict is derived here (see [HistoryEntry]).
     *
     * Same envelope contract as [latest]: business error or empty page -> empty list.
     */
    fun list(token: Token): List<HistoryEntry> = fetchRows(token).map { row ->
        HistoryEntry(
            id = rowId(row).toString(),
            startTimeMs = rowStartTimeMs(row),
            distanceM = pyRound(rowDistanceM(row)).toInt(),
            durationSec = rowDurationSec(row),
        )
    }

    /**
     * Page 1 (`pageNum=1, pageSize=20, sid=0, type=1`, the upstream defaults) with the
     * credential install at leg entry; `null`/empty history -> `[]`. Response tolerance
     * mirrors `parse_history_records` (see the class KDoc).
     */
    private fun fetchRows(token: Token): List<JsonObject> {
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
            return emptyList()
        }
        return parseHistoryRows(root["data"])
    }

    private companion object {
        const val RECORDS_PATH = "/api/v70230/runnings/records"
        const val BUSINESS_SUCCESS = 10000L
    }
}

/**
 * Cross-session 每日 ≤1 gate (spec §5.5-4 #1 risk rule; final-fix-wave executor ruling).
 * The in-session self-limit in both shells only sees one process, so before any upload the
 * caller must show the account has NOT already run today — this function answers that from
 * [HistoryClient.latest], the newest history row.
 *
 * Returns the refusal message when submission must not proceed, `null` when it may:
 *
 * - no record at all → first run, proceed;
 * - record dated **today** (read in [zone], the platform's wall clock) → refuse, daily ≤1;
 * - record dated a **future** day (server clock ahead / corrupt timestamp) → refuse
 *   (fail-closed: it is not "an earlier day", so it must not open the gate);
 * - record present but **undated** → refuse (fail-closed: an unparseable `startTime` must
 *   not silently reopen the very gap this gate exists to close);
 * - record dated strictly before today → proceed.
 *
 * Known limitation, documented rather than hidden: [HistoryClient.latest] returns `null`
 * both for an empty history and for a business-error envelope, so a *rejected* history
 * query reads as "no record" and proceeds. Transport failures throw and abort the run
 * (fail-closed) before this function is reached.
 */
fun dailySelfLimitRefusal(latest: RunRecord?, nowMs: Long, zone: ZoneId = RunPolicyValidator.RULE_ZONE): String? {
    if (latest == null) return null
    val startMs = latest.startTimeMs
        ?: return (
            "最新记录 id=${latest.id} 缺少开始时间，无法确认今日是否已跑 " +
                "—— 已拒绝（fail-closed），请人工核对历史记录"
            )
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    val recordDay = Instant.ofEpochMilli(startMs).atZone(zone).toLocalDate()
    return when {
        recordDay < today -> null
        recordDay == today ->
            "今日已有跑步记录（id=${latest.id}，${latest.distanceM} m），按每日 1 次自限拒绝再次提交"
        else ->
            "最新记录 id=${latest.id} 的开始时间 ${recordDay} 晚于今日 ${today}，" +
                "无法确认今日是否已跑 —— 已拒绝（fail-closed），请人工核对历史记录"
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

/**
 * Upstream row `startTime` (history/models.py reads it as `int`): epoch ms, epoch
 * seconds, or a `yyyy-MM-dd[...]` date string — the three shapes the Task 9 history
 * preflight (`reference/out/_obs/history_dates.py`) saw in the wild. `null` when the row
 * carries no usable timestamp, which [dailySelfLimitRefusal] fails closed on.
 */
private fun rowStartTimeMs(row: JsonObject): Long? {
    val text = row.primitive("startTime")?.contentOrNull?.trim().orEmpty()
    if (text.isEmpty()) return null
    text.toLongOrNull()?.let { n ->
        return when {
            n > 10_000_000_000L -> n // epoch milliseconds
            n > 1_000_000_000L -> n * 1000 // epoch seconds
            else -> null // 0 / not a timestamp
        }
    }
    // Date-shaped string: anchor at local noon so the calendar day is unambiguous.
    val date = runCatching { LocalDate.parse(text.take(10).replace('/', '-')) }.getOrNull() ?: return null
    return date.atTime(12, 0).atZone(RunPolicyValidator.RULE_ZONE).toInstant().toEpochMilli()
}

/** Upstream row `totalTime` (seconds); absent/non-positive -> `null` (unknown, never 0-as-fact). */
private fun rowDurationSec(row: JsonObject): Int? =
    row.longField("totalTime")?.takeIf { it in 1..MAX_DURATION_SEC }?.toInt()

private const val MAX_DURATION_SEC = 24 * 60 * 60

private fun JsonObject.longField(key: String): Long? =
    primitive(key)?.let { it.content.toLongOrNull() ?: it.content.toDoubleOrNull()?.toLong() }

private fun JsonObject.doubleField(key: String): Double? =
    primitive(key)?.content?.toDoubleOrNull()

private fun JsonObject.boolField(key: String): Boolean? =
    (primitive(key) as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toBooleanStrictOrNull()

private fun JsonObject.primitive(key: String): JsonPrimitive? =
    this[key]?.let { if (it is JsonNull) null else it as? JsonPrimitive }
