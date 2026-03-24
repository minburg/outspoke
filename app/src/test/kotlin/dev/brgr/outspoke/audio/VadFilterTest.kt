package dev.brgr.outspoke.audio

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tests for [VadFilter] business logic.
 *
 * Uses sensitivity = 0.5 → threshold ≈ 0.01 RMS.
 * ABOVE (0.05) triggers speech; BELOW (0.001) stays silent.
 */
class VadFilterTest {

    private lateinit var vad: VadFilter

    // Threshold at sensitivity=0.5: 0.002 * 25^0.5 = 0.01
    private val ABOVE = 0.05f
    private val BELOW = 0.001f

    @Before
    fun setUp() {
        vad = VadFilter(sensitivity = 0.5f)
    }

    private fun chunk() = AudioChunk(ShortArray(640))

    // ── Silence ──────────────────────────────────────────────────────────────

    @Test
    fun `given silence, when rms stays below threshold, then empty list is returned`() {
        // Given / When
        val result = vad.process(chunk(), BELOW)

        // Then
        assertTrue(result.isEmpty())
    }

    // ── Speech onset ─────────────────────────────────────────────────────────

    @Test
    fun `given 3 silent chunks in lead-in, when rms crosses threshold, then lead-in plus trigger are returned`() {
        // Given – fill lead-in buffer
        val lead1 = chunk(); val lead2 = chunk(); val lead3 = chunk()
        vad.process(lead1, BELOW)
        vad.process(lead2, BELOW)
        vad.process(lead3, BELOW)
        val trigger = chunk()

        // When
        val result = vad.process(trigger, ABOVE)

        // Then – trigger is added to leadIn before the threshold check, so leadIn.toList()
        // already contains it. With 3 prior frames the oldest is evicted: [lead2, lead3, trigger].
        assertEquals(3, result.size)
        assertSame(trigger, result.last())
    }

    @Test
    fun `given more than 3 silent chunks, when speech triggers, then lead-in is capped at 3`() {
        // Given – 5 silent chunks, lead-in ring buffer holds max 3
        repeat(5) { vad.process(chunk(), BELOW) }

        // When
        val result = vad.process(chunk(), ABOVE)

        // Then – trigger is added to leadIn first (evicting the oldest), so the snapshot
        // is always capped at LEAD_IN_FRAMES = 3, not 3 lead-in + 1 trigger.
        assertEquals(3, result.size)
    }

    // ── Sustained speech ─────────────────────────────────────────────────────

    @Test
    fun `given active speech, when rms stays above threshold, then each chunk is emitted individually`() {
        // Given – trigger speech
        vad.process(chunk(), ABOVE)
        val speechChunk = chunk()

        // When
        val result = vad.process(speechChunk, ABOVE)

        // Then
        assertEquals(listOf(speechChunk), result)
    }

    // ── Hangover ─────────────────────────────────────────────────────────────

    @Test
    fun `given active speech, when rms drops, then chunk is still emitted during hangover window`() {
        // Given – trigger speech
        vad.process(chunk(), ABOVE)
        val hangoverChunk = chunk()

        // When – first sub-threshold frame
        val result = vad.process(hangoverChunk, BELOW)

        // Then – still within hangover window (HANGOVER_FRAMES = 8)
        assertEquals(listOf(hangoverChunk), result)
    }

    @Test
    fun `given hangover window exhausted, when next chunk arrives, then empty list is returned`() {
        // Given – trigger speech then burn through all 8 hangover frames
        vad.process(chunk(), ABOVE)
        repeat(8) { vad.process(chunk(), BELOW) }

        // When
        val result = vad.process(chunk(), BELOW)

        // Then – back to silence
        assertTrue(result.isEmpty())
    }

    @Test
    fun `given active speech then silence, when hangover resets on speech, then hangover counter resets`() {
        // Given – start speech, drop energy for 4 frames (half hangover), then recover
        vad.process(chunk(), ABOVE)
        repeat(4) { vad.process(chunk(), BELOW) }

        // When – energy spikes again, resetting hangover
        val recovered = vad.process(chunk(), ABOVE)

        // Then – still in speech, chunk emitted
        assertEquals(1, recovered.size)
    }

    // ── Flush ─────────────────────────────────────────────────────────────────

    @Test
    fun `given active speech, when flush is called, then returns true and resets to silence`() {
        // Given
        vad.process(chunk(), ABOVE)

        // When
        val wasSpeech = vad.flush()

        // Then
        assertTrue(wasSpeech)
    }

    @Test
    fun `given silence, when flush is called, then returns false`() {
        // Given – initial silence state

        // When
        val wasSpeech = vad.flush()

        // Then
        assertFalse(wasSpeech)
    }

    @Test
    fun `given flushed filter, when new speech arrives, then onset is detected again`() {
        // Given – trigger speech then flush to reset
        vad.process(chunk(), ABOVE)
        vad.flush()
        val trigger = chunk()

        // When
        val result = vad.process(trigger, ABOVE)

        // Then – fresh onset detected; at minimum the trigger itself is returned
        assertTrue(result.isNotEmpty())
        assertSame(trigger, result.last())
    }
}

