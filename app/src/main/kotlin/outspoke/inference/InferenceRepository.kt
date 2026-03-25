package dev.brgr.outspoke.inference

import android.util.Log
import dev.brgr.outspoke.audio.AudioChunk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

private const val TAG = "InferenceRepository"
private const val SAMPLE_RATE = 16_000

/** Minimum audio before the first partial inference is emitted (1 s). */
private const val MIN_SAMPLES = SAMPLE_RATE

/** Emit a fresh partial inference every time this many new samples arrive (1 s). */
private const val STRIDE_SAMPLES = SAMPLE_RATE

/** Maximum audio context kept in the rolling window (30 s). */
private const val MAX_WINDOW_SAMPLES = SAMPLE_RATE * 30

/**
 * Bridges the audio capture pipeline to any [SpeechEngine] with a sliding-window
 * buffering strategy.
 *
 * Key design decisions:
 *
 * **No backpressure on the audio producer.**
 * The upstream audio flow is buffered with [Channel.UNLIMITED] so the AudioRecord
 * hardware thread is *never* suspended while inference is running.  Without this,
 * the hardware AudioRecord buffer (≈ 120 ms) overflows within one inference pass and
 * audio is silently dropped.
 *
 * **Non-blocking inference.**
 * Stride-based partial inferences are launched in a child coroutine so the
 * `audio.collect` loop continues uninterrupted.  The engine's internal `tryLock`
 * ensures only one inference runs at a time — any stride that fires while a previous
 * inference is in flight returns [TranscriptResult.Partial] with empty text and is
 * silently discarded.
 *
 * **Definitive final inference.**
 * When the upstream flow completes (user releases the record button), any in-flight
 * partial inference is joined, then a single final inference is run on the complete
 * accumulated window and emitted as [TranscriptResult.Final].
 */
class InferenceRepository(private val engine: SpeechEngine) {

    fun transcribe(audio: Flow<AudioChunk>): Flow<TranscriptResult> = channelFlow {
        val window      = ArrayDeque<ShortArray>()
        var windowSamples = 0
        var strideAccum   = 0
        // All launched stride coroutines — most return instantly (tryLock miss), but
        // the one that actually acquired the lock runs for the full inference duration.
        // We must join ALL of them so the engine lock is free before the final pass.
        val partialJobs = mutableListOf<Job>()

        fun buildChunk(): AudioChunk {
            val merged = ShortArray(windowSamples)
            var pos = 0
            for (arr in window) { arr.copyInto(merged, pos); pos += arr.size }
            return AudioChunk(merged)
        }

        // Buffer without backpressure so the AudioRecord hardware thread is NEVER
        // suspended regardless of how long inference takes.  Each chunk is 1 280 B;
        // a full 30 s recording is 750 chunks ≈ 960 KB — well within budget.
        audio.buffer(Channel.UNLIMITED).collect { incoming ->
            window.addLast(incoming.samples)
            windowSamples += incoming.samples.size
            strideAccum   += incoming.samples.size

            // Slide window — drop oldest chunks when the cap is exceeded.
            while (windowSamples > MAX_WINDOW_SAMPLES) {
                windowSamples -= window.removeFirst().size
            }

            // Fire partial inference every stride once minimum context is available.
            // Inference runs in a child coroutine so this collect loop is never blocked.
            // If inference is already running, the engine's tryLock returns Partial("")
            // immediately; we discard that and let the in-flight inference finish.
            if (windowSamples >= MIN_SAMPLES && strideAccum >= STRIDE_SAMPLES) {
                strideAccum = 0
                val chunk = buildChunk()
                partialJobs += launch {
                    val result = engine.transcribe(chunk)
                    val sec = "%.2f".format(chunk.samples.size / SAMPLE_RATE.toFloat())
                    Log.d(TAG, "Partial inference on ${sec}s: $result")
                    if (result is TranscriptResult.Partial && result.text.isBlank()) return@launch
                    send(when (result) {
                        is TranscriptResult.Final -> TranscriptResult.Partial(result.text)
                        else -> result
                    })
                }
            }
        }

        // Flow completed — user released the record button.
        // Join ALL partial jobs so the engine lock is free before the final pass.
        // Most return instantly (tryLock miss); the one that acquired the lock may
        // still be running — without joining it, the final inference would hit a
        // held lock, return Partial(""), and commit empty text to the input field.
        partialJobs.joinAll()

        if (windowSamples > 0) {
            val result = engine.transcribe(buildChunk())
            val sec = "%.2f".format(windowSamples / SAMPLE_RATE.toFloat())
            Log.d(TAG, "Final inference on ${sec}s: $result")
            send(when (result) {
                is TranscriptResult.Partial -> TranscriptResult.Final(result.text)
                else -> result
            })
        }
    }.flowOn(Dispatchers.Default)
}
