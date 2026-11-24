package dev.campusrun.core.upload

import dev.campusrun.core.api.ApiClient
import dev.campusrun.core.session.Token
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * Server-side counted semester total — the official "计入成绩 X 公里" number the Home
 * progress line prefers over the client-side page sum when the server provides it.
 *
 * Two read-only calls (upstream `policy/client.py get_semester_info` +
 * `get_semester_summary`, paths unchanged in 7.4.x):
 * 1. `POST /api/v41/running/getPersonalSemesterInfo` `{runMode:1}` → `data.sid`
 *    (the semester id — **must be fetched**, the summary endpoint returns all-zeros
 *    for `sid=0`; the id is semester-scoped so it is never hard-coded);
 * 2. `POST /api/v55/runnings/recordssummary/semester` `{sid}` → `data.semesterDis`,
 *    the official counted (计入成绩) distance in meters.
 *
 * Live probe (2026-11-24, read-only): sid 9497 → `semesterDis: 4039`, which equals
 * exactly the counted sum of the account's two 官方 达标 rows (2009+2030) while the
 * naive five-row page sums to 10129 — `semesterDis` counts only 达标 records, matching
 * the official app header ("学期累计总里程 2.00 · 计入成绩 2.00" on 11-21, when only
 * one record was 达标 and four rows summed to 8099 m).
 *
 * Failure contract (best-effort leg): a business error, a missing `sid`/`semesterDis`,
 * or an empty `data` yields `null` — "no total available", never a fabricated `0`.
 * A structurally wrong document (non-object root or non-object `data`) throws
 * [IllegalArgumentException] rather than guessing.
 */
class SemesterSummaryClient(val api: ApiClient) {

    fun countedMeters(token: Token): Int? {
        // The token is the request identity (same contract as PolicyClient/HistoryClient).
        api.setCredentials(token.uid, token.value)
        val sid = semesterSid(api.post(SEMESTER_INFO_PATH, buildJsonObject { put("runMode", 1) }))
            ?: return null
        val summary = api.post(SUMMARY_PATH, buildJsonObject { put("sid", sid) })
        return countedFromSummary(summary)
    }

    private companion object {
        /** Upstream `policy/config.py URL_SEMESTER_INFO` — path unchanged in 7.4.x. */
        const val SEMESTER_INFO_PATH = "/api/v41/running/getPersonalSemesterInfo"

        /** Upstream `policy/config.py URL_SEMESTER_SUMMARY` — path unchanged in 7.4.x. */
        const val SUMMARY_PATH = "/api/v55/runnings/recordssummary/semester"

        const val BUSINESS_SUCCESS = 10000L

        /** `data.sid` when the info envelope carries one; `null` on any business/shape miss. */
        fun semesterSid(payload: JsonElement): Long? {
            val root = payload as? JsonObject
                ?: throw IllegalArgumentException(
                    "semester info response must be a JSON object, got ${payload::class.java.simpleName}",
                )
            if (root.longField("error") != BUSINESS_SUCCESS) return null
            val data = root["data"] as? JsonObject ?: return null
            return data.longField("sid")
        }

        /** `data.semesterDis` (counted meters) when present; `null` otherwise, never 0-as-fact. */
        fun countedFromSummary(payload: JsonElement): Int? {
            val root = payload as? JsonObject
                ?: throw IllegalArgumentException(
                    "semester summary response must be a JSON object, got ${payload::class.java.simpleName}",
                )
            if (root.longField("error") != BUSINESS_SUCCESS) return null
            val data = root["data"]
            if (data == null || data is JsonNull) return null
            val obj = data as? JsonObject
                ?: throw IllegalArgumentException("semester summary data must be a JSON object")
            val meters = obj.longField("semesterDis") ?: return null
            return meters.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
        }

        fun JsonObject.longField(key: String): Long? {
            val value = this[key] ?: return null
            if (value is JsonNull) return null
            val primitive = value as? JsonPrimitive ?: return null
            if (primitive.isString && primitive.contentOrNull == null) return null
            return primitive.content.toLongOrNull() ?: primitive.content.toDoubleOrNull()?.toLong()
        }
    }
}
