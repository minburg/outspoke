package dev.brgr.outspoke.inference

import dev.brgr.outspoke.audio.AudioChunk
import java.io.File

/**
 * Abstraction over any on-device speech recognition engine.
 *
 * Implementing this interface allows [InferenceService] and [InferenceRepository] to
 * remain model-agnostic — swapping to Whisper, Moonshine, Canary, etc. requires only
 * a new class that implements this contract, with zero changes to the service layer.
 */
interface SpeechEngine {

    /** `true` once [load] has completed successfully and before [close] is called. */
    val isLoaded: Boolean

    /**
     * Initialises all inference sessions from [modelDir].
     * Must be called on a background thread — loading takes 1–3 s on first run.
     *
     * @throws IllegalStateException if called while already loaded.
     * @throws Exception if any required model file is missing or corrupt.
     */
    fun load(modelDir: File)

    /**
     * Runs inference on [chunk] and returns the recognised text.
     * Must be called only after [load] has returned without error.
     */
    fun transcribe(chunk: AudioChunk): TranscriptResult

    /**
     * Releases all native ONNX sessions and frees memory.
     * Safe to call even if [load] was never invoked.
     */
    fun close()
}

