package dev.brgr.outspoke.ui.keyboard

/** All possible states the keyboard UI can be in at any given moment. */
sealed class KeyboardUiState {
    /** Mic is idle - waiting for the user to press the talk button. */
    object Idle : KeyboardUiState()

    /** Mic is active and audio is being captured. */
    object Listening : KeyboardUiState()

    /**
     * Audio has been sent to the inference engine.
     * [partial] holds the most recent in-progress transcript shown as composing text.
     */
    data class Processing(val partial: String) : KeyboardUiState()

    /**
     * Audio capture has ended; the engine is running its final (definitive) inference
     * pass over the complete recording.  The mic button is idle but the talk button is
     * disabled while we wait for the transcript.  No partial text is available yet.
     */
    object Transcribing : KeyboardUiState()

    /**
     * The model detected audio but could not resolve a word (e.g. a short utterance whose
     * high-frequency fricatives were too weak). No text was committed; the UI shows a brief
     * "didn't catch that" cue. The talk button stays active so the user can retry.
     */
    object NoSpeech : KeyboardUiState()

    /**
     * Something went wrong. [reason] identifies the failure for localized display;
     * [detail] carries an optional technical detail (e.g. exception message) for
     * additional context.
     */
    data class Error(val reason: ErrorReason, val detail: String? = null) : KeyboardUiState()

    /**
     * The inference engine is not yet ready (model missing or still loading).
     * The talk button is disabled while in this state.
     * [reason] distinguishes between "no model" and "model loading".
     */
    data class EngineLoading(val reason: LoadingReason) : KeyboardUiState()

    /** Typed reasons for [Error] - mapped to localized strings in the UI layer. */
    enum class ErrorReason {
        MicPermissionDenied,

        /**
         * Permission granted and the recorder built, but the microphone belongs to another app:
         * either the platform refused the start or it client-silenced the session mid-recording
         * (Android feeds digital silence instead of failing). Recoverable by closing the other
         * app, so it is treated as a transient error with a retry action.
         */
        MicBusy,
        MicInitFailed,
        TranscriptionFailed,
        AudioCaptureFailed,
        EngineLoadFailed,
    }

    /** Typed reasons for [EngineLoading] - mapped to localized strings in the UI layer. */
    enum class LoadingReason {
        ModelNotDownloaded,
        EngineStarting,
    }
}
