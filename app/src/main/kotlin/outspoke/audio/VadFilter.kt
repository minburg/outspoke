package dev.brgr.outspoke.audio

import kotlin.math.exp
import kotlin.math.ln

/**
 * A stateful energy-threshold VAD filter with onset gate, pre-roll buffer, and hangover.
 *
 * Call [process] once per [AudioChunk] in capture order. The method returns a
 * (possibly empty) list of chunks to forward to inference:
 *   - During SILENCE: returns an empty list.
 *   - On speech onset (after gate confirms): returns the pre-roll buffer + triggering chunk.
 *   - During SPEECH: returns a single-element list containing the chunk.
 *   - During hangover: continues returning chunks until silence is confirmed.
 *   - After hangover expires: returns empty lists again.
 *
 * Call [flush] when capture ends to reset internal state.
 *
 * ### Three smoothing layers (reference: Handy pipeline SmoothedVad)
 *
 * **Layer A — Onset Gate ([ONSET_FRAMES] = 2 frames / 60 ms)**
 * The energy must exceed [threshold] for 2 consecutive frames before the pipeline
 * transitions to IN_SPEECH. Suppresses single-frame activations from plosive pops,
 * finger taps, and brief noise events.
 *
 * **Layer B — Pre-roll buffer ([LEAD_IN_FRAMES] = 15 frames / 450 ms)**
 * A ring buffer of the last 15+1 frames is always maintained during silence. When the
 * onset gate fires, the entire buffer (including the onset gate frames themselves) is
 * emitted before the triggering frame. This captures the first consonant/phoneme of the
 * word that triggered the gate — the word would otherwise be truncated.
 *
 * **Layer C — Hangover ([HANGOVER_FRAMES] = 15 frames / 450 ms)**
 * After energy first drops below threshold, the filter continues emitting frames as SPEECH
 * for 15 more frames (450 ms). Captures trailing soft syllables (-ing, -ed, -s) and brief
 * inter-word pauses that momentarily dip below the threshold.
 *
 * @param sensitivity  [0.0, 1.0] - higher values require louder speech to pass.
 */
class VadFilter(sensitivity: Float = 0.5f) : IVadFilter {

    // Map sensitivity to RMS threshold via exponential curve.
    // 0.0 → ~0.002, 0.5 → ~0.010, 1.0 → ~0.050
    private val threshold: Float =
        (0.002f * exp(ln(25f) * sensitivity.coerceIn(0f, 1f)))

    private enum class State { SILENCE, SPEECH }

    private var state = State.SILENCE

    /** Consecutive above-threshold frames seen while in SILENCE (onset gate counter). */
    private var onsetCount = 0

    /** Frames of sub-threshold audio tolerated since energy dropped (hangover counter). */
    private var hangoverFrames = 0

    /**
     * Ring buffer of up to [LEAD_IN_FRAMES] recent chunks, always maintained during silence.
     * Flushed to inference on speech onset so the very first phoneme is never clipped.
     */
    private val leadIn = ArrayDeque<AudioChunk>(LEAD_IN_FRAMES + 1)

    /** `true` while outputting speech frames (including during the hangover window). */
    override val isSpeechActive: Boolean get() = state == State.SPEECH

    override fun process(chunk: AudioChunk, rms: Float): List<AudioChunk> {
        return when (state) {
            State.SILENCE -> {
                // Always maintain the pre-roll ring buffer during silence so that
                // onset-gate frames are included in the emitted pre-roll.
                if (leadIn.size >= LEAD_IN_FRAMES) leadIn.removeFirst()
                leadIn.addLast(chunk)

                if (rms >= threshold) {
                    onsetCount++
                    if (onsetCount >= ONSET_FRAMES) {
                        // Onset gate confirmed — emit pre-roll buffer + triggering frame.
                        state = State.SPEECH
                        onsetCount = 0
                        hangoverFrames = 0
                        val out = leadIn.toList()
                        leadIn.clear()
                        out
                    } else {
                        // Gate not yet confirmed — chunk already in ring buffer; emit nothing.
                        emptyList()
                    }
                } else {
                    // Sub-threshold — reset onset counter, keep buffering.
                    onsetCount = 0
                    emptyList()
                }
            }

            State.SPEECH -> {
                if (rms >= threshold) {
                    // Active speech — reset hangover counter and emit.
                    hangoverFrames = 0
                    listOf(chunk)
                } else {
                    // Energy dropped — start/continue hangover.
                    hangoverFrames++
                    if (hangoverFrames >= HANGOVER_FRAMES) {
                        // Hangover expired — transition to silence.
                        state = State.SILENCE
                        hangoverFrames = 0
                        onsetCount = 0
                        leadIn.clear()
                        emptyList()
                    } else {
                        // Still within hangover window — keep emitting.
                        listOf(chunk)
                    }
                }
            }
        }
    }

    /**
     * Resets all internal state. Should be called when a recording session ends.
     * Returns `true` if the session ended while speech was still active (hangover or
     * confirmed speech), meaning trailing audio may have been cut off mid-word.
     */
    override fun flush(): Boolean {
        val wasSpeech = state == State.SPEECH
        state = State.SILENCE
        hangoverFrames = 0
        onsetCount = 0
        leadIn.clear()
        return wasSpeech
    }

    companion object {
        /**
         * Number of consecutive above-threshold frames required before speech onset is
         * confirmed. 2 frames × 30 ms = 60 ms — eliminates single-frame glitch activations.
         */
        private const val ONSET_FRAMES = 2

        /**
         * Frames of audio prepended before confirmed speech onset (pre-roll buffer).
         * 15 frames × 30 ms = 450 ms — captures onset phonemes that preceded the gate.
         */
        private const val LEAD_IN_FRAMES = 15

        /**
         * Frames of sub-threshold audio tolerated before declaring silence (hangover).
         * 15 frames × 30 ms = 450 ms — captures trailing soft syllables and brief pauses.
         */
        private const val HANGOVER_FRAMES = 15
    }
}
