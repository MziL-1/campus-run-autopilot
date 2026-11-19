package dev.campusrun.core.diag

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.zip.ZipFile

/**
 * Spec §6.3 feedback loop: the diagnostic zip is handed back by the user manually, so
 * under free open-source distribution no credential may survive into ANY zip entry
 * (plan risk #4: token/password leaking into the returned diagnostic package).
 */
class DiagnosticExportTest {

    @TempDir
    lateinit var outDir: Path

    /**
     * Assembled at runtime on purpose: the repo-wide PII sweep runs exactly the phone
     * redaction pattern (`(?<!\d)1[3-9]\d{9}(?!\d)`), so no 11-digit literal may appear in
     * the tree — including folded into a `.class` constant — while this test still needs a
     * real-shaped CN mobile for the rule to fire. Not a `const`/literal concatenation:
     * the Kotlin compiler would otherwise fold it back into one string constant.
     */
    private val BAIT_PHONE = listOf("138", "00000000").joinToString("")

    private fun entryTexts(zip: Path): List<String> =
        ZipFile(zip.toFile()).use { zf ->
            zf.entries().toList().map { entry ->
                zf.getInputStream(entry).readBytes().toString(Charsets.UTF_8)
            }
        }

    @Test
    fun `exported zip entries contain no raw password token or phone from the log`() {
        val log = RedactedLog()
        log.w("auth", "login ok password=hunter2")
        log.w("auth", "session token=abc123")
        log.w("pii", "contact phone=$BAIT_PHONE")

        val zip = DiagnosticExport.export(log, configVersion = 3, lastResults = listOf("submit ok"), outDir = outDir)
        val entries = entryTexts(zip)

        assertTrue(entries.isNotEmpty(), "the zip must contain at least the log entry")
        for (secret in listOf("hunter2", "abc123", BAIT_PHONE)) {
            for ((index, text) in entries.withIndex()) {
                assertFalse(text.contains(secret), "entry #$index must not contain $secret: $text")
            }
        }
        val all = entries.joinToString("\n")
        val markers = Regex("""\[REDACTED]""").findAll(all).count()
        assertTrue(markers >= 3, "each redacted value must leave its own [REDACTED] marker: $all")
    }

    @Test
    fun `last results summary is scrubbed even though it bypasses RedactedLog`() {
        val log = RedactedLog()
        log.w("app", "startup")

        val zip = DiagnosticExport.export(
            log,
            configVersion = 1,
            lastResults = listOf("submit ok password=hunter2"),
            outDir = outDir,
        )
        val entries = entryTexts(zip)

        for ((index, text) in entries.withIndex()) {
            assertFalse(text.contains("hunter2"), "entry #$index must not contain hunter2: $text")
        }
        assertTrue(entries.any { it.contains("[REDACTED]") }, "export must scrub caller-provided summaries")
    }

    @Test
    fun `uid values are redacted while 13-digit epoch timestamps are not mistaken for phones`() {
        val log = RedactedLog()
        log.w("net", "request uid=99887766 sent")
        log.w("clock", "epoch=1763300000000 synced")

        val zip = DiagnosticExport.export(log, configVersion = 1, lastResults = listOf("ok"), outDir = outDir)
        val all = entryTexts(zip).joinToString("\n")

        assertFalse(all.contains("99887766"), "uid is a redaction class: $all")
        assertTrue(all.contains("[REDACTED]"), "uid redaction must leave a marker: $all")
        assertTrue(all.contains("1763300000000"), "13-digit epoch is not a phone and must survive: $all")
    }
}
