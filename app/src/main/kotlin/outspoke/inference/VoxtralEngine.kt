package dev.brgr.outspoke.inference

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import dev.brgr.outspoke.audio.AudioChunk
import java.io.File

private const val TAG = "VoxtralEngine"

/**
 * [SpeechEngine] implementation for the Voxtral Mini 4B Realtime ONNX model from
 * https://huggingface.co/onnx-community/Voxtral-Mini-4B-Realtime-2602-ONNX.
 *
 * The full inference pipeline is left as a TODO below; session loading is functional.
 *
 * NOTE: Voxtral is a ~4 GB model — it requires a high-end device with substantial RAM.
 *       Verify the actual file names from the repository's `onnx/` directory and update
 *       [ENCODER_FILENAME] / [DECODER_FILENAME] accordingly before using in production.
 *
 * TODO: Implement the Voxtral inference pipeline:
 *   1. Convert PCM audio to the format expected by the encoder
 *      (likely log-mel features, similar to Whisper).
 *   2. Run the encoder session.
 *   3. Run the autoregressive Mistral-based decoder to generate token IDs.
 *   4. Detokenise using the Voxtral tokenizer vocabulary (tokenizer.json / vocab.json).
 */
class VoxtralEngine : SpeechEngine {

    private companion object {
        // TODO: Verify against https://huggingface.co/onnx-community/Voxtral-Mini-4B-Realtime-2602-ONNX/tree/main/onnx
        const val ENCODER_FILENAME = "encoder_model.onnx"
        const val DECODER_FILENAME = "decoder_model_merged.onnx"
    }

    private var env: OrtEnvironment? = null
    private var encoderSession: OrtSession? = null
    private var decoderSession: OrtSession? = null

    @Volatile override var isLoaded: Boolean = false
        private set

    override fun load(modelDir: File) {
        check(!isLoaded) { "Already loaded; call close() before reloading" }

        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(2)
            setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
        }

        env = OrtEnvironment.getEnvironment()
        val e = env!!

        val encoderFile = File(modelDir, ENCODER_FILENAME)
        val decoderFile = File(modelDir, DECODER_FILENAME)

        if (encoderFile.exists()) {
            encoderSession = e.createSession(encoderFile.absolutePath, opts)
            Log.d(TAG, "Encoder session loaded: ${encoderFile.name}")
        } else {
            Log.w(TAG, "$ENCODER_FILENAME not found in ${modelDir.path}")
        }

        if (decoderFile.exists()) {
            decoderSession = e.createSession(decoderFile.absolutePath, opts)
            Log.d(TAG, "Decoder session loaded: ${decoderFile.name}")
        } else {
            Log.w(TAG, "$DECODER_FILENAME not found in ${modelDir.path}")
        }

        opts.close()
        isLoaded = true
        Log.d(TAG, "VoxtralEngine ready (modelDir=${modelDir.path})")
    }

    /**
     * Transcribes [chunk] using the Voxtral encoder + Mistral decoder.
     *
     * TODO: Replace the stub below with a real Voxtral inference pipeline.
     *       See class-level KDoc for the pipeline steps.
     */
    override fun transcribe(chunk: AudioChunk): TranscriptResult =
        TranscriptResult.Failure(
            UnsupportedOperationException(
                "VoxtralEngine: full inference pipeline not yet implemented. " +
                "See VoxtralEngine.kt TODO for the required steps."
            )
        )

    override fun close() {
        encoderSession?.close(); encoderSession = null
        decoderSession?.close(); decoderSession = null
        env?.close();            env = null
        isLoaded = false
        Log.d(TAG, "VoxtralEngine closed")
    }
}

