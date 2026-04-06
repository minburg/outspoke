package dev.brgr.outspoke.inference

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

/**
 * Layer-1 unit tests for [cleanTranscript] and [applySentenceCapitalization].
 *
 * Every test is pure Kotlin - no Android framework, no coroutines, no fake engines.
 * Tests are grouped by cleaning step so a failing step is immediately obvious.
 *
 * Regression anchors:
 *  - [BUG A] Leading comma artefact from Parakeet's SentencePiece tokenizer that caused
 *    cascading TextInjector alignment failures (normalizeWord(",") == "" can never match
 *    any real word, so every subsequent stride produces REALIGN failure).
 *  - [BUG B] Rogue comma appearing between sentences when LEAD_PUNCT was missing.
 */
class CleanTranscriptTest {

    @Test
    fun `cleanTranscript strips leading triple-dot ellipsis`() {
        assertThat("...Hello world".cleanTranscript()).isEqualTo("Hello world")
    }

    @Test
    fun `cleanTranscript strips leading double-dot with trailing space`() {
        assertThat(".. Ich denke".cleanTranscript()).isEqualTo("Ich denke")
    }

    @Test
    fun `cleanTranscript strips leading comma BUG A exact stride-1 artefact`() {
        // Exact raw output from the 2026-04-05 log, stride 1, session 3.
        // Without LEAD_PUNCT fix: committed word "," normalises to "" and
        // permanently breaks alignment for every subsequent stride.
        assertThat(", jetzt sehr gut ausgeben.".cleanTranscript())
            .isEqualTo("Jetzt sehr gut ausgeben.")
    }

    @Test
    fun `cleanTranscript strips leading comma BUG B session-2 artefact`() {
        assertThat(", es jetzt sehr gut aussieht.".cleanTranscript())
            .isEqualTo("Es jetzt sehr gut aussieht.")
    }

    @Test
    fun `cleanTranscript strips leading double semicolons`() {
        assertThat(";; nächste Zeile".cleanTranscript()).isEqualTo("Nächste Zeile")
    }

    @Test
    fun `cleanTranscript preserves comma that appears in the middle of a sentence`() {
        // Only the LEADING punctuation is stripped; an embedded comma must survive untouched.
        assertThat("Ich denke, dass es gut ist.".cleanTranscript())
            .isEqualTo("Ich denke, dass es gut ist.")
    }

    @Test
    fun `cleanTranscript collapses trailing double-dot to single dot`() {
        assertThat("gut aus..".cleanTranscript()).isEqualTo("Gut aus.")
    }

    @Test
    fun `cleanTranscript collapses trailing triple-dot to single dot`() {
        assertThat("sicher...".cleanTranscript()).isEqualTo("Sicher.")
    }

    @Test
    fun `cleanTranscript inserts space after period followed directly by letter`() {
        assertThat("gut.Ich bin".cleanTranscript()).isEqualTo("Gut. Ich bin")
    }

    @Test
    fun `cleanTranscript inserts space after exclamation mark followed directly by letter`() {
        assertThat("sicher!Und".cleanTranscript()).isEqualTo("Sicher! Und")
    }

    @Test
    fun `cleanTranscript capitalizes first word and word after sentence-ending punctuation`() {
        assertThat("ich denke. und das ist gut.".cleanTranscript())
            .isEqualTo("Ich denke. Und das ist gut.")
    }

    @Test
    fun `cleanTranscript combined BUG A - strips leading comma collapses trailing dots and capitalizes`() {
        // This is the exact raw string from stride 1 of the failing session.
        // All three steps must fire in the correct order for the output to be right.
        assertThat(", jetzt sehr gut ausgeben..".cleanTranscript())
            .isEqualTo("Jetzt sehr gut ausgeben.")
    }

    @Test
    fun `cleanTranscript discards string of only dots`() {
        assertThat("..".cleanTranscript()).isEqualTo("")
    }

    @Test
    fun `cleanTranscript discards bare comma`() {
        assertThat(",".cleanTranscript()).isEqualTo("")
    }

    @Test
    fun `cleanTranscript discards blank whitespace`() {
        assertThat("   ".cleanTranscript()).isEqualTo("")
    }

    @Test
    fun `applySentenceCapitalization capitalizes first letter`() {
        assertThat("hello world".applySentenceCapitalization()).isEqualTo("Hello world")
    }

    @Test
    fun `applySentenceCapitalization capitalizes letter immediately after period`() {
        assertThat("hello. world".applySentenceCapitalization()).isEqualTo("Hello. World")
    }

    /**
     * BUG B regression: verifies that [cleanTranscript] strips the leading comma in step 5
     * *before* capitalization fires in step 8.  In the old (unfixed) code, `,` was still
     * present when capitalization ran, so the second character `e` was capitalised giving
     * `, Es jetzt gut."` - which then appeared in the field as `". , Es jetzt gut."`.
     */
    @Test
    fun `cleanTranscript step-order BUG B regression - leading comma stripped before capitalization`() {
        val result = ", es jetzt gut.".cleanTranscript()
        assertThat(result).isEqualTo("Es jetzt gut.")
        assertThat(result).doesNotStartWith(",")
    }

    /**
     * After an audio-window trim, the model transcribes the retained tail starting
     * mid-sentence (e.g. `"sind vielleicht noch falsch…"`).  With isContinuation=true
     * the first letter must stay lowercase; sentence-internal capitalisation still fires.
     */
    @Test
    fun `cleanTranscript isContinuation skips initial capitalization`() {
        assertThat("sind vielleicht noch falsch.".cleanTranscript(isContinuation = true))
            .isEqualTo("sind vielleicht noch falsch.")
    }

    @Test
    fun `cleanTranscript isContinuation still capitalizes after sentence-ending punctuation`() {
        // First word lowercase, but "Naja" starts a new sentence after the dot → capitalized.
        assertThat("sind falsch. naja, also jetzt.".cleanTranscript(isContinuation = true))
            .isEqualTo("sind falsch. Naja, also jetzt.")
    }

    @Test
    fun `cleanTranscript default isContinuation false capitalizes first word as before`() {
        assertThat("sind vielleicht falsch.".cleanTranscript())
            .isEqualTo("Sind vielleicht falsch.")
    }

    @Test
    fun `applySentenceCapitalization skipInitialCapitalize keeps first letter lowercase`() {
        assertThat("hello world".applySentenceCapitalization(skipInitialCapitalize = true))
            .isEqualTo("hello world")
    }

    @Test
    fun `applySentenceCapitalization skipInitialCapitalize still capitalizes after period`() {
        assertThat("hello. world".applySentenceCapitalization(skipInitialCapitalize = true))
            .isEqualTo("hello. World")
    }

    /**
     * A corpus of real and synthetic model outputs. Running `cleanTranscript` twice on
     * each must produce the same result as running it once - if any cleaning step
     * introduces artefacts that a later pass would remove, the pipeline has a
     * step-ordering bug.
     */
    private val idempotencyCorpus = listOf(
        // Simple sentences
        "Ich denke, dass es gut ist.",
        "hello world",
        // Leading artefacts
        ".. Ich denke",
        "...Hello world",
        ", jetzt sehr gut ausgeben..",
        ";; nächste Zeile",
        // Trailing dots
        "gut aus..",
        "sicher...",
        // Missing sentence space
        "gut.Ich bin",
        "sicher!Und",
        // Multiple sentences
        "ich denke. und das ist gut.",
        "Das klingt gut. Das klingt gut.",
        // Emphasis repetition
        "Das ist sehr sehr gut.",
        "Nein nein, das stimmt nicht.",
        "So so geht das nicht.",
        // Stutter threshold
        "nein nein nein, das stimmt nicht.",
        // Hallucination loop
        "ich wollte sagen ich wollte sagen ich wollte sagen dass es gut ist.",
        // Blank/noise inputs
        "..",
        ",",
        "   ",
        // Mixed artefacts
        ".. , gut.Ich denke... dass..",
        "Ich bin relativ sicher, dass das hier gut funktioniert und keine Sätze verloren gehen. Weiter geht es dann.",
        // Decimal numbers near sentence boundaries
        "Das kostet 3.50 Euro. Und das andere kostet 2.99.",
        // German filler context
        "äh dann ähm ja genau also ich wollte sagen.",
        // Continuation mode
        "sind vielleicht noch falsch.",
        "sind falsch. naja, also jetzt.",
    )

    @Test
    fun `cleanTranscript is idempotent on a corpus of real model outputs`() {
        idempotencyCorpus.forEach { input ->
            val once = input.cleanTranscript()
            val twice = once.cleanTranscript()
            assertThat(twice)
                .describedAs("cleanTranscript must be idempotent for input: \"$input\" (once=\"$once\")")
                .isEqualTo(once)
        }
    }

    @Test
    fun `cleanTranscript with isContinuation is idempotent`() {
        idempotencyCorpus.forEach { input ->
            val once = input.cleanTranscript(isContinuation = true)
            val twice = once.cleanTranscript(isContinuation = true)
            assertThat(twice)
                .describedAs("cleanTranscript(isContinuation=true) must be idempotent for: \"$input\"")
                .isEqualTo(once)
        }
    }

    @Test
    fun `cleanTranscript output never starts with leading dots or punctuation`() {
        idempotencyCorpus.forEach { input ->
            val result = input.cleanTranscript()
            if (result.isNotEmpty()) {
                assertThat(result)
                    .describedAs("Output must not start with dots for input: \"$input\"")
                    .doesNotMatch("^\\..*")
                assertThat(result)
                    .describedAs("Output must not start with comma/semicolon for input: \"$input\"")
                    .doesNotMatch("^[,;].*")
            }
        }
    }

    @Test
    fun `cleanTranscript output never contains consecutive dots`() {
        idempotencyCorpus.forEach { input ->
            val result = input.cleanTranscript()
            assertThat(result)
                .describedAs("Output must not contain '..' for input: \"$input\"")
                .doesNotContain("..")
        }
    }

    @Test
    fun `cleanTranscript output has space after sentence-ending punctuation before a letter`() {
        idempotencyCorpus.forEach { input ->
            val result = input.cleanTranscript()
            // Verify no period/exclamation/question mark is immediately followed by a letter
            assertThat(result.contains(Regex("""[.!?][A-Za-zÀ-ÖØ-öø-ÿ]""")))
                .describedAs("Output must have space after sentence punctuation for input: \"$input\" → \"$result\"")
                .isFalse()
        }
    }

    @Test
    fun `removeFillerWords on text without fillers is identity`() {
        val noFillers = listOf(
            "Ich denke, dass es gut ist.",
            "Das klingt wirklich gut.",
            "Heute wollte ich über das Projekt sprechen.",
        )
        noFillers.forEach { input ->
            assertThat(input.removeFillerWords())
                .describedAs("removeFillerWords must be identity when no fillers present: \"$input\"")
                .isEqualTo(input)
        }
    }
}
