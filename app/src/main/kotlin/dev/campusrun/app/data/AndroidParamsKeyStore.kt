package dev.campusrun.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dev.campusrun.core.config.ParamsKeyStore
import java.util.Base64

/**
 * `EncryptedSharedPreferences` implementation of core's [ParamsKeyStore] — the Android
 * landing the T10 KDoc promised ("the Android implementation lands in Task 12").
 *
 * Holds the per-install `aesKey || kdfSalt` blob (spec: T5 ruling — install keys are
 * generated once, persisted locally, and never shipped by remote config). A separate
 * encrypted file from the session store so [AndroidTokenStore.clear] (logout) can never
 * touch the install keys: they must survive logout, or every re-login would re-derive
 * new wire keys for the same install.
 *
 * Load/save failures propagate to [dev.campusrun.core.config.ParamsStore], which logs
 * them through its seam and falls back to the bundled keys (first run must never brick).
 */
class AndroidParamsKeyStore(context: Context) : ParamsKeyStore {

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

    override fun load(): ByteArray? =
        prefs.getString(KEY_PARAMS, null)?.let { Base64.getDecoder().decode(it) }

    override fun save(keys: ByteArray) {
        prefs.edit()
            .putString(KEY_PARAMS, Base64.getEncoder().encodeToString(keys))
            .apply()
    }

    private companion object {
        const val FILE_NAME = "campusrun_params"
        const val KEY_PARAMS = "params.installKeys"
    }
}
