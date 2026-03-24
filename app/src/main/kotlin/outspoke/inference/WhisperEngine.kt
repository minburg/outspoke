package dev.brgr.outspoke.inference

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import dev.brgr.outspoke.audio.AudioChunk
import java.io.File

private const val TAG = "WhisperEngine"

/**
 * [SpeechEngine] implementation for the Whisper Small INT8 ONNX model distributed as a
 * ZIP archive from https://huggingface.co/DocWolle/whisperOnnx.
 *
 * The full inference pipeline (mel-spectrogram → encoder → autoregressive decoder) is
 * a significant undertaking and is left as a TODO below. The session loading is fully
 * functional so the engine lifecycle (load / close) works correctly.
 *
 * TODO: Implement the Whisper inference pipeline:
 *   1. Convert PCM audio to log-mel spectrogram (80 mel bins, 25 ms window, 10 ms hop).
 *   2. Pad / trim to 30 s (3 000 frames) and normalise to [-1, 1].
 *   3. Run the encoder session to obtain encoder hidden states.
 *   4. Run the decoder session autoregressively, seeding with the SOT token,
 *      until the EOT token or max-token limit is reached.
 *   5. Detokenise the predicted token IDs using the Whisper tokenizer vocabulary.
 *
 * NOTE: Verify the actual ONNX file names inside whisper_small_int8.zip and update
 * the [ENCODER_FILENAME] / [DECODER_FILENAME] constants accordingly.
 */
class WhisperEngine : SpeechEngine {

    private companion object {
        // TODO: Confirm exact file names from the contents of whisper_small_int8.zip.
        //       Common patterns used by ONNX Whisper exports:
        //         encoder_model_quantized.onnx / decoder_model_merged_quantized.onnx  (onnx-community)
        //         whisper_encoder.onnx         / whisper_decoder.onnx                 (other exports)
        const val ENCODER_FILENAME = "encoder_model_quantized.onnx"
        const val DECODER_FILENAME = "decoder_model_merged_quantized.onnx"
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
            Log.w(TAG, "$ENCODER_FILENAME not found — verify file names in the ZIP archive")
        }

        if (decoderFile.exists()) {
            decoderSession = e.createSession(decoderFile.absolutePath, opts)
            Log.d(TAG, "Decoder session loaded: ${decoderFile.name}")
        } else {
            Log.w(TAG, "$DECODER_FILENAME not found — verify file names in the ZIP archive")
        }

        opts.close()
        isLoaded = true
        Log.d(TAG, "WhisperEngine ready (modelDir=${modelDir.path})")
    }

    /**
     * Transcribes [chunk] using the Whisper encoder + decoder.
     *
     * TODO: Replace the stub below with a real Whisper inference pipeline.
     *       See class-level KDoc for the pipeline steps.
     */
    override fun transcribe(chunk: AudioChunk): TranscriptResult =
        TranscriptResult.Failure(
            UnsupportedOperationException(
                "WhisperEngine: full inference pipeline not yet implemented. " +
                "See WhisperEngine.kt TODO for the required steps."
            )
        )

    override fun close() {
        encoderSession?.close(); encoderSession = null
        decoderSession?.close(); decoderSession = null
        env?.close();            env = null
        isLoaded = false
        Log.d(TAG, "WhisperEngine closed")
    }
}

