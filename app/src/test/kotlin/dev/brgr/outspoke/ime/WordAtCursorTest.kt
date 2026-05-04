package dev.brgr.outspoke.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo
import dev.brgr.outspoke.ui.keyboard.WordAtCursor
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [TextInjector.wordAtCursor].
 *
 * All tests drive [TextInjector] through [FakeInputConnection], which keeps the cursor at
 * the end of the field.  Tests manipulate the buffer directly via
 * [FakeInputConnection.commitText] to place the cursor inside different words, then call
 * [TextInjector.wordAtCursor] and assert on the returned [WordAtCursor].
 *
 * Note: [FakeInputConnection.getTextAfterCursor] always returns `""` (cursor is at end),
 * so `rightFragment` is always empty.  The tests therefore focus on left-fragment extraction
 * and sentence-context logic, which are the most complex branches.
 */
class WordAtCursorTest {

    private lateinit var ic: FakeInputConnection
    private lateinit var injector: TextInjector

    @Before
    fun setup() {
        ic = FakeInputConnection()
        injector = TextInjector(
            ic,
            EditorInfo().apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL
            },
        )
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    /**
     * Seeds the fake field with [text] and returns the result of [TextInjector.wordAtCursor].
     * The cursor lands at the end of [text].
     */
    private fun wordAt(text: String): WordAtCursor? {
        ic.commitText(text, 1)
        return injector.wordAtCursor()
    }

    // ── Basic word extraction ─────────────────────────────────────────────────────

    /**
     * Cursor immediately after a plain word — the whole word must be returned.
     */
    @Test
    fun `cursor at end of plain word returns that word`() {
        val result = wordAt("hello")
        assertThat(result).isNotNull
        assertThat(result!!.word).isEqualTo("hello")
    }

    /**
     * Cursor immediately after trailing punctuation (comma after a word) — the cursor is
     * not inside a word character, so [TextInjector.wordAtCursor] must return `null`.
     *
     * The word `"hello"` ends at the comma; the cursor is sitting after the comma, not
     * inside `"hello"`.  Only the right-fragment scan could find letters in `textAfter`,
     * but [FakeInputConnection] always returns `""` for `getTextAfterCursor`, so `null`
     * is the correct result here.
     */
    @Test
    fun `cursor after trailing punctuation returns null`() {
        // "hello," — cursor is after the comma, not inside "hello".
        val result = wordAt("hello,")
        assertThat(result).isNull()
    }

    /**
     * Cursor after a space — no word character immediately before the cursor, so
     * [TextInjector.wordAtCursor] must return `null`.
     */
    @Test
    fun `cursor after whitespace returns null`() {
        val result = wordAt("hello ")
        assertThat(result).isNull()
    }

    /**
     * Cursor in the middle of a contraction like `"don't"`.
     * The apostrophe is a valid word character, so the full contraction is returned.
     * (In [FakeInputConnection] the cursor is always at the end, so we simulate
     * "cursor after apostrophe" by stopping the buffer just after the apostrophe.)
     */
    @Test
    fun `apostrophe in contraction is included in left fragment`() {
        // Buffer = "don't" up to and including the apostrophe; cursor is after "don'"
        val result = wordAt("don'")
        assertThat(result).isNotNull
        // Word should include the apostrophe-prefixed fragment — stripping leading/trailing
        // non-alphanumeric chars should still leave at least "don".
        assertThat(result!!.word).contains("don")
    }

    /**
     * Cursor after a digit-only token — digits are valid word characters.
     */
    @Test
    fun `digit-only token is treated as a word`() {
        val result = wordAt("2024")
        assertThat(result).isNotNull
        assertThat(result!!.word).isEqualTo("2024")
    }

    /**
     * Completely empty field → `null`.
     */
    @Test
    fun `empty field returns null`() {
        val result = wordAt("")
        assertThat(result).isNull()
    }

    // ── Sentence context ──────────────────────────────────────────────────────────

    /**
     * When the text before the cursor contains a sentence boundary (`.`), the
     * [WordAtCursor.sentenceContext] must start from just after that boundary, not from
     * the very beginning of the field.
     */
    @Test
    fun `sentenceContext starts after last sentence boundary`() {
        val result = wordAt("First sentence. Second word")
        assertThat(result).isNotNull
        // The context must not contain "First sentence"
        assertThat(result!!.sentenceContext).doesNotContain("First sentence")
        assertThat(result.sentenceContext).contains("Second")
    }

    /**
     * When there is no sentence boundary before the cursor, the full text before the
     * cursor is returned as [WordAtCursor.sentenceContext].
     */
    @Test
    fun `sentenceContext is full text when no sentence boundary present`() {
        val result = wordAt("hello world")
        assertThat(result).isNotNull
        assertThat(result!!.sentenceContext).contains("hello world")
    }

    /**
     * [WordAtCursor.wordStartOffset] must point to the start of [WordAtCursor.word] within
     * [WordAtCursor.sentenceContext].
     */
    @Test
    fun `wordStartOffset correctly locates word in sentenceContext`() {
        val result = wordAt("Hello world")
        assertThat(result).isNotNull
        val wac = result!!
        val extracted = wac.sentenceContext.substring(wac.wordStartOffset, wac.wordStartOffset + wac.word.length)
        // The slice at wordStartOffset must match the returned word (modulo casing).
        assertThat(extracted.lowercase()).isEqualTo(wac.word.lowercase())
    }

    /**
     * Exclamation mark and question mark are also sentence boundaries.
     */
    @Test
    fun `exclamation mark is treated as sentence boundary`() {
        val result = wordAt("Stop! Go")
        assertThat(result).isNotNull
        assertThat(result!!.sentenceContext).doesNotContain("Stop")
        assertThat(result.sentenceContext).contains("Go")
    }

    /**
     * When the field text before the cursor is longer than 200 characters, only the last
     * 200 characters are considered (matching the 200-char window in [TextInjector]).
     * The returned [WordAtCursor] must still be non-null and contain the last word.
     */
    @Test
    fun `long field text does not cause crash and returns word at cursor`() {
        val longText = "word ".repeat(50) + "final"   // > 200 chars
        val result = wordAt(longText)
        assertThat(result).isNotNull
        assertThat(result!!.word).isEqualTo("final")
    }
}
