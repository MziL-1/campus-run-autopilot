package dev.campusrun.core.profile

import dev.campusrun.core.api.ApiClient
import dev.campusrun.core.session.Token
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The two display facts Home needs after login (brief: 当前校园名 + display name),
 * flattened from the 7.4.x `GET /api/v70390/user/info` payload (findings §3.1 A).
 *
 * @param displayName `nickname` only (trimmed; `""` when absent) — the brief allows the
 *   school name and nickname on-device, but **not** the 真名 from `user/info.name`, so
 *   there is deliberately no fallback to it.
 * @param schoolName `campusName` (`universityName` as the documented fallback).
 *
 * Only these two strings leave this seam: phone/uid/学号 and the other 50 keys are
 * deliberately not modelled, so no extra PII can reach the UI from here.
 */
data class UserProfile(val displayName: String, val schoolName: String)

/**
 * Minimal read-only profile fetch for the Home header, reusing the shared [ApiClient]
 * (and therefore the session credential snapshot + wire envelope) exactly like
 * `PolicyClient`/`HistoryClient`: the [Token] is re-installed at leg entry.
 *
 * [fetch] mirrors upstream `ProfileClient.get_user_info`: only `error == 10000` with a
 * `data` object yields a profile; a business error or a success envelope without `data`
 * reads as **no profile** (`null`), while a structurally wrong document (non-object root,
 * non-object `data`) throws [IllegalArgumentException] rather than guessing.
 */
class ProfileClient(val api: ApiClient) {

    fun fetch(token: Token): UserProfile? {
        // The token is the request identity (same contract as PolicyClient/HistoryClient).
        api.setCredentials(token.uid, token.value)
        return parseUserProfile(api.get(USER_INFO_PATH))
    }

    private companion object {
        /** Upstream `profile/config.py URL_USER_INFO` — path unchanged in 7.4.x (§3). */
        const val USER_INFO_PATH = "/api/v70390/user/info"
    }
}

/**
 * Pure parser for the `user/info` envelope so fixtures can be tested offline.
 * See [ProfileClient.fetch] for the error/shape contract.
 */
internal fun parseUserProfile(payload: JsonElement): UserProfile? {
    val root = payload as? JsonObject
        ?: throw IllegalArgumentException(
            "profile response must be a JSON object, got ${payload::class.java.simpleName}",
        )
    if (root.longField("error") != BUSINESS_SUCCESS) return null
    val data = root["data"] ?: return null
    if (data is JsonNull) return null
    val profile = data as? JsonObject
        ?: throw IllegalArgumentException(
            "profile data must be a JSON object, got ${data::class.java.simpleName}",
        )

    val school = profile.stringField("campusName") ?: profile.stringField("universityName") ?: ""
    // Nickname only: the real name (`name`) is deliberately NOT modelled for display
    // (PII rule — Home may show school name + nickname, not 真名).
    val nickname = profile.stringField("nickname").orEmpty().trim()
    return UserProfile(
        displayName = nickname,
        schoolName = school.trim(),
    )
}

private const val BUSINESS_SUCCESS = 10000L

private fun JsonObject.longField(key: String): Long? {
    val value = this[key] ?: return null
    if (value is JsonNull) return null
    val primitive = value as? JsonPrimitive ?: return null
    return primitive.content.toLongOrNull() ?: primitive.content.toDoubleOrNull()?.toLong()
}

/** Null-safe string read: `JsonNull` (a [JsonPrimitive] subclass) reads as absent, not `"null"`. */
private fun JsonObject.stringField(key: String): String? {
    val value = this[key] ?: return null
    if (value is JsonNull) return null
    return (value as? JsonPrimitive)?.contentOrNull
}
