package dev.brgr.outspoke.ui.keyboard

/** All possible states the keyboard UI can be in at any given moment. */
sealed class KeyboardUiState {
    /** Mic is idle — waiting for the user to press the talk button. */
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
     *
     * This state exists to distinguish "engine working, mic off" (e.g. Whisper's
     * 10-20 s final decode) from "engine working, mic on" ([Processing]).
     */
    object Transcribing : KeyboardUiState()

    /** Something went wrong. [message] is a user-facing description. */
    data class Error(val message: String) : KeyboardUiState()

    /**
     * The inference engine is not yet ready (model missing or still loading).
     * The talk button is disabled while in this state.
     */
    data class EngineLoading(val message: String) : KeyboardUiState()
}

