package dev.campusrun.app.update

import dev.campusrun.app.home.HomeData
import dev.campusrun.app.home.HomeViewModel
import dev.campusrun.core.config.RemoteVersionInfo
import dev.campusrun.core.policy.RunPolicy
import dev.campusrun.core.profile.UserProfile
import dev.campusrun.core.session.Token
import dev.campusrun.core.session.TokenStore
import dev.campusrun.core.upload.HistoryEntry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.Executor

/**
 * Update-prompt state ownership (fix: prompt must show **regardless of auth state**).
 *
 * [UpdatePromptState] is created by MainActivity in `onCreate` — *before* the
 * `token == null` branch — so these tests exercise exactly the logged-out path: no
 * session, no Home, no login ever happens, yet the startup `version.json` result must
 * produce (and retain) the prompt. They also pin the double-prompt guard: one shared
 * instance + a dismissal latch, so the global dialog and Home can never show twice.
 */
class UpdatePromptStateTest {

    @Test
    fun `logged-out path - the startup check raises the prompt with no session involved`() {
        // MainActivity creates this in onCreate, before any token exists — no login, no
        // HomeViewModel, nothing but the install's own version code.
        val state = UpdatePromptState(CURRENT_VERSION)

        state.onVersionCheck(outdated(mirrors = listOf(MIRROR)))

        val prompt = state.prompt
        assertNotNull(prompt, "an outdated build must raise the prompt pre-login")
        assertTrue(
            prompt!!.message.contains("${CURRENT_VERSION + 1}"),
            "prompt must name the required version: ${prompt.message}",
        )
        assertTrue(prompt.message.contains("$CURRENT_VERSION"), "prompt must name the installed version")
        assertEquals(MIRROR, prompt.downloadUrl, "dialog must offer the first apk mirror")
    }

    @Test
    fun `logged-out path - a failed check fails silently and never blocks startup`() {
        val state = UpdatePromptState(CURRENT_VERSION)

        state.onVersionCheck(null) // every mirror failed → UpdateChecker returns null

        assertNull(state.prompt, "a failed check must fail silently to 'no update'")
        assertFalse(state.dismissed, "a failed check must not latch the dismissal")
    }

    @Test
    fun `dismissed - the global latch keeps a later re-feed from re-raising the prompt`() {
        val state = UpdatePromptState(CURRENT_VERSION)
        state.onVersionCheck(outdated())

        state.dismiss()

        assertNull(state.prompt, "the prompt must be dismissible (non-blocking)")
        // Re-feed (e.g. re-entering Home after a logout/login cycle): must stay closed.
        state.onVersionCheck(outdated())
        assertNull(state.prompt, "a dismissed prompt must not re-raise on a re-feed")
        assertTrue(state.dismissed, "the dismissal latch must stick for the activity lifetime")
    }

    @Test
    fun `single source of truth - Home reads the same instance the global layer dismisses`() {
        // The shared holder MainActivity injects into HomeViewModel — one instance for
        // the global dialog AND the Home view, so dismissing anywhere clears both.
        val shared = UpdatePromptState(CURRENT_VERSION)
        val vm = HomeViewModel(
            FakeHomeData(),
            TOKEN,
            CURRENT_VERSION,
            DIRECT,
            FakeStore(),
            updatePromptState = shared,
        )

        vm.onVersionCheck(outdated(mirrors = listOf(MIRROR)))

        // Feed through Home, render through the global layer: same state, one dialog.
        assertNotNull(shared.prompt, "the shared holder must carry the prompt")
        assertEquals(shared.prompt, vm.updatePrompt, "Home must view the very same prompt state")

        shared.dismiss()

        assertNull(vm.updatePrompt, "a global dismissal must clear Home's view too (no double dialog)")
        assertNull(shared.prompt, "and the global layer must not render it either")
    }

    private fun outdated(mirrors: List<String> = emptyList()) = RemoteVersionInfo(
        minApkVersionCode = CURRENT_VERSION + 1,
        configVersion = 1,
        protocolParamsVersion = 1,
        apkMirrors = mirrors,
    )

    private class FakeHomeData : HomeData {
        override fun fetchPolicy(token: Token): RunPolicy = RunPolicy(
            requiredPoints = emptyList(),
            optionalPoints = emptyList(),
            fence = emptyList(),
            paceMinSecPerKm = 180,
            paceMaxSecPerKm = 600,
            validFromHour = 10,
            validToHour = 22,
            maxDistancePerRunM = 2000,
            dailyRunLimit = 0,
            faceWindow = null,
            minDistancePerRunM = 2000,
        )

        override fun fetchProfile(token: Token): UserProfile? = null

        override fun fetchHistory(token: Token): List<HistoryEntry> = emptyList()

        override fun fetchCountedSemester(token: Token): Int? = null
    }

    private class FakeStore : TokenStore {
        override fun save(t: Token) = Unit

        override fun load(): Token? = null

        override fun clear() = Unit
    }

    private companion object {
        /** Dummy session — no credential material (fixtures rule). */
        val TOKEN = Token(value = "unit-test-token", uid = "12345678", unid = "90001", expiresAtMs = null)

        const val CURRENT_VERSION = 1

        const val MIRROR = "https://example.invalid/releases/v9.apk"

        val DIRECT = Executor { it.run() }
    }
}
