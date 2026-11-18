package dev.campusrun.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dev.campusrun.core.session.Token
import dev.campusrun.core.session.TokenStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.security.SecureRandom

/**
 * Device-identity snapshot persisted with the session (T5/T12 ruling: spec §5.5-3 — once
 * generated, a fingerprint never churns, so an later OAID-permission change cannot
 * re-derive the id).
 */
data class DeviceIdentitySnapshot(val stableId: String, val hints: Map<String, String>)

/**
 * `EncryptedSharedPreferences` implementation of core's [TokenStore] (spec C7: credentials
 * are stored encrypted on the device and never leave it except as target-platform API
 * calls). The same encrypted file also holds the two non-session secrets the app must
 * never re-derive:
 *
 *  - the **DeviceIdentity salt** (ruling: persisted with the identity, spec §5.5-3) —
 *    a fresh random value on first access;
 *  - the **device-identity snapshot** ([DeviceIdentitySnapshot]) — written at login
 *    success so later hint changes cannot churn the `DeviceId` header.
 *
 * [clear] removes **only** the session keys: logout must not invalidate the device
 * identity (spec §5.5-3: fingerprints persist across sessions).
 *
 * Creation failures (broken AndroidKeyStore) propagate — a silent plaintext fallback
 * would violate C7 just as badly as leaking the value.
 */
class AndroidTokenStore(context: Context) : TokenStore {

    private val appContext = context.applicationContext

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override fun save(t: Token) {
        prefs.edit()
            .putString(KEY_TOKEN, t.value)
            .putString(KEY_UID, t.uid)
            .putString(KEY_UNID, t.unid)
            .putLong(KEY_EXPIRES, t.expiresAtMs ?: NO_EXPIRY)
            .apply()
    }

    override fun load(): Token? {
        val value = prefs.getString(KEY_TOKEN, null) ?: return null
        val expires = prefs.getLong(KEY_EXPIRES, NO_EXPIRY)
        return Token(
            value = value,
            uid = prefs.getString(KEY_UID, null).orEmpty(),
            unid = prefs.getString(KEY_UNID, null).orEmpty(),
            expiresAtMs = expires.takeIf { it != NO_EXPIRY },
        )
    }

    /** Drops the session only — device identity and its salt survive logout (§5.5-3). */
    override fun clear() {
        prefs.edit()
            .remove(KEY_TOKEN)
            .remove(KEY_UID)
            .remove(KEY_UNID)
            .remove(KEY_EXPIRES)
            .apply()
    }

    /** Create-once random salt for [dev.campusrun.core.identity.DeviceIdentity]. */
    fun deviceSalt(): ByteArray {
        prefs.getString(KEY_DEVICE_SALT, null)?.let { existing ->
            return java.util.Base64.getDecoder().decode(existing)
        }
        val fresh = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        prefs.edit().putString(KEY_DEVICE_SALT, java.util.Base64.getEncoder().encodeToString(fresh)).apply()
        return fresh
    }

    /** Persists the identity snapshot at login success (T12 ruling). */
    fun saveDeviceIdentity(snapshot: DeviceIdentitySnapshot) {
        val hintsJson = buildJsonObject {
            snapshot.hints.forEach { (key, value) -> put(key, value) }
        }.toString()
        prefs.edit()
            .putString(KEY_DEVICE_ID, snapshot.stableId)
            .putString(KEY_DEVICE_HINTS, hintsJson)
            .apply()
    }

    /** The pinned snapshot, or `null` before the first login on this install. */
    fun loadDeviceIdentity(): DeviceIdentitySnapshot? {
        val stableId = prefs.getString(KEY_DEVICE_ID, null) ?: return null
        val hintsJson = prefs.getString(KEY_DEVICE_HINTS, null) ?: return DeviceIdentitySnapshot(stableId, emptyMap())
        val hints = runCatching { Json.parseToJsonElement(hintsJson) }
            .getOrNull() as? JsonObject ?: return DeviceIdentitySnapshot(stableId, emptyMap())
        return DeviceIdentitySnapshot(
            stableId = stableId,
            hints = hints.entries.associate { (key, value) ->
                key to ((value as? JsonPrimitive)?.contentOrNull ?: "")
            },
        )
    }

    private companion object {
        const val FILE_NAME = "campusrun_secure"
        const val KEY_TOKEN = "session.token"
        const val KEY_UID = "session.uid"
        const val KEY_UNID = "session.unid"
        const val KEY_EXPIRES = "session.expiresAtMs"
        const val KEY_DEVICE_SALT = "device.salt"
        const val KEY_DEVICE_ID = "device.stableId"
        const val KEY_DEVICE_HINTS = "device.hints"
        const val SALT_BYTES = 16
        const val NO_EXPIRY = -1L
    }
}
