package dev.brgr.outspoke.audio

import kotlin.math.exp
import kotlin.math.ln

/**
 * A stateful energy-threshold VAD filter.
 *
 * Call [process] once per [AudioChunk] in capture order. The method returns a
 * (possibly empty) list of chunks to forward to inference:
 *   - During SILENCE: returns an empty list.
 *   - On speech onset: returns the lead-in buffer + the triggering chunk.
 *   - During SPEECH: returns a single-element list containing the chunk.
 *   - During hangover: continues returning chunks until silence is confirmed.
 *   - After hangover expires: returns empty lists again.
 *
 * Call [flush] when capture ends to reset internal state.
 *
 * @param sensitivity  [0.0, 1.0] — higher values require louder speech to pass.
 */
class VadFilter(sensitivity: Float = 0.5f) {

    // Map sensitivity to RMS threshold via exponential curve.
    // 0.0 → ~0.002, 0.5 → ~0.010, 1.0 → ~0.050
    private val threshold: Float =
        (0.002f * exp(ln(25f) * sensitivity.coerceIn(0f, 1f)))

    private enum class State { SILENCE, SPEECH }

    private var state = State.SILENCE
    private var hangoverFrames = 0

    /** Ring buffer of up to LEAD_IN_FRAMES recent chunks, prepended on speech onset. */
    private val leadIn = ArrayDeque<AudioChunk>(LEAD_IN_FRAMES)

    fun process(chunk: AudioChunk, rms: Float): List<AudioChunk> {
        return when (state) {
            State.SILENCE -> {
                // Always maintain the lead-in ring buffer during silence.
                if (leadIn.size >= LEAD_IN_FRAMES) leadIn.removeFirst()
                leadIn.addLast(chunk)

                if (rms >= threshold) {
                    // Speech onset — emit lead-in + triggering frame.
                    state = State.SPEECH
                    hangoverFrames = 0
                    val out = leadIn.toList()
                    leadIn.clear()
                    out
                } else {
                    emptyList()
                }
            }

            State.SPEECH -> {
                if (rms >= threshold) {
                    // Still active speech — reset hangover and emit.
                    hangoverFrames = 0
                    listOf(chunk)
                } else {
                    // Energy dropped — start/continue hangover.
                    hangoverFrames++
                    if (hangoverFrames >= HANGOVER_FRAMES) {
                        state = State.SILENCE
                        hangoverFrames = 0
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
     * Resets internal state. Returns `true` if the session ended while speech was active
     * (i.e. the user stopped recording mid-word).
     */
    fun flush(): Boolean {
        val wasSpeech = state == State.SPEECH
        state = State.SILENCE
        hangoverFrames = 0
        leadIn.clear()
        return wasSpeech
    }

    companion object {
        /** Frames of audio prepended before speech onset to avoid clipping. */
        private const val LEAD_IN_FRAMES  = 3   // 3 × 40 ms = 120 ms

        /** Frames of sub-threshold audio tolerated before declaring silence. */
        private const val HANGOVER_FRAMES = 8   // 8 × 40 ms = 320 ms
    }
}

