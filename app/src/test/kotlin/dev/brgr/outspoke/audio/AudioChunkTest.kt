package dev.brgr.outspoke.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Tests for [AudioChunk]'s structural equals/hashCode implementation.
 * ShortArray does not provide structural equality by default, so the overrides matter.
 */
class AudioChunkTest {

    @Test
    fun `given two chunks with identical samples, when compared, then they are equal`() {
        // Given
        val a = AudioChunk(samples = shortArrayOf(1, 2, 3), sampleRate = 16_000, timestampMs = 0L)
        val b = AudioChunk(samples = shortArrayOf(1, 2, 3), sampleRate = 16_000, timestampMs = 0L)

        // When / Then
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `given two chunks with different samples, when compared, then they are not equal`() {
        // Given
        val a = AudioChunk(samples = shortArrayOf(1, 2, 3), sampleRate = 16_000, timestampMs = 0L)
        val b = AudioChunk(samples = shortArrayOf(4, 5, 6), sampleRate = 16_000, timestampMs = 0L)

        // When / Then
        assertNotEquals(a, b)
    }

    @Test
    fun `given two chunks with different sample rates, when compared, then they are not equal`() {
        // Given
        val samples = shortArrayOf(1, 2, 3)
        val a = AudioChunk(samples = samples.copyOf(), sampleRate = 16_000, timestampMs = 0L)
        val b = AudioChunk(samples = samples.copyOf(), sampleRate = 8_000,  timestampMs = 0L)

        // When / Then
        assertNotEquals(a, b)
    }

    @Test
    fun `given two chunks with different timestamps, when compared, then they are not equal`() {
        // Given
        val a = AudioChunk(samples = shortArrayOf(1), sampleRate = 16_000, timestampMs = 100L)
        val b = AudioChunk(samples = shortArrayOf(1), sampleRate = 16_000, timestampMs = 200L)

        // When / Then
        assertNotEquals(a, b)
    }

    @Test
    fun `given a chunk compared to itself, when compared, then it is equal`() {
        // Given
        val chunk = AudioChunk(samples = shortArrayOf(1, 2), sampleRate = 16_000, timestampMs = 0L)

        // When / Then
        assertEquals(chunk, chunk)
    }

    @Test
    fun `given default sample rate, when chunk is created, then sample rate is 16000`() {
        // Given / When
        val chunk = AudioChunk(samples = ShortArray(640))

        // Then
        assertEquals(16_000, chunk.sampleRate)
    }
}

