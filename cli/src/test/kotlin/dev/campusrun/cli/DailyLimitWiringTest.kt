package dev.campusrun.cli

import dev.campusrun.core.policy.RunPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Review fix (Important): the 每日次数上限 gate must be **fail-closed at the CLI
 * wiring**. An omitted `--runs-today` parses as null — never as "0 runs today" — so a
 * school advertising `dailyRunLimit > 0` refuses before any submission until the
 * operator states the count explicitly (including an explicit 0). A school with no
 * advertised limit (the 7.4.x reality) is unaffected. The validator itself
 * ([dev.campusrun.core.policy.RunPolicyValidator.validateDailyLimit]) is unchanged and
 * covered by ValidatorTest.
 */
class DailyLimitWiringTest {

    private fun policy(dailyRunLimit: Int): RunPolicy = RunPolicy(
        requiredPoints = emptyList(),
        optionalPoints = emptyList(),
        fence = emptyList(),
        paceMinSecPerKm = 0,
        paceMaxSecPerKm = 0,
        validFromHour = 0,
        validToHour = 0,
        maxDistancePerRunM = 0,
        dailyRunLimit = dailyRunLimit,
        faceWindow = null,
    )

    @Test
    fun `absent runs-today flag parses as null`() {
        val options = parseRunOptions(listOf("--distance", "2030", "--time", "760"))

        assertNotNull(options)
        assertNull(options!!.runsToday, "an omitted --runs-today must not read as 0")
    }

    @Test
    fun `explicit runs-today zero parses as zero`() {
        val options = parseRunOptions(
            listOf("--distance", "2030", "--time", "760", "--runs-today", "0"),
        )

        assertEquals(0, options!!.runsToday, "explicit 0 is the operator's assertion of no runs yet")
    }

    @Test
    fun `advertised daily limit without flag refuses before submission`() {
        val refusal = dailyLimitAdmission(null, policy(dailyRunLimit = 1))

        assertNotNull(refusal, "dailyRunLimit > 0 with no explicit count must refuse")
        assertTrue(refusal!!.contains("dailyRunLimit=1"), refusal)
        assertTrue(refusal.contains("--runs-today"), refusal)
    }

    @Test
    fun `advertised daily limit with explicit zero proceeds to validation`() {
        assertNull(dailyLimitAdmission(0, policy(dailyRunLimit = 1)))
    }

    @Test
    fun `absent flag is fine when no daily limit is advertised`() {
        assertNull(dailyLimitAdmission(null, policy(dailyRunLimit = 0)))
    }
}
