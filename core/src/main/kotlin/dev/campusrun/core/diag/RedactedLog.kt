package dev.campusrun.core.diag

/**
 * In-memory diagnostic log that redacts at WRITE time, so raw credentials never sit in
 * memory or in any line a caller can read back (plan risk #4: free open-source
 * distribution means whatever a user exports could be posted publicly).
 *
 * Redaction classes, four for now and extensible later (plan Task 11): **token**,
 * **password**, **phone**, **uid**. Key/value classes match `key=value` / `key: value`
 * shapes including `CAMPUS_TOKEN=…` and `Authorization: Bearer …`; the phone class
 * matches a bare CN mobile number with digit boundaries, the same anchor as Task 2's
 * red-line checks and `tools/probe.ps1`, so 13-digit epoch millis are not mistaken for
 * phones.
 *
 * [DiagnosticExport] re-applies [sanitize] on export (defense in depth): the
 * `lastResults` summary comes from the caller, not through this log, and the export
 * path must stay safe even if a future caller bypasses [w].
 */
class RedactedLog {

    private val store = mutableListOf<String>()

    /** Appends one line, already redacted, as `"$tag: $msg"`. */
    @Synchronized
    fun w(tag: String, msg: String) {
        store += sanitize("$tag: $msg")
    }

    /** Snapshot of the redacted lines, safe to hand to an exporter. */
    @Synchronized
    fun lines(): List<String> = store.toList()

    companion object {

        private val rules: List<Pair<Regex, String>> = listOf(
            // password class: password=…, pwd: …
            Regex("""(?i)(password|passwd|pwd)(\s*[=:]\s*)(?:bearer\s+)?[^\s,;]+""") to "$1$2[REDACTED]",
            // token class: token=…, CAMPUS_TOKEN=…, Authorization: Bearer …
            Regex("""(?i)(token|authorization|auth)(\s*[=:]\s*)(?:bearer\s+)?[^\s,;]+""") to "$1$2[REDACTED]",
            // uid class: uid=…, unid=… (look-behind keeps `guid=`-shaped words intact)
            Regex("""(?i)(?<![A-Za-z])(unid|uid)(\s*[=:]\s*)[^\s,;]+""") to "$1$2[REDACTED]",
            // phone class: bare 11-digit CN mobile, digit-anchored — 13-digit epoch
            // timestamps fail the trailing boundary and survive untouched.
            Regex("""(?<!\d)1[3-9]\d{9}(?!\d)""") to "[REDACTED]",
        )

        /** Applies every redaction class; idempotent, so export can re-apply it safely. */
        fun sanitize(text: String): String = rules.fold(text) { acc, (pattern, replacement) ->
            acc.replace(pattern, replacement)
        }
    }
}
