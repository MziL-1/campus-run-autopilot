package dev.campusrun.cli

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.random.Random

/**
 * Review Focus #1 hard gate (plan Task 9 Step 3): **one real submission against
 * production 7.4.x**, ending in `HistoryClient.latest` returning `valid == true`.
 *
 * Session comes from `CAMPUS_TOKEN` (+ `CAMPUS_UID` / `CAMPUS_UNID`), minted immediately
 * before the run through the upstream Python login path (`tools/probe.ps1`'s
 * Get-TokenContext flow: `reference/venv/Scripts/python` + `login/cli.py`, credentials
 * only via `$env:CAMPUS_PHONE`/`$env:CAMPUS_PASSWORD`, output redacted) — Kotlin has no
 * credential login until Task 12 (spec R2 sequencing). The brief's enable gate stays
 * `CAMPUS_PHONE`; a set phone without a session FAILS loudly rather than skipping, so
 * the gate cannot go green by omission.
 *
 * Risk control (spec §5.5, dispatch rulings): one short run just over the 2000 m
 * completeness floor (settlement sends `selDistance = 2000`; anything shorter reads
 * back `complete=false`), in-window (enforced by the policy gate — outside the school
 * window the rule body is absent and the run stops at the `policy` stage), daily ≤ 1.
 *
 * The daily ≤ 1 rule no longer rests on an operator assertion: `executeRun` reads the
 * newest history record immediately before uploading and refuses when it is already
 * today's (`dailySelfLimitRefusal`), and this test additionally demands an explicit
 * `CAMPUS_ALLOW_LIVE_SUBMIT=1` confirmation before it may spend the daily budget at all.
 * `runsToday` is therefore left `null` (unknown, fail-closed against any advertised
 * server-side limit) instead of being hardcoded to 0.
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "CAMPUS_PHONE", matches = ".+")
class LiveRunTest {

    @Test
    fun `live submission reaches valid history record`() {
        val confirm = System.getenv("CAMPUS_ALLOW_LIVE_SUBMIT")
        require(confirm == "1") {
            "CAMPUS_ALLOW_LIVE_SUBMIT is not \"1\": a live submission spends the daily " +
                "budget (spec §5.5-4 ≤ 1) and needs explicit confirmation. Check that no " +
                "record exists for today, then re-run with CAMPUS_ALLOW_LIVE_SUBMIT=1."
        }

        val token = sessionFromEnv()
            ?: error(
                "CAMPUS_TOKEN/CAMPUS_UID not set — mint a session via the upstream Python login path " +
                    "(tools/probe.ps1 Get-TokenContext flow) immediately before :cli:liveTest",
            )

        val logLines = mutableListOf<String>()
        val outcome = executeRun(
            token = token,
            distanceM = LIVE_DISTANCE_M,
            durationSec = LIVE_DURATION_SEC,
            seed = Random.nextLong(),
            runsToday = null,
            log = logLines::add,
        )
        logLines.forEach { println("[live] $it") }

        assertTrue(outcome.violations.isEmpty(), "gate refused submission: ${outcome.violations}")
        assertTrue(outcome.uploaded, "submission was not uploaded")
        assertNotNull(outcome.rrid, "settlement did not return an rrid")

        val record = outcome.record
        assertNotNull(record, "history read-back returned no record — settlement or records-endpoint drift")
        assertTrue(record!!.valid) {
            "record id=${record.id} distance=${record.distanceM} m came back valid==false — " +
                "diagnose pace/checkpoints/distance/time before any re-submit (daily ≤ 1)"
        }
        assertTrue(record.distanceM >= 2000, "record distance ${record.distanceM} m below completeness floor")
    }

    private companion object {
        /** 2030 m: emitted length lands just under target, safely ≥ the 2000 m floor. */
        const val LIVE_DISTANCE_M = 2030

        /** 760 s → 374 s/km (inside 180–600) and ≈2090 steps (≥ settlement minSteps 2000). */
        const val LIVE_DURATION_SEC = 760
    }
}
