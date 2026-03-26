package dev.brgr.outspoke.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlin.math.sqrt

private const val TAG = "AudioCaptureManager"

/** 16 kHz mono — matches Parakeet V3's expected input format. */
private const val SAMPLE_RATE = 16_000

/** 40 ms window at 16 kHz = 640 samples per chunk. */
private const val CHUNK_SAMPLES = 640

// ── Adaptive Gain Control (AGC) ──────────────────────────────────────────────
// VOICE_RECOGNITION disables hardware AGC, so raw RMS values are tiny
// (speech ≈ 0.003–0.05, shouting ≈ 0.05–0.2 on most devices).  A fixed gain
// can't cover the range of microphone sensitivities and room volumes, so we
// use a peak-envelope follower:
//   • Fast attack  → envelope rises to a new peak within ~7 chunks (280 ms).
//   • Slow decay   → envelope falls back with a half-life of ~2 seconds after
//                     the user stops talking, then hits the floor.
//   • Floor        → keeps bars near-zero during genuine silence so the user
//                     can see the mic is active without the bars being distracting.

/** Minimum peak envelope — prevents infinite gain in total silence. */
private const val AGC_FLOOR   = 0.01f

/** Per-chunk attack coefficient (0 = no rise, 1 = instant). */
private const val AGC_ATTACK  = 0.15f

/**
 * Per-chunk decay multiplier.
 * Half-life = log(0.5) / log(AGC_DECAY) chunks ≈ 45 chunks ≈ 1.8 s.
 */
private const val AGC_DECAY   = 0.985f

/**
 * Manages a single [AudioRecord] session and emits captured audio as a cold
 * [Flow]<[AudioChunk]>. Runs entirely on [Dispatchers.IO].
 *
 * Typical usage:
 * ```kotlin
 * val job = scope.launch {
 *     manager.startCapture().collect { chunk -> } // forward to inference
 * }
 * job.cancel() // stops recording and releases AudioRecord
 * ```
 */
class AudioCaptureManager(private val context: Context) {

    private val _amplitude = MutableStateFlow(0f)

    /**
     * Set to `true` by [stopCapture] to end the read loop on the next iteration.
     * Using a flag instead of coroutine cancellation lets the [Flow] complete normally
     * so that the inference layer can emit a final result before tearing down.
     */
    @Volatile private var stopRequested: Boolean = false

    /**
     * Running peak envelope for AGC.  Intentionally NOT reset between sessions so
     * the gain stays calibrated across repeated short recordings in the same environment.
     */
    @Volatile private var agcEnvelope: Float = AGC_FLOOR

    /**
     * Signals the active [startCapture] flow to exit its read loop and complete normally.
     * The flow will finish within one 40 ms chunk read cycle (~40 ms latency).
     */
    fun stopCapture() {
        stopRequested = true
    }

    /**
     * Normalised RMS amplitude of the most recently captured chunk, in the range [0.0, 1.0].
     * Resets to 0.0 when capture stops.
     */
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    /**
     * Starts microphone capture and emits each 40 ms [AudioChunk] downstream.
     *
     * **Prerequisites:** [PermissionHelper.hasRecordPermission] must return `true` before
     * calling this. If the permission is absent a [SecurityException] is thrown immediately
     * so the caller can transition the UI to an error state.
     *
     * The flow is cold — a new [AudioRecord] is created per collection. The [AudioRecord]
     * is always released in the `finally` block, even if the collector cancels mid-stream.
     *
     * @throws SecurityException if [android.Manifest.permission.RECORD_AUDIO] is not granted.
     * @throws IllegalStateException if [AudioRecord] fails to initialise.
     */
    // Permission is checked manually via PermissionHelper before AudioRecord is created.
    @SuppressLint("MissingPermission")
    fun startCapture(vadSensitivity: Float = 0f): Flow<AudioChunk> = channelFlow {
        if (!PermissionHelper.hasRecordPermission(context)) {
            throw SecurityException(
                "RECORD_AUDIO permission is not granted. " +
                    "Open the Outspoke app to grant microphone access."
            )
        }

        stopRequested = false   // reset for this capture session

        // Only create a filter when sensitivity > 0; null means pass-through (VAD disabled).
        val vad: VadFilter? = if (vadSensitivity > 0f) VadFilter(vadSensitivity) else null

        val minBufferBytes = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        // Use at least 2× the chunk size so the hardware buffer never overflows.
        val bufferBytes = maxOf(minBufferBytes, CHUNK_SAMPLES * 2 * 2)

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,  // optimised for speech recognition
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferBytes,
        )

        check(recorder.state == AudioRecord.STATE_INITIALIZED) {
            "AudioRecord failed to initialise (state=${recorder.state})"
        }

        val buffer = ShortArray(CHUNK_SAMPLES)

        try {
            recorder.startRecording()
            Log.d(TAG, "AudioRecord started — chunk=$CHUNK_SAMPLES samples, buf=$bufferBytes bytes")

            while (currentCoroutineContext().isActive && !stopRequested) {
                val read = recorder.read(buffer, 0, buffer.size)
                when {
                    read > 0 -> {
                        val chunk = AudioChunk(samples = buffer.copyOf(read))
                        val rms = calculateRms(chunk.samples)
                        _amplitude.value = normaliseAmplitude(rms)

                        val toSend = vad?.process(chunk, rms) ?: listOf(chunk)
                        for (c in toSend) send(c)
                    }
                    read == AudioRecord.ERROR_DEAD_OBJECT -> {
                        // Hardware-level error — the audio subsystem was taken away.
                        Log.e(TAG, "AudioRecord ERROR_DEAD_OBJECT — stopping capture")
                        return@channelFlow
                    }
                    read < 0 -> {
                        // Non-fatal read error — log and skip this chunk.
                        Log.w(TAG, "AudioRecord read returned error code: $read")
                    }
                    // read == 0: no data yet; continue the loop
                }
            }
        } finally {
            // Reset VAD state; hangover frames were already emitted during the read loop.
            vad?.flush()
            recorder.stop()
            recorder.release()
            _amplitude.value = 0f
            Log.d(TAG, "AudioRecord stopped and released")
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Computes the RMS amplitude of [samples] and normalises it to [0.0, 1.0] relative to
     * [Short.MAX_VALUE] (32 767).
     */
    private fun calculateRms(samples: ShortArray): Float {
        if (samples.isEmpty()) return 0f
        val sumOfSquares = samples.fold(0.0) { acc, s -> acc + s.toDouble() * s.toDouble() }
        val rms = sqrt(sumOfSquares / samples.size)
        return (rms / Short.MAX_VALUE.toDouble()).toFloat().coerceIn(0f, 1f)
    }

    /**
     * Applies an adaptive peak-envelope AGC to [raw], returning a value in [0.0, 1.0]
     * where 1.0 means "the loudest sound captured recently".
     *
     * The envelope rises quickly ([AGC_ATTACK]) so a sudden loud sound is tracked within
     * ~280 ms, and decays slowly ([AGC_DECAY]) so a brief silence after speech lets the
     * bars ease back to the floor over ~2 seconds rather than snapping immediately.
     *
     * Keeping [agcEnvelope] across sessions means subsequent short recordings in the
     * same environment benefit from the already-calibrated gain without a warm-up period.
     */
    private fun normaliseAmplitude(raw: Float): Float {
        agcEnvelope = if (raw > agcEnvelope) {
            // Fast attack: blend envelope quickly toward the new peak.
            raw * AGC_ATTACK + agcEnvelope * (1f - AGC_ATTACK)
        } else {
            // Slow decay: let envelope drift back toward the floor.
            (agcEnvelope * AGC_DECAY).coerceAtLeast(AGC_FLOOR)
        }
        return (raw / agcEnvelope).coerceIn(0f, 1f)
    }
}

