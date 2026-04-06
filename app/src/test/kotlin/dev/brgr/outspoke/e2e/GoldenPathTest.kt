package dev.brgr.outspoke.e2e

import android.text.InputType
import android.view.inputmethod.EditorInfo
import dev.brgr.outspoke.ime.FakeInputConnection
import dev.brgr.outspoke.ime.TextInjector
import dev.brgr.outspoke.inference.FakeSpeechEngine
import dev.brgr.outspoke.inference.InferenceRepository
import dev.brgr.outspoke.inference.TranscriptResult
import dev.brgr.outspoke.inference.silentAudioFlow
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

/**
 * Builds an [EditorInfo] representing a standard single-line text field so that
 * [TextInjector.supportsComposing] returns `true`.
 */
private fun textEditorInfo(): EditorInfo = EditorInfo().apply {
    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL
}

/**
 * Layer-4 golden-path E2E tests (test concept section 6).
 *
 * Each test drives **both** [InferenceRepository] and [TextInjector] end-to-end with a
 * scripted [FakeSpeechEngine] and an in-memory [FakeInputConnection].  No emulator, no
 * physical device, and no ML model file are required.
 *
 * The core question answered by every scenario:
 * > *"Given this exact sequence of engine responses, what text ends up in the field?"*
 *
 * Regression anchors:
 *  - **BUG A** - leading comma artefact (`, jetzt sehr gut ausgeben..`) that cascaded into
 *    repeated `REALIGN complete alignment failure` events and left the wrong text in the field.
 *  - **BUG B** - rogue comma between sentences: `"verloren gehen. , Jetzt sehr..."`.
 */
class GoldenPathTest {

    /**
     * Replicates the exact engine response sequence from session 3 of the 2026-04-05 log
     * (strides 1–5).
     *
     * Before the `LEADING_PUNCT_RE` fix stride 1 committed `","` as the first word.
     * `normalizeWord(",") == ""` can never match any real word, so every subsequent stride
     * produced a `REALIGN complete alignment failure` and the stale stride-1 composing span
     * (`"jetzt sehr gut ausgeben."`) was left in the field permanently.
     *
     * With the fix in place:
     *  1. `cleanTranscript` strips the leading comma → stride 1 partial is "Jetzt sehr gut ausgeben."
     *  2. Strides 2–5 converge on the full sentence and alignment succeeds normally.
     *  3. The full sentence appears in the field; no stray comma is present.
     */
    @Test
    fun `BUG A scenario - leading comma artefact produces correct final output`() = runTest {
        val ic = FakeInputConnection()
        val injector = TextInjector(ic, textEditorInfo())

        val engine = FakeSpeechEngine(
            listOf(
                // Stride 1 - 2 s window, model starts mid-sentence with leading-comma artefact
                TranscriptResult.Final(", jetzt sehr gut ausgeben.."),
                // Stride 2 - 3 s window, full sentence becomes visible
                TranscriptResult.Final("Ich denke, dass es jetzt sehr gut aussieht.."),
                // Stride 3 - 4 s window, stabilising
                TranscriptResult.Final("Ich denke, dass es jetzt sehr gut aussieht und der Jo aus.."),
                // Stride 4 - 5 s window
                TranscriptResult.Final("Ich denke, dass es jetzt sehr gut aussieht und der Jo auch wirklich zufrieden."),
                // Stride 5 - 6 s window, final result
                TranscriptResult.Final("Ich denke, dass es jetzt sehr gut aussieht und der Jo auch wirklich zufrieden sein wird."),
            )
        )

        val repo = InferenceRepository(engine)
        // 7 × 1 s chunks → strides fire at 2 s, 3 s, 4 s, 5 s, 6 s (5 strides) then final.
        repo.transcribe(silentAudioFlow(7)).collect { result ->
            when (result) {
                is TranscriptResult.Partial -> injector.setPartial(result.text)
                is TranscriptResult.Final -> injector.commitFinal(result.text)
                is TranscriptResult.WindowTrimmed -> injector.resetAfterTrim()
                else -> {}
            }
        }

        val fieldText = ic.fieldText.trim()

        // Core: the full sentence must be in the field.
        assertThat(fieldText)
            .describedAs("Full sentence must reach the field")
            .contains("Ich denke")
        assertThat(fieldText)
            .describedAs("Sentence tail must be present")
            .contains("zufrieden sein wird")

        // BUG A regression: no stray leading comma from stride 1.
        assertThat(fieldText)
            .describedAs("Field must not start with a comma (BUG A regression)")
            .doesNotStartWith(",")

        // BUG B regression: no rogue standalone comma injected between sentences.
        assertThat(fieldText)
            .describedAs("No rogue '. ,' between sentences (BUG B regression)")
            .doesNotContain(". ,")
        assertThat(fieldText)
            .describedAs("No run-together '., ' variant")
            .doesNotContain("., ")
    }

    /**
     * Verifies that both sentences survive a mid-session stable-chunk window trim.
     *
     * Scenario:
     *  - 4 consecutive strides return only `sentence1` - the stable prefix builds up.
     *  - Once the window exceeds TRIGGER_WINDOW_SAMPLES (6 s), a trim fires and
     *    [TranscriptResult.WindowTrimmed] is emitted.
     *  - Subsequent strides return `sentence1` + `sentence2`; both sentences must appear
     *    exactly once in the final field - no duplication from a double-commit of `sentence1`.
     */
    @Test
    fun `two sentences survive a mid-session window trim`() = runTest {
        val ic = FakeInputConnection()
        val injector = TextInjector(ic, textEditorInfo())

        val sentence1 = "Ich bin mir relativ sicher, dass es jetzt besser ist."
        val sentence2 = "Und was ich wirklich nicht haben möchte, ist, dass hier Sätze verloren gehen."

        val responses = buildList {
            // First 4 strides return only sentence1 → stable prefix builds up.
            repeat(4) { add(TranscriptResult.Final(sentence1)) }
            // After trim, model sees only the tail context → now emits both sentences.
            add(TranscriptResult.Final("$sentence1 $sentence2"))
            add(TranscriptResult.Final("$sentence1 $sentence2"))
            add(TranscriptResult.Final("$sentence1 $sentence2"))
        }

        val engine = FakeSpeechEngine(responses)
        val repo = InferenceRepository(engine)
        // 10 × 1 s chunks → window exceeds 6 s trigger after chunk 7; trim fires; final at end.
        repo.transcribe(silentAudioFlow(10)).collect { result ->
            when (result) {
                is TranscriptResult.Partial -> injector.setPartial(result.text)
                is TranscriptResult.Final -> injector.commitFinal(result.text)
                is TranscriptResult.WindowTrimmed -> injector.resetAfterTrim()
                else -> {}
            }
        }

        val fieldText = ic.fieldText

        assertThat(fieldText)
            .describedAs("First sentence must be present in the field")
            .contains("relativ sicher")
        assertThat(fieldText)
            .describedAs("Second sentence must not be dropped during trim")
            .contains("verloren gehen")

        // "relativ sicher" must appear exactly once - sentence1 must not be double-committed.
        assertThat(fieldText.split("relativ sicher"))
            .describedAs("'relativ sicher' must appear exactly once (no duplication from trim)")
            .hasSize(2)
    }

    /**
     * When the field already contains committed text from a previous session and that text
     * ends with a sentence-ending punctuation mark, the next session must be separated by
     * exactly one space - neither run together nor double-spaced.
     *
     * Also verifies the BUG B regression: no comma may appear between the two sessions.
     */
    @Test
    fun `second session appended with space separator when field ends with punctuation`() = runTest {
        // Simulate a field that already has committed text from the first session.
        val ic = FakeInputConnection()
        ic.commitText("Sätze verloren gehen.", 1)

        val injector = TextInjector(ic, textEditorInfo())

        // Second session - driven directly through TextInjector (no InferenceRepository needed).
        injector.setPartial("Jetzt sehr gut.")
        injector.commitFinal("Jetzt sehr gut.")

        val fieldText = ic.fieldText

        assertThat(fieldText)
            .describedAs("Second session must be separated by a single space")
            .isEqualTo("Sätze verloren gehen. Jetzt sehr gut.")

        // BUG B regression: must not produce ". ," or run together ".,".
        assertThat(fieldText)
            .describedAs("No run-together '., ' between sessions")
            .doesNotContain(".,")
        assertThat(fieldText)
            .describedAs("No rogue comma '. ,' between sessions (BUG B regression)")
            .doesNotContain(". ,")
    }
}



