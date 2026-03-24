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
}

