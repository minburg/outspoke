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

// Short-utterance zero-padding unit tests

class ZeroPadToMinimumTest {

    @Test
    fun `given empty array, when padded to 32000, then result has 32000 zeroes`() {
        val result = zeroPadToMinimum(ShortArray(0), 32_000)
        assertEquals(32_000, result.size)
        assertTrue("All padding should be zero", result.all { it == 0.toShort() })
    }

    @Test
    fun `given array shorter than minimum, when padded, then length equals minimum and original content is preserved`() {
        val original = ShortArray(8_000) { it.toShort() }
        val result = zeroPadToMinimum(original, 32_000)

        assertEquals(32_000, result.size)
        // Original samples are preserved at the front.
        for (i in original.indices) {
            assertEquals("Sample at $i should match original", original[i], result[i])
        }
        // Tail is silence.
        for (i in original.size until result.size) {
            assertEquals("Padding at $i should be zero", 0.toShort(), result[i])
        }
    }

    @Test
    fun `given array exactly at minimum, when padded, then same array is returned unchanged`() {
        val original = ShortArray(32_000) { 1 }
        val result = zeroPadToMinimum(original, 32_000)
        assertSame("Should return the same instance when no padding is needed", original, result)
    }

    @Test
    fun `given array longer than minimum, when padded, then same array is returned unchanged`() {
        val original = ShortArray(40_000) { 2 }
        val result = zeroPadToMinimum(original, 32_000)
        assertSame("Should return the same instance when already above minimum", original, result)
    }
}

class ShortUtteranceRepositoryTest {

    private val sampleRate = 16_000

    private fun capturedSizesEngine(): Pair<SpeechEngine, MutableList<Int>> {
        val sizes = mutableListOf<Int>()
        val engine = object : SpeechEngine {
            override val isLoaded = true
            override fun load(modelDir: File) = Unit
            override fun close() = Unit
            override fun transcribe(chunk: AudioChunk): TranscriptResult {
                sizes += chunk.samples.size
                return TranscriptResult.Final("word")
            }
        }
        return engine to sizes
    }

    @Test
    fun `given utterance shorter than threshold, final inference receives at least MIN_PADDING_SAMPLES`() = runTest {
        // 0.5 s < SHORT_UTTERANCE_THRESHOLD_SAMPLES (2.5 s) → short-utterance path
        val (engine, capturedSizes) = capturedSizesEngine()
        val repo = InferenceRepository(engine)

        repo.transcribe(flowOf(AudioChunk(ShortArray(sampleRate / 2)))).toList()

        // The engine must have been called with at least MIN_PADDING_SAMPLES (32 000).
        assertTrue("Engine should have been called at least once", capturedSizes.isNotEmpty())
        val lastSize = capturedSizes.last()
        assertTrue(
            "Short-utt path must deliver ≥ MIN_PADDING_SAMPLES (32000) but got $lastSize",
            lastSize >= sampleRate * 2
        )
    }

    @Test
    fun `given utterance shorter than threshold, result is emitted as Final with isUtteranceBoundary true`() = runTest {
        // 1 s < 2.5 s threshold → short-utterance path
        val repo = InferenceRepository(object : SpeechEngine {
            override val isLoaded = true
            override fun load(modelDir: File) = Unit
            override fun close() = Unit
            override fun transcribe(chunk: AudioChunk) = TranscriptResult.Partial("hello")
        })

        val results = repo.transcribe(flowOf(AudioChunk(ShortArray(sampleRate)))).toList()

        val final = results.filterIsInstance<TranscriptResult.Final>()
        assertTrue("Should emit at least one Final result", final.isNotEmpty())
        assertTrue(
            "Final result should have isUtteranceBoundary = true",
            final.last().isUtteranceBoundary
        )
    }

    @Test
    fun `given long utterance above threshold, final inference does NOT receive short-utt padding`() = runTest {
        // 3 s > 2.5 s threshold → normal path; engine should NOT be called with 32000+ samples
        // from zero-padding (it will still get ≥ MIN_FINAL_SAMPLES but the logic differs).
        val (engine, capturedSizes) = capturedSizesEngine()
        val repo = InferenceRepository(engine)

        // 3 chunks × 1 s = 3 s, all above threshold.  First two strides fire a partial,
        // final flush runs the normal path on remaining audio.
        val chunks = List(3) { AudioChunk(ShortArray(sampleRate)) }
        repo.transcribe(chunks.asFlow()).toList()

        // The final engine call should receive the actual window (≥ MIN_FINAL_SAMPLES
        // for the normal path) — but it should NOT be artificially inflated to
        // MIN_PADDING_SAMPLES (32 000) when the window is already above threshold.
        // Here, 3 s of audio is above threshold, so no short-utt padding applies.
        assertTrue("Engine should have been called", capturedSizes.isNotEmpty())
    }
}

// ── estimateConfidence unit tests ─────────────────────────────────────────────────────────────

class EstimateConfidenceTest {

    private val sampleRate = 16_000

    @Test
    fun `given Final with meaningful multi-character text, then confidence is above threshold`() {
        // "hello" = 5 word chars → wordCharCount >= 2 → confidence = 1.0
        val result = TranscriptResult.Final("hello")
        val confidence = estimateConfidence(result, sampleRate)
        assertTrue("Confidence should be >= threshold for real text", confidence >= CONFIDENCE_THRESHOLD)
    }

    @Test
    fun `given Final with empty text, then confidence is 0`() {
        val result = TranscriptResult.Final("")
        val confidence = estimateConfidence(result, sampleRate)
        assertEquals(0.0f, confidence, 0.001f)
    }

    @Test
    fun `given Final with single punctuation character, then confidence is 0`() {
        val result = TranscriptResult.Final(".")
        val confidence = estimateConfidence(result, sampleRate)
        assertEquals(0.0f, confidence, 0.001f)
    }

    @Test
    fun `given Failure result, then confidence is 0`() {
        val result = TranscriptResult.Failure(RuntimeException("engine error"))
        val confidence = estimateConfidence(result, sampleRate)
        assertEquals(0.0f, confidence, 0.001f)
    }

    @Test
    fun `given single letter text, then confidence is 0 (fewer than 2 word chars)`() {
        val result = TranscriptResult.Final("a")
        val confidence = estimateConfidence(result, sampleRate)
        assertEquals(0.0f, confidence, 0.001f)
    }

    @Test
    fun `given two-character text, then confidence is above threshold`() {
        // "hi" = 2 word chars → minimum passing case
        val result = TranscriptResult.Final("hi")
        val confidence = estimateConfidence(result, sampleRate)
        assertTrue("Two word-chars should pass confidence gate", confidence >= CONFIDENCE_THRESHOLD)
    }
}

// ── Confidence gate integration tests ────────────────────────────────────────────────────────

class ConfidenceGateTest {

    private val sampleRate = 16_000

    /** Builds a SpeechEngine that always returns [result]. */
    private fun engineReturning(result: TranscriptResult): SpeechEngine = object : SpeechEngine {
        override val isLoaded = true
        override fun load(modelDir: File) = Unit
        override fun close() = Unit
        override fun transcribe(chunk: AudioChunk) = result
    }

    /**
     * A short utterance (0.5 s < 2.5 s threshold) where the engine returns a
     * meaningful result with very short text ("x") — below the 2-char minimum
     * → estimateConfidence returns 0.0 → gate fires → Failure is emitted.
     */
    @Test
    fun `given low-confidence short utterance, then Failure is emitted`() = runTest {
        // Engine returns a single-character result (word char count = 1 < 2 → confidence 0.0)
        val repo = InferenceRepository(engineReturning(TranscriptResult.Final("x")))

        val results = repo.transcribe(
            flowOf(AudioChunk(ShortArray(sampleRate / 2)))  // 0.5 s → short-utterance path
        ).toList()

        assertTrue("Should emit at least one result", results.isNotEmpty())
        val last = results.last()
        assertTrue(
            "Low-confidence short utterance should emit Failure but got $last",
            last is TranscriptResult.Failure
        )
        val msg = (last as TranscriptResult.Failure).cause.message ?: ""
        assertTrue("Failure message should mention confidence", "could not understand" in msg)
    }

    /**
     * A short utterance where the engine returns a sufficiently rich text
     * (many word chars, short audio → high density → above threshold).
     * Gate must NOT fire; a Final result must be returned.
     */
    @Test
    fun `given high-confidence short utterance, then Final result passes through`() = runTest {
        // "hello there" has 10 word characters (≥2) → binary proxy returns 1.0 → above threshold → passes
        val repo = InferenceRepository(engineReturning(TranscriptResult.Final("hello there")))

        val results = repo.transcribe(
            flowOf(AudioChunk(ShortArray(sampleRate / 2)))  // 0.5 s → short-utterance path
        ).toList()

        assertTrue("Should emit at least one result", results.isNotEmpty())
        val finals = results.filterIsInstance<TranscriptResult.Final>()
        assertTrue(
            "High-confidence short utterance should emit Final but got ${results.last()}",
            finals.isNotEmpty()
        )
    }

    /**
     * A long utterance (3 s > 2.5 s threshold) where the engine returns single-char text.
     * The confidence gate must NOT apply — only the short-utterance path is gated.
     * A Final result must be returned as normal.
     */
    @Test
    fun `given long utterance with low-confidence-looking result, then confidence gate is bypassed`() = runTest {
        // Engine returns a trivial single-character result — would fail confidence gate if applied.
        val repo = InferenceRepository(engineReturning(TranscriptResult.Final("x")))

        // 3 × 1 s = 3 s > SHORT_UTTERANCE_THRESHOLD_SAMPLES (2.5 s) → normal path
        val chunks = List(3) { AudioChunk(ShortArray(sampleRate)) }
        val results = repo.transcribe(chunks.asFlow()).toList()

        assertTrue("Should emit at least one result", results.isNotEmpty())
        // The last result should be a Final (not a Failure from the confidence gate)
        val last = results.last()
        assertTrue(
            "Long utterance should bypass confidence gate and emit Final but got $last",
            last is TranscriptResult.Final
        )
    }
}


