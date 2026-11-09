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
        ModelId.PARAKEET_V3 -> ParakeetEngine()
        ModelId.PARAKEET_ULTRA -> ParakeetEngine()   // same NeMo TDT ONNX graph as v3 - engine is file-format driven
        ModelId.ORUKEET -> ParakeetEngine()          // v3 fine-tune; reordered decoder outputs handled by name resolution (issue #39)
        ModelId.VOXTRAL_MINI -> VoxtralEngine()
        ModelId.WHISPER_SMALL -> WhisperEngine()
    }
}

