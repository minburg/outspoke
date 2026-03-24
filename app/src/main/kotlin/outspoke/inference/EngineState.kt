package dev.brgr.outspoke.inference

/** Loading / runtime state of the [ParakeetEngine] inside [InferenceService]. */
sealed class EngineState {
    /** Model files are absent; the user needs to download them. */
    object Unloaded : EngineState()

    /** Engine is currently loading ONNX sessions into memory. */
    object Loading : EngineState()

    /** All sessions are ready; transcription can begin. */
    object Ready : EngineState()

    /** Loading failed. [message] is suitable for display in the keyboard UI. */
    data class Error(val message: String) : EngineState()
}

