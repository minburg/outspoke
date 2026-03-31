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
    private val threshold: Float = 0.3f
) : VadFilter {

    private enum class State { SILENCE, SPEECH }

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null

    // Silero RNN states: [2, 1, 64] float tensors
    private val hState: FloatArray = FloatArray(2 * 1 * 64)
    private val cState: FloatArray = FloatArray(2 * 1 * 64)

    private var state = State.SILENCE
    private var onsetCount = 0
    private var hangoverFrames = 0
    private val leadIn = ArrayDeque<AudioChunk>(LEAD_IN_FRAMES + 1)

    override val isSpeechActive: Boolean get() = state == State.SPEECH

    init {
        try {
            env = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(1)
                setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
            }
            session = env?.createSession(modelBytes, opts)
            Log.d(TAG, "Silero VAD loaded successfully from RAW resource")
            opts.close()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load Silero VAD model from bytes", e)
            session = null
        }
    }

    override fun process(chunk: AudioChunk, rms: Float): List<AudioChunk> {
        val e = env
        val s = session
        var speechProb = 0f

        if (e != null && s != null && chunk.samples.size == REQUIRED_SAMPLES) {
            try {
                // Silero requires float32 samples in range [-1.0, 1.0].
                val floatSamples = FloatArray(REQUIRED_SAMPLES) { i -> chunk.samples[i] / 32_768f }

                val inputTensor = OnnxTensor.createTensor(
                    e,
                    FloatBuffer.wrap(floatSamples),
                    longArrayOf(1, REQUIRED_SAMPLES.toLong())
                )
                val srTensor =
                    OnnxTensor.createTensor(e, LongBuffer.wrap(longArrayOf(SAMPLE_RATE.toLong())), longArrayOf(1))
                val hTensor = OnnxTensor.createTensor(e, FloatBuffer.wrap(hState), longArrayOf(2, 1, 64))
                val cTensor = OnnxTensor.createTensor(e, FloatBuffer.wrap(cState), longArrayOf(2, 1, 64))

                val inputs = mapOf(
                    "input" to inputTensor,
                    "sr" to srTensor,
                    "h" to hTensor,
                    "c" to cTensor
                )

                s.run(inputs).use { result ->
                    val outputTensor = result.get("output").get() as OnnxTensor
                    speechProb = outputTensor.floatBuffer[0]

                    val hnTensor = result.get("hn").get() as OnnxTensor
                    hnTensor.floatBuffer.get(hState)
                    val cnTensor = result.get("cn").get() as OnnxTensor
                    cnTensor.floatBuffer.get(cState)
                }

                inputTensor.close()
                srTensor.close()
                hTensor.close()
                cTensor.close()
            } catch (ex: Exception) {
                Log.e(TAG, "Silero VAD inference failed", ex)
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
                if (leadIn.size >= LEAD_IN_FRAMES) leadIn.removeFirst()
                leadIn.addLast(chunk)

                if (prob >= threshold) {
                    onsetCount++
                    if (onsetCount >= ONSET_FRAMES) {
                        state = State.SPEECH
                        onsetCount = 0
                        hangoverFrames = 0
                        val out = leadIn.toList()
                        leadIn.clear()
                        out
                    } else {
                        emptyList()
                    }
                } else {
                    onsetCount = 0
                    emptyList()
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

        /** 2 frames × 30 ms = 60 ms - eliminates single-frame false triggers. */
        private const val ONSET_FRAMES = 2

        /** 15 frames × 30 ms = 450 ms - captures onset phonemes before speech gate opened. */
        private const val LEAD_IN_FRAMES = 15

        /** 15 frames × 30 ms = 450 ms - captures trailing soft syllables. */
        private const val HANGOVER_FRAMES = 15
    }
}
