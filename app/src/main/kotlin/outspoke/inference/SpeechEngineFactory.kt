package dev.brgr.outspoke.inference

import dev.brgr.outspoke.settings.model.ModelId

/**
 * Creates the appropriate [SpeechEngine] implementation for the given [ModelId].
 *
 * Adding support for a new model requires:
 *  1. A new [ModelId] enum value
 *  2. A new [SpeechEngine] implementation class
 *  3. A branch here
 */
object SpeechEngineFactory {
    fun create(modelId: ModelId): SpeechEngine = when (modelId) {
        ModelId.PARAKEET_V3   -> ParakeetEngine()
        ModelId.VOXTRAL_MINI  -> VoxtralEngine()
        ModelId.WHISPER_SMALL -> WhisperEngine()
    }
}

