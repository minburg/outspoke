package dev.brgr.outspoke.inference

import android.util.Log
import dev.brgr.outspoke.audio.AudioChunk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

private const val TAG = "InferenceRepository"
private const val SAMPLE_RATE = 16_000

/** Minimum audio before the first partial inference is emitted (1 s). */
private const val MIN_SAMPLES = SAMPLE_RATE              // 16 000

/** Emit a fresh partial inference every time this many new samples arrive (1 s).
 *  Keeping the stride at 1 s rather than 500 ms halves the re-encoding work as the
 *  window grows — the encoder must re-process the full accumulated buffer on every
 *  partial, so a longer stride noticeably reduces CPU/thermal pressure for longer
 *  recordings without meaningfully hurting perceived latency. */
private const val STRIDE_SAMPLES = SAMPLE_RATE           // 16 000

/** Maximum audio context kept in the rolling window (30 s).
 *  8 s was too tight for natural dictation; 30 s covers virtually any single utterance
 *  while staying within manageable encoder memory. */
private const val MAX_WINDOW_SAMPLES = SAMPLE_RATE * 30  // 480 000

/**
 * Bridges the audio capture pipeline to the [ParakeetEngine] with a sliding-window
 * buffering strategy:
 *
 *  - Raw 40 ms chunks from [AudioCaptureManager] are accumulated in a rolling window.
 *  - A [TranscriptResult.Partial] is emitted every [STRIDE_SAMPLES] once at least
 *    [MIN_SAMPLES] of audio are buffered, giving progressive UI feedback.
 *  - When the upstream flow completes normally (i.e. the user releases the record button
 *    via [AudioCaptureManager.stopCapture]), a final inference is run on the complete
 *    buffer and a [TranscriptResult.Final] is emitted to commit the text.
 *  - The window is capped at [MAX_WINDOW_SAMPLES] to bound memory; older audio is
 *    dropped from the front when the cap is exceeded.
 */
class InferenceRepository(private val engine: SpeechEngine) {

    fun transcribe(audio: Flow<AudioChunk>): Flow<TranscriptResult> = flow {
        // Rolling window stored as a deque of ShortArrays to avoid O(n) prepend on slide.
        val window = ArrayDeque<ShortArray>()
        var windowSamples = 0
        var strideAccum   = 0

        /** Merge all buffered chunks into a single AudioChunk for the engine. */
        fun buildChunk(): AudioChunk {
            val merged = ShortArray(windowSamples)
            var pos = 0
            for (arr in window) { arr.copyInto(merged, pos); pos += arr.size }
            return AudioChunk(merged)
        }

        audio.collect { incoming ->
            window.addLast(incoming.samples)
            windowSamples += incoming.samples.size
            strideAccum   += incoming.samples.size

            // Slide window — drop oldest chunks when the cap is exceeded.
            while (windowSamples > MAX_WINDOW_SAMPLES) {
                windowSamples -= window.removeFirst().size
            }

            // Emit a partial result every STRIDE_SAMPLES once we have minimum context.
            if (windowSamples >= MIN_SAMPLES && strideAccum >= STRIDE_SAMPLES) {
                strideAccum = 0
                val result = engine.transcribe(buildChunk())
                Log.d(TAG, "Partial inference on ${"%.2f".format(windowSamples / SAMPLE_RATE.toFloat())}s: $result")
                // Always emit as Partial during active recording — the engine returns Final
                // for any non-blank result, but committing mid-session would inject the
                // growing window text repeatedly and trigger "character limit reached" toasts.
                // Only the end-of-stream result (below) is promoted to Final.
                emit(
                    when (result) {
                        is TranscriptResult.Final -> TranscriptResult.Partial(result.text)
                        else -> result
                    }
                )
            }
        }

        // Flow completed naturally (stopCapture() was called) — run final inference on
        // the full accumulated buffer and always emit Final so the ViewModel commits.
        if (windowSamples > 0) {
            val result = engine.transcribe(buildChunk())
            Log.d(TAG, "Final inference on ${"%.2f".format(windowSamples / SAMPLE_RATE.toFloat())}s: $result")
            emit(
                when (result) {
                    is TranscriptResult.Partial -> TranscriptResult.Final(result.text)
                    else                        -> result
                }
            )
        }
    }.flowOn(Dispatchers.Default)
}
