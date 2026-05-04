package dev.brgr.outspoke.inference

import dev.brgr.outspoke.audio.AudioChunk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Unit tests for [GrammarCorrector] implementations and [InferenceRepository] integration. */
class GrammarCorrectorTest {

    // ── NoOpGrammarCorrector ──────────────────────────────────────────────────────────────────

    @Test
    fun `NoOpGrammarCorrector returns text unchanged for simple string`() {
        val corrector = NoOpGrammarCorrector
        val input = "This is a test sentence."
        assertEquals(input, corrector.correct(input))
    }

    @Test
    fun `NoOpGrammarCorrector returns text unchanged with explicit language`() {
        val corrector = NoOpGrammarCorrector
        val input = "a apple fell from the tree"
        assertEquals(input, corrector.correct(input, language = "en"))
    }

    @Test
    fun `NoOpGrammarCorrector returns empty string unchanged`() {
        val corrector = NoOpGrammarCorrector
        assertEquals("", corrector.correct(""))
    }

    @Test
    fun `NoOpGrammarCorrector close does not throw`() {
        // close() must be safe to call any number of times
        NoOpGrammarCorrector.close()
        NoOpGrammarCorrector.close()
    }

    // ── InferenceRepository grammar corrector integration ─────────────────────────────────────

    /**
     * A [GrammarCorrector] that records every call and appends a marker to the text
     * so tests can verify both that it was called and what it received.
     */
    private class RecordingCorrector : GrammarCorrector {
        val calls = mutableListOf<Pair<String, String>>() // (text, language)
        override fun correct(text: String, language: String): String {
            calls += text to language
            return "$text [corrected]"
        }

        override fun close() = Unit
    }

    private fun engineReturning(result: TranscriptResult): SpeechEngine = object : SpeechEngine {
        override val isLoaded = true
        override fun load(modelDir: File) = Unit
        override fun close() = Unit
        override fun transcribe(chunk: AudioChunk) = result
    }

    @Test
    fun `given mock corrector, when InferenceRepository emits Final, then corrector is applied`() = runTest {
        val corrector = RecordingCorrector()
        val repo = InferenceRepository(
            engine = engineReturning(TranscriptResult.Final("hello world")),
            grammarCorrector = corrector,
        )

        // 0.5 s → short-utterance path, emits exactly one Final
        val results = repo.transcribe(
            flowOf(AudioChunk(ShortArray(8_000)))
        ).toList()

        val finals = results.filterIsInstance<TranscriptResult.Final>()
        assertTrue("Should emit at least one Final result", finals.isNotEmpty())

        val last = finals.last()
        assertTrue(
            "Corrector output should be present in Final text, got: \"${last.text}\"",
            last.text.endsWith("[corrected]")
        )
        assertTrue("Corrector should have been called at least once", corrector.calls.isNotEmpty())
    }

    @Test
    fun `given mock corrector, when InferenceRepository emits Partial mid-stream, corrector is NOT applied`() =
        runTest {
            val corrector = RecordingCorrector()
            val repo = InferenceRepository(
                engine = engineReturning(TranscriptResult.Partial("growing text")),
                grammarCorrector = corrector,
            )

            // 2 s → exactly MIN_SAMPLES, fires one mid-stream Partial then one end-of-stream Final
            val results = repo.transcribe(
                flowOf(AudioChunk(ShortArray(32_000)))
            ).toList()

            val partials = results.filterIsInstance<TranscriptResult.Partial>()
            assertTrue("Should emit at least one Partial mid-stream", partials.isNotEmpty())

            // None of the Partial results should contain the corrector marker
            for (p in partials) {
                assertFalse(
                    "Corrector must NOT be applied to Partial results, got: \"${p.text}\"",
                    p.text.endsWith("[corrected]")
                )
            }
        }

    @Test
    fun `given NoOpGrammarCorrector, Final result text is unchanged`() = runTest {
        val repo = InferenceRepository(
            engine = engineReturning(TranscriptResult.Final("hello there")),
            grammarCorrector = NoOpGrammarCorrector,
        )

        val results = repo.transcribe(
            flowOf(AudioChunk(ShortArray(8_000)))
        ).toList()

        val finals = results.filterIsInstance<TranscriptResult.Final>()
        assertTrue("Should emit at least one Final", finals.isNotEmpty())
        // NoOp must not modify the text (cleaned text only)
        for (f in finals) {
            assertFalse(
                "NoOp corrector must not append any marker to Final text, got: \"${f.text}\"",
                f.text.contains("[corrected]")
            )
        }
    }
}
