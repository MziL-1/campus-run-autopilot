package dev.campusrun.core.upload

import dev.campusrun.core.config.ProtocolParams
import dev.campusrun.core.crypto.Signer
import dev.campusrun.core.policy.LatLng
import dev.campusrun.core.policy.RunPolicy
import dev.campusrun.core.track.Geo
import dev.campusrun.core.track.TrackPoint
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.floor

/**
 * 31-field settlement request assembly, ported from upstream
 * `协议/7.3.92/running/builder.py` `build_submission_payload` (the `save/record` body).
 *
 * ### What is produced
 *
 * [buildRequest] returns the compact UTF-8 JSON of upstream's `save_record` dict: the 31
 * sign fields (`sign_map`, builder.py:165-197) plus the body-only entries
 * (`allLocJson`, `fivePointJson`, `originalSign`, `signature`, the 10 s bucket arrays,
 * ...), in upstream's key order. `signature` is [Signer.settlementDigest] (upstream
 * `compute_sport_signature`) over the pre-rendered `k=v` field list; `originalSign` is
 * upstream `compute_original_sign` (plain key sort, same field list). None of the 31
 * fields is null upstream, so no key is omitted — except `goalId`, which upstream signs
 * as the literal string `"null"` (builder.py:177) while the body carries JSON `null`
 * (builder.py:237); the rendering helper reproduces exactly that.
 *
 * ### Field derivations (upstream formulas, unchanged — except `speed`, see below)
 *
 * ```text
 * durationSec = last.elapsedSec - first.elapsedSec      (1 Hz TrackPoint samples)
 * totalDis    = round(path length)                      (consistency.check_track meters)
 * totalSteps  = round(sum(stepRateHz * dt) / 60)        (stepRateHz is steps/MINUTE)
 * avgStepFreq = round(totalSteps * 60.0 / durationSec)  (builder.py:161)
 * speed       = round(durationSec * 1e6 / (60 * totalDis))   PACE — DELIBERATE DEVIATION
 * complete    = totalDis >= selDistance && durationSec >= selRunTime (builder.py:159)
 * unCompleteReason = 0 if complete else 10              (builder.py:160)
 * ```
 *
 * ### Why `speed` deviates from upstream `builder.py:162`
 *
 * Upstream computes `speed = round(totalDis / durationSec * 1000)` — average SPEED in
 * m/s ×1000 (2671 for the canonical 2030 m / 760 s run). The platform, however, consumes
 * `speed` as **平均配速**: it renders `speed / 1000` in **min/km** (2671 → 2.671 → "2'40"")
 * and validates that number against `speedTop 3.0 … speedBottom 10.0` 分/km
 * (= `RunPolicy.paceMinSecPerKm … paceMaxSecPerKm` / 60). 2.671 < 3.0 ⇒ the 配速 rule
 * fails ⇒ 成绩无效 on every real record, while the true pace 6'14"/km ∈ [3'00", 10'00"]
 * passes. Full evidence chain: `.superpowers/sdd/pace-diagnosis.md` (root cause §3).
 * Hence this build emits **pace in min/km ×1000** instead — a deliberate, documented
 * divergence from `builder.py:162`, not a porting slip; every other field stays
 * byte-identical to upstream.
 *
 * `stopTime - startTime == durationSec * 1000` is enforced like upstream
 * `consistency.validate_totals` (the same check [UploadHandle] already guarantees).
 *
 * ### Constants RunPolicy cannot express (Task 9 live calibration)
 *
 * Upstream takes these from the school rule body, which [RunPolicy] (Task 6) does not
 * carry, so the upstream builder *defaults* are used: `selDistance = 2000`,
 * `selRunTime = 0`, `policy = 1`, `sportType = 1` (计分跑), `calorie = 135`,
 * `avgPower = 150`. The settlement salt comes from `params.signSalt`, whose value is
 * byte-identical to upstream `running/config.py` `SPORT_STATIC_SALT`
 * (`2slhe02lsfiwowlcixisla_sls-_slaor`).
 *
 * `fivePointJson` rebuilds the sportType=1 envelope of builder.py:210-219 from
 * `policy.requiredPoints` (`isFixed=1`) + `policy.optionalPoints` (`isFixed=0`) — the
 * upstream fence `passPoints` split — with `runAreaId=-1` (upstream builder default;
 * the live area id is not in the model). Point names are the upstream default
 * `P_{n}` because [RunPolicy] keeps coordinates only.
 */
object Settlement {

    /** Upstream builder.py defaults (see KDoc; RunPolicy carries no rule-body values). */
    private const val SEL_DISTANCE_M = 2000
    private const val SEL_RUN_TIME_S = 0
    private const val POLICY_FLAG = 1
    private const val SPORT_TYPE = 1
    private const val CALORIE = 135
    private const val AVG_POWER = 150

    private const val MAX_RUN_TIME = 7200
    private const val MIN_STEPS = 2000

    fun buildRequest(
        policy: RunPolicy,
        track: List<TrackPoint>,
        handle: UploadHandle,
        params: ProtocolParams,
    ): ByteArray {
        val totals = deriveTotals(track)
        require(handle.stopTimeMs - handle.startTimeMs == totals.durationSec * 1000L) {
            "timestamps do not match totalTime: " +
                "${handle.stopTimeMs} - ${handle.startTimeMs} != ${totals.durationSec * 1000L}"
        }
        val startTimeMs = handle.startTimeMs
        val uid = handle.uid.toLongOrNull()
            ?: throw IllegalArgumentException("settlement uid must be numeric, got ${handle.uid}")
        val unid = handle.unid.toLongOrNull() ?: 0 // upstream int(self.unid or 0)
        val complete = totals.totalDis >= SEL_DISTANCE_M && totals.durationSec >= SEL_RUN_TIME_S

        // The 31 upstream sign fields, in upstream sign_map order (builder.py:165-197).
        val signValues = linkedMapOf<String, JsonElement>(
            "address" to JsonPrimitive(""),
            "avgPower" to JsonPrimitive(AVG_POWER),
            "avgStepFreq" to JsonPrimitive(avgStepFreq(totals)),
            "calorie" to JsonPrimitive(CALORIE),
            "complete" to JsonPrimitive(complete),
            "errorCode" to JsonPrimitive(0),
            "faceCheck" to JsonPrimitive(0),
            "geeToken" to JsonPrimitive(""),
            "getPrize" to JsonPrimitive(false),
            "goalId" to JsonNull,
            "policy" to JsonPrimitive(POLICY_FLAG),
            "selDistance" to JsonPrimitive(SEL_DISTANCE_M),
            "selRunTime" to JsonPrimitive(SEL_RUN_TIME_S),
            "selectedUnid" to JsonPrimitive(unid),
            "speed" to JsonPrimitive(averagePaceMilli(totals)), // platform reads it as 配速 (min/km x1000)
            "sportType" to JsonPrimitive(SPORT_TYPE),
            "startTime" to JsonPrimitive(startTimeMs),
            "status" to JsonPrimitive(1),
            "stopTime" to JsonPrimitive(handle.stopTimeMs),
            "themeId" to JsonPrimitive(0),
            "totalAscent" to JsonPrimitive(0),
            "totalDis" to JsonPrimitive(totals.totalDis),
            "totalSteps" to JsonPrimitive(totals.totalSteps),
            "totalTime" to JsonPrimitive(totals.durationSec),
            "uid" to JsonPrimitive(uid),
            "unCompleteReason" to JsonPrimitive(if (complete) 0 else 10),
            "unauthorized" to JsonPrimitive(0),
            "useMobilityTools" to JsonPrimitive(0),
            "uuid" to JsonPrimitive(handle.uuid),
            "validDis" to JsonPrimitive(totals.totalDis),
            "validTime" to JsonPrimitive(totals.durationSec),
        )
        require(signValues.size == SIGN_FIELD_COUNT) {
            "settlement sign map must have $SIGN_FIELD_COUNT fields, got ${signValues.size}"
        }

        val signFields = signValues.map { (key, value) -> "$key=${pythonStr(value)}" }
        val signature = Signer.settlementDigest(signFields, params.signSalt)
        val originalSign = signFields
            .sortedBy { it.substringBefore('=') } // upstream compute_original_sign key sort
            .joinToString("&")

        val extras = mapOf<String, JsonElement>(
            "allLocJson" to JsonPrimitive(allLocJson(track, startTimeMs)),
            "fivePointJson" to JsonPrimitive(fivePointJson(policy)),
            "isUpload" to JsonPrimitive(false),
            "latitude" to JsonPrimitive(track.first().lat),
            "longitude" to JsonPrimitive(track.first().lng),
            "maxRunTime" to JsonPrimitive(MAX_RUN_TIME),
            "minSteps" to JsonPrimitive(MIN_STEPS),
            "originalSign" to JsonPrimitive(originalSign),
            "recordUrl" to JsonPrimitive(""),
            "roomId" to JsonPrimitive(0),
            "segmentJson" to JsonPrimitive("[]"),
            "signature" to JsonPrimitive(signature),
            "speedPerTenSec" to speedBuckets(totals, startTimeMs),
            "stepsPerTenSec" to stepBuckets(totals, startTimeMs),
        )

        // Upstream save_record key order (builder.py:225-271): sign fields and extras
        // interleaved, originalSign after minSteps, signature after selectedUnid.
        val body = LinkedHashMap<String, JsonElement>(SAVE_RECORD_ORDER.size)
        SAVE_RECORD_ORDER.forEach { key -> body[key] = extras[key] ?: signValues.getValue(key) }
        return JsonObject(body).toString().toByteArray(Charsets.UTF_8)
    }

    private fun avgStepFreq(totals: TrackTotals): Int =
        pyRound(totals.totalSteps * 60.0 / totals.durationSec).toInt()

    /**
     * The `speed` sign/body field as the platform reads it: **average pace in min/km ×1000**
     * (`pyRound(durationSec * 1_000_000 / (60 * totalDis))`, half-even like every other
     * derivation here). 2030 m / 760 s → 6240 → 6.240 分/km → "6'14"", inside the
     * `speedTop 3.0 … speedBottom 10.0` 分/km window.
     *
     * Deliberate deviation from upstream `builder.py:162` (average speed, m/s ×1000) —
     * see the class KDoc and `.superpowers/sdd/pace-diagnosis.md`: the platform consumes
     * `speed` as 配速, so upstream's formula makes every submission fail the 配速 rule.
     */
    private fun averagePaceMilli(totals: TrackTotals): Int =
        pyRound(totals.durationSec * 1_000_000.0 / (60.0 * totals.totalDis)).toInt()
}

/** Derived run totals shared with the OBS payload ([RunUploader]). */
internal data class TrackTotals(val durationSec: Int, val totalDis: Int, val totalSteps: Int)

/**
 * Upstream `validate_totals` + `check_track` totals derivation over a 1 Hz [TrackPoint]
 * track: duration from the endpoints, distance as the rounded path length, steps from
 * the per-minute cadence carried in `stepRateHz` (`sum(rate * dt) / 60`).
 */
internal fun deriveTotals(track: List<TrackPoint>): TrackTotals {
    require(track.size >= 2) { "track needs at least 2 points, got ${track.size}" }
    require(track.first().elapsedSec == 0) {
        "track must start at elapsedSec 0 (upstream check_track: points[0].time == startTime)"
    }
    val durationSec = track.last().elapsedSec - track.first().elapsedSec
    require(durationSec > 0) { "track duration must be positive, got $durationSec" }
    for (i in 1 until track.size) {
        require(track[i].elapsedSec > track[i - 1].elapsedSec) {
            "track timestamps must strictly increase at index $i"
        }
    }
    val totalDis = pyRound(Geo.pathLengthM(track.map { LatLng(it.lat, it.lng) })).toInt()
    require(totalDis > 0) { "track path length must be a positive number of metres" }
    var cadenceSeconds = 0.0
    for (i in 0 until track.size - 1) {
        cadenceSeconds += track[i].stepRateHz * (track[i + 1].elapsedSec - track[i].elapsedSec)
    }
    val totalSteps = pyRound(cadenceSeconds / 60.0).toInt()
    require(totalSteps >= 0) { "total steps must be non-negative" }
    return TrackTotals(durationSec, totalDis, totalSteps)
}

/** Upstream `allLocJson`: one `GpsPoint.to_dict()` per sample, absolute epoch ms. */
internal fun allLocJson(track: List<TrackPoint>, startTimeMs: Long): String =
    buildJsonArray {
        track.forEach { point ->
            add(
                buildJsonObject {
                    put("lat", point.lat)
                    put("lon", point.lng)
                    put("time", startTimeMs + point.elapsedSec * 1000L)
                    put("speed", point.speedMps)
                    put("accuracy", 3.0) // upstream GpsPoint default
                    put("isValid", true)
                },
            )
        }
    }.toString()

/** Upstream `fivePointJson` sportType=1 envelope (builder.py:210-219). */
internal fun fivePointJson(policy: RunPolicy): String {
    val points = policy.requiredPoints.map { it to 1 } + policy.optionalPoints.map { it to 0 }
    val inner = buildJsonArray {
        points.forEachIndexed { index, (point, fixed) ->
            add(
                buildJsonObject {
                    put("lat", point.lat)
                    put("lon", point.lng)
                    put("isFixed", fixed)
                    put("isPass", true) // upstream marks every checkpoint passed
                    put("pointName", "P_${index + 1}")
                    put("position", index + 1)
                },
            )
        }
    }.toString()
    return buildJsonObject {
        put("useZip", false)
        put("fivePointJson", inner)
        put("runAreaId", -1)
        put("freedomShowFence", false)
    }.toString()
}

/** Upstream `time_buckets` distance buckets (10 s, integer-division arithmetic). */
internal fun speedBuckets(totals: TrackTotals, startTimeMs: Long): JsonArray =
    bucketWindows(totals) { begin, end, index ->
        val cents = totals.totalDis * 100L
        val length = (cents * end / (totals.durationSec * 1000L) - cents * begin / (totals.durationSec * 1000L)) / 100.0
        buildJsonObject {
            put("distance", length)
            put("beginTime", startTimeMs + begin)
            put("endTime", startTimeMs + end)
            put("flag", 0)
            put("state", 0)
            put("queueNum", index)
        }
    }

/** Upstream `time_buckets` step buckets (10 s, integer-division arithmetic). */
internal fun stepBuckets(totals: TrackTotals, startTimeMs: Long): JsonArray {
    val durationMs = totals.durationSec * 1000L
    return bucketWindows(totals) { begin, end, index ->
        val steps = totals.totalSteps * end / durationMs - totals.totalSteps * begin / durationMs
        buildJsonObject {
            put("stepsNum", steps)
            put("beginTime", startTimeMs + begin)
            put("endTime", startTimeMs + end)
            put("flag", 0)
            put("maxDiff", 0.0)
            put("minDiff", 0.0)
            put("avgDiff", 0.0)
            put("state", 0)
            put("queueNum", index)
        }
    }
}

/** `for begin in range(0, duration_ms, 10000): end = min(begin + 10000, duration_ms)`. */
private fun bucketWindows(
    totals: TrackTotals,
    make: (beginMs: Long, endMs: Long, queueNum: Int) -> JsonElement,
): JsonArray {
    val durationMs = totals.durationSec * 1000L
    return buildJsonArray {
        var begin = 0L
        var queueNum = 1
        while (begin < durationMs) {
            val end = minOf(begin + BUCKET_MS, durationMs)
            add(make(begin, end, queueNum))
            queueNum++
            begin = end
        }
    }
}

/** Python `str(v)` for the sign field list (bools lower-case, JSON null -> "null"). */
private fun pythonStr(value: JsonElement): String = when (value) {
    is JsonNull -> "null" // upstream sign_map carries goalId as the string "null"
    is JsonPrimitive -> value.content
    else -> throw IllegalArgumentException("settlement sign value must be scalar, got $value")
}

/**
 * Python 3 `round()` (round-half-even) for the integer derivations upstream applies with
 * `round()` — [Geo] and upstream `consistency.distance` may differ in the last ulp, and
 * the half-even rule keeps both languages on the same integer.
 */
internal fun pyRound(value: Double): Long {
    val base = floor(value)
    val fraction = value - base
    val whole = base.toLong()
    return when {
        fraction > 0.5 -> whole + 1
        fraction < 0.5 -> whole
        whole % 2 == 0L -> whole
        else -> whole + 1
    }
}

private const val SIGN_FIELD_COUNT = 31
private const val BUCKET_MS = 10_000L

/** Upstream `save_record` key order (builder.py:225-271). */
private val SAVE_RECORD_ORDER = listOf(
    "address", "allLocJson", "avgPower", "avgStepFreq", "calorie", "complete", "errorCode",
    "faceCheck", "fivePointJson", "geeToken", "getPrize", "goalId", "isUpload", "latitude",
    "longitude", "maxRunTime", "minSteps", "originalSign", "policy", "recordUrl", "roomId",
    "segmentJson", "selDistance", "selRunTime", "selectedUnid", "signature", "speed",
    "speedPerTenSec", "sportType", "startTime", "status", "stepsPerTenSec", "stopTime",
    "themeId", "totalAscent", "totalDis", "totalSteps", "totalTime", "uid", "unCompleteReason",
    "unauthorized", "useMobilityTools", "uuid", "validDis", "validTime",
)
