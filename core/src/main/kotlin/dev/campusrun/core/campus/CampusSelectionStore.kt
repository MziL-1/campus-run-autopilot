package dev.campusrun.core.campus

/**
 * Persistence seam for the user's campus choice (which campus's rule Home reads).
 *
 * The selection is **not** a credential: it only decides the `selectUnid`/`selectedUnid`
 * values the policy leg sends, so it lives outside [dev.campusrun.core.session.TokenStore]
 * and must never rewrite the session token (settlement keeps the login `unid` — spec:
 * no Settlement changes).
 *
 * [load] returns the persisted unid as text (`null` = no choice yet); [clear] runs on
 * logout so a later account on the same device cannot inherit it.
 */
interface CampusSelectionStore {
    fun load(): String?
    fun save(unid: String)
    fun clear()
}

/** Process-local [CampusSelectionStore] for the CLI and unit tests. */
class InMemoryCampusSelectionStore : CampusSelectionStore {

    @Volatile
    private var unid: String? = null

    override fun load(): String? = unid

    override fun save(unid: String) {
        this.unid = unid
    }

    override fun clear() {
        unid = null
    }
}
