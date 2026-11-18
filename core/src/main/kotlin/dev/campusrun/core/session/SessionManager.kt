package dev.campusrun.core.session

import dev.campusrun.core.api.ApiClient

/**
 * Session lifecycle: keeps [TokenStore] and [ApiClient]'s credentials in sync.
 *
 * The `ApiClient` instance is long-lived (one per account/process) and its `uid`/`token`
 * fields are exactly the headers upstream `BaseNetSecClient` signs with, so a session
 * change has to be applied to it as well as persisted. `unid` lives only in the [Token]
 * (settlement/policy bodies need it; it is not a request header).
 *
 * Expiry is judged locally against [nowMs]: a token with `expiresAtMs` in the past is not
 * returned by [current] (callers then re-login). An expired entry is left in the store
 * until [clear], matching the "persist credentials until replaced" rule of spec §5.5-3.
 */
class SessionManager(
    private val api: ApiClient,
    private val store: TokenStore,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    /** The persisted token if it has not expired, without touching [api]. */
    fun current(): Token? = store.load()?.takeIf { token ->
        token.expiresAtMs == null || token.expiresAtMs > nowMs()
    }

    /** Cold start: returns the persisted token (if live) and applies it to [api]. */
    fun restore(): Token? = current()?.also(::applyToApi)

    fun save(token: Token) {
        store.save(token)
        applyToApi(token)
    }

    fun clear() {
        store.clear()
        applyToApi(null)
    }

    private fun applyToApi(token: Token?) {
        api.setCredentials(token?.uid, token?.value)
    }
}
