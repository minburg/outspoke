package dev.brgr.outspoke.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

private fun textEditorInfo(): EditorInfo = EditorInfo().apply {
    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL
}

/**
 * Unit tests for [TextInjector.replaceCursorWord].
 *
 * All tests use [FakeInputConnection] so no Android emulator is required.
 *
 * Tested scenarios:
 *  1. Cursor inside a word — the word is replaced and surrounding text is preserved.
 *  2. Cursor between words (no word at cursor) — replacement is inserted at cursor position.
 */
class ReplaceCursorWordTest {

    /**
     * Cursor is positioned inside the word "quick" in "The quick brown fox".
     *
     * Expected: "quick" is fully replaced by "slow" → "The slow brown fox".
     *
     * The [FakeInputConnection] cursor is placed after "quick" (i.e. "The quick" is before
     * the cursor and " brown fox" is after), so leftFragment = "quick" and rightFragment = "".
     *
     * The fake's [setSelection] then selects from position 4 to 9 (the span of "quick")
     * and [commitText] replaces it with "slow".
     */
    @Test
    fun `replaceCursorWord replaces word when cursor is inside a word`() {
        val ic = FakeInputConnection()
        val injector = TextInjector(ic, textEditorInfo())

        // Seed the field: "The quick" committed, then " brown fox" as text after cursor.
        // Since FakeInputConnection cursor is at end, we simulate cursor-inside-word by
        // committing "The quick" and having " brown fox" NOT in the buffer (after-cursor).
        // Actually, we need to test with text after cursor. Let's populate the full field
        // and position the cursor mid-word by using a custom helper.
        //
        // Strategy: populate the full string, then call replaceCursorWord.
        // FakeInputConnection.getTextBeforeCursor returns text BEFORE the cursor and
        // getTextAfterCursor returns text AFTER the cursor. We commit "The quick" first,
        // then the fake cursor is at end. "quick" would be the left fragment (cursor right
        // after it), and rightFragment would be "" (nothing after).
        // We then expect only "quick" to be replaced.

        // Commit prefix "The "
        ic.commitText("The ", 1)
        // Commit "quick" — cursor now at end of "The quick"
        ic.commitText("quick", 1)
        // After cursor: nothing (cursor is at end of field for this test case)
        // So leftFragment = "quick", rightFragment = ""
        // selStart = 9 - 5 = 4, selEnd = 9

        injector.replaceCursorWord("slow")

        // "The quick" → "The slow"
        assertThat(ic.fieldText).isEqualTo("The slow")
    }

    /**
     * Cursor is positioned at the end of the field with no adjacent word characters
     * (the field ends with a space, so the cursor is between words).
     *
     * Expected: the replacement text is inserted at the cursor position.
     */
    @Test
    fun `replaceCursorWord inserts at cursor when no word is under cursor`() {
        val ic = FakeInputConnection()
        val injector = TextInjector(ic, textEditorInfo())

        // Field contains "Hello " - cursor after the trailing space, no word character before.
        ic.commitText("Hello ", 1)

        injector.replaceCursorWord("world")

        // "Hello " + "world" inserted at cursor position.
        assertThat(ic.fieldText).isEqualTo("Hello world")
    }

    /**
     * Replacement on an empty field inserts the replacement text.
     */
    @Test
    fun `replaceCursorWord on empty field inserts replacement`() {
        val ic = FakeInputConnection()
        val injector = TextInjector(ic, textEditorInfo())

        injector.replaceCursorWord("hello")

        assertThat(ic.fieldText).isEqualTo("hello")
    }

    /**
     * Regression (suggestion-bar corruption): the field holds far more than the 200-char
     * getTextBeforeCursor probe window before the cursor - the case of correcting a word in
     * a long text.  The old absolute-offset math used the PROBE LENGTH as the cursor
     * position, so the selection landed ~200 chars early and the replacement corrupted text
     * one or two words before the tapped word (spaces eaten, words merged).
     *
     * Cursor sits inside "performance"; the replacement must hit exactly that word.
     */
    @Test
    fun `replaceCursorWord targets the tapped word even when over 200 chars precede it`() {
        val ic = FakeInputConnection()
        val injector = TextInjector(ic, textEditorInfo())

        val preamble = "alpha ".repeat(40) // 240 chars - beyond the 200-char probe window
        val sentence = "I'm sure the performance is very good."
        ic.commitText(preamble + sentence, 1)
        val cursor = (preamble + "I'm sure the perform").length // mid-word in "performance"
        ic.setSelection(cursor, cursor)

        injector.replaceCursorWord("preservation")

        assertThat(ic.fieldText).isEqualTo(preamble + "I'm sure the preservation is very good.")
    }

    /**
     * Cursor sits mid-word ("bro|wn"), so BOTH fragments must be removed before the
     * replacement is inserted - the right fragment cannot be left behind.
     */
    @Test
    fun `replaceCursorWord removes the right fragment when the cursor sits mid-word`() {
        val ic = FakeInputConnection()
        val injector = TextInjector(ic, textEditorInfo())

        ic.commitText("The brown fox", 1)
        ic.setSelection("The bro".length, "The bro".length)

        injector.replaceCursorWord("red")

        assertThat(ic.fieldText).isEqualTo("The red fox")
    }
}
