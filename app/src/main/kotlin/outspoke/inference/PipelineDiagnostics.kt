package dev.brgr.outspoke.inference

/**
 * Lightweight counters tracked during a single recording session.
 *
 * Exposed to the UI via [KeyboardViewModel] so that a quick glance at the status
 * indicator reveals whether anything unusual happened - without digging through logcat.
 *
 * Reset at the start of each recording session in [KeyboardViewModel.onRecordStart].
 */
data class PipelineDiagnostics(
    /** Number of [TranscriptResult.WindowTrimmed] events received. */
    val windowTrims: Int = 0,
    /** Number of times TextInjector's alignment recovery layers fired. */
    val alignmentRecoveries: Int = 0,
    /** Number of blank-after-cleaning strides discarded by InferenceRepository. */
    val blanksDiscarded: Int = 0,
) {
    /** True when all counters are zero - nothing unusual happened. */
    val isClean: Boolean get() = windowTrims == 0 && alignmentRecoveries == 0 && blanksDiscarded == 0

    /** Short one-line summary for display, e.g. "2T · 1R · 3B" or "✓" when clean. */
    fun summary(): String = if (isClean) "✓" else buildString {
        if (windowTrims > 0) append("${windowTrims}T")
        if (alignmentRecoveries > 0) {
            if (isNotEmpty()) append(" · ")
            append("${alignmentRecoveries}R")
        }
        if (blanksDiscarded > 0) {
            if (isNotEmpty()) append(" · ")
            append("${blanksDiscarded}B")
        }
    }
}

