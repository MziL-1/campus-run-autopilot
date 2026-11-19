package dev.campusrun.core.upload

import dev.campusrun.core.policy.RunPolicyValidator.RULE_ZONE
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * Cross-session 每日 ≤1 gate (spec §5.5-4 #1 risk rule; final-fix-wave ruling):
 * [dailySelfLimitRefusal] turns the newest history row into a go/no-go answer, which is
 * what closes the "process restart re-enables a second run today" gap in both shells.
 *
 * Pure and offline — no HistoryClient, no network: the row's `startTime` is parsed by
 * [HistoryClient] (the wire side is pinned in RunUploaderTest) and only the *date
 * decision* lives here.
 */
class DailySelfLimitTest {

    private fun atNoon(date: LocalDate, zone: ZoneId = RULE_ZONE): Long =
        date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

    private val todayNoon: Long = atNoon(LocalDate.now(RULE_ZONE))
    private val yesterdayNoon: Long = atNoon(LocalDate.now(RULE_ZONE).minusDays(1))

    private fun record(startTimeMs: Long?, id: String = "9001", distanceM: Int = 2030) =
        RunRecord(id = id, distanceM = distanceM, valid = true, startTimeMs = startTimeMs)

    @Test
    fun `no history at all - first run proceeds`() {
        assertNull(dailySelfLimitRefusal(null, todayNoon), "an empty history must not block the first run")
    }

    @Test
    fun `record dated an earlier day - proceeds`() {
        assertNull(
            dailySelfLimitRefusal(record(yesterdayNoon), todayNoon),
            "yesterday's record must not block today's run",
        )
    }

    @Test
    fun `record dated today - refuses with the daily rule named`() {
        val message = dailySelfLimitRefusal(record(todayNoon), todayNoon)

        assertNotNull(message, "today's record must refuse the submission")
        assertTrue(message!!.contains("今日已有跑步记录"), "refusal must name the rule: $message")
        assertTrue(message.contains("9001"), "refusal must identify the blocking record: $message")
    }

    @Test
    fun `record dated today late in the day - still refuses`() {
        val lateToday = atNoon(LocalDate.now(RULE_ZONE).plusDays(0)) + 11 * 3_600_000L // 23:00
        val message = dailySelfLimitRefusal(record(lateToday), todayNoon)

        assertNotNull(message, "any timestamp of today must refuse, got: $message")
    }

    @Test
    fun `record dated in the future - fails closed instead of opening the gate`() {
        // A row dated tomorrow (server clock ahead, or a corrupt timestamp) is not "an
        // earlier day", so it must not be read as "not today" and let a run through.
        val tomorrow = atNoon(LocalDate.now(RULE_ZONE).plusDays(1))
        val message = dailySelfLimitRefusal(record(tomorrow), todayNoon)

        assertNotNull(message, "a future-dated record must not open the gate")
        assertTrue(message!!.contains("fail-closed"), "the message must state the posture: $message")
    }

    @Test
    fun `undated record - fails closed rather than silently proceeding`() {
        val message = dailySelfLimitRefusal(record(startTimeMs = null), todayNoon)

        assertNotNull(message, "an undatable record must fail closed")
        assertTrue(message!!.contains("缺少开始时间"), "the message must explain the cause: $message")
        assertTrue(message.contains("fail-closed"), "the message must state the posture: $message")
    }

    @Test
    fun `the comparison zone is honoured - not hard-coded`() {
        // 2026-06-15 00:30 +08 is 2026-06-14 16:30 UTC: same instant, two calendar days.
        val instant = LocalDate.of(2026, 6, 15).atTime(0, 30).atZone(RULE_ZONE).toInstant().toEpochMilli()
        val noonSameDay = atNoon(LocalDate.of(2026, 6, 15))

        assertNotNull(
            dailySelfLimitRefusal(record(instant), noonSameDay, zone = RULE_ZONE),
            "in the platform zone the record is today -> refuse",
        )
        assertNull(
            dailySelfLimitRefusal(record(instant), noonSameDay, zone = ZoneId.of("UTC")),
            "the same instant read in UTC is yesterday -> proceed; the zone parameter must win",
        )
    }
}
