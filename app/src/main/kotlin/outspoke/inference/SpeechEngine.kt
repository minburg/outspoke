package dev.brgr.outspoke.inference

import dev.brgr.outspoke.audio.AudioChunk
import java.io.File

/**
 * Abstraction over any on-device speech recognition engine.
 *
 * Implementing this interface allows [InferenceService] and [InferenceRepository] to
 * remain model-agnostic - swapping to Whisper, Moonshine, Canary, etc. requires only
 * a new class that implements this contract, with zero changes to the service layer.
 */
interface SpeechEngine {

    /** `true` once [load] has completed successfully and before [close] is called. */
    val isLoaded: Boolean

    /**
     * Initialises all inference sessions from [modelDir].
     * Must be called on a background thread - loading takes 1-3 s on first run.
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
     * Sets the active language for inference.
     *
     * [tag] is a BCP-47 language tag (e.g. `"en"`, `"de"`, `"nl"`) or `"auto"` to let the
     * engine detect the language automatically.  Engines that do not support language selection
     * (Parakeet, Voxtral) may leave this as a no-op.
     *
     * Thread-safe; may be called at any time, including between [transcribe] calls.
     */
    fun setLanguage(tag: String) { /* no-op by default */
    }

    /**
     * Restricts automatic language detection to a subset of BCP-47 tags.
     *
     * When [tags] is non-empty and the active language is `"auto"`, the engine will only
     * consider these languages during detection instead of the full ~100-language vocabulary.
     * This dramatically improves reliability when the user only ever speaks 2-4 languages:
     * the model's cross-attention correctly encodes the language; the constraint just stops
     * it picking an adjacent token (e.g. Spanish = 50262) over the intended one (English = 50259)
     * due to a tiny logit difference on short clips.
     *
     * No-op for engines that do not support language selection.
     */
    fun setLanguageConstraints(tags: List<String>) { /* no-op by default */
    }

    /**
     * Releases all native ONNX sessions and frees memory.
     * Safe to call even if [load] was never invoked.
     */
    fun close()
}

