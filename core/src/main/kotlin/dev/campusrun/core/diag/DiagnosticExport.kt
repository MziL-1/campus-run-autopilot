package dev.campusrun.core.diag

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * One-tap diagnostic package (spec §6.3 feedback loop, no backend): a local zip holding
 * the redacted log, the config version, and the recent-results summary. The user hands
 * the file back manually, so every entry is scrubbed with [RedactedLog.sanitize] even
 * though the log was already redacted at write time — `lastResults` bypasses
 * [RedactedLog] entirely, and defense in depth keeps the export path safe on its own.
 */
object DiagnosticExport {

    /**
     * Writes `diagnostics-<epochMillis>.zip` into [outDir] and returns its path.
     * Entries: `log.txt`, `config.txt`, `results.txt`.
     */
    fun export(log: RedactedLog, configVersion: Int, lastResults: List<String>, outDir: Path): Path {
        val zipPath = outDir.resolve("diagnostics-${System.currentTimeMillis()}.zip")
        Files.createDirectories(outDir)
        ZipOutputStream(Files.newOutputStream(zipPath)).use { zip ->
            writeEntry(zip, "log.txt", log.lines().joinToString("\n"))
            writeEntry(zip, "config.txt", "configVersion=$configVersion")
            writeEntry(zip, "results.txt", lastResults.joinToString("\n"))
        }
        return zipPath
    }

    private fun writeEntry(zip: ZipOutputStream, name: String, body: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(RedactedLog.sanitize(body).toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }
}
