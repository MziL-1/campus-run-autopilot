package dev.campusrun.cli

import dev.campusrun.core.upload.RunRecord
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verdict-free read-back reporting for the CLI's `verify` / `run` output — the same
 * defect class as the Home fix (a93ea89): our [RunRecord.valid] diverges from the
 * official 达标 scoring in *both* directions (11-17 ours `true`/官方 不达标; 11-21 &
 * 11-23 ours `false`/官方 达标), so only objective facts (id · distance · date) may be
 * printed, and the exit code may key only on whether a record was read back at all.
 */
class RecordFactLineTest {

    @Test
    fun `objective row carries id, distance and date - never a verdict or the flag`() {
        val line = recordFactLine(
            RunRecord(id = "1373345979", distanceM = 2030, valid = false, startTimeMs = PAST_MS),
        )

        assertTrue(line.contains("id=1373345979"), "record id missing: $line")
        assertTrue(line.contains("2030 m"), "distance missing: $line")
        assertTrue(
            Regex("""· \d{2}-\d{2} \d{2}:\d{2}$""").containsMatchIn(line),
            "rule-zone date missing: $line",
        )
        assertFalse(line.contains("达标"), "our verdict must never render: $line")
        assertFalse(line.contains("valid"), "the divergent flag must stay out of output: $line")
    }

    @Test
    fun `a valid=true row reports the same facts with no verdict either`() {
        val line = recordFactLine(
            RunRecord(id = "1367719089", distanceM = 2030, valid = true, startTimeMs = PAST_MS),
        )

        assertTrue(line.contains("id=1367719089 · 2030 m"), "objective row mismatch: $line")
        assertFalse(line.contains("达标"), "our verdict must never render: $line")
    }

    @Test
    fun `an undated row degrades to an explicit placeholder instead of a fake date`() {
        val line = recordFactLine(RunRecord(id = "9001", distanceM = 2030, valid = false))

        assertTrue(line.contains("日期未知"), "missing date must be explicit: $line")
        assertFalse(line.contains("达标"), "our verdict must never render: $line")
    }

    private companion object {
        /** 2025-10-09 16:53:20 +08 — always a past date for these fixtures. */
        const val PAST_MS = 1_760_000_000_000L
    }
}
