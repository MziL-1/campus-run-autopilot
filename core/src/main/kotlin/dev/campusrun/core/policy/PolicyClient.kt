package dev.campusrun.core.policy

import dev.campusrun.core.api.ApiClient
import dev.campusrun.core.session.Token
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.roundToInt

/**
 * Fetch seam for [RunPolicy]: read the live rule (`runModePolicy`) and fence
 * (`getGeoFenceForRun`) with an authenticated [Token] and flatten them into a [RunPolicy].
 *
 * Parsing is pure and lives in [parseRunPolicy] so fixtures can be tested offline. The
 * empty rule body 7.4.x returns outside the valid window (findings §3.5) is a **valid
 * empty policy**, not an error; structurally wrong documents (non-object roots, a
 * `runRuleModel` or point that is not an object, non-numeric coordinates/speeds) throw
 * [IllegalArgumentException].
 */
class PolicyClient(val api: ApiClient) {

    fun fetch(token: Token, mode: Int): RunPolicy {
        // The token is the request identity: apply it like SessionManager would, so callers
        // cannot accidentally fetch policy with a stale/absent header pair.
        api.uid = token.uid
        api.token = token.value

        val policyPath = if (mode == INDOOR_MODE) INDOOR_POLICY_PATH else RUN_POLICY_PATH
        val policy = api.post(
            policyPath,
            buildJsonObject {
                put("runMode", mode)
                put("ruleUpdateTime", 0)
                put("geoFenceUpdateTime", 0)
                put("selectUnid", token.unid.toIntOrNull() ?: 0)
                put("operateType", 0)
            },
        )
        val fence = api.post(GEO_FENCE_PATH, buildJsonObject { put("geoFenceUpdateTime", 0) })
        return parseRunPolicy(policy, fence)
    }

    private companion object {
        const val RUN_POLICY_PATH = "/api/v70103/runModePolicy"
        const val INDOOR_POLICY_PATH = "/api/v59/indoorRunModePolicy"
        const val GEO_FENCE_PATH = "/api/v1/getGeoFenceForRun"
        const val INDOOR_MODE = 5
    }
}

/**
 * Pure parser for the two live business payloads:
 *
 * - [policy] is the decrypted `data` of `POST /api/v70103/runModePolicy` (Task 2 fixture
 *   `run_mode_policy.json`): `{policy, timestamp, runRuleModel, geoFence, ...}`.
 * - [geoFence] is the whole decrypted `POST /api/v1/getGeoFenceForRun` response
 *   (`geo_fence.json`): `{error, message, data:{geoFences:[..]}}`.
 *
 * Missing `runRuleModel` and `null`/empty `geoFences` are valid empty states; wrong JSON
 * shapes throw [IllegalArgumentException].
 */
internal fun parseRunPolicy(policy: JsonElement, geoFence: JsonElement): RunPolicy {
    val geometry = parseFence(geoFence)
    val root = policy as? JsonObject
        ?: throw IllegalArgumentException("runModePolicy payload must be a JSON object, got ${policy::class.java.simpleName}")

    val ruleElement = root["runRuleModel"]
    if (ruleElement == null || ruleElement is JsonNull) {
        // 7.4.x out-of-window response: valid empty rule, keep any fence geometry.
        return geometry.toPolicy(
            paceMinSecPerKm = 0,
            paceMaxSecPerKm = 0,
            validFromHour = 0,
            validToHour = 0,
            maxDistancePerRunM = 0,
        )
    }
    val rule = ruleElement as? JsonObject
        ?: throw IllegalArgumentException("runRuleModel must be a JSON object, got ${ruleElement::class.java.simpleName}")

    val speedTop = rule.doubleField("speedTop") ?: 0.0
    val speedBottom = rule.doubleField("speedBottom") ?: 0.0
    require(speedTop >= 0.0 && speedBottom >= 0.0) { "runRuleModel speeds must be non-negative" }

    val window = parseWindow(rule["validTime"])
    val dayGoal = rule.doubleField("dayGoal") ?: 0.0
    require(dayGoal >= 0.0) { "runRuleModel dayGoal must be non-negative" }

    return geometry.toPolicy(
        paceMinSecPerKm = (speedTop * SECONDS_PER_MINUTE).roundToInt(),
        paceMaxSecPerKm = (speedBottom * SECONDS_PER_MINUTE).roundToInt(),
        validFromHour = window.first,
        validToHour = window.second,
        maxDistancePerRunM = dayGoal.roundToInt(),
    )
}

private const val SECONDS_PER_MINUTE = 60.0
private const val BUSINESS_SUCCESS = 10000

private data class FenceGeometry(
    val polygon: List<LatLng>,
    val required: List<LatLng>,
    val optional: List<LatLng>,
) {
    fun toPolicy(
        paceMinSecPerKm: Int,
        paceMaxSecPerKm: Int,
        validFromHour: Int,
        validToHour: Int,
        maxDistancePerRunM: Int,
    ): RunPolicy = RunPolicy(
        requiredPoints = required,
        optionalPoints = optional,
        fence = polygon,
        paceMinSecPerKm = paceMinSecPerKm,
        paceMaxSecPerKm = paceMaxSecPerKm,
        validFromHour = validFromHour,
        validToHour = validToHour,
        maxDistancePerRunM = maxDistancePerRunM,
        dailyRunLimit = 0,
        faceWindow = null,
    )
}

private val EMPTY_GEOMETRY = FenceGeometry(emptyList(), emptyList(), emptyList())

/**
 * `{error, message, data:{geoFences:[{points:[..], passPoints:[..]}]}}` -> first usable
 * fence. Non-10000 business codes and `geoFences: null/[]` mean "no fence configured".
 */
private fun parseFence(root: JsonElement): FenceGeometry {
    val envelope = root as? JsonObject
        ?: throw IllegalArgumentException("geo fence response must be a JSON object, got ${root::class.java.simpleName}")

    val error = envelope.intField("error")
    if (error != null && error != BUSINESS_SUCCESS) return EMPTY_GEOMETRY

    val data = envelope["data"]
    if (data == null || data is JsonNull) return EMPTY_GEOMETRY
    val dataObject = data as? JsonObject
        ?: throw IllegalArgumentException("geo fence data must be a JSON object, got ${data::class.java.simpleName}")

    val rawFences = dataObject["geoFences"]
    if (rawFences == null || rawFences is JsonNull) return EMPTY_GEOMETRY
    val fences = rawFences as? JsonArray
        ?: throw IllegalArgumentException("geoFences must be a JSON array, got ${rawFences::class.java.simpleName}")

    for (element in fences) {
        val fence = element as? JsonObject
            ?: throw IllegalArgumentException("geoFences entries must be JSON objects")
        val polygon = parsePoints(fence["points"], "points")
        if (polygon.isEmpty()) continue
        val passPoints = parseTaggedPoints(fence["passPoints"], "passPoints")
        return FenceGeometry(
            polygon = polygon,
            required = passPoints.filter { it.required }.map { it.point },
            optional = passPoints.filterNot { it.required }.map { it.point },
        )
    }
    return EMPTY_GEOMETRY
}

private data class TaggedPoint(val point: LatLng, val required: Boolean)

private fun parsePoints(element: JsonElement?, field: String): List<LatLng> =
    parseTaggedPoints(element, field).map { it.point }

/** `passPoints` carry `isFixed` (1 = required, 0 = optional); absent is treated as required. */
private fun parseTaggedPoints(element: JsonElement?, field: String): List<TaggedPoint> {
    if (element == null || element is JsonNull) return emptyList()
    val points = element as? JsonArray
        ?: throw IllegalArgumentException("$field must be a JSON array, got ${element::class.java.simpleName}")
    return points.map { entry ->
        val point = entry as? JsonObject
            ?: throw IllegalArgumentException("$field entries must be JSON objects")
        val lat = point.doubleField("lat")
            ?: throw IllegalArgumentException("$field entry is missing numeric lat")
        val lng = point.doubleField("lng") ?: point.doubleField("lon")
            ?: throw IllegalArgumentException("$field entry is missing numeric lng/lon")
        require(lat in -90.0..90.0 && lng in -180.0..180.0) { "$field entry is out of range: $lat,$lng" }
        val fixed = point.intField("isFixed")
        TaggedPoint(LatLng(lat, lng), required = fixed == null || fixed != 0)
    }
}

/** `validTime[0] = {start:"10:00:00", end:"22:00:00"}` -> (10, 22); none -> (0, 0). */
private fun parseWindow(element: JsonElement?): Pair<Int, Int> {
    if (element == null || element is JsonNull) return 0 to 0
    val windows = element as? JsonArray
        ?: throw IllegalArgumentException("validTime must be a JSON array, got ${element::class.java.simpleName}")
    for (entry in windows) {
        val window = entry as? JsonObject
            ?: throw IllegalArgumentException("validTime entries must be JSON objects")
        val start = window.stringField("start")
        val end = window.stringField("end")
        if (start == null || end == null) continue
        return hourOf(start) to hourOf(end)
    }
    return 0 to 0
}

private fun hourOf(value: String): Int = value.substringBefore(':').toIntOrNull()
    ?: throw IllegalArgumentException("validTime hour must be HH:mm[:ss], got $value")

/** Present-but-non-numeric is malformed; absent/null returns null. */
private fun JsonObject.doubleField(key: String): Double? {
    val value = this[key]
    if (value == null || value is JsonNull) return null
    val primitive = value as? JsonPrimitive
        ?: throw IllegalArgumentException("$key must be a JSON number, got ${value::class.java.simpleName}")
    return primitive.content.toDoubleOrNull()?.takeIf { it.isFinite() }
        ?: throw IllegalArgumentException("$key must be a finite JSON number, got ${primitive.content}")
}

private fun JsonObject.intField(key: String): Int? {
    val value = this[key]
    if (value == null || value is JsonNull) return null
    val primitive = value as? JsonPrimitive
        ?: throw IllegalArgumentException("$key must be a JSON number, got ${value::class.java.simpleName}")
    return primitive.content.toIntOrNull()
        ?: primitive.content.toDoubleOrNull()?.takeIf { it.isFinite() }?.roundToInt()
        ?: throw IllegalArgumentException("$key must be an integer, got ${primitive.content}")
}

private fun JsonObject.stringField(key: String): String? {
    val value = this[key]
    if (value == null || value is JsonNull) return null
    val primitive = value as? JsonPrimitive
        ?: throw IllegalArgumentException("$key must be a JSON string, got ${value::class.java.simpleName}")
    return primitive.content
}
