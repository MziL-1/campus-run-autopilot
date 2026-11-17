package dev.campusrun.core.session

import dev.campusrun.core.api.ApiClient
import dev.campusrun.core.config.ProtocolParams
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SessionManagerTest {

    @Test
    fun `save persists the token and applies it to the api client`() {
        val store = InMemoryTokenStore()
        val api = api()
        val session = SessionManager(api, store, nowMs = { NOW })

        val token = Token(value = "tok-1", uid = "90001", unid = "55001", expiresAtMs = NOW + 60_000)
        session.save(token)

        assertEquals(token, store.load())
        assertEquals(token, session.current())
        assertEquals("90001", api.uid)
        assertEquals("tok-1", api.token)
    }

    @Test
    fun `current hides expired tokens but keeps tokens without an expiry`() {
        val store = InMemoryTokenStore()
        val session = SessionManager(api(), store, nowMs = { NOW })

        session.save(Token("expired", "90001", "55001", expiresAtMs = NOW - 1))
        assertNull(session.current())

        session.save(Token("no-expiry", "90001", "55001", expiresAtMs = null))
        assertEquals("no-expiry", session.current()?.value)

        session.save(Token("expiring-later", "90001", "55001", expiresAtMs = NOW + 1))
        assertEquals("expiring-later", session.current()?.value)
    }

    @Test
    fun `restore reapplies the persisted token after a process restart`() {
        val store = InMemoryTokenStore()
        val token = Token("tok-2", "90002", "55002", expiresAtMs = null)
        SessionManager(api(), store, nowMs = { NOW }).save(token)

        // A new process: same persisted store, fresh ApiClient without credentials.
        val restartedApi = api()
        val restored = SessionManager(restartedApi, store, nowMs = { NOW }).restore()

        assertEquals(token, restored)
        assertEquals("90002", restartedApi.uid)
        assertEquals("tok-2", restartedApi.token)
    }

    @Test
    fun `clear drops the token from the store and the api client`() {
        val store = InMemoryTokenStore()
        val api = api()
        val session = SessionManager(api, store, nowMs = { NOW })
        session.save(Token("tok-3", "90003", "55003", expiresAtMs = null))

        session.clear()

        assertNull(store.load())
        assertNull(session.current())
        assertNull(session.restore())
        assertNull(api.uid)
        assertNull(api.token)
    }

    private fun api() = ApiClient(
        params = ProtocolParams(
            aesKey = "0123456789abcdef".toByteArray(),
            kdfSalt = "0123456789abcdefghijABCDEFGHIJKLMNOPQRST".toByteArray(),
            rsaPublicKey = "unused-offline",
            endpointBase = "https://offline.invalid",
            signSalt = "2slhe02lsfiwowlcixisla_sls-_slaor",
            versionTag = 1,
        ),
    )

    private companion object {
        const val NOW = 1_750_123_454_000L
    }
}
