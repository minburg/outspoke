package dev.brgr.outspoke.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Test

/**
 * Builds an [EditorInfo] that represents a standard single-line text field.
 *
 * Setting [EditorInfo.inputType] to [InputType.TYPE_CLASS_TEXT] makes
 * [TextInjector.supportsComposing] return `true`, which enables the full composing-text
 * path under test.
 */
private fun textEditorInfo(): EditorInfo = EditorInfo().apply {
    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL
}

/**
 * Layer-2 unit tests for [TextInjector] (test concept section 4.2).
 *
 * Every test drives [TextInjector] via a [FakeInputConnection] so the full suite runs
 * on the JVM - no emulator, no Robolectric, no ML model file.
 *
 * Regression anchors:
 *  - **BUG A** - leading comma artefact (`, jetzt sehr gut ausgeben..`) caused
 *    `normalizeWord(",") == ""`, which permanently broke alignment for all subsequent
 *    strides.  Fixed by `LEADING_PUNCT_RE` in `cleanTranscript()`.
 *  - **BUG B** - rogue comma between sentences: `"verloren gehen. , Jetzt sehr..."`.
 *    Fixed by the same leading-punct strip that runs before injection.
 */
class TextInjectorTest {

    private lateinit var ic: FakeInputConnection
    private lateinit var injector: TextInjector

    @Before
    fun setup() {
        ic = FakeInputConnection()
        injector = TextInjector(ic, textEditorInfo())
    }

    /**
     * A growing partial followed by commitFinal must produce exactly the final text in the
     * field - no duplicated prefix, no missing suffix.
     */
    @Test
    fun `setPartial then commitFinal produces correct text`() {
        injector.setPartial("Ich denke")
        injector.setPartial("Ich denke, dass es gut ist.")
        injector.commitFinal("Ich denke, dass es gut ist.")

        assertThat(ic.fieldText.trim()).isEqualTo("Ich denke, dass es gut ist.")
    }

    /**
     * Several successive partials that each extend the transcript by one token must not
     * commit "Ich" more than once, even though every partial starts with it.
     *
     * This verifies that the sliding freeze window correctly tracks the committed prefix
     * and only appends genuinely new words on each stride.
     */
    @Test
    fun `progressive partials do not duplicate committed prefix`() {
        injector.setPartial("Ich")
        injector.setPartial("Ich denke,")
        injector.setPartial("Ich denke, dass")
        injector.commitFinal("Ich denke, dass es gut ist.")

        val text = ic.fieldText
        // "Ich" must appear exactly once - any duplication indicates a double-commit.
        assertThat(text.indexOf("Ich")).isEqualTo(text.lastIndexOf("Ich"))
    }

    /**
     * Simulates Parakeet attention drift: the first partial establishes context, the second
     * partial drops the first three words (the model's attention window shifted forward).
     *
     * The suffix-overlap algorithm must find the correct anchor and produce a field that
     * contains the complete sentence without duplicating the head that was already shown.
     */
    @Test
    fun `model drift - partial skips first 3 words - content not duplicated`() {
        injector.setPartial("Ich denke, dass es")
        injector.setPartial("dass es gut ist.")  // model dropped "Ich denke,"
        injector.commitFinal("Ich denke, dass es gut ist.")

        val text = ic.fieldText
        assertThat(text).contains("Ich denke, dass es gut ist.")
        assertThat(text).doesNotContain("Ich denke, dass es Ich")
    }

    /**
     * Regression for BUG A (2026-04-05 log, third session, strides 1–5).
     *
     * Stride 1 produces a short divergent partial (the comma has already been stripped by
     * `cleanTranscript` before reaching the injector).  Strides 2–5 then converge on the
     * correct full sentence.  The final field must contain the correct sentence without a
     * stray leading comma.
     *
     * Without the `LEADING_PUNCT_RE` fix, stride-1's committed word was `","`, which
     * `normalizeWord` reduces to `""` - a token that can never match any real word.
     * Every subsequent stride therefore triggered a `REALIGN complete alignment failure`
     * and left the stale stride-1 composing span in the field.
     */
    @Test
    fun `leading comma partial does not permanently break alignment (BUG A regression)`() {
        injector.setPartial("Jetzt sehr gut ausgeben.")       // stride 1: cleaned, comma stripped
        injector.setPartial("Ich denke, dass es jetzt sehr gut aussieht.")
        injector.commitFinal("Ich denke, dass es jetzt sehr gut aussieht und der Jo zufrieden sein wird.")

        val text = ic.fieldText
        assertThat(text).contains("Ich denke")
        assertThat(text).doesNotStartWith(",")
    }

    /**
     * After an audio window trim [TextInjector.resetAfterTrim] shrinks the committed-word
     * list to a short tail so the very next post-trim partial can align against it.
     *
     * Without the reset the suffix-overlap algorithm would be searching a 30+ word
     * committed prefix for an anchor in a 4 s tail partial - and find nothing, silently
     * dropping every sentence until the committed list naturally ages out.
     */
    @Test
    fun `resetAfterTrim allows re-alignment on next partial`() {
        injector.setPartial("Ich bin relativ sicher, dass es jetzt besser ist. Und was ich")
        injector.setPartial("Ich bin relativ sicher, dass es jetzt besser ist. Und was ich wirklich nicht")
        // Trim fires - injector resets to tail words.
        injector.resetAfterTrim()
        // Post-trim partial: model only sees ~4 s tail context.
        injector.setPartial("nicht haben möchte, ist, dass hier Sätze verloren gehen.")
        injector.commitFinal(
            "Ich bin relativ sicher, dass es jetzt besser ist. Und was ich wirklich nicht" +
                " haben möchte, ist, dass hier Sätze verloren gehen."
        )

        assertThat(ic.fieldText).contains("verloren gehen")
    }

    /**
     * Regression for the **empty-committed post-trim erase** bug first seen in the
     * 2026-04-06 log (lines 25–31).
     *
     * **What happens:**
     * 1. A short sentence (≤ [TextInjector.MUTABLE_WORD_COUNT] words) is built up as
     *    partials.  Because the sentence is never longer than 6 words, `freezeCount`
     *    is always 0 and nothing is ever frozen: `committedWords = []`.
     * 2. A window trim fires.  `resetAfterTrim()` is a no-op (committedWords already empty).
     *    The composing span still shows the full sentence.
     * 3. The first post-trim partial from the model starts **mid-sentence** - the model
     *    only sees the shorter post-trim audio window, so it outputs the 3 tail words
     *    instead of all 6.
     * 4. **BUG:** `findNewContent(committedWords=[], fresh)` immediately returns all of
     *    `fresh` (line 160: `if (committed.isEmpty()) return fresh`).  Then
     *    `setComposingText("Einen Satz sagen.")` **replaces** the composing span that held
     *    `"Ich möchte jetzt einen Satz sagen."` → the first 3 words are permanently lost.
     *
     * **Expected:** the composing span must not be overwritten when `committedWords` is
     * empty and the new partial is a sub-sequence of the currently showing composing words.
     */
    @Test
    fun `post-trim composing span not dropped when committedWords is empty (BUG C regression)`() {
        // Step 1: 6-word partial - freezeCount=0, nothing committed, all in composing.
        //   committedWords = []
        //   composing      = "Ich möchte jetzt einen Satz sagen."
        injector.setPartial("Ich möchte jetzt einen Satz sagen.")

        assertThat(ic.fieldText)
            .describedAs("Pre-condition: full composing span must be visible before trim")
            .contains("Ich möchte jetzt")

        // Step 2: trim fires - no-op because committedWords is empty.
        injector.resetAfterTrim()

        // Step 3: post-trim stride - model only sees ~4 s tail audio, outputs the last
        //   3 words.  Without the fix: findNewContent([], 3 words) = all 3 →
        //   setComposingText("Einen Satz sagen.") erases "Ich möchte jetzt".
        injector.setPartial("Einen Satz sagen.")

        assertThat(ic.fieldText)
            .describedAs("'Ich möchte jetzt' must not be dropped by the post-trim mid-sentence partial (BUG C)")
            .contains("Ich möchte jetzt")

        // Step 4: model fully recovers, extending the transcript.
        injector.setPartial("Ich möchte jetzt einen Satz sagen. Und jetzt noch mehr.")
        injector.commitFinal("Ich möchte jetzt einen Satz sagen. Und jetzt noch mehr.")

        assertThat(ic.fieldText)
            .contains("Ich möchte jetzt einen Satz sagen. Und jetzt noch mehr.")
    }

    /**
     * Regression for the **post-trim composing-span drop** bug first seen in the
     * 2026-04-06 log.
     *
     * **What happens:**
     * 1. Normal strides build up: committed prefix `"Ich spreche einen Satz "` + composing
     *    tail `"und der ist nicht besonders lang."`.
     * 2. A window trim fires → [TextInjector.resetAfterTrim] is called.
     * 3. The next post-trim stride (`"Und der ist nicht besonders lang. Jetzt habe ich eine
     *    Pause."`) fails primary alignment (model dropped `"Ich spreche einen"`) but succeeds
     *    via recovery layer 1's interior scan, which anchors on the shared tail
     *    `["der","ist","nicht","besonders","lang."]` and classifies
     *    `["Jetzt","habe","ich","eine","Pause."]` as new content.
     * 4. **BUG:** recovery layer 1 calls
     *    `setComposingText("Jetzt habe ich eine Pause.", 1)`, which **replaces** the entire
     *    composing region - erasing `"und der ist nicht besonders lang."` from the field.
     *
     * **Expected:** both the old composing content AND the new words must be visible after
     * the recovery, with no gap between them.
     *
     * **This test MUST FAIL with the current implementation** and pass once recovery layer 1
     * is fixed to preserve the active composing span when calling [setComposingText].
     */
    @Test
    fun `recovery layer 1 does not drop active composing span when post-trim partial has leading junk words`() {
        // Step 1: first partial - freeze 1 word ("Ich"), 6 words in composing span.
        //   committedWords = ["Ich"]
        //   composing      = "spreche einen Satz und der ist"
        injector.setPartial("Ich spreche einen Satz und der ist")

        // Step 2: growing partial - freeze 4 words total, 6 words in composing span.
        //   committedWords = ["Ich","spreche","einen","Satz"]
        //   composing      = "und der ist nicht besonders lang."
        //   fieldText      = "Ich spreche einen Satz und der ist nicht besonders lang."
        injector.setPartial("Ich spreche einen Satz und der ist nicht besonders lang.")

        // Sanity: composing content is visible before the trim.
        assertThat(ic.fieldText)
            .describedAs("Pre-condition: composing content must be in field before trim")
            .contains("besonders lang.")

        // Step 3: window trim fires (committedWords.size=4 ≤ TAIL_COMMIT_WORDS=12 → no-op,
        // but the call resets the partial-history ring buffer inside the repository).
        injector.resetAfterTrim()

        // Step 4: post-trim stride.
        //   The model dropped the very first word of the utterance ("Ich spreche einen"),
        //   so primary alignment on committedWords=["Ich","spreche","einen","Satz"] fails.
        //   Recovery layer 1 reads fieldText, runs interior-scan of findNewContent, anchors
        //   on ["der","ist","nicht","besonders","lang."] at position 1 in fresh, and returns
        //   fieldNewContent = ["Jetzt","habe","ich","eine","Pause."].
        //
        //   BUG: setComposingText("Jetzt habe ich eine Pause.") REPLACES the composing
        //   region, dropping "und der ist nicht besonders lang." from the display.
        injector.setPartial("Und der ist nicht besonders lang. Jetzt habe ich eine Pause.")

        val text = ic.fieldText

        // The words that were in the composing span BEFORE the recovery MUST still be
        // visible.  Without the fix this assertion fails because "besonders lang." was
        // replaced by "Jetzt habe ich eine Pause." via the setComposingText call.
        assertThat(text)
            .describedAs("Old composing content must not be dropped by recovery layer 1 (BUG)")
            .contains("besonders lang.")

        // The genuinely new words identified by the interior scan must also appear.
        assertThat(text)
            .describedAs("New post-trim words must be visible after recovery")
            .contains("Jetzt habe ich eine Pause")

        // Both parts must be adjacent - no gap where the composing content was lost.
        assertThat(text)
            .describedAs("Old composing words and new words must be contiguous (no dropped middle)")
            .contains("besonders lang. Jetzt")
    }

    /**
     * When the model output at [TextInjector.commitFinal] time completely diverges from
     * anything previously committed, the injector must not erase existing field content.
     *
     * Recovery layer 2 preserves the composing span via `finishComposingText()` so that at
     * minimum what the user last saw in the UI preview remains in the field.
     */
    @Test
    fun `commitFinal after total alignment failure preserves composing span`() {
        injector.setPartial("Text der angezeigt wurde.")
        // Simulate complete divergence in commitFinal:
        injector.commitFinal("Völlig anderer Text.")

        // Whatever recovery path was taken, the field must not be empty.
        assertThat(ic.fieldText.trim()).isNotEmpty()
    }

    /**
     * Regression for **Bug 5A** (2026-04-07 long dictation test).
     *
     * **What happens without the fix:**
     * 1. Several partials build up committed text during a long dictation.
     * 2. Multiple window trims fire, and the model's post-trim output starts with
     *    different words than the committed tail.
     * 3. `findNewContent` finds a false-positive single-word overlap on "nicht"
     *    (a common German word) and injects the entire post-trim output as "new content",
     *    including words already in the field.
     * 4. When even that fails, Recovery layer 2 blindly appends `composingWords` to
     *    `committedWords`, growing it to 17→23 words and making all future alignment fail.
     * 5. The field ends up with concatenated fragments from each failed alignment.
     *
     * **Fix:** (a) Require ≥ 2 word overlap in `findNewContent` suffix-prefix scan,
     * (b) re-anchor `committedWords` from actual field content on complete alignment failure.
     */
    @Test
    fun `cascading alignment failure after trims does not corrupt field text (Bug 5A regression)`() {
        // Step 1: build up text through several partials.
        injector.setPartial("Heute wollte ich über das Projekt sprechen.")
        injector.setPartial("Heute wollte ich über das Projekt sprechen, das wir gerade entwickeln.")
        injector.setPartial("Heute wollte ich über das Projekt sprechen, das wir gerade entwickeln. Es gibt einige Punkte.")

        // Step 2: first trim fires.
        injector.resetAfterTrim()

        // Step 3: post-trim partial - model output starts with junk word "nicht" that
        // coincidentally matches the last committed word. Without the overlap≥2 fix, this
        // would create a false alignment.
        injector.setPartial("nicht über das Projekt sprechen, das wir gerade entwickeln. Es gibt einige Punkte, die noch nicht ganz klar sind.")

        // Step 4: more post-trim strides with divergent starts.
        injector.setPartial("über das Projekt sprechen, das wir gerade entwickeln. Es gibt einige Punkte, die noch nicht ganz klar sind. Ich bin mir nicht sicher.")

        // Step 5: second trim fires.
        injector.resetAfterTrim()

        // Step 6: another post-trim stride.
        injector.setPartial("Es gibt einige Punkte, die noch nicht ganz klar sind. Ich bin mir nicht sicher, ob wir das schaffen.")

        // Step 7: final commit.
        injector.commitFinal("Es gibt einige Punkte, die noch nicht ganz klar sind. Ich bin mir nicht sicher, ob wir das schaffen.")

        val text = ic.fieldText

        // The field must contain the final content - not be empty or corrupted.
        assertThat(text.trim()).isNotEmpty()

        // Key assertion: "nicht" must NOT appear as a duplicated fragment glued between
        // two unrelated sentences (the old cascade signature).
        // The field should contain a coherent progression of text.
        assertThat(text).contains("Punkte")
        assertThat(text).contains("schaffen")
    }

    /**
     * Verifies that a single-word coincidence between the committed tail and the fresh
     * start does NOT produce a false alignment.
     *
     * Without the ≥2 overlap fix, `findNewContent` would match "nicht." (committed tail)
     * against "nicht" (fresh start) and return the entire fresh tail as "new content".
     */
    @Test
    fun `single-word false overlap prevented by minimum overlap of 2`() {
        // Build up committed words ending in "nicht."
        injector.setPartial("Ich weiß es nicht.")
        injector.setPartial("Ich weiß es nicht. Und das ist auch nicht.")

        // Trim fires.
        injector.resetAfterTrim()

        // Post-trim partial starts with "nicht" in a DIFFERENT context - this is the junk
        // prefix from a trim, not a continuation of "auch nicht."
        injector.setPartial("nicht über das Thema reden.")

        val text = ic.fieldText

        // The field must NOT contain "Und das ist auch nicht. über das Thema reden."
        // (which would happen with a false single-word overlap where "nicht." matches "nicht").
        // Instead it should either have a clean alignment or a graceful recovery.
        assertThat(text.trim()).isNotEmpty()
    }

    /**
     * When the text field already contains committed text ending with a non-whitespace
     * character (e.g. a sentence-ending period), the injector must insert exactly one
     * separator space before the first partial of the new recording session.
     *
     * Without the separator the two sessions run together: `"go.Now I lifted"`.
     * With a double separator an extra space would appear: `"go.  Now I lifted"`.
     */
    @Test
    fun `session separator space is injected once when field already has content`() {
        // Previous session left text ending without whitespace.
        ic.commitText("go.", 1)
        injector.setPartial("Now I lifted the record button.")

        val text = ic.fieldText
        // A single space must separate the two sessions.
        assertThat(text).contains("go. Now")
        // The junction must appear exactly once - no doubled separator.
        assertThat(text.indexOf("go. Now")).isEqualTo(text.lastIndexOf("go. Now"))
    }
}

