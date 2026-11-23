package dev.campusrun.cli

import dev.campusrun.core.policy.RunPolicyValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.time.Instant
import java.time.LocalDate
import kotlin.math.abs
import kotlin.random.Random

/**
 * Review Focus #1 hard gate (plan Task 9 Step 3): **one real submission against
 * production 7.4.x**, ending in `HistoryClient.latest` returning the row we just
 * submitted — present, the target distance, and dated today.
 *
 * **No verdict assertion.** Our `valid` field diverges from official scoring in
 * *both* directions: 11-17 ours `true` / official 不达标; 11-21 ours `false` /
 * official 达标; 11-23 ours `false` / official 达标 (avg pace 6'14"). The 7.4.x
 * 19-key row carries no official-scoring field at all (home-enhance report), so
 * `valid == true` is not something our read path can truthfully establish.
 * Official scoring is asserted **externally** by the grayscale daily official-app
 * check (`docs/acceptance/grayscale-log.md`), never by this test.
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
    fun `live submission reaches objective history record`() {
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

        // Submission flow: gate passed, OBS accepted, settle rrid, verify id all logged above.
        assertTrue(outcome.violations.isEmpty(), "gate refused submission: ${outcome.violations}")
        assertTrue(outcome.uploaded, "submission was not uploaded")
        assertNotNull(outcome.rrid, "settlement did not return an rrid")

        // Objective read-back only. Why NOT `valid == true` (removed deliberately):
        // our flag diverges from official scoring in all three observed directions —
        //   11-17: ours valid=true  / official 不达标
        //   11-21: ours valid=false / official 达标
        //   11-23: ours valid=false / official 达标 (6'14"/km, rrid=1373345979)
        // and the 19-key row has no official-scoring field (home-enhance report), so
        // our read path cannot establish verdicts. Official scoring is asserted by the
        // grayscale daily official-app check (docs/acceptance/grayscale-log.md), NOT
        // here. Failures below therefore point at pace/checkpoints/distance/time only
        // when the record is MISSING or objectively wrong — never at a validity flag.
        val record = outcome.record
        assertNotNull(
            record,
            "history read-back returned no record — settlement or records-endpoint drift; " +
                "diagnose pace/checkpoints/distance/time before any re-submit (daily ≤ 1)",
        )
        assertTrue(
            abs(record!!.distanceM - LIVE_DISTANCE_M) <= DISTANCE_TOLERANCE_M,
            "record id=${record.id} distance=${record.distanceM} m ≠ submitted target " +
                "$LIVE_DISTANCE_M m ±$DISTANCE_TOLERANCE_M — diagnose pace/checkpoints/distance/time " +
                "before any re-submit (daily ≤ 1)",
        )
        assertTrue(record.distanceM >= 2000, "record distance ${record.distanceM} m below completeness floor")

        // Daily-gate evidence: the row we read back must be dated today (same
        // startTimeMs parsing / RULE_ZONE as dailySelfLimitRefusal).
        val startMs = record.startTimeMs
        assertNotNull(
            startMs,
            "record id=${record.id} carries no startTime — daily gate cannot confirm today's run; " +
                "check the records row's startTime field",
        )
        val zone = RunPolicyValidator.RULE_ZONE
        val recordDay = Instant.ofEpochMilli(startMs!!).atZone(zone).toLocalDate()
        assertEquals(
            LocalDate.now(zone),
            recordDay,
            "record id=${record.id} dated $recordDay is not today — this submission's " +
                "daily-gate evidence is broken (check startTime parsing / server clock)",
        )
    }

    private companion object {
        /** 2030 m: emitted length lands just under target, safely ≥ the 2000 m floor. */
        const val LIVE_DISTANCE_M = 2030

        /** Read-back tolerance: server rounding of the submitted length (upstream `totalDis`). */
        const val DISTANCE_TOLERANCE_M = 50

        /** 760 s → 374 s/km (inside 180–600) and ≈2090 steps (≥ settlement minSteps 2000). */
        const val LIVE_DURATION_SEC = 760
    }
}
