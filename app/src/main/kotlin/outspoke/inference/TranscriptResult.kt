package dev.brgr.outspoke.inference

/** Represents the outcome of a single transcription attempt. */
sealed class TranscriptResult {

    /**
     * In-progress transcript from streaming inference.
     * The keyboard should show [text] as composing (underlined) text.
     */
    data class Partial(val text: String) : TranscriptResult()

    /**
     * Final, confirmed transcript for a completed utterance.
     * The keyboard should commit [text] to the active input field.
     */
    data class Final(val text: String) : TranscriptResult()

    /** Inference failed. [cause] carries the underlying exception for logging/display. */
    data class Failure(val cause: Throwable) : TranscriptResult()

    /**
     * The rolling audio window was trimmed to prevent attention drift.
     *
     * [TextInjector] must call [resetAfterTrim][dev.brgr.outspoke.ime.TextInjector.resetAfterTrim]
     * when it receives this so that its committed-word tracking is shrunk to the words most
     * likely to still be inside the retained tail audio.  Without this, the suffix-overlap
     * alignment in [setPartial][dev.brgr.outspoke.ime.TextInjector.setPartial] fails for
     * every stride after the trim and middle sentences are silently dropped.
     *
     * [stableWords] carries the confirmed-stable leading words from the partial that triggered
     * the trim (up to [safeStableCount] words from InferenceRepository).  TextInjector uses
     * them as the new [committedWords] anchor directly, bypassing the field re-read which may
     * be stale when the composing span was stuck (RC-3 / P4 fix).  Defaults to an empty list
     * for force-trims and silence-trims where no stable word list exists.
     */
    data class WindowTrimmed(val stableWords: List<String> = emptyList()) : TranscriptResult()
}

