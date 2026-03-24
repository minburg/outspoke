package dev.brgr.outspoke.inference

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for [TranscriptResult] sealed class variants.
 */
class TranscriptResultTest {

    @Test
    fun `given partial result, when text is accessed, then it is preserved`() {
        // Given / When
        val result = TranscriptResult.Partial("hello world")

        // Then
        assertEquals("hello world", result.text)
    }

    @Test
    fun `given final result, when text is accessed, then it is preserved`() {
        // Given / When
        val result = TranscriptResult.Final("confirmed text")

        // Then
        assertEquals("confirmed text", result.text)
    }

    @Test
    fun `given failure result, when cause is accessed, then the original exception is preserved`() {
        // Given
        val cause = RuntimeException("inference failed")

        // When
        val result = TranscriptResult.Failure(cause)

        // Then
        assertSame(cause, result.cause)
    }

    @Test
    fun `given partial and final with the same text, when compared, then they are not equal`() {
        // Given
        val partial = TranscriptResult.Partial("hello")
        val final   = TranscriptResult.Final("hello")

        // When / Then
        assertNotEquals(partial, final)
    }

    @Test
    fun `given two partials with the same text, when compared, then they are equal`() {
        // Given
        val a = TranscriptResult.Partial("hello")
        val b = TranscriptResult.Partial("hello")

        // When / Then
        assertEquals(a, b)
    }

    @Test
    fun `given two finals with different text, when compared, then they are not equal`() {
        // Given
        val a = TranscriptResult.Final("foo")
        val b = TranscriptResult.Final("bar")

        // When / Then
        assertNotEquals(a, b)
    }
}

