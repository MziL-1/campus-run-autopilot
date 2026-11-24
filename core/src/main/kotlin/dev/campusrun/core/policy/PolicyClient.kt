package dev.campusrun.core.policy

import dev.campusrun.core.api.ApiClient
import dev.campusrun.core.session.Token
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Fetch seam for [RunPolicy]: read the live rule (`runModePolicy`) and fence
 * (`getGeoFenceForRun`) with an authenticated [Token] and flatten them into a [RunPolicy].
 *
 * Parsing is pure and lives in [parseRunPolicy] so fixtures can be tested offline. `fetch`
 * first unwraps the live envelope exactly like upstream `get_run_policy`
 * (`policy/client.py`): only `{error:10000, data:{...}}` carries a rule body, and a
 * business-error envelope becomes an **empty policy**, not an error. The empty rule body
 * 7.4.x returns outside the valid window (findings §3.5) is likewise a **valid empty
 * policy**; structurally wrong documents (non-object roots, a `runRuleModel` or point that
 * is not an object, non-numeric coordinates/speeds) throw [IllegalArgumentException].
 */
class PolicyClient(val api: ApiClient) {

    /**
     * @param selectUnid the campus/organisation id the rule is read **for** — upstream's
     *   `selectUnid` (runModePolicy) and `selectedUnid` (five-point) fields. Defaults to
     *   the session's [Token.unid]; Home passes the persisted campus choice here
     *   (`CampusClient` → `HomeViewModel`) so a campus switch changes ONLY which rule is
     *   read. The session token itself is never rewritten (settlement keeps the login
     *   `unid` — spec: no Settlement changes).
     */
    fun fetch(token: Token, mode: Int, selectUnid: String = token.unid): RunPolicy {
        // The token is the request identity: apply it like SessionManager would, so callers
        // cannot accidentally fetch policy with a stale/absent header pair.
        api.setCredentials(token.uid, token.value)
        val campusUnid = selectUnid.toIntOrNull() ?: 0

        val policyPath = if (mode == INDOOR_MODE) INDOOR_POLICY_PATH else RUN_POLICY_PATH
        val policy = api.post(
            policyPath,
            buildJsonObject {
                put("runMode", mode)
                put("ruleUpdateTime", 0)
                put("geoFenceUpdateTime", 0)
                put("selectUnid", campusUnid)
                put("operateType", 0)
            },
        )
        val fence = api.post(GEO_FENCE_PATH, buildJsonObject { put("geoFenceUpdateTime", 0) })
        val base = parseRunPolicy(unwrapPolicyEnvelope(policy), fence)

        // Task 9 integration: the five-point query (upstream get_distance_route_points),
        // attempted exactly when upstream would need it — outdoor mode, a per-run floor
        // to run against, and no checkpoints already carried by the fence's passPoints
        // (execute_full_run only queries when `not checkpoints`). Production schools with
        // `geoFences = null` get their required points from HERE — without it the fence
        // parser can never produce any.
        if (mode != INDOOR_MODE &&
            base.minDistancePerRunM > 0 &&
            base.requiredPoints.isEmpty() &&
            base.optionalPoints.isEmpty()
        ) {
            val anchor = parseCheckpointAnchor(fence) ?: LatLng(NO_FENCE_ANCHOR_DEG, NO_FENCE_ANCHOR_DEG)
            val checkpoints = fetchCheckpoints(token, base.minDistancePerRunM, anchor, campusUnid)
            if (checkpoints.required.isNotEmpty() || checkpoints.optional.isNotEmpty()) {
                return base.copy(
                    requiredPoints = checkpoints.required,
                    optionalPoints = checkpoints.optional,
                )
            }
        }
        return base
    }

    /**
     * Upstream `get_distance_route_points` (`policy/client.py:90-119`):
     * `POST /api/v560/get/1/distance/{minDistance}` with the GCJ anchor of the first
     * fence point, `sign = md5(url.replace("https://", "http://") + salt)` over the
     * canonical production host (the sign input never follows the actual transport —
     * upstream signs `HOST_RUN` verbatim), and `runAreaId = -1` (our model carries no
     * `runAreaModels`, and the live school advertises none). A business error or any
     * shape deviation yields "no checkpoints" — upstream records
     * `last_checkpoint_error` and returns `[]` rather than failing the run flow; only
     * transport failures propagate (they already aborted the two calls before this one).
     */
    private fun fetchCheckpoints(token: Token, distanceM: Int, anchor: LatLng, selectUnid: Int): Checkpoints {
        val path = "$CHECKPOINT_PATH_PREFIX$distanceM"
        val sign = md5Hex(CHECKPOINT_SIGN_ORIGIN + path + CHECKPOINT_SIGN_SALT)
        val payload = api.post(
            path,
            buildJsonObject {
                put("longitude", anchor.lng)
                put("latitude", anchor.lat)
                put("selectedUnid", selectUnid)
                put("uuid", UUID.randomUUID().toString())
                put("runec", "")
                put("runAreaId", -1)
                put("sign", sign)
            },
        )
        return parseCheckpoints(payload)
    }

    private companion object {
        const val RUN_POLICY_PATH = "/api/v70103/runModePolicy"
        const val INDOOR_POLICY_PATH = "/api/v59/indoorRunModePolicy"
        const val GEO_FENCE_PATH = "/api/v1/getGeoFenceForRun"
        const val INDOOR_MODE = 5

        /** Upstream fixed path key `1` (Android `CommonApiManager$requestFivePoint`). */
        const val CHECKPOINT_PATH_PREFIX = "/api/v560/get/1/distance/"

        /** Sign origin: upstream `HOST_RUN` with `https://` -> `http://` (client.py:115). */
        const val CHECKPOINT_SIGN_ORIGIN = "http://run.gxapp.iydsj.com"

        /** Upstream's inline sign salt (policy/client.py:114) — the protocol signSalt. */
        const val CHECKPOINT_SIGN_SALT = "2slhe02lsfiwowlcixisla_sls-_slaor"

        /**
         * Anchor used when the school advertises no fence at all (the live school):
         * upstream has no path for this case (it requires a fence polygon), so the
         * query goes out at null island and whatever the server answers — school-wide
         * points or anchor-relative issuance — is taken as-is; the caller's bounding-box
         * sanity gate refuses absurd coordinates before anything is synthesized.
         */
        const val NO_FENCE_ANCHOR_DEG = 0.0
    }
}

/**
 * Upstream `get_run_policy` success check, applied to the payload [ApiClient] returns:
 * `error == 10000` and a non-null `data` is the rule body; anything else (business error,
 * `error == 10000` without `data`) means "no rule" and yields an empty document. A payload
 * without an `error` field is already the rule body (what the parser is given in tests).
 */
private fun unwrapPolicyEnvelope(payload: JsonElement): JsonElement {
    val envelope = payload as? JsonObject
        ?: throw IllegalArgumentException(
            "runModePolicy response must be a JSON object, got ${payload::class.java.simpleName}",
        )
    val error = envelope.intField("error")
    if (error == null) return payload
    if (error != BUSINESS_SUCCESS) return EMPTY_POLICY
    val data = envelope["data"] ?: return EMPTY_POLICY
    if (data is JsonNull) return EMPTY_POLICY
    return data
}

private val EMPTY_POLICY: JsonElement = JsonObject(emptyMap())

/**
 * Pure parser for the two live business payloads:
 *
 * - [policy] is the rule body: the decrypted `data` of `POST /api/v70103/runModePolicy`
 *   (Task 2 fixture `run_mode_policy.json` is exactly this level). A document that still
 *   carries a non-10000 `error` is an empty rule, mirroring the fence envelope.
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

    val error = root.intField("error")
    if (error != null && error != BUSINESS_SUCCESS) {
        // Business-error envelope: upstream returns no rule at all (empty policy).
        return geometry.toPolicy(
            paceMinSecPerKm = 0,
            paceMaxSecPerKm = 0,
            validFromHour = 0,
            validToHour = 0,
            maxDistancePerRunM = 0,
        )
    }

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
    val minDistance = rule.doubleField("minDistance") ?: 0.0
    require(minDistance >= 0.0) { "runRuleModel minDistance must be non-negative" }
    val weekCountGoal = rule.doubleField("weekCountGoal") ?: 0.0
    require(weekCountGoal >= 0.0) { "runRuleModel weekCountGoal must be non-negative" }

    return geometry.toPolicy(
        paceMinSecPerKm = (speedTop * SECONDS_PER_MINUTE).roundToInt(),
        paceMaxSecPerKm = (speedBottom * SECONDS_PER_MINUTE).roundToInt(),
        validFromHour = window.first,
        validToHour = window.second,
        maxDistancePerRunM = dayGoal.roundToInt(),
        minDistancePerRunM = minDistance.roundToInt(),
        weekCountGoal = weekCountGoal.roundToInt(),
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
        minDistancePerRunM: Int = 0,
        weekCountGoal: Int = 0,
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
        minDistancePerRunM = minDistancePerRunM,
        weekCountGoal = weekCountGoal,
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

/** Five-point result: `pointsResModels` split by `isFixed` (0 = optional, else required). */
private data class Checkpoints(val required: List<LatLng>, val optional: List<LatLng>)

private val NO_CHECKPOINTS = Checkpoints(emptyList(), emptyList())

/**
 * Upstream `get_run_checkpoints` response handling (`policy/client.py:157-184`),
 * deliberately lenient like the original: `error == 10000`, `data` object (or a JSON
 * string of one), `pointsResModels` a non-empty array of in-range `{lat, lng|lon}`
 * pairs, `isFixed` (absent -> required, mirroring `passPoints`). Any deviation —
 * business error, missing/empty array, bad entry, broken string — yields "no
 * checkpoints" instead of an exception, so a 7.4.x shape change degrades the run to
 * fence/passPoints-only instead of aborting the fetch.
 */
private fun parseCheckpoints(payload: JsonElement): Checkpoints {
    try {
        val root = payload as? JsonObject ?: return NO_CHECKPOINTS
        if (root.intField("error") != BUSINESS_SUCCESS) return NO_CHECKPOINTS
        var data = root["data"] ?: return NO_CHECKPOINTS
        if (data is JsonNull) return NO_CHECKPOINTS
        if (data is JsonPrimitive) data = Json.parseToJsonElement(data.content) // upstream json.loads(str)
        val model = data as? JsonObject ?: return NO_CHECKPOINTS
        val points = model["pointsResModels"] as? JsonArray ?: return NO_CHECKPOINTS
        if (points.isEmpty()) return NO_CHECKPOINTS
        val required = mutableListOf<LatLng>()
        val optional = mutableListOf<LatLng>()
        for (element in points) {
            val point = element as? JsonObject ?: return NO_CHECKPOINTS
            val lat = point.doubleField("lat") ?: return NO_CHECKPOINTS
            val lng = point.doubleField("lng") ?: point.doubleField("lon") ?: return NO_CHECKPOINTS
            if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return NO_CHECKPOINTS
            val target = if (point.intField("isFixed") == 0) optional else required
            target += LatLng(lat, lng)
        }
        return Checkpoints(required, optional)
    } catch (_: IllegalArgumentException) {
        return NO_CHECKPOINTS // upstream catches (TypeError, ValueError, KeyError) -> []
    }
}

/**
 * Anchor for the five-point query: upstream sends the first fence point's GCJ
 * duplicate (`GeoPoint.gcj_coordinates`, `policy/models.py:95-101` — `glat`/`glon`,
 * tolerating `gLat`/`glng` key spellings). When the fence carries no usable GCJ pair
 * (upstream raises `ValueError` there), this port falls back to the point's WGS
 * `lat`/`lng` rather than dropping the query, and returns null only when no fence
 * polygon exists at all (the caller decides the no-fence anchor).
 */
private fun parseCheckpointAnchor(geoFence: JsonElement): LatLng? {
    val root = geoFence as? JsonObject ?: return null
    val data = root["data"] as? JsonObject ?: return null
    val fences = data["geoFences"] as? JsonArray ?: return null
    for (element in fences) {
        val fence = element as? JsonObject ?: continue
        val points = fence["points"] as? JsonArray ?: continue
        val first = points.firstOrNull() as? JsonObject ?: continue
        val gcjLat = first.doubleField("glat") ?: first.doubleField("gLat")
        val gcjLng = first.doubleField("glon") ?: first.doubleField("glng")
        if (gcjLat != null && gcjLng != null && gcjLat != -1.0 && gcjLng != -1.0 &&
            gcjLat in -90.0..90.0 && gcjLng in -180.0..180.0
        ) {
            return LatLng(gcjLat, gcjLng)
        }
        val wgsLat = first.doubleField("lat") ?: continue
        val wgsLng = first.doubleField("lng") ?: first.doubleField("lon") ?: continue
        return LatLng(wgsLat, wgsLng)
    }
    return null
}

/** Upstream `hashlib.md5(...).hexdigest()` for the five-point `sign` field. */
private fun md5Hex(text: String): String {
    val digest = MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8))
    val out = StringBuilder(digest.size * 2)
    for (byte in digest) {
        val value = byte.toInt() and 0xFF
        out.append(HEX[value ushr 4]).append(HEX[value and 0x0F])
    }
    return out.toString()
}

private const val HEX = "0123456789abcdef"
