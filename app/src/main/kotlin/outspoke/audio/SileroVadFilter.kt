package dev.brgr.outspoke.audio

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import java.nio.FloatBuffer
import java.nio.LongBuffer

private const val TAG = "SileroVadFilter"

/**
 * A stateful neural-network VAD filter using Silero VAD v4 (ONNX).
 *
 * Silero provides highly accurate frame-level speech probabilities. We wrap this
 * raw probability stream with exactly the same onset, pre-roll, and hangover smoothing
 * logic used in the energy-based [RMSVadFilter].
 *
 * The `h` and `c` RNN state tensors are preserved across chunks in a continuous recording
 * session, and reset when [flush] is called.
 */
class SileroVadFilter(
    modelBytes: ByteArray,
    private val threshold: Float = 0.3f,
    private val leadInFrames: Int = LEAD_IN_FRAMES,
    private val onsetFrames: Int = ONSET_FRAMES,
) : VadFilter {

    private enum class State { SILENCE, SPEECH }

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var neuralFailed = false

    // Silero RNN states: [2, 1, 64] float tensors
    private val hState: FloatArray = FloatArray(2 * 1 * 64)
    private val cState: FloatArray = FloatArray(2 * 1 * 64)

    private var state = State.SILENCE
    private var onsetCount = 0
    private var hangoverFrames = 0
    private var consecutiveSilenceFrames = 0
    private val leadIn = ArrayDeque<AudioChunk>(leadInFrames + 1)

    override val isSpeechActive: Boolean get() = state == State.SPEECH

    init {
        try {
            env = OrtEnvironment.getEnvironment()
            OrtSession.SessionOptions().use { opts ->
                opts.setIntraOpNumThreads(1)
                opts.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
                opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
                session = env?.createSession(modelBytes, opts)
            }
            Log.d(TAG, "Silero VAD loaded successfully from RAW resource")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load Silero VAD model from bytes", e)
            session = null
            throw e
        }
    }

    override fun process(chunk: AudioChunk, rms: Float): List<AudioChunk> {
        val e = env
        val s = session
        var speechProb = 0f

        if (!neuralFailed && e != null && s != null && chunk.samples.size == REQUIRED_SAMPLES) {
            try {
                // Silero requires float32 samples in range [-1.0, 1.0].
                val floatSamples = FloatArray(REQUIRED_SAMPLES) { i -> chunk.samples[i] / 32_768f }

                OnnxTensor.createTensor(e, FloatBuffer.wrap(floatSamples), longArrayOf(1, REQUIRED_SAMPLES.toLong())).use { input ->
                    OnnxTensor.createTensor(e, LongBuffer.wrap(longArrayOf(SAMPLE_RATE.toLong())), longArrayOf(1)).use { rate ->
                        OnnxTensor.createTensor(e, FloatBuffer.wrap(hState), longArrayOf(2, 1, 64)).use { h ->
                            OnnxTensor.createTensor(e, FloatBuffer.wrap(cState), longArrayOf(2, 1, 64)).use { c ->
                                s.run(mapOf("input" to input, "sr" to rate, "h" to h, "c" to c)).use { result ->
                                    speechProb = (result.get("output").get() as OnnxTensor).floatBuffer[0]
                                    check(speechProb.isFinite()) { "VAD returned an invalid probability" }
                                    (result.get("hn").get() as OnnxTensor).floatBuffer.get(hState)
                                    (result.get("cn").get() as OnnxTensor).floatBuffer.get(cState)
                                }
                            }
                        }
                    }
                }
            } catch (ex: Exception) {
                Log.e(TAG, "Silero VAD failed; using energy detection for this recording", ex)
                neuralFailed = true
                speechProb = if (rms > 0.01f) 1.0f else 0.0f
            }
        } else {
            // Unaligned chunk size or missing session: Fallback purely on RMS gating just to avoid crashing pipeline.
            speechProb = if (rms > 0.01f) 1.0f else 0.0f
        }

        return applySmoothing(chunk, speechProb)
    }

    private fun applySmoothing(chunk: AudioChunk, prob: Float): List<AudioChunk> {
        return when (state) {
            State.SILENCE -> {
                if (leadIn.size >= leadInFrames) leadIn.removeFirst()
                leadIn.addLast(chunk)

                if (prob >= threshold) {
                    onsetCount++
                    if (onsetCount >= onsetFrames) {
                        state = State.SPEECH
                        onsetCount = 0
                        hangoverFrames = 0
                        consecutiveSilenceFrames = 0
                        val out = leadIn.toList()
                        leadIn.clear()
                        out
                    } else {
                        emptyList()
                    }
                } else {
                    onsetCount = 0
                    consecutiveSilenceFrames++
                    if (consecutiveSilenceFrames == SILENCE_BOUNDARY_FRAMES) {
                        consecutiveSilenceFrames = 0
                        listOf(AudioChunk(ShortArray(0), isSilenceBoundary = true))
                    } else {
                        emptyList()
                    }
                }
            }

            State.SPEECH -> {
                if (prob >= threshold) {
                    hangoverFrames = 0
                    listOf(chunk)
                } else {
                    hangoverFrames++
                    if (hangoverFrames >= HANGOVER_FRAMES) {
                        state = State.SILENCE
                        hangoverFrames = 0
                        onsetCount = 0
                        leadIn.clear()
                        emptyList()
                    } else {
                        listOf(chunk)
                    }
                }
            }
        }
    }

    override fun flush(): Boolean {
        val wasSpeech = state == State.SPEECH
        state = State.SILENCE
        hangoverFrames = 0
        onsetCount = 0
        consecutiveSilenceFrames = 0
        leadIn.clear()

        // Reset RNN state for the next recording session
        hState.fill(0f)
        cState.fill(0f)

        return wasSpeech
    }

    override fun close() {
        session?.close()
        session = null
    }

    companion object {
        private const val SAMPLE_RATE = 16_000

        /** 30 ms window at 16 kHz = 480 samples. Required by Silero v4 ONNX. */
        private const val REQUIRED_SAMPLES = 480

        /** 1 frame × 30 ms = 30 ms - opens gate on first confirmed speech frame to avoid clipping onset phonemes. */
        private const val ONSET_FRAMES = 1

        /** 20 frames × 30 ms = 600 ms - gives model pre-speech silence as acoustic context. */
        private const val LEAD_IN_FRAMES = 20

        /** 15 frames × 30 ms = 450 ms - captures trailing soft syllables. */
        private const val HANGOVER_FRAMES = 15

        /** 20 frames × 30 ms = 600 ms of silence after hangover before emitting an utterance boundary. */
        internal const val SILENCE_BOUNDARY_FRAMES = 20

        // ── Short-utterance VAD parameters ───────────────────────────────────────────
        // These constants define tighter onset and lead-in values intended for the
        // short-utterance path (< 2.5 s of VAD-active audio).  Reducing onset latency
        // and lead-in avoids clipping the very first phoneme of a brief command.
        //
        // TODO: Wire these into the short-utterance path once the VAD parameter
        //   injection API is in place.  The current architecture does not support
        //   per-path VAD configuration — SileroVadFilter is constructed once and
        //   shared for both short and long recordings — so these values are documented
        //   here as named constants for A/B testing readiness but are NOT applied yet.

        /** Target onset gate for the short-utterance path: 1 frame × 30 ms = 30 ms. */
        internal const val SHORT_UTT_VAD_ONSET_MS = 30

        /** Target lead-in for the short-utterance path: ~6-7 frames × 30 ms ≈ 200 ms. */
        internal const val SHORT_UTT_VAD_LEAD_IN_MS = 200
    }
}
