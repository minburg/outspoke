package dev.brgr.outspoke.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection

/**
 * How many words at the tail of the transcript remain editable (composing text).
 * Words earlier than this threshold are committed permanently so that model drift on
 * long utterances never rewrites already-settled text.
 *
 * 4 words covers the worst-case rewrite window observed empirically (e.g.
 * "Ian Ting" → "i am eating", a 3-token correction spanning 3 positions).
 */
private const val MUTABLE_WORD_COUNT = 4

/**
 * Thin wrapper around [InputConnection] that writes transcribed text into the focused
 * text field, handling the composing-text lifecycle correctly.
 *
 * **Composing text** (shown underlined, not yet committed) is used only when the editor
 * is a text-class field and is not a password variant. All other field types receive
 * direct [InputConnection.commitText] calls with no intermediate composing span.
 *
 * **Sliding freeze window**: while recording, the last [MUTABLE_WORD_COUNT] words of the
 * partial transcript stay as composing text. Any word that leaves that tail window is
 * permanently committed via [InputConnection.commitText] so that model drift on later
 * strides cannot rewrite already-settled text. [commitFinal] only commits the words that
 * haven't been frozen yet.
 *
 * One instance is created per [android.inputmethodservice.InputMethodService.onStartInput]
 * call and discarded in [android.inputmethodservice.InputMethodService.onFinishInput].
 */
class TextInjector(
    private val inputConnection: InputConnection,
    editorInfo: EditorInfo,
) {

    /**
     * True when the target editor supports Android's composing-text protocol.
     *
     * Password fields are excluded — most password `EditText` implementations ignore
     * `setComposingText` or render it as plain text anyway, and showing partial speech
     * there would be a security concern.
     */
    val supportsComposing: Boolean = run {
        val cls = editorInfo.inputType and InputType.TYPE_MASK_CLASS
        val variation = editorInfo.inputType and InputType.TYPE_MASK_VARIATION
        val isTextClass = cls == InputType.TYPE_CLASS_TEXT
        val isPassword = variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
        isTextClass && !isPassword
    }

    /** Last partial text sent, used to skip no-op `setComposingText` calls. */
    private var lastPartial: String = ""

    /**
     * Number of words permanently committed to the field during the current recording
     * session.  Words beyond this frozen prefix are still composing (mutable).
     * Reset to 0 by [commitFinal] and [clear].
     */
    private var committedWordCount: Int = 0

    /**
     * Show [text] as provisional composing text (underlined, uncommitted).
     *
     * If the editor does not support composing text this is a no-op — the full
     * transcript will be delivered via [commitFinal] when the utterance ends.
     * Duplicate calls with the same [text] value are skipped to reduce binder overhead.
     *
     * **Sliding freeze window**: words that move beyond the last [MUTABLE_WORD_COUNT]
     * positions in the transcript are permanently committed via [InputConnection.commitText]
     * so that model drift on later inference strides can never rewrite already-settled
     * text.  Only the tail window remains as underlined composing text.
     */
    fun setPartial(text: String) {
        if (!supportsComposing || text == lastPartial) return
        lastPartial = text

        val words = text.splitToWords()
        if (words.isEmpty()) return

        // Words at index [0, newFrozenCount) are now stable enough to commit permanently.
        val newFrozenCount = maxOf(0, words.size - MUTABLE_WORD_COUNT)

        if (newFrozenCount > committedWordCount) {
            // Commit the delta: words that just moved out of the mutable window.
            // commitText() replaces the current composing span with the provided text;
            // the composing span is then re-established below with just the mutable tail.
            val textToCommit = words
                .subList(committedWordCount, newFrozenCount)
                .joinToString(" ") + " "
            inputConnection.commitText(textToCommit, 1)
            committedWordCount = newFrozenCount
        }

        // Re-establish the composing span over the mutable tail only.
        val mutableText = words.drop(committedWordCount).joinToString(" ")
        if (mutableText.isNotEmpty()) {
            inputConnection.setComposingText(mutableText, 1)
        }
    }

    /**
     * Commit [text] as final, confirmed text.
     *
     * Only the words not yet frozen (beyond [committedWordCount]) are written — the
     * permanently committed prefix is left untouched.  [commitText] replaces the
     * current composing span (the mutable tail) with the remaining final words, so
     * the engine's authoritative final pass can still refine those last few words.
     *
     * [committedWordCount] is reset to 0 so the injector is ready for the next
     * recording session within the same input field.
     */
    fun commitFinal(text: String) {
        lastPartial = ""
        val savedCommittedCount = committedWordCount
        committedWordCount = 0  // ready for next recording session

        val remainingText = text.splitToWords().drop(savedCommittedCount).joinToString(" ")
        // commitText replaces the composing span (mutable tail) with remainingText.
        // If the Final result is shorter than what was already frozen, remainingText is
        // empty and this effectively clears the composing span without adding new text.
        inputConnection.commitText(remainingText, 1)
        inputConnection.finishComposingText()
    }

    /**
     * Insert a newline character at the current cursor position, replacing any
     * active selection.
     */
    fun sendNewline() {
        lastPartial = ""
        inputConnection.commitText("\n", 1)
    }

    /**
     * Cancel any pending composing text without committing it.
     *
     * Call this inside [android.inputmethodservice.InputMethodService.onFinishInput] so
     * that no stale underlined text is left behind when the keyboard is dismissed or the
     * user switches to a different app mid-dictation.
     *
     * [committedWordCount] is reset so the injector is clean for the next session.
     */
    fun clear() {
        lastPartial = ""
        committedWordCount = 0
        inputConnection.finishComposingText()
    }

    /**
     * Returns `true` when the editor currently has a non-collapsed text selection.
     * Used by delete helpers to decide whether to delete the selection or fall back
     * to the regular cursor-based deletion.
     */
    private fun hasActiveSelection(): Boolean =
        inputConnection.getSelectedText(0)?.isNotEmpty() == true

    /**
     * Delete the active selection if one exists, otherwise delete the single
     * character immediately before the cursor.
     */
    fun deleteChar() {
        if (hasActiveSelection()) {
            // Replace the selection with nothing — equivalent to the Delete key
            // behaviour on desktop editors when text is selected.
            inputConnection.commitText("", 1)
            return
        }
        inputConnection.deleteSurroundingText(1, 0)
    }

    /**
     * Delete the active selection if one exists, otherwise delete backward until
     * (but not including) the last space before the cursor, effectively removing
     * the last word.  Trailing whitespace is skipped first, then word characters
     * are consumed, so "Hello world " → "Hello ".
     */
    fun deleteWord() {
        if (hasActiveSelection()) {
            inputConnection.commitText("", 1)
            return
        }
        val before = inputConnection.getTextBeforeCursor(200, 0) ?: return
        if (before.isEmpty()) return

        var idx = before.length
        // Skip trailing whitespace
        while (idx > 0 && before[idx - 1].isWhitespace()) idx--
        if (idx == 0) {
            // Only whitespace — delete one character
            inputConnection.deleteSurroundingText(1, 0)
            return
        }
        // Skip word characters back to the previous boundary
        while (idx > 0 && !before[idx - 1].isWhitespace()) idx--

        // delete from word-start to original cursor (includes any trailing spaces)
        val deleteCount = before.length - idx
        if (deleteCount > 0) {
            inputConnection.deleteSurroundingText(deleteCount, 0)
        }
    }

    /**
     * Delete all text in the editor (before and after the cursor).
     * Uses a large but finite window to avoid binder-size issues.
     */
    fun deleteAll() {
        val before = inputConnection.getTextBeforeCursor(100_000, 0)?.length ?: 0
        val after  = inputConnection.getTextAfterCursor(100_000, 0)?.length ?: 0
        if (before > 0 || after > 0) {
            inputConnection.deleteSurroundingText(before, after)
        }
    }

    /**
     * Splits a transcript string into individual word tokens by whitespace, discarding
     * empty segments caused by leading/trailing spaces or multiple consecutive spaces.
     */
    private fun String.splitToWords(): List<String> =
        trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
}

