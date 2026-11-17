package dev.campusrun.core.session

/**
 * Authenticated session credentials, the values upstream `BaseNetSecClient` holds after
 * login (`uid` / `token` / `unid`).
 *
 * @param value the `token` request header value.
 * @param uid the `uid` request header value (upstream `profile.user_info.uid`).
 * @param unid the campus/organisation id (`unid`), used by run-policy and settlement
 *   bodies; upstream never sends it as a header.
 * @param expiresAtMs epoch milliseconds after which the session is considered dead, or
 *   `null` when the server did not advertise an expiry.
 */
data class Token(
    val value: String,
    val uid: String,
    val unid: String,
    val expiresAtMs: Long?,
)

/**
 * Session persistence seam. The CLI uses [InMemoryTokenStore]; the App's
 * `EncryptedSharedPreferences` implementation lands in Task 12 (spec C7: the token is a
 * credential and never leaves encrypted storage).
 */
interface TokenStore {
    fun save(t: Token)
    fun load(): Token?
    fun clear()
}

/** Process-local [TokenStore] for the CLI and tests. */
class InMemoryTokenStore : TokenStore {

    @Volatile
    private var token: Token? = null

    override fun save(t: Token) {
        token = t
    }

    override fun load(): Token? = token

    override fun clear() {
        token = null
    }
}
