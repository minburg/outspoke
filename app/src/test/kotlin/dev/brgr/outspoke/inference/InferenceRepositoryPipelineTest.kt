package dev.brgr.outspoke.inference

import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

/**
 * Layer-3 integration tests for [InferenceRepository].
 *
 * These tests verify the full stride loop - audio accumulation, cleaning, blank-discard,
 * stable-chunk trim, and postprocessing toggle - using [FakeSpeechEngine] as a scripted
 * stand-in for the ML model and [silentAudioFlow] to drive the pipeline without real audio.
 *
 * No emulator or physical device is required; all tests run on the JVM.
 *
 * Key constants (from production code):
 *   MIN_SAMPLES            = 32 000 (2 s)  - first partial fires once window reaches this
 *   STRIDE_SAMPLES         = 16 000 (1 s)  - new partial every stride after MIN_SAMPLES
 *   TRIGGER_WINDOW_SAMPLES = 96 000 (6 s)  - stable-chunk trim activates above this
 *   STABLE_STRIDES         = 3             - consecutive partials with same prefix to trigger trim
 *   MIN_CONTEXT_SAMPLES    = 64 000 (4 s)  - tail kept after trim
 */
class InferenceRepositoryPipelineTest {

    /**
     * Stride 1 produces the engine's leading-comma artefact (", jetzt sehr gut ausgeben..").
     * [cleanTranscript] must strip the comma and trailing dots so no Partial ever starts
     * with a comma - preventing the cascading TextInjector alignment failures described in
     * the test concept Bug A trace.
     */
    @Test
    fun `leading comma is stripped before emitting partial`() = runTest {
        val engine = FakeSpeechEngine(
            listOf(
                // stride 1 (2 s window): SentencePiece leading-comma artefact
                TranscriptResult.Final(", jetzt sehr gut ausgeben.."),
                // stride 2 (3 s window): model sees full sentence
                TranscriptResult.Final("Ich denke, dass es jetzt sehr gut aussieht.."),
                // stride 3+: stabilising
                TranscriptResult.Final("Ich denke, dass es jetzt sehr gut aussieht.."),
            )
        )
        val repo = InferenceRepository(engine)

        // silentAudioFlow(5) → 5 × 16 000 samples = 5 s total.
        // Stride fires after chunk 2 (window=32 000=MIN_SAMPLES) then each subsequent chunk.
        val partials = repo.transcribe(silentAudioFlow(5))
            .filterIsInstance<TranscriptResult.Partial>()
            .toList()

        // No partial may start with a comma - the LEAD_PUNCT cleaning step must have fired.
        partials.forEach { partial ->
            assertThat(partial.text)
                .describedAs("Partial text must not start with ','")
                .doesNotStartWith(",")
        }

        // After 3 strides the full sentence must be the current partial.
        assertThat(partials.last().text)
            .describedAs("Last partial should start with 'Ich denke'")
            .startsWith("Ich denke")
    }

    /**
     * When the same transcript is returned for ≥ STABLE_STRIDES consecutive strides AND
     * the window exceeds TRIGGER_WINDOW_SAMPLES, the repository must trim the front of the
     * window and emit [TranscriptResult.WindowTrimmed].
     *
     * silentAudioFlow(12) → 12 s of audio.  The window grows past 6 s (TRIGGER) by stride 6,
     * at which point the full stable-prefix condition is met and the trim fires.
     */
    @Test
    fun `window trim fires after stable prefix exceeds TRIGGER_WINDOW`() = runTest {
        val stableText = TranscriptResult.Final(
            "Ich bin relativ sicher, dass es jetzt besser ist. " +
                    "Und was ich wirklich nicht haben möchte, ist, dass hier Sätze verloren gehen."
        )
        val engine = FakeSpeechEngine(List(12) { stableText })
        val repo = InferenceRepository(engine)

        val results = repo.transcribe(silentAudioFlow(12)).toList()

        assertThat(results)
            .describedAs("A WindowTrimmed event must be emitted once the stable window exceeds TRIGGER_WINDOW")
            .anyMatch { it is TranscriptResult.WindowTrimmed }
    }

    /**
     * When [InferenceRepository.transcribe] is called with postprocessingEnabled = false
     * the raw engine output must be forwarded unchanged, bypassing all cleaning steps.
     */
    @Test
    fun `postprocessing disabled passes raw text through unchanged`() = runTest {
        val rawText = ", jetzt sehr gut ausgeben.."
        val engine = FakeSpeechEngine(listOf(TranscriptResult.Final(rawText)))
        val repo = InferenceRepository(engine)

        // silentAudioFlow(3): chunk 1 accumulates below MIN_SAMPLES;
        // chunk 2 hits MIN_SAMPLES → stride fires with the raw text.
        val partials = repo.transcribe(silentAudioFlow(3), postprocessingEnabled = false)
            .filterIsInstance<TranscriptResult.Partial>()
            .toList()

        assertThat(partials.first().text)
            .describedAs("Raw text must not be modified when postprocessing is disabled")
            .isEqualTo(rawText)
    }

    /**
     * The repository must always emit a [TranscriptResult.Final] at the end of the audio
     * stream, even when only a handful of strides have fired.
     */
    @Test
    fun `final inference is emitted at end of audio`() = runTest {
        val engine = FakeSpeechEngine(
            listOf(TranscriptResult.Final("Ich denke, dass es gut ist."))
        )
        val repo = InferenceRepository(engine)

        val results = repo.transcribe(silentAudioFlow(3)).toList()

        assertThat(results)
            .describedAs("A Final result must be emitted at end of stream")
            .anyMatch { it is TranscriptResult.Final }
    }

    /**
     * A raw transcript that reduces to blank after cleaning (e.g. ".." → "") must be
     * silently discarded - not forwarded as a Partial with empty or whitespace-only text.
     * This prevents TextInjector from receiving an empty composing span that would
     * interfere with subsequent alignment.
     */
    @Test
    fun `blank transcript after cleaning is discarded and not emitted as partial`() = runTest {
        val engine = FakeSpeechEngine(
            listOf(
                TranscriptResult.Final(".."),         // stride 1: only dots → cleaned to ""
                TranscriptResult.Final("Ich denke."), // stride 2+: real content
            )
        )
        val repo = InferenceRepository(engine)

        // silentAudioFlow(4): 4 strides possible; stride 1 returns ".." which must be dropped.
        val partials = repo.transcribe(silentAudioFlow(4))
            .filterIsInstance<TranscriptResult.Partial>()
            .toList()

        partials.forEach { partial ->
            assertThat(partial.text)
                .describedAs("Every emitted Partial must have non-blank text")
                .isNotBlank()
        }
        assertThat(partials)
            .describedAs("At least one Partial with real content must be emitted after the blank stride")
            .anyMatch { it.text.contains("Ich") }
    }
}






