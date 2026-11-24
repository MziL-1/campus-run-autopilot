package dev.campusrun.app.home

import dev.campusrun.app.run.RunStages
import dev.campusrun.app.run.RunViewModel
import dev.campusrun.core.config.RemoteVersionInfo
import dev.campusrun.core.campus.CampusOption
import dev.campusrun.core.campus.CampusSelectionStore
import dev.campusrun.core.policy.LatLng
import dev.campusrun.core.policy.RunPolicy
import dev.campusrun.core.profile.UserProfile
import dev.campusrun.core.session.Token
import dev.campusrun.core.session.TokenStore
import dev.campusrun.core.track.TrackPoint
import dev.campusrun.core.upload.HistoryEntry
import dev.campusrun.core.upload.RunRecord
import dev.campusrun.core.upload.UploadHandle
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
 * - `history present → counted cumulative + server-sourced badges` (计入成绩 sums only
 *   official 达标 rows; rows render the server's 达标/未达标 badge — our divergent
 *   `valid` never renders)
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
    fun `history present - progress shows the counted cumulative and badge-bearing rows`() {
        // The account's real page shape: two official 达标, three official 不达标.
        val rows = listOf(
            HistoryEntry(id = "1373345979", startTimeMs = PAST_MS, distanceM = 2030, durationSec = 760, officialResult = true),
            HistoryEntry(id = "1371833451", startTimeMs = PAST_MS, distanceM = 2009, durationSec = 700, officialResult = true),
            HistoryEntry(id = "1370876678", startTimeMs = PAST_MS, distanceM = 2030, durationSec = 760, officialResult = false),
            HistoryEntry(id = "1369631995", startTimeMs = PAST_MS, distanceM = 2030, durationSec = 760, officialResult = false),
        )
        val vm = HomeViewModel(FakeHomeData(policy(), rows), TOKEN, CURRENT_VERSION, DIRECT, FakeSecureStore())

        vm.refresh()

        val state = vm.state
        // Counted: only the two 达标 rows sum (4.04 km), scope-labeled to the fetched
        // page — never a semester total, never the naive 8.09 km.
        assertTrue(state.cumulativeLine.contains("计入成绩"), "counted semantics missing: ${state.cumulativeLine}")
        assertTrue(state.cumulativeLine.contains("近 4 条"), "scope missing: ${state.cumulativeLine}")
        assertTrue(state.cumulativeLine.contains("4.04 km"), "counted sum missing: ${state.cumulativeLine}")
        assertFalse(state.cumulativeLine.contains("8.09"), "不达标 rows must not contribute: ${state.cumulativeLine}")
        // History list: one row per record with the server-sourced badge.
        assertEquals(4, state.historyRows.size, "every fetched row must render")
        assertTrue(state.historyRows[0].endsWith(" · 达标"), "达标 badge missing: ${state.historyRows[0]}")
        assertTrue(state.historyRows[2].endsWith(" · 未达标"), "未达标 badge missing: ${state.historyRows[2]}")
        assertTrue(state.latestLine.contains("id=1373345979"), "latest must name the newest record: ${state.latestLine}")
        assertTrue(state.latestLine.endsWith(" · 达标"), "latest badge missing: ${state.latestLine}")
        // Our divergent `valid` flag must never render — only the server verdict does.
        val allText = (state.historyRows + state.latestLine + state.cumulativeLine).joinToString("\n")
        assertFalse(allText.contains("valid"), "our divergent flag must not render: $allText")
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

            override fun fetchCountedSemester(token: Token): Int? = null
        }
        val vm = HomeViewModel(data, TOKEN, CURRENT_VERSION, DIRECT, FakeSecureStore())

        vm.refresh()

        assertFalse(vm.state.loading, "a profile failure must not strand the refresh")
        assertNull(vm.state.error, "a cosmetic leg failure must not raise the error banner")
        assertTrue(vm.state.rules.isNotEmpty(), "rules must still render")
        assertEquals("", vm.state.schoolName, "a failed profile leaves the school name blank")
    }

    @Test
    fun `server semester total - preferred over the page sum and labeled as server-sourced`() {
        val rows = listOf(
            HistoryEntry(id = "1373345979", startTimeMs = PAST_MS, distanceM = 2030, durationSec = 760, officialResult = true),
            HistoryEntry(id = "1370876678", startTimeMs = PAST_MS, distanceM = 2030, durationSec = 760, officialResult = false),
        )
        val vm = HomeViewModel(
            FakeHomeData(policy(), rows, serverCountedM = 4039),
            TOKEN,
            CURRENT_VERSION,
            DIRECT,
            FakeSecureStore(),
        )

        vm.refresh()

        val line = vm.state.cumulativeLine
        assertTrue(line.contains("4.04 km"), "the server total must render: $line")
        assertTrue(line.contains("服务端"), "the server source must be disclosed: $line")
        assertFalse(line.contains("近 2 条"), "the server total must not carry the page scope: $line")
    }

    @Test
    fun `semester leg failure - refresh still lands with the counted page-sum fallback`() {
        // Best-effort like the profile leg: a thrown semester error degrades to the
        // counted client-side sum, never fails the refresh.
        val rows = listOf(
            HistoryEntry(id = "1373345979", startTimeMs = PAST_MS, distanceM = 2030, durationSec = 760, officialResult = true),
            HistoryEntry(id = "1370876678", startTimeMs = PAST_MS, distanceM = 2030, durationSec = 760, officialResult = false),
        )
        val vm = HomeViewModel(
            FakeHomeData(policy(), rows, semesterFailure = true),
            TOKEN,
            CURRENT_VERSION,
            DIRECT,
            FakeSecureStore(),
        )

        vm.refresh()

        assertFalse(vm.state.loading, "a semester failure must not strand the refresh")
        assertNull(vm.state.error, "a best-effort leg failure must not raise the error banner")
        val line = vm.state.cumulativeLine
        assertTrue(line.contains("2.03 km"), "counted page-sum fallback must render: $line")
        assertTrue(line.contains("近 2 条"), "the fallback keeps its page scope: $line")
        assertTrue(vm.state.rules.isNotEmpty(), "rules must still render")
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
            var semesterCalls = 0

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

            override fun fetchCountedSemester(token: Token): Int? {
                api.setCredentials(token.uid, token.value) // mimics SemesterSummaryClient (entry)
                semesterCalls += 1
                return null
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
        assertEquals(0, data.semesterCalls, "the semester leg must not start after logout either")
    }

    // --- 校区下拉 (campus switcher): the behavior matrix over BOTH platform signals ---
    // 1) one campus → plain line · 2) several + all allowChange=false → read-only ·
    // 3) gate closed OR gate fetch failed → read-only (conservative) ·
    // 4) several + permitted + gate open → dropdown.

    @Test
    fun `matrix - permitted campuses with an open gate offer the dropdown`() {
        val selection = FakeCampusSelection()
        val data = FakeHomeData(policy(), campuses = CAMPUS_LIST, gate = true)
        val vm = HomeViewModel(
            data,
            TOKEN,
            CURRENT_VERSION,
            DIRECT,
            FakeSecureStore(),
            campusSelection = selection,
        )

        vm.refresh()

        assertEquals(2, vm.state.campuses.size, "the account's campuses must surface")
        assertTrue(vm.state.campusSwitchable, "case 4: several + permitted + gate open")
        assertEquals(CAMPUS_LIST, vm.state.campusChoices, "only allowChange=true entries are offered")
        assertEquals(35102, vm.state.selectedCampusUnid, "the platform's isSelected campus is in effect")
        assertEquals(listOf("35102"), data.policyUnids, "the rule is read for that campus (selectUnid)")
        assertEquals(
            "35102",
            selection.value,
            "store ≡ effective: the run pipeline reads this SAME unid (display/execution agree)",
        )
    }

    @Test
    fun `matrix - a single campus stays a plain line even with the gate open`() {
        val data = FakeHomeData(policy(), campuses = CAMPUS_LIST.take(1), gate = true)
        val vm = HomeViewModel(data, TOKEN, CURRENT_VERSION, DIRECT, FakeSecureStore())

        vm.refresh()

        assertFalse(vm.state.campusSwitchable, "case 1: one campus is not a choice")
        assertEquals(
            listOf(35102),
            vm.state.campusChoices.map { it.unid },
            "the permitted entry is known, but one campus offers no choice",
        )
        assertEquals(35102, vm.state.selectedCampusUnid, "the only campus is still the one in effect")
        assertEquals(listOf("35102"), data.policyUnids, "the rule keeps following that campus")
    }

    @Test
    fun `matrix - several campuses the platform marks unchangeable render read-only`() {
        val locked = CAMPUS_LIST.map { it.copy(allowChange = false) }
        val data = FakeHomeData(policy(), campuses = locked, gate = true)
        val vm = HomeViewModel(data, TOKEN, CURRENT_VERSION, DIRECT, FakeSecureStore())

        vm.refresh()

        assertFalse(vm.state.campusSwitchable, "case 2: no permitted entry -> no affordance")
        assertEquals(emptyList<CampusOption>(), vm.state.campusChoices, "nothing unchangeable is ever offered")
        assertEquals(locked, vm.state.campuses, "the names stay visible in the read-only line")
        vm.selectCampus(35103)
        assertEquals(1, data.policyUnids.size, "a read-only header cannot trigger a switch")
    }

    @Test
    fun `matrix - a closed gate renders read-only even when the campuses allow it`() {
        val data = FakeHomeData(policy(), campuses = CAMPUS_LIST, gate = false)
        val vm = HomeViewModel(data, TOKEN, CURRENT_VERSION, DIRECT, FakeSecureStore())

        vm.refresh()

        assertFalse(vm.state.campusSwitchable, "case 3: openChangeCampus=false is decisive")
        assertEquals(CAMPUS_LIST, vm.state.campusChoices, "the permitted entries are known, the gate is not")
        vm.selectCampus(35103)
        assertEquals(1, data.policyUnids.size, "a closed gate cannot switch")
    }

    @Test
    fun `matrix - a failed gate fetch is conservative and stays read-only`() {
        // Documented failure policy: no answer (transport/business error) reads as
        // CLOSED — a hidden menu costs convenience, a false menu claims a switch the
        // platform never granted.
        val data = FakeHomeData(policy(), campuses = CAMPUS_LIST, gateFailure = true)
        val vm = HomeViewModel(data, TOKEN, CURRENT_VERSION, DIRECT, FakeSecureStore())

        vm.refresh()

        assertFalse(vm.state.campusSwitchable, "no answer -> no affordance")
        assertNull(vm.state.error, "a best-effort leg failure must not raise the error banner")
        assertTrue(vm.state.rules.isNotEmpty(), "the risk-control facts must still render")
        vm.selectCampus(35103)
        assertEquals(listOf("35102"), data.policyUnids, "and no switch can happen either")
    }

    @Test
    fun `campus switch - picking another campus persists it and re-reads the policy for it`() {
        val selection = FakeCampusSelection()
        val data = FakeHomeData(policy(), campuses = CAMPUS_LIST, gate = true)
        val vm = HomeViewModel(
            data,
            TOKEN,
            CURRENT_VERSION,
            DIRECT,
            FakeSecureStore(),
            campusSelection = selection,
        )

        vm.refresh()
        vm.selectCampus(35103)

        assertEquals("35103", selection.value, "the choice must survive a refresh or an app restart")
        assertEquals(35103, vm.state.selectedCampusUnid, "the switch must land in the state the UI reads")
        assertEquals(listOf("35102", "35103"), data.policyUnids, "the switch re-reads the rule for the new campus")
        assertEquals(2, vm.state.campuses.size, "the menu stays the account's own campuses")
    }

    @Test
    fun `display and execution agree - the run reads the campus Home just selected`() {
        // One store shared by both ViewModels (MainActivity passes one instance), so the
        // value selectCampus(B) writes on Home is literally what the run pipeline reads.
        val selection = FakeCampusSelection()
        val home = HomeViewModel(
            FakeHomeData(policy(), campuses = CAMPUS_LIST, gate = true),
            TOKEN,
            CURRENT_VERSION,
            DIRECT,
            FakeSecureStore(),
            campusSelection = selection,
        )
        home.refresh()
        home.selectCampus(35103) // the user picks campus B in the Home dropdown

        val stages = RecordingRunStages(policy())
        val run = RunViewModel(
            stages,
            TOKEN,
            DIRECT,
            settleWaitMs = 0,
            campusSelection = selection,
        )
        run.startRun(distanceM = 2030, durationSec = 760)

        assertEquals(
            listOf("35103"),
            stages.selectUnids,
            "the run's policy leg must carry the campus Home displays — no display/execution divergence",
        )
        assertEquals("90001", TOKEN.unid, "the session token itself stays untouched (settlement unchanged)")
    }

    @Test
    fun `campus switch - an id outside the account's own campuses is ignored`() {
        val selection = FakeCampusSelection()
        val data = FakeHomeData(policy(), campuses = CAMPUS_LIST, gate = true)
        val vm = HomeViewModel(
            data,
            TOKEN,
            CURRENT_VERSION,
            DIRECT,
            FakeSecureStore(),
            campusSelection = selection,
        )

        vm.refresh()
        vm.selectCampus(99999)

        assertEquals(
            "35102",
            selection.value,
            "no blind write: the store keeps the effective campus, never an unknown id",
        )
        assertEquals(35102, vm.state.selectedCampusUnid, "the effect stays where it was")
        assertEquals(1, data.policyUnids.size, "an ignored selection must not trigger a reload")
    }

    @Test
    fun `campus list failure - the refresh still lands and the persisted choice still applies`() {
        // Best-effort like the profile leg: a thrown zonelist error must not strand the
        // risk-control facts, and a transient failure must not silently move the rule
        // back to another campus.
        val selection = FakeCampusSelection(value = "35103")
        val data = FakeHomeData(policy(), campusFailure = true)
        val vm = HomeViewModel(
            data,
            TOKEN,
            CURRENT_VERSION,
            DIRECT,
            FakeSecureStore(),
            campusSelection = selection,
        )

        vm.refresh()

        assertFalse(vm.state.loading, "a campus failure must not strand the refresh")
        assertNull(vm.state.error, "a best-effort leg failure must not raise the error banner")
        assertTrue(vm.state.rules.isNotEmpty(), "rules must still render")
        assertEquals(emptyList<CampusOption>(), vm.state.campuses, "no answer -> no menu state")
        assertFalse(vm.state.campusSwitchable, "no answer -> no affordance")
        assertEquals(listOf("35103"), data.policyUnids, "the persisted choice keeps applying")
        assertEquals("35103", selection.value, "the failure must not clear the user's choice")
    }

    @Test
    fun `stale campus choice - replaced by the platform's own campus once it stops listing it`() {
        val selection = FakeCampusSelection(value = "99999")
        val data = FakeHomeData(policy(), campuses = CAMPUS_LIST, gate = true)
        val vm = HomeViewModel(
            data,
            TOKEN,
            CURRENT_VERSION,
            DIRECT,
            FakeSecureStore(),
            campusSelection = selection,
        )

        vm.refresh()

        assertEquals("35102", selection.value, "store ≡ effective: the stale id is replaced, not kept")
        assertEquals(listOf("35102"), data.policyUnids, "the platform's own campus takes over")
    }

    @Test
    fun `logout - clears the persisted campus choice with the session`() {
        val selection = FakeCampusSelection(value = "35103")
        val vm = HomeViewModel(
            FakeHomeData(policy(), campuses = CAMPUS_LIST, gate = true),
            TOKEN,
            CURRENT_VERSION,
            DIRECT,
            FakeSecureStore(),
            campusSelection = selection,
        )

        vm.logout()

        assertEquals(1, selection.clears, "the choice is per-account and drops with the session")
        assertNull(selection.value)
    }

    @Test
    fun `campus helpers - the full behavior matrix and the effective-unid rules`() {
        val allowed = CAMPUS_LIST
        val locked = CAMPUS_LIST.map { it.copy(allowChange = false) }

        // case 1: no choice
        assertFalse(campusSwitchable(emptyList(), gateOpen = true, selectedUnid = null), "no answer is never a menu")
        assertFalse(campusSwitchable(allowed.take(1), gateOpen = true, selectedUnid = null), "one campus is not a menu")
        // case 2: nothing the platform permits
        assertFalse(campusSwitchable(locked, gateOpen = true, selectedUnid = 35102), "all unchangeable -> read-only")
        // case 3: gate not granted
        assertFalse(campusSwitchable(allowed, gateOpen = false, selectedUnid = 35102), "gate closed -> read-only")
        // case 4: the real thing
        assertTrue(campusSwitchable(allowed, gateOpen = true, selectedUnid = 35102), "several + permitted + open")
        // no-op guard: the only permitted campus is already the one in effect
        val onlyCurrentPermitted = listOf(allowed[0], allowed[1].copy(allowChange = false))
        assertFalse(
            campusSwitchable(onlyCurrentPermitted, gateOpen = true, selectedUnid = 35102),
            "a menu whose only entry is already in effect is not a choice",
        )

        // choices: only the platform's permitted entries are ever offered
        assertEquals(listOf(35102, 35103), campusChoices(allowed).map { it.unid })
        assertEquals(listOf(35102), campusChoices(onlyCurrentPermitted).map { it.unid })

        // effective unid: persisted choice > platform default > session default
        assertEquals(35103, effectiveCampusUnid(35103, null), "a list failure keeps the persisted choice")
        assertEquals(35102, effectiveCampusUnid(null, CAMPUS_LIST), "the platform's isSelected campus wins")
        assertEquals(35102, effectiveCampusUnid(99999, CAMPUS_LIST), "an id the platform dropped is ignored")
        assertNull(
            effectiveCampusUnid(null, listOf(CAMPUS_LIST[1].copy(isSelected = false))),
            "no choice and no platform default -> session unid",
        )
        assertNull(effectiveCampusUnid(null, emptyList()), "no campuses and no choice -> session default")
        assertNull(effectiveCampusUnid(35103, emptyList()), "an empty list is a real answer: no override")
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
        private val serverCountedM: Int? = null,
        private val semesterFailure: Boolean = false,
        private val campuses: List<CampusOption>? = null,
        private val campusFailure: Boolean = false,
        private val gate: Boolean? = null,
        private val gateFailure: Boolean = false,
    ) : HomeData {
        /** Every unid the policy leg was asked to read — pins which campus's rule was fetched. */
        val policyUnids = mutableListOf<String>()

        override fun fetchPolicy(token: Token): RunPolicy {
            policyUnids += token.unid
            return policy
        }

        override fun fetchProfile(token: Token): UserProfile? = profile

        override fun fetchHistory(token: Token): List<HistoryEntry> = records

        override fun fetchCountedSemester(token: Token): Int? =
            if (semesterFailure) throw java.io.IOException("semester transport failed") else serverCountedM

        override fun fetchCampuses(token: Token): List<CampusOption>? =
            if (campusFailure) throw java.io.IOException("campus transport failed") else campuses

        /** `gate = null` mirrors the defaulted seam (no answer → conservatively closed). */
        override fun fetchCampusGate(token: Token): Boolean? =
            if (gateFailure) throw java.io.IOException("gate transport failed") else gate
    }

    /** [CampusSelectionStore] fake: records the persisted choice like the Android impl. */
    private class FakeCampusSelection(var value: String? = null) : CampusSelectionStore {
        var clears = 0

        override fun load(): String? = value

        override fun save(unid: String) {
            value = unid
        }

        override fun clear() {
            clears++
            value = null
        }
    }

    /**
     * [RunStages] fake that records only what this batch cares about: which campus the
     * run's policy leg was asked to read. Every other leg is inert (no network, no
     * submission — the pipeline may refuse the empty synthetic track at the validator,
     * which is fine: the policy leg already ran).
     */
    private class RecordingRunStages(private val policy: RunPolicy) : RunStages {
        val selectUnids = mutableListOf<String>()

        override fun syncSkewMs(): Long = 0L

        override fun fetchPolicy(token: Token, selectUnid: String): RunPolicy {
            selectUnids += selectUnid
            return policy
        }

        override fun synthesize(policy: RunPolicy, distanceM: Int, durationSec: Int, seed: Long): List<TrackPoint> =
            emptyList()

        override fun upload(token: Token, track: List<TrackPoint>): UploadHandle = UploadHandle(
            uid = "12345678",
            unid = "90001",
            uuid = "00000000-0000-0000-0000-000000000000",
            startTimeMs = 1_700_000_000_000L,
            stopTimeMs = 1_700_000_000_000L + 760_000L,
        )

        override fun settle(policy: RunPolicy, track: List<TrackPoint>, handle: UploadHandle): String? = "9001"

        override fun latestHistory(token: Token): RunRecord? = null
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

        /** Two dummy campuses (the upstream `MyCampusBean` field set, no live PII). */
        val CAMPUS_LIST = listOf(
            CampusOption(unid = 35102, name = "示例校区A", allowChange = true, isSelected = true),
            CampusOption(unid = 35103, name = "示例校区B", allowChange = true, isSelected = false),
        )
    }
}
