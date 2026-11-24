package dev.campusrun.app.data

import android.content.Context
import android.content.SharedPreferences
import dev.campusrun.core.campus.CampusSelectionStore

/**
 * Plain-`SharedPreferences` implementation of core's [CampusSelectionStore].
 *
 * The value is the chosen campus **unid only** — not a credential (the session token
 * stays in `AndroidTokenStore`'s encrypted file) and not personal data, so a
 * non-encrypted prefs file is the honest storage for it: it must survive refreshes and
 * app restarts, and [clear] runs on logout so a later account cannot inherit it.
 */
class AndroidCampusSelectionStore(context: Context) : CampusSelectionStore {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    override fun load(): String? = prefs.getString(KEY_SELECTED_UNID, null)

    override fun save(unid: String) {
        prefs.edit().putString(KEY_SELECTED_UNID, unid).apply()
    }

    override fun clear() {
        prefs.edit().remove(KEY_SELECTED_UNID).apply()
    }

    private companion object {
        const val FILE_NAME = "campusrun_prefs"
        const val KEY_SELECTED_UNID = "campus.selectedUnid"
    }
}
