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

    /** Something went wrong. [message] is a user-facing description. */
    data class Error(val message: String) : KeyboardUiState()

    /**
     * The inference engine is not yet ready (model missing or still loading).
     * The talk button is disabled while in this state.
     */
    data class EngineLoading(val message: String) : KeyboardUiState()
}

