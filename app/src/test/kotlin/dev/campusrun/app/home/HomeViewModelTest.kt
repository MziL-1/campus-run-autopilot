package dev.campusrun.app.home

import dev.campusrun.core.config.RemoteVersionInfo
import dev.campusrun.core.policy.LatLng
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Home (rules / school / progress / history) tests — brief Step 1:
 *
 * - `历史为空 → 空态文案`
 * - `profile → 当前校园名` (the read-only user/info leg surfaces the user's own school)
 * - `verdict removal → 达标/未达标 must never render` (our `valid` diverges from the
 *   official scoring in both directions, so it cannot be presented as fact)
 * - `update required → 提示态` (the startup `UpdateChecker` result is fed in through
 *   [HomeViewModel.onVersionCheck]; a required bump raises a non-blocking prompt state)
 *
 * Plus pins for the spec §5.5-6 risk-control UX (rules visible; requirement visible;
 * cumulative progress visible with its scope label).
 */
class HomeViewModelTest {

    @Test
    fun `history empty - empty history shows the empty-state copy`() {
        val vm = HomeViewModel(FakeHomeData(policy()), TOKEN, CURRENT_VERSION, DIRECT, FakeSecureStore())

        vm.refresh()

        assertFalse(vm.state.loading, "refresh must complete on the direct executor")
        assertTrue(
            vm.state.cumulativeLine.contains("暂无"),
            "empty history must show the empty-state copy, got: '${vm.state.cumulativeLine}'",
        )
        assertEquals(emptyList<String>(), vm.state.historyRows, "no rows -> no history list")
        assertEquals("", vm.state.latestLine, "no rows -> no latest line")
    }

    @Test
    fun `history present - progress shows the scope-labeled cumulative and objective rows`() {
        val rows = listOf(
            HistoryEntry(id = "9001", startTimeMs = PAST_MS, distanceM = 2030, durationSec = 760),
            HistoryEntry(id = "9000", startTimeMs = PAST_MS, distanceM = 1500, durationSec = 600),
        )
        val vm = HomeViewModel(FakeHomeData(policy(), rows), TOKEN, CURRENT_VERSION, DIRECT, FakeSecureStore())

        vm.refresh()

        val state = vm.state
        // Cumulative: sum 3530 m, scope-labeled to the fetched page — never a semester total.
        assertTrue(state.cumulativeLine.contains("近 2 条累计"), "scope missing: ${state.cumulativeLine}")
        assertTrue(state.cumulativeLine.contains("3.53 km"), "sum missing: ${state.cumulativeLine}")
        // History list: one objective row per record, date/distance/duration only.
        assertEquals(2, state.historyRows.size, "every fetched row must render")
        assertTrue(state.latestLine.contains("id=9001"), "latest must name the newest record: ${state.latestLine}")
        // Verdict removal: our valid-derived 达标/未达标 must never surface as fact.
        val allText = (state.historyRows + state.latestLine + state.cumulativeLine).joinToString("\n")
        assertFalse(allText.contains("达标"), "our unreliable verdict must not render: $allText")
    }

    @Test
    fun `profile - school name and nickname surface on Home once fetched`() {
        val vm = HomeViewModel(
            FakeHomeData(policy(), profile = UserProfile("示例昵称", "示例大学")),
            TOKEN,
            CURRENT_VERSION,
            DIRECT,
            FakeSecureStore(),
        )

        vm.refresh()

        assertEquals("示例大学", vm.state.schoolName, "the school name must surface (brief item 1)")
        assertEquals("示例昵称", vm.state.nickname, "the user's own nickname may show alongside")
    }

    @Test
    fun `profile failure - the refresh still lands with rules and history`() {
        // Best-effort cosmetic leg: a thrown user/info error must not blank the
        // risk-control facts (rules + history) or raise an error state.
        val data = object : HomeData {
            override fun fetchPolicy(token: Token): RunPolicy = policy()

            override fun fetchProfile(token: Token): UserProfile? =
                throw java.io.IOException("profile transport failed")

            override fun fetchHistory(token: Token): List<HistoryEntry> = emptyList()
        }
        val vm = HomeViewModel(data, TOKEN, CURRENT_VERSION, DIRECT, FakeSecureStore())

        vm.refresh()

        assertFalse(vm.state.loading, "a profile failure must not strand the refresh")
        assertNull(vm.state.error, "a cosmetic leg failure must not raise the error banner")
        assertTrue(vm.state.rules.isNotEmpty(), "rules must still render")
        assertEquals("", vm.state.schoolName, "a failed profile leaves the school name blank")
    }

    @Test
    fun `requirement - the school's required mileage renders as its own prominent line`() {
        val vm = HomeViewModel(FakeHomeData(policy()), TOKEN, CURRENT_VERSION, DIRECT, FakeSecureStore())

        vm.refresh()

        assertTrue(
            vm.state.requirementLine.contains("学校要求"),
            "requirement line missing: ${vm.state.requirementLine}",
        )
        assertTrue(vm.state.requirementLine.contains("2000"), "required distance missing: ${vm.state.requirementLine}")
    }

    @Test
    fun `update required - version check raises the prompt state`() {
        val vm = HomeViewModel(FakeHomeData(policy()), TOKEN, CURRENT_VERSION, DIRECT, FakeSecureStore())

        vm.onVersionCheck(
            RemoteVersionInfo(
                minApkVersionCode = CURRENT_VERSION + 1,
                configVersion = 1,
                protocolParamsVersion = 1,
                apkMirrors = emptyList(),
            ),
        )

        val prompt = vm.updatePrompt
        assertNotNull(prompt, "minApkVersionCode > current must raise the prompt state")
        assertTrue(
            prompt!!.message.contains("${CURRENT_VERSION + 1}"),
            "prompt must name the required version: $prompt",
        )
        assertNull(prompt.downloadUrl, "no mirror in the document -> no link to offer")

        vm.dismissUpdatePrompt()
        assertNull(vm.updatePrompt, "the prompt is dismissible (non-blocking)")
    }

    @Test
    fun `update required - prompt carries the first apk mirror url`() {
        val mirror = "https://example.invalid/releases/v9.apk"
        val vm = HomeViewModel(FakeHomeData(policy()), TOKEN, CURRENT_VERSION, DIRECT, FakeSecureStore())

        vm.onVersionCheck(
            RemoteVersionInfo(
                minApkVersionCode = CURRENT_VERSION + 1,
                configVersion = 1,
                protocolParamsVersion = 1,
                // Ordered mirrors: only the FIRST may surface in the dialog.
                apkMirrors = listOf(mirror, "https://mirror2.invalid/releases/v9.apk"),
            ),
        )

        val prompt = vm.updatePrompt
        assertNotNull(prompt, "a required update must raise the prompt state")
        assertEquals(mirror, prompt!!.downloadUrl, "dialog must offer the first apk mirror")
    }

    @Test
    fun `no update document - version check stays silent`() {
        val vm = HomeViewModel(FakeHomeData(policy()), TOKEN, CURRENT_VERSION, DIRECT, FakeSecureStore())
        vm.refresh()

        vm.onVersionCheck(null) // every mirror failed → UpdateChecker returns null

        assertNull(vm.updatePrompt, "a failed check must fail silently to 'no update'")
        assertFalse(vm.state.loading, "a silent update check must not disturb the loaded screen")
    }

    @Test
    fun `rules - school rule lines carry the run window and pace bounds`() {
        val vm = HomeViewModel(FakeHomeData(policy()), TOKEN, CURRENT_VERSION, DIRECT, FakeSecureStore())

        vm.refresh()

        val rules = vm.state.rules.joinToString("\n")
        assertTrue(rules.contains("10:00-22:00"), "run window missing: $rules")
        assertTrue(rules.contains("3:00-10:00"), "pace window missing: $rules")
        assertTrue(rules.contains("2000"), "distance floor missing: $rules")
        // weekCountGoal=0 (not advertised) → the weekly line must NOT render a fake 0.
        assertFalse(rules.contains("每周要求"), "absent weekCountGoal must not render: $rules")
    }

    @Test
    fun `rules - an advertised weekCountGoal renders as the weekly requirement`() {
        val vm = HomeViewModel(
            FakeHomeData(policy().copy(weekCountGoal = 4)),
            TOKEN,
            CURRENT_VERSION,
            DIRECT,
            FakeSecureStore(),
        )

        vm.refresh()

        val rules = vm.state.rules.joinToString("\n")
        assertTrue(rules.contains("每周要求：4 次"), "weekCountGoal must surface: $rules")
    }

    @Test
    fun `logout - drops the session exactly once and flips to logged-out`() {
        val store = FakeSecureStore()
        val vm = HomeViewModel(FakeHomeData(policy()), TOKEN, CURRENT_VERSION, DIRECT, store)

        vm.logout()

        assertTrue(vm.loggedOut, "logout must transition to the logged-out state")
        assertEquals(1, store.clearCalls, "logout must clear the session exactly once")
        assertEquals(0, store.sessionLeft, "the persisted session must be dropped")
        assertEquals(
            0,
            store.identity.calls,
            "device identity must survive logout — never re-derive the device id (spec §5.5-3)",
        )
    }

    @Test
    fun `logout - a second logout and a refresh after logout are no-ops`() {
        val store = FakeSecureStore()
        val vm = HomeViewModel(FakeHomeData(policy()), TOKEN, CURRENT_VERSION, DIRECT, store)
        vm.refresh()

        vm.logout()
        vm.logout() // already logged out: must not clear again, must not crash
        vm.refresh() // and the Home data load must stay parked

        assertEquals(1, store.clearCalls, "the second logout must not clear again")
        assertTrue(vm.loggedOut, "the logged-out state must stick")
        assertFalse(vm.state.loading, "a refresh after logout must not restart the load")
    }

    @Test
    fun `logout during refresh - in-flight legs never re-install the cleared credentials`() {
        // Fake ApiClient seam: the core clients (PolicyClient.kt:34 / ProfileClient /
        // HistoryClient) re-install their token into the shared ApiClient at the top of
        // every leg, and SessionManager.clear() nulls them — so "credentials stay null
        // after logout" is exactly the reviewer's race assertion.
        val api = FakeApiClient()
        api.setCredentials(TOKEN.uid, TOKEN.value) // live session before the logout
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "home-test").apply { isDaemon = true }
        }
        val store = object : TokenStore {
            var clears = 0
            override fun save(t: Token) = Unit
            override fun load(): Token? = null
            override fun clear() {
                clears += 1
                api.setCredentials(null, null) // models SessionManager.clear()
            }
        }
        val data = object : HomeData {
            var historyCalls = 0

            override fun fetchPolicy(token: Token): RunPolicy {
                api.setCredentials(token.uid, token.value) // mimics PolicyClient (entry)
                entered.countDown()
                release.await(5, TimeUnit.SECONDS) // in-flight window: logout lands here
                return policy()
            }

            override fun fetchProfile(token: Token): UserProfile? {
                api.setCredentials(token.uid, token.value) // mimics ProfileClient (entry)
                return null
            }

            override fun fetchHistory(token: Token): List<HistoryEntry> {
                api.setCredentials(token.uid, token.value) // mimics HistoryClient (entry)
                historyCalls += 1
                return emptyList()
            }
        }
        val vm = HomeViewModel(data, TOKEN, CURRENT_VERSION, executor, store)

        vm.refresh()
        assertTrue(entered.await(5, TimeUnit.SECONDS), "refresh must reach the first leg")
        vm.logout() // session dropped while the first leg is on the wire
        release.countDown()

        // Single-thread executor: a follower task runs only after the refresh task ended.
        val drained = CountDownLatch(1)
        executor.execute { drained.countDown() }
        assertTrue(drained.await(5, TimeUnit.SECONDS), "the refresh worker must finish")

        assertEquals(1, store.clears, "logout must clear the session exactly once")
        assertNull(api.uid, "no in-flight leg may re-install the cleared credentials")
        assertEquals(0, data.historyCalls, "the later token-taking legs must not start after logout")
    }

    /** Spec §5.5-6 display fixture — the live school's advertised rule (T2/T9 fixture). */
    private fun policy() = RunPolicy(
        requiredPoints = listOf(LatLng(34.7546, 113.6208)),
        optionalPoints = List(4) { LatLng(34.755, 113.621) },
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

    private class FakeHomeData(
        private val policy: RunPolicy,
        private val records: List<HistoryEntry> = emptyList(),
        private val profile: UserProfile? = null,
    ) : HomeData {
        override fun fetchPolicy(token: Token): RunPolicy = policy

        override fun fetchProfile(token: Token): UserProfile? = profile

        override fun fetchHistory(token: Token): List<HistoryEntry> = records
    }

    /**
     * Mirrors [dev.campusrun.app.data.AndroidTokenStore]: one store holding the session
     * keys AND the device-identity material, where [clear] drops only the session
     * (spec §5.5-3 — logout must not re-derive the device id).
     */
    private class FakeSecureStore : TokenStore {
        var clearCalls = 0
        var sessionLeft = 1

        val identity = FakeIdentityStore()

        override fun save(t: Token) {
            sessionLeft = 1
        }

        override fun load(): Token? = TOKEN.takeIf { sessionLeft == 1 }

        override fun clear() {
            clearCalls++
            sessionLeft = 0
        }
    }

    /** Identity surface (salt + snapshot) — the logout path must never reach it. */
    private class FakeIdentityStore {
        var calls = 0

        fun deviceSalt(): ByteArray {
            calls++
            return ByteArray(16)
        }

        fun saveDeviceIdentity() {
            calls++
        }

        fun loadDeviceIdentity() {
            calls++
        }
    }

    /** Stand-in for core's [dev.campusrun.core.api.ApiClient] credential snapshot. */
    private class FakeApiClient {
        var uid: String? = null
            private set

        fun setCredentials(uid: String?, token: String?) {
            this.uid = uid
        }
    }

    private companion object {
        /** Dummy session — no credential material (fixtures rule). */
        val TOKEN = Token(value = "unit-test-token", uid = "12345678", unid = "90001", expiresAtMs = null)

        const val CURRENT_VERSION = 1

        /** 2026-11-18 — a settled past date, safely before "today" for any wall clock. */
        const val PAST_MS = 1795000000000L

        val DIRECT = Executor { it.run() }
    }
}
