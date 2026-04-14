package dev.brgr.outspoke.audio

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tests for [RMSVadFilter] business logic.
 *
 * Uses sensitivity = 0.5 → threshold ≈ 0.01 RMS.
 * ABOVE (0.05) triggers speech; BELOW (0.001) stays silent.
 */
class RMSVadFilterTest {

    private lateinit var vad: RMSVadFilter

    // Threshold at sensitivity=0.5: 0.002 * 25^0.5 = 0.01
    private val above = 0.05f
    private val below = 0.001f
    private val leadInFrames = 15
    private val hangoverFrames = 15

    @Before
    fun setUp() {
        vad = RMSVadFilter(sensitivity = 0.5f)
    }

    private fun chunk(ts: Long) = AudioChunk(ShortArray(640), timestampMs = ts)

    @Test
    fun `given silence, when rms stays below threshold, then empty list is returned`() {
        // Given / When
        val result = vad.process(chunk(0L), below)

        // Then
        assertTrue(result.isEmpty())
    }

    @Test
    fun `given 15 silent chunks in lead-in, when rms crosses threshold twice, then lead-in plus triggers are returned`() {
        // Given - fill lead-in buffer
        val leadChunks = (1..leadInFrames).map { chunk(it.toLong()) }
        leadChunks.forEach { vad.process(it, below) }
        val trigger1 = chunk(100L)
        val trigger2 = chunk(101L)
        // When - need two consecutive above-threshold frames for onset
        vad.process(trigger1, above)
        val result = vad.process(trigger2, above)
        // Then - lead-in buffer (last 14), trigger1, trigger2
        val expected = (leadChunks.drop(1) + listOf(trigger1, trigger2)).takeLast(leadInFrames)
        // Use references to the same chunk objects
        println("expected: $expected\nactual: $result")
        assertEquals(expected, result)
        assertSame(trigger2, result.last())
    }

    @Test
    fun `given more than 15 silent chunks, when speech triggers, then lead-in is capped at 15`() {
        // Given - 20 silent chunks, lead-in ring buffer holds max 15
        val silentChunks = (1..20).map { chunk(it.toLong()) }
        silentChunks.forEach { vad.process(it, below) }
        val trigger1 = chunk(200L)
        val trigger2 = chunk(201L)
        // When - need two consecutive above-threshold frames for onset
        vad.process(trigger1, above)
        val result = vad.process(trigger2, above)
        val expected = (silentChunks.takeLast(leadInFrames - 1) + listOf(trigger1, trigger2)).takeLast(leadInFrames)
        println("expected: $expected\nactual: $result")
        assertEquals(expected, result)
        assertSame(trigger2, result.last())
    }

    @Test
    fun `given active speech, when rms stays above threshold, then each chunk is emitted individually`() {
        // Given - trigger speech (need two above-threshold frames)
        vad.process(chunk(1000L), above)
        vad.process(chunk(1001L), above)
        val speechChunk = chunk(1002L)
        // When
        val result = vad.process(speechChunk, above)
        // Then
        println("expected: ${listOf(speechChunk)}\nactual: $result")
        assertEquals(listOf(speechChunk), result)
        assertSame(speechChunk, result.first())
    }

    @Test
    fun `given active speech, when rms drops, then chunk is still emitted during hangover window`() {
        // Given - trigger speech (need two above-threshold frames)
        vad.process(chunk(1000L), above)
        vad.process(chunk(1001L), above)
        val hangoverChunk = chunk(1002L)
        // When - first sub-threshold frame
        val result = vad.process(hangoverChunk, below)
        // Then - still within hangover window (HANGOVER_FRAMES = 15)
        println("expected: ${listOf(hangoverChunk)}\nactual: $result")
        assertEquals(listOf(hangoverChunk), result)
        assertSame(hangoverChunk, result.first())
    }

    @Test
    fun `given hangover window exhausted, when next chunk arrives, then empty list is returned`() {
        // Given - trigger speech then burn through all 15 hangover frames
        vad.process(chunk(1000L), above)
        vad.process(chunk(1001L), above)
        repeat(hangoverFrames) { vad.process(chunk(2000L + it), below) }
        // When
        val result = vad.process(chunk(3000L), below)
        // Then - back to silence
        assertTrue(result.isEmpty())
    }

    @Test
    fun `given active speech then silence, when hangover resets on speech, then hangover counter resets`() {
        // Given - start speech (need two above-threshold frames)
        vad.process(chunk(1000L), above)
        vad.process(chunk(1001L), above)
        repeat(hangoverFrames / 2) { vad.process(chunk(2000L + it), below) }
        // When - energy spikes again, resetting hangover
        val recoveredChunk = chunk(3000L)
        val recovered = vad.process(recoveredChunk, above)
        // Then - still in speech, chunk emitted
        println("expected: ${listOf(recoveredChunk)}\nactual: $recovered")
        assertEquals(listOf(recoveredChunk), recovered)
        assertSame(recoveredChunk, recovered.first())
    }

    @Test
    fun `given active speech, when flush is called, then returns true and resets to silence`() {
        // Given - trigger speech (need two above-threshold frames)
        vad.process(chunk(1000L), above)
        vad.process(chunk(1001L), above)
        // When
        val wasSpeech = vad.flush()
        // Then
        println("wasSpeech: $wasSpeech")
        assertTrue(wasSpeech)
    }

    @Test
    fun `given silence, when flush is called, then returns false`() {
        // Given - initial silence state

        // When
        val wasSpeech = vad.flush()

        // Then
        assertFalse(wasSpeech)
    }

    @Test
    fun `given flushed filter, when new speech arrives, then onset is detected again`() {
        // Given - trigger speech then flush to reset (need two above-threshold frames)
        vad.process(chunk(1000L), above)
        vad.process(chunk(1001L), above)
        vad.flush()
        val trigger1 = chunk(500L)
        val trigger2 = chunk(501L)
        // When - need two consecutive above-threshold frames for onset
        vad.process(trigger1, above)
        val result = vad.process(trigger2, above)
        // Then - fresh onset detected; at minimum the trigger itself is returned
        println("result: $result")
        assertTrue(result.isNotEmpty())
        assertEquals(trigger2, result.last())
    }
}
