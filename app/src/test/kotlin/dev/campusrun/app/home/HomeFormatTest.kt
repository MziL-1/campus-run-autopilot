package dev.campusrun.app.home

import dev.campusrun.core.policy.LatLng
import dev.campusrun.core.policy.RunPolicy
import dev.campusrun.core.upload.HistoryEntry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pure Home formatting tests (brief Step 1) — the three facts the Home must show:
 *
 * - 学校要求的里程 (prominent copy of the rule's distance bounds, 未公布 when absent),
 * - 当前已完成的里程 — **counted only (计入成绩)**: prefers the server-side semester
 *   total (`SemesterSummaryClient.semesterDis`, the official 计入成绩 number) when the
 *   leg yields one, labeled as server/学期-sourced; otherwise sums the fetched page's
 *   official 达标 rows (`officialResult == true`), scope-labeled `近N条` because the
 *   records response carries no server-side semester summary (findings §3.1 D: `data`
 *   is a bare row array, never a semester total), with an honest labeled fallback when
 *   some rows carry no verdict field,
 * - 历史跑步记录 rows (date / distance / duration + the **server-sourced**
 *   达标/未达标 badge; rows without a verdict field carry no badge — never our
 *   divergent `valid`, which is display-suppressed).
 */
class HomeFormatTest {

    @Test
    fun `requirement - rule distance bounds render prominently and 未公布 when absent`() {
        val line = requirementText(policy(minDistancePerRunM = 2000, maxDistancePerRunM = 3000))

        assertTrue(line.contains("学校要求"), "line must be labeled as the school requirement: $line")
        assertTrue(line.contains("2000"), "per-run floor missing: $line")
        assertTrue(line.contains("3000"), "daily cap missing: $line")

        val absent = requirementText(policy(minDistancePerRunM = 0, maxDistancePerRunM = 0))
        assertTrue(absent.contains("未公布"), "empty out-of-window policy must read 未公布: $absent")
    }

    @Test
    fun `cumulative - counted progress sums only the official 达标 rows, scope-labeled`() {
        // The account's five real rows (2026-11-24 dump): two 官方 达标 (2009 + 2030),
        // three 官方 不达标 (2030 each) — the naive total is 10.13 km, counted is 4.04 km.
        val rows = listOf(
            HistoryEntry("1373345979", PAST_MS, 2030, 760, officialResult = true),
            HistoryEntry("1371833451", PAST_MS, 2009, 700, officialResult = true),
            HistoryEntry("1370876678", PAST_MS, 2030, 760, officialResult = false),
            HistoryEntry("1369631995", PAST_MS, 2030, 760, officialResult = false),
            HistoryEntry("1367719089", PAST_MS, 2030, 760, officialResult = false),
        )

        val line = cumulativeText(rows)

        assertTrue(line.contains("计入成绩"), "counted-only semantics must be labeled: $line")
        assertTrue(line.contains("近 5 条"), "scope must name the fetched row count: $line")
        assertTrue(line.contains("4.04 km"), "only the two 达标 rows may sum: $line")
        assertFalse(line.contains("10.13"), "不达标 rows must not contribute: $line")
        // The label must NOT masquerade as a semester/server total.
        assertFalse(line.contains("学期"), "a client-side page sum must not claim to be a semester total: $line")
    }

    @Test
    fun `cumulative - a server-side counted total is preferred and labeled as such`() {
        val rows = listOf(
            HistoryEntry("1373345979", PAST_MS, 2030, 760, officialResult = true),
            HistoryEntry("1371833451", PAST_MS, 2009, 700, officialResult = true),
            HistoryEntry("1370876678", PAST_MS, 2030, 760, officialResult = false),
        )

        val line = cumulativeText(rows, serverCountedM = 4039)

        assertTrue(line.contains("计入成绩"), "counted semantics must be labeled: $line")
        assertTrue(line.contains("4.04 km"), "the server total must render: $line")
        assertTrue(line.contains("服务端"), "the server source must be disclosed: $line")
        assertTrue(line.contains("学期"), "a server semester total may say so: $line")
        // The server number wins even over a divergent client sum (page rows here sum
        // to a different naive total) — no scope suffix that would contradict it.
        assertFalse(line.contains("近 3 条"), "the server total is not a page sum: $line")
    }

    @Test
    fun `cumulative - rows without a verdict field fall back to a labeled known-count line`() {
        val mixed = listOf(
            HistoryEntry("1", PAST_MS, 2030, 760, officialResult = true),
            HistoryEntry("2", PAST_MS, 1500, 600, officialResult = false),
            HistoryEntry("3", PAST_MS, 1000, 480, officialResult = null), // older row: no status
        )

        val line = cumulativeText(mixed)

        assertTrue(line.contains("计入成绩"), "counted semantics must survive the fallback: $line")
        assertTrue(line.contains("已知 1 条计入"), "known-count must be stated: $line")
        assertTrue(line.contains("1 条计分状态未知"), "unknown rows must be disclosed, not hidden: $line")
        assertTrue(line.contains("2.03 km"), "only the one 达标 row counts: $line")
        assertFalse(line.contains("学期"), "never a semester claim: $line")
    }

    @Test
    fun `cumulative - all rows unknown reads as unknown, never as a zero total`() {
        val allUnknown = listOf(
            HistoryEntry("1", PAST_MS, 2030, 760),
            HistoryEntry("2", PAST_MS, 1500, 600),
        )

        val line = cumulativeText(allUnknown)

        assertTrue(line.contains("计入成绩"), "the metric must still be named: $line")
        assertTrue(line.contains("未知"), "an all-unknown page must not print a counted total: $line")
        assertFalse(line.contains("0 m"), "unknown must not render as a zero sum: $line")
    }

    @Test
    fun `cumulative - small sums stay in meters and an empty page reads as the empty state`() {
        val small = cumulativeText(listOf(HistoryEntry("1", PAST_MS, 800, 300, officialResult = true)))
        assertTrue(small.contains("800 m"), "sub-km sums must stay in meters: $small")

        assertEquals("暂无跑步记录", cumulativeText(emptyList()))
    }

    @Test
    fun `row - maps date distance duration and the server-sourced verdict badge`() {
        val passed = historyRowText(
            HistoryEntry(
                id = "9001",
                startTimeMs = KNOWN_MS,
                distanceM = 2084,
                durationSec = 760,
                officialResult = true,
            ),
        )
        assertEquals("11-21 15:30 · 2084 m · 12:40 · 达标", passed, "badge must follow the objective fields")

        val failed = historyRowText(
            HistoryEntry(
                id = "9000",
                startTimeMs = KNOWN_MS,
                distanceM = 2030,
                durationSec = 760,
                officialResult = false,
            ),
        )
        assertTrue(failed.endsWith(" · 未达标"), "official 不达标 must render: $failed")
    }

    @Test
    fun `row - a row without a verdict field carries no badge instead of a guess`() {
        val unknown = historyRowText(
            HistoryEntry(id = "9002", startTimeMs = KNOWN_MS, distanceM = 2084, durationSec = 760),
        )

        assertEquals("11-21 15:30 · 2084 m · 12:40", unknown, "no verdict field -> no badge, never a guess")
        assertFalse(unknown.contains("达标"), "an unknown verdict must not be stated: $unknown")
    }

    @Test
    fun `row - unknown date or duration degrade to honest placeholders not zeros`() {
        val noDate = historyRowText(HistoryEntry("1", null, 100, 60, officialResult = true))
        assertTrue(noDate.contains("日期未知"), "missing startTime must not print epoch 0: $noDate")

        val noDuration = historyRowText(HistoryEntry("2", PAST_MS, 100, null, officialResult = false))
        assertTrue(noDuration.contains("时长未知"), "missing totalTime must not print 0 s: $noDuration")
    }

    @Test
    fun `duration - renders minute second under an hour and hours above`() {
        assertEquals("12:40", formatDuration(760))
        assertEquals("1:02:03", formatDuration(3723))
        assertEquals("时长未知", formatDuration(null))
    }

    @Test
    fun `latest - objective summary plus the server-sourced badge, never our divergent flag`() {
        val line = latestText(HistoryEntry("9001", KNOWN_MS, 2084, 760, officialResult = true))

        assertTrue(line.contains("id=9001"), "latest must name the record id: $line")
        assertTrue(line.contains("11-21 15:30"), "latest must carry the date: $line")
        assertTrue(line.contains("2084 m"), "latest must carry the distance: $line")
        assertTrue(line.contains("12:40"), "latest must carry the duration: $line")
        assertTrue(line.endsWith(" · 达标"), "the official badge must surface: $line")

        val unknown = latestText(HistoryEntry("9001", KNOWN_MS, 2084, 760))
        assertFalse(unknown.contains("达标"), "an unknown verdict must not be stated: $unknown")

        assertEquals("", latestText(null), "no record -> no latest line (the cumulative line owns the empty state)")
    }

    private fun policy(minDistancePerRunM: Int, maxDistancePerRunM: Int) = RunPolicy(
        requiredPoints = emptyList(),
        optionalPoints = emptyList(),
        fence = emptyList(),
        paceMinSecPerKm = 180,
        paceMaxSecPerKm = 600,
        validFromHour = 10,
        validToHour = 22,
        maxDistancePerRunM = maxDistancePerRunM,
        dailyRunLimit = 0,
        faceWindow = null,
        minDistancePerRunM = minDistancePerRunM,
    )

    private companion object {
        /** 2026-11-21 15:30 Asia/Shanghai (epoch ms) — the row date assertion pins this. */
        const val KNOWN_MS = 1795246200000L

        const val PAST_MS = 1795000000000L
    }
}
