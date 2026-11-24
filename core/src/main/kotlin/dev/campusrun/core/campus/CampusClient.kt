package dev.campusrun.core.campus

import dev.campusrun.core.api.ApiClient
import dev.campusrun.core.session.Token
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Read-only campus-list fetch seam: which campuses this account may run under.
 *
 * Upstream `CommonServer.getMyCampusList` (7.4.0 FridaDump DEX, `net/CommonServer`):
 *
 * ```text
 * @GET("api/v61101/campus/zonelist")  @Query("unid") String
 *   -> suspend BasicBean<List<MyCampusBean>>
 * ```
 *
 * so the request is `GET /api/v61101/campus/zonelist?unid=<session unid>` and the body is
 * the usual `{error, message, data:[...]}` envelope with `data` a bare array of
 * [CampusOption] objects (BasicBean<T> = `{error, message, data:T}`, the same envelope
 * `ProfileClient` already unwraps).
 *
 * [fetch] mirrors [dev.campusrun.core.profile.ProfileClient.fetch]: only `error == 10000`
 * with a `data` array yields campuses; a business error or a success envelope without
 * `data` reads as **no answer** (`null`, the caller degrades to the plain profile line),
 * while a structurally wrong document (non-object root, `data` that is not an array, an
 * entry without `unid`/`name`) throws [IllegalArgumentException] rather than guessing.
 *
 * The endpoint is a pure read, so it is safe to call on every refresh; nothing here can
 * mutate the account (the official client's own switch, `POST /api/v61101/user/updatezone`,
 * is deliberately NOT called — see the campus-switcher report).
 */
class CampusClient(val api: ApiClient) {

    /** Best-effort list for [dev.campusrun.app.home] — `null` means "no answer", never "no campuses". */
    fun fetch(token: Token): List<CampusOption>? {
        // The token is the request identity (same contract as PolicyClient/ProfileClient).
        api.setCredentials(token.uid, token.value)
        return parseCampusList(api.get("$ZONELIST_PATH?unid=${token.unid}"))
    }

    /**
     * The platform's switch permission: upstream `CommonServer.getIsSwitchSchool`
     * (`@GET("api/v70103/university/getUserInformation")` → `BasicBean<ChangeCampusBean>`,
     * DEX-recovered like [fetch]) — `data.openChangeCampus` is the account-level gate.
     *
     * `true` only when the platform **positively** grants it; a business error, a missing
     * `data` or a transport failure returns `null`, and the caller treats anything but
     * `true` as closed (conservative: never offer a switch the platform did not grant).
     */
    fun fetchGate(token: Token): Boolean? {
        api.setCredentials(token.uid, token.value)
        return parseCampusGate(api.get(GATE_PATH))
    }

    private companion object {
        /** Upstream `CommonServer.getMyCampusList` path (7.4.0 DEX annotation value). */
        const val ZONELIST_PATH = "/api/v61101/campus/zonelist"

        /** Upstream `CommonServer.getIsSwitchSchool` path (7.4.0 DEX annotation value). */
        const val GATE_PATH = "/api/v70103/university/getUserInformation"
    }
}

/**
 * Pure parser for the `zonelist` envelope so fixtures can be tested offline.
 * See [CampusClient.fetch] for the error/shape contract.
 */
internal fun parseCampusList(payload: JsonElement): List<CampusOption>? {
    val root = payload as? JsonObject
        ?: throw IllegalArgumentException(
            "campus list response must be a JSON object, got ${payload::class.java.simpleName}",
        )
    if (root.longField("error") != BUSINESS_SUCCESS) return null
    val data = root["data"] ?: return null
    if (data is JsonNull) return null
    val array = data as? JsonArray
        ?: throw IllegalArgumentException(
            "campus list data must be a JSON array, got ${data::class.java.simpleName}",
        )

    return array.map { element ->
        val entry = element as? JsonObject
            ?: throw IllegalArgumentException("campus list entries must be JSON objects")
        val unid = entry.intField("unid")
            ?: throw IllegalArgumentException("campus entry is missing numeric unid")
        val name = entry.stringField("name")
            ?: throw IllegalArgumentException("campus entry is missing name")
        CampusOption(
            unid = unid,
            name = name,
            allowChange = entry.boolField("allowChange") ?: false,
            isSelected = entry.boolField("isSelected") ?: false,
        )
    }
}

private const val BUSINESS_SUCCESS = 10000L

/**
 * Pure parser for the `getUserInformation` envelope: `openChangeCampus` is a primitive
 * `boolean` upstream (`ChangeCampusBean`), so a **missing key reads as `false`** — exactly
 * what the official client's Gson default produces; only an envelope that answers nothing
 * (business error / `data` absent or null) yields `null` ("no answer"). A structurally
 * wrong document (non-object root, non-object `data`) throws [IllegalArgumentException].
 */
internal fun parseCampusGate(payload: JsonElement): Boolean? {
    val root = payload as? JsonObject
        ?: throw IllegalArgumentException(
            "campus gate response must be a JSON object, got ${payload::class.java.simpleName}",
        )
    if (root.longField("error") != BUSINESS_SUCCESS) return null
    val data = root["data"] ?: return null
    if (data is JsonNull) return null
    val gate = data as? JsonObject
        ?: throw IllegalArgumentException(
            "campus gate data must be a JSON object, got ${data::class.java.simpleName}",
        )
    return gate.boolField("openChangeCampus") ?: false
}

private fun JsonObject.longField(key: String): Long? {
    val value = this[key] ?: return null
    if (value is JsonNull) return null
    val primitive = value as? JsonPrimitive ?: return null
    return primitive.content.toLongOrNull() ?: primitive.content.toDoubleOrNull()?.toLong()
}

private fun JsonObject.intField(key: String): Int? = longField(key)?.toInt()

/** Null-safe string read: `JsonNull` (a [JsonPrimitive] subclass) reads as absent, not `"null"`. */
private fun JsonObject.stringField(key: String): String? {
    val value = this[key] ?: return null
    if (value is JsonNull) return null
    return (value as? JsonPrimitive)?.contentOrNull
}

/** Accepts real booleans and the `1`/`0`/`"true"` spellings; absent/null reads as `null`. */
private fun JsonObject.boolField(key: String): Boolean? {
    val value = this[key] ?: return null
    if (value is JsonNull) return null
    val primitive = value as? JsonPrimitive ?: return null
    return when (val content = primitive.content) {
        "true", "1" -> true
        "false", "0" -> false
        else -> content.toLongOrNull()?.let { it != 0L }
    }
}
