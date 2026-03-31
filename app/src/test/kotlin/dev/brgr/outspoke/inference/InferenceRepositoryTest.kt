package dev.brgr.outspoke.inference

import dev.brgr.outspoke.audio.AudioChunk
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Tests for [InferenceRepository]'s sliding-window buffering strategy.
 *
 * Constants from production code (package-private):
 *   MIN_SAMPLES    = 32 000  (2 s - minimum context before a partial is emitted)
 *   STRIDE_SAMPLES = 16 000  (1 s - partial emitted every stride once min is met)
 *   MAX_WINDOW     = 480 000 (30 s - oldest audio dropped when exceeded)
 */
class InferenceRepositoryTest {

    private fun fakeEngine(result: TranscriptResult): SpeechEngine = object : SpeechEngine {
        override val isLoaded = true
        override fun load(modelDir: File) = Unit
        override fun transcribe(chunk: AudioChunk) = result
        override fun close() = Unit
    }

    /** 1-second chunk - meets STRIDE_SAMPLES but not MIN_SAMPLES on its own. */
    private fun oneSecondChunk() = AudioChunk(ShortArray(16_000), timestampMs = 0L)

    /** 2-second chunk - exactly meets MIN_SAMPLES (32 000) and STRIDE_SAMPLES (16 000). */
    private fun twoSecondChunk() = AudioChunk(ShortArray(32_000), timestampMs = 0L)

    /** Half-second chunk - below MIN_SAMPLES on its own. */
    private fun halfSecondChunk() = AudioChunk(ShortArray(8_000), timestampMs = 0L)

    @Test
    fun `given no audio chunks, when stream ends, then nothing is emitted`() = runTest {
        // Given
        val repo = InferenceRepository(fakeEngine(TranscriptResult.Final("x")))

        // When
        val results = repo.transcribe(flowOf()).toList()

        // Then
        assertTrue(results.isEmpty())
    }

    @Test
    fun `given less than minimum audio, when stream ends, then only a final is emitted`() = runTest {
        // Given - 0.5 s < MIN_SAMPLES (1 s) so no mid-stream partial fires
        val repo = InferenceRepository(fakeEngine(TranscriptResult.Final("hi")))

        // When
        val results = repo.transcribe(flowOf(halfSecondChunk())).toList()

        // Then - no partial, exactly one final
        assertEquals(1, results.size)
        assertTrue(results[0] is TranscriptResult.Final)
    }

    @Test
    fun `given exactly minimum audio, when stream ends, then a partial and a final are both emitted`() = runTest {
        // Given - 2 s hits both MIN_SAMPLES (32 000) and STRIDE_SAMPLES (16 000) exactly
        val repo = InferenceRepository(fakeEngine(TranscriptResult.Final("hello")))

        // When
        val results = repo.transcribe(flowOf(twoSecondChunk())).toList()

        // Then
        assertEquals(2, results.size)
        assertTrue("First result should be Partial", results[0] is TranscriptResult.Partial)
        assertEquals("Hello", (results[0] as TranscriptResult.Partial).text)
        assertTrue("Second result should be Final", results[1] is TranscriptResult.Final)
        assertEquals("Hello", (results[1] as TranscriptResult.Final).text)
    }

    @Test
    fun `given engine returns Final mid-stream, when collected, then it is downcast to Partial`() = runTest {
        // Given - engine always returns Final; repository must not commit mid-session
        val repo = InferenceRepository(fakeEngine(TranscriptResult.Final("growing text")))
        val chunk = twoSecondChunk()   // 2 s meets MIN_SAMPLES so a mid-stream partial fires

        // When
        val results = repo.transcribe(flowOf(chunk)).toList()

        // Then - first emission is Partial, not Final
        assertTrue(
            "Mid-stream Final should be downcast to Partial but got ${results[0]}",
            results[0] is TranscriptResult.Partial,
        )
    }

    @Test
    fun `given engine returns Partial mid-stream, when stream ends, then end result is promoted to Final`() = runTest {
        // Given - engine always returns Partial
        val repo = InferenceRepository(fakeEngine(TranscriptResult.Partial("growing text")))

        // When - send two strides so the end-of-stream triggers with accumulated audio
        val results = repo.transcribe(flowOf(halfSecondChunk())).toList()

        // Then - the end-of-stream result is promoted to Final
        assertTrue(
            "End-of-stream Partial should be promoted to Final but got ${results.last()}",
            results.last() is TranscriptResult.Final,
        )
    }

    @Test
    fun `given engine returns Failure, when stream ends, then failure is emitted`() = runTest {
        // Given
        val cause = RuntimeException("model error")
        val repo = InferenceRepository(fakeEngine(TranscriptResult.Failure(cause)))

        // When - half-second chunk skips mid-stream partial; only end-of-stream fires
        val results = repo.transcribe(flowOf(halfSecondChunk())).toList()

        // Then
        assertEquals(1, results.size)
        val failure = results[0] as TranscriptResult.Failure
        assertSame(cause, failure.cause)
    }

    @Test
    fun `given more than 30 seconds of audio, when collected, then inference still completes without error`() =
        runTest {
            // Given - 32 chunks × 1 s = 32 s, exceeds MAX_WINDOW_SAMPLES (30 s)
            val repo = InferenceRepository(fakeEngine(TranscriptResult.Final("long dictation")))
            val chunks = List(32) { oneSecondChunk() }

            // When - should not throw; old audio is silently dropped
            val results = repo.transcribe(chunks.asFlow()).toList()

            // Then - at least one final result is emitted at stream end
            assertTrue(results.last() is TranscriptResult.Final)
        }
}



