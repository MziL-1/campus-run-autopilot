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
 * - 当前已完成的里程 (client-side sum of the fetched page, **labeled with its scope** —
 *   the records response carries no server-side semester summary, findings §3.1 D:
 *   `data` is a bare row array, so the sum is honestly `近N条累计`, never a semester total),
 * - 历史跑步记录 rows (date / distance / duration only — **objective data, no verdict**;
 *   the 19-key row has no field that tracks the official 达标 scoring, so our derived
 *   `valid` must never surface as fact).
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
    fun `cumulative - sum of the fetched page is labeled with its own scope`() {
        val rows = listOf(
            HistoryEntry(id = "1", startTimeMs = PAST_MS, distanceM = 2030, durationSec = 760),
            HistoryEntry(id = "2", startTimeMs = PAST_MS, distanceM = 1500, durationSec = 600),
            HistoryEntry(id = "3", startTimeMs = PAST_MS, distanceM = 1000, durationSec = 480),
        )

        val line = cumulativeText(rows)

        assertTrue(line.contains("近 3 条累计"), "scope must name the fetched row count: $line")
        assertTrue(line.contains("4.53 km"), "sum of 4530 m must render as km: $line")
        // The label must NOT masquerade as a semester/server total.
        assertFalse(line.contains("学期"), "a client-side page sum must not claim to be a semester total: $line")
    }

    @Test
    fun `cumulative - small sums stay in meters and an empty page reads as the empty state`() {
        val small = cumulativeText(listOf(HistoryEntry("1", PAST_MS, 800, 300)))
        assertTrue(small.contains("800 m"), "sub-km sums must stay in meters: $small")

        assertEquals("暂无跑步记录", cumulativeText(emptyList()))
    }

    @Test
    fun `row - maps date distance and duration from the objective fields only`() {
        val row = HistoryEntry(id = "9001", startTimeMs = KNOWN_MS, distanceM = 2084, durationSec = 760)

        val text = historyRowText(row)

        assertEquals("11-21 15:30 · 2084 m · 12:40", text, "row must be date · distance · duration")
        assertFalse(text.contains("达标"), "rows must never carry our verdict: $text")
        assertFalse(text.contains("未达标"), "rows must never carry our verdict: $text")
    }

    @Test
    fun `row - unknown date or duration degrade to honest placeholders not zeros`() {
        val noDate = historyRowText(HistoryEntry("1", null, 100, 60))
        assertTrue(noDate.contains("日期未知"), "missing startTime must not print epoch 0: $noDate")

        val noDuration = historyRowText(HistoryEntry("2", PAST_MS, 100, null))
        assertTrue(noDuration.contains("时长未知"), "missing totalTime must not print 0: $noDuration")
    }

    @Test
    fun `duration - renders minute second under an hour and hours above`() {
        assertEquals("12:40", formatDuration(760))
        assertEquals("1:02:03", formatDuration(3723))
        assertEquals("时长未知", formatDuration(null))
    }

    @Test
    fun `latest - objective summary carries id date distance duration, never a verdict`() {
        val line = latestText(HistoryEntry("9001", KNOWN_MS, 2084, 760))

        assertTrue(line.contains("id=9001"), "latest must name the record id: $line")
        assertTrue(line.contains("11-21 15:30"), "latest must carry the date: $line")
        assertTrue(line.contains("2084 m"), "latest must carry the distance: $line")
        assertTrue(line.contains("12:40"), "latest must carry the duration: $line")
        assertFalse(line.contains("达标"), "our valid-derived verdict must not surface: $line")

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
