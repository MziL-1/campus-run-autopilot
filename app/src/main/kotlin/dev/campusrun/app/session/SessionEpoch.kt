package dev.campusrun.app.session

import java.util.concurrent.atomic.AtomicLong

/**
 * Process-scoped logout generation counter (fix: invalidate in-flight work on logout).
 *
 * The shared `ApiClient` is long-lived and every token-taking core leg re-installs its
 * Token into it at entry ([dev.campusrun.core.policy.PolicyClient],
 * [dev.campusrun.core.upload.HistoryClient], [dev.campusrun.core.upload.RunUploader]),
 * while the workers that call them (Home refresh, the run pipeline) run on daemon
 * executors that composition disposal does NOT cancel. Dropping the session alone
 * therefore leaves a window where an in-flight leg re-populates the cleared
 * credentials — and a run can still submit after the user logged out.
 *
 * [bump] runs the moment the session is dropped (MainActivity's logout seam, *before* the store clear). Each pipeline captures [current] at task start and
 * re-compares before every token-taking leg; a mismatch aborts the pipeline before it
 * can re-install the dead token or submit. Capture-at-start keeps later sessions
 * unaffected — only work that outlived its own logout dies.
 *
 * Process-scoped like [dev.campusrun.app.run.RecentResults]: one per process, no reset
 * needed because every comparison is relative to the caller's captured value.
 */
object SessionEpoch {

    private val generation = AtomicLong()

    /** The current logout generation. */
    val current: Long
        get() = generation.get()

    /** Invalidate every worker holding an older generation (idempotent, cheap). */
    fun bump() {
        generation.incrementAndGet()
    }
}
