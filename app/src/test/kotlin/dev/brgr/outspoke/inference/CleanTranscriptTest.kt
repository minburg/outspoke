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
        // "ich denke" is only 2 words before the period → short-segment rule removes it.
        // Use a sentence with ≥5 words before the period to test capitalization.
        // "naja" is not in SHOULD_STAY_LOWERCASE so it must be capitalised after the period.
        assertThat("ich denke dass es wirklich gut ist. naja das stimmt.".cleanTranscript())
            .isEqualTo("Ich denke dass es wirklich gut ist. Naja das stimmt.")
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
        // First word lowercase. Use ≥5 words before the period so it is a real sentence boundary.
        assertThat("sind vielleicht wirklich noch falsch. naja, also jetzt.".cleanTranscript(isContinuation = true))
            .isEqualTo("sind vielleicht wirklich noch falsch. Naja, also jetzt.")
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
        // Missing sentence space (raw model artefacts — idempotency applies to
        // already-cleaned text, not to raw input that has no spaces around punctuation)
        // Note: "gut.Ich bin" is intentionally excluded: the missing-space repair produces
        // "gut. Ich bin" whose 1-word segment "gut." is then removed by filterSpuriousPeriods
        // on a second pass, making the transformation non-idempotent by design.
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
        // Mixed artefacts (raw inputs with no spaces around punctuation are excluded from
        // idempotency checks because the missing-space repair creates new period-terminated
        // segments whose word count may trigger filterSpuriousPeriods on a second pass)
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
    fun `removeFillerWords with language de removes German disfluencies`() {
        assertThat("äh dann ähm ja genau.".removeFillerWords(language = "de"))
            .isEqualTo("dann ja genau.")
    }

    @Test
    fun `removeFillerWords with language de preserves German content word um`() {
        // "um" is a valid German preposition meaning "around/at" — must NOT be removed.
        assertThat("wir treffen uns um acht Uhr.".removeFillerWords(language = "de"))
            .isEqualTo("wir treffen uns um acht Uhr.")
    }

    @Test
    fun `removeFillerWords with language de preserves German content words ja na halt eben`() {
        val input = "ja, na gut, halt mal kurz, eben das."
        assertThat(input.removeFillerWords(language = "de")).isEqualTo(input)
    }

    @Test
    fun `cleanTranscript with language de removes German disfluencies`() {
        assertThat("äh dann ähm ja genau.".cleanTranscript(language = "de"))
            .isEqualTo("Dann ja genau.")
    }

    @Test
    fun `cleanTranscript with language de preserves um as content word and converts acht to digit`() {
        // "um 8 Uhr" is the standard written form in German — number normalisation applies.
        assertThat("wir treffen uns um acht Uhr.".cleanTranscript(language = "de"))
            .isEqualTo("Wir treffen uns um 8 Uhr.")
    }

    @Test
    fun `cleanTranscript with language en removes English fillers including um`() {
        assertThat("um so I was saying uh hello.".cleanTranscript(language = "en"))
            .isEqualTo("So I was saying hello.")
    }

    @Test
    fun `cleanTranscript default language en removes um as English filler`() {
        // Default language is "en", so "um" should be stripped as a filler.
        assertThat("um hello world".cleanTranscript()).isEqualTo("Hello world")
    }

    // ── filterSpuriousPeriods ────────────────────────────────────────────────

    @Test
    fun `filterSpuriousPeriods removes period after non-sentence-closing word 'and'`() {
        // "and." in the middle of a sentence is a prosodic-pause artefact.
        assertThat("I went to the store and. bought some milk".filterSpuriousPeriods())
            .isEqualTo("I went to the store and bought some milk")
    }

    @Test
    fun `filterSpuriousPeriods keeps period after non-function-word when segment has 5 or more words`() {
        // "Hello there my dear friend." is 5 words → period survives (not a short segment).
        assertThat("Hello there my dear friend. how are you doing today".filterSpuriousPeriods())
            .isEqualTo("Hello there my dear friend. how are you doing today")
    }

    @Test
    fun `filterSpuriousPeriods keeps period when segment has 5 or more words and word is not non-closing`() {
        // 6 words before the period, last word is "friend" (not in the set) → real boundary.
        assertThat("I went to see my friend. How are you".filterSpuriousPeriods())
            .isEqualTo("I went to see my friend. How are you")
    }

    @Test
    fun `filterSpuriousPeriods removes period after German preposition 'von'`() {
        assertThat("Das ist ein Beispiel von. dem was ich meine".filterSpuriousPeriods())
            .isEqualTo("Das ist ein Beispiel von dem was ich meine")
    }

    @Test
    fun `filterSpuriousPeriods handles consecutive spurious periods in one pass`() {
        // Both periods follow non-sentence-closing words: "the" and "on" are in the set.
        assertThat("I saw the. cat sat on. a mat here now".filterSpuriousPeriods())
            .isEqualTo("I saw the cat sat on a mat here now")
    }

    @Test
    fun `filterSpuriousPeriods does not affect text with no periods`() {
        val input = "hello world how are you"
        assertThat(input.filterSpuriousPeriods()).isEqualTo(input)
    }

    @Test
    fun `cleanTranscript does not produce spurious capital after pause-period mid-sentence`() {
        // Parakeet emits "and." mid-sentence; without the filter capitalisation fires on "bought".
        assertThat("I went to the store and. bought some milk".cleanTranscript())
            .isEqualTo("I went to the store and bought some milk")
    }

    // ── filterSpuriousPeriods — short-segment branch ─────────────────────────

    @Test
    fun `filterSpuriousPeriods removes period when segment is 2 words she ran`() {
        // "she ran." is only 2 words → short-segment rule removes the period.
        assertThat("she ran. fast".filterSpuriousPeriods())
            .isEqualTo("she ran fast")
    }

    @Test
    fun `filterSpuriousPeriods removes period when segment is 2 words I went`() {
        // "I went." is only 2 words → short-segment rule removes the period.
        assertThat("I went. home".filterSpuriousPeriods())
            .isEqualTo("I went home")
    }

    @Test
    fun `filterSpuriousPeriods keeps period when segment is exactly 6 words`() {
        // "the cat sat on the mat." is 6 words (≥5) → period survives.
        assertThat("the cat sat on the mat. quickly".filterSpuriousPeriods())
            .isEqualTo("the cat sat on the mat. quickly")
    }

    @Test
    fun `filterSpuriousPeriods keeps period when segment is exactly 5 words`() {
        // 5 words is the threshold — period at exactly 5 words is kept.
        assertThat("one two three four five. next".filterSpuriousPeriods())
            .isEqualTo("one two three four five. next")
    }

    @Test
    fun `filterSpuriousPeriods removes period when segment is 4 words`() {
        // 4 words < 5 → short-segment rule removes the period.
        assertThat("one two three four. next".filterSpuriousPeriods())
            .isEqualTo("one two three four next")
    }

    @Test
    fun `filterSpuriousPeriods short-segment word counter resets after a kept period`() {
        // First period: 6 words → kept (resets counter).
        // Second period: "she ran." is 2 words since the first period → removed.
        assertThat("one two three four five six. she ran. fast".filterSpuriousPeriods())
            .isEqualTo("one two three four five six. she ran fast")
    }

    @Test
    fun `filterSpuriousPeriods never removes the final period of the utterance`() {
        // Even if the entire utterance is fewer than 5 words, the last period is real.
        assertThat("she ran.".filterSpuriousPeriods())
            .isEqualTo("she ran.")
    }

    // ── Track D.2 — Capitalisation guard on non-sentence-final words ────────────────

    @Test
    fun `applySentenceCapitalization does not capitalise lowercase-guard word after period`() {
        // "the" is in SHOULD_STAY_LOWERCASE → must stay lowercase after the period
        assertThat("hello. the world".applySentenceCapitalization())
            .isEqualTo("Hello. the world")
    }

    @Test
    fun `applySentenceCapitalization does not capitalise German article after period`() {
        // "die" is in SHOULD_STAY_LOWERCASE
        assertThat("Ich denke. die Sache ist klar.".applySentenceCapitalization())
            .isEqualTo("Ich denke. die Sache ist klar.")
    }

    @Test
    fun `applySentenceCapitalization capitalises first word of utterance unconditionally even if in guard set`() {
        // "the" at utterance start must still be capitalised (isFirstCapitalize = true)
        assertThat("the quick brown fox.".applySentenceCapitalization())
            .isEqualTo("The quick brown fox.")
    }

    @Test
    fun `applySentenceCapitalization capitalises normal word after period when not in guard set`() {
        // "Quick" is not in SHOULD_STAY_LOWERCASE → capitalise as before
        assertThat("hello. quick brown fox.".applySentenceCapitalization())
            .isEqualTo("Hello. Quick brown fox.")
    }

    @Test
    fun `applySentenceCapitalization guard does not apply after exclamation mark`() {
        // After "!" the guard must NOT apply — "the" should be capitalised
        assertThat("hello! the world".applySentenceCapitalization())
            .isEqualTo("Hello! The world")
    }

    @Test
    fun `applySentenceCapitalization skipInitialCapitalize with guard word after period`() {
        // Mid-sentence continuation: first word stays lowercase; period-triggered "or" guarded
        assertThat("sind wirklich falsch. or maybe not".applySentenceCapitalization(skipInitialCapitalize = true))
            .isEqualTo("sind wirklich falsch. or maybe not")
    }
}

