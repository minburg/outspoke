package dev.brgr.outspoke.inference

import android.util.Log
import dev.brgr.outspoke.audio.AudioChunk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

private const val TAG = "InferenceRepository"
private const val SAMPLE_RATE = 16_000


private val LEADING_DOTS_RE  = Regex("""^\.+\s*""")
private val TRAILING_DOTS_RE = Regex("""\.{2,}$""")

/**
 * Removes common model artefacts from a raw transcript:
 *  - Leading dots / ellipsis: `"...Hello"` → `"Hello"`, `". world"` → `"world"`
 *  - Trailing multiple dots: `"Hello..."` → `"Hello."`, `"Hello.."` → `"Hello."`
 *  - Strings that contain no alphanumeric content (e.g. `"..."`, `"."`) are
 *    discarded entirely — a lone period is never a useful transcript.
 */
internal fun String.cleanTranscript(): String {
    if (isBlank()) return ""
    val s = this
        .replace(LEADING_DOTS_RE, "")   // strip leading dots + optional space
        .replace(TRAILING_DOTS_RE, ".") // reduce trailing 2+ dots to a single dot
        .trim()
    // If nothing meaningful remains (no letters or digits), discard the whole string.
    return if (s.none { it.isLetterOrDigit() }) "" else s
}

/** Minimum audio before the first partial inference is emitted (1 s). */
private const val MIN_SAMPLES = SAMPLE_RATE

/** Emit a fresh partial inference every time this many new samples arrive (1 s). */
private const val STRIDE_SAMPLES = SAMPLE_RATE

/** Maximum audio context kept in the rolling window (30 s). */
private const val MAX_WINDOW_SAMPLES = SAMPLE_RATE * 30

/**
 * Bridges the audio capture pipeline to any [SpeechEngine] with a sliding-window strategy.
 *
 * Audio is buffered without backpressure so the AudioRecord thread is never suspended during
 * inference. Partial inferences run in child coroutines on each stride; a definitive final
 * inference is run after the upstream flow completes and all in-flight partials are joined.
 */
class InferenceRepository(private val engine: SpeechEngine) {

    /**
     * Forwards a language tag to the underlying engine.
     * No-op for engines that do not support language selection (Parakeet, Voxtral).
     */
    fun setLanguage(tag: String) = engine.setLanguage(tag)

    /**
     * Forwards a language constraint set to the underlying engine.
     * No-op for engines that do not support language selection (Parakeet, Voxtral).
     */
    fun setLanguageConstraints(tags: List<String>) = engine.setLanguageConstraints(tags)

    fun transcribe(audio: Flow<AudioChunk>): Flow<TranscriptResult> = channelFlow {
        val window      = ArrayDeque<ShortArray>()
        var windowSamples = 0
        var strideAccum   = 0
        val partialJobs = mutableListOf<Job>()

        fun buildChunk(): AudioChunk {
            val merged = ShortArray(windowSamples)
            var pos = 0
            for (arr in window) { arr.copyInto(merged, pos); pos += arr.size }
            return AudioChunk(merged)
        }

        audio.buffer(Channel.UNLIMITED).collect { incoming ->
            window.addLast(incoming.samples)
            windowSamples += incoming.samples.size
            strideAccum   += incoming.samples.size

            while (windowSamples > MAX_WINDOW_SAMPLES) {
                windowSamples -= window.removeFirst().size
            }

            if (windowSamples >= MIN_SAMPLES && strideAccum >= STRIDE_SAMPLES) {
                strideAccum = 0
                val chunk = buildChunk()
                partialJobs += launch {
                    val result = engine.transcribe(chunk)
                    val sec = "%.2f".format(chunk.samples.size / SAMPLE_RATE.toFloat())
                    Log.d(TAG, "Partial inference on ${sec}s: $result")
                    val cleaned = when (result) {
                        is TranscriptResult.Partial -> result.copy(text = result.text.cleanTranscript())
                        is TranscriptResult.Final   -> TranscriptResult.Partial(result.text.cleanTranscript())
                        else -> result
                    }
                    if (cleaned is TranscriptResult.Partial && cleaned.text.isBlank()) return@launch
                    send(cleaned)
                }
            }
        }

        partialJobs.joinAll()

        if (windowSamples > 0) {
            val result = engine.transcribe(buildChunk())
            val sec = "%.2f".format(windowSamples / SAMPLE_RATE.toFloat())
            Log.d(TAG, "Final inference on ${sec}s: $result")
            val cleaned = when (result) {
                is TranscriptResult.Partial -> TranscriptResult.Final(result.text.cleanTranscript())
                is TranscriptResult.Final   -> result.copy(text = result.text.cleanTranscript())
                else -> result
            }
            send(cleaned)
        }
    }.flowOn(Dispatchers.Default)
}
