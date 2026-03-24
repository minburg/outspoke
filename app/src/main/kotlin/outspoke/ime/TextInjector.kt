package dev.brgr.outspoke.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection

/**
 * Thin wrapper around [InputConnection] that writes transcribed text into the focused
 * text field, handling the composing-text lifecycle correctly.
 *
 * **Composing text** (shown underlined, not yet committed) is used only when the editor
 * is a text-class field and is not a password variant. All other field types receive
 * direct [InputConnection.commitText] calls with no intermediate composing span.
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

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Show [text] as provisional composing text (underlined, uncommitted).
     *
     * If the editor does not support composing text this is a no-op — the full
     * transcript will be delivered via [commitFinal] when the utterance ends.
     * Duplicate calls with the same [text] value are skipped to reduce binder overhead.
     */
    fun setPartial(text: String) {
        if (!supportsComposing || text == lastPartial) return
        lastPartial = text
        inputConnection.setComposingText(text, 1)
    }

    /**
     * Commit [text] as final, confirmed text, replacing any composing span.
     *
     * Safe to call even if no composing span is currently set.
     */
    fun commitFinal(text: String) {
        lastPartial = ""
        inputConnection.commitText(text, 1)
        inputConnection.finishComposingText()
    }

    /**
     * Cancel any pending composing text without committing it.
     *
     * Call this inside [android.inputmethodservice.InputMethodService.onFinishInput] so
     * that no stale underlined text is left behind when the keyboard is dismissed or the
     * user switches to a different app mid-dictation.
     */
    fun clear() {
        lastPartial = ""
        inputConnection.finishComposingText()
    }

    // -------------------------------------------------------------------------
    // Deletion helpers
    // -------------------------------------------------------------------------

    /** Delete the single character immediately before the cursor. */
    fun deleteChar() {
        inputConnection.deleteSurroundingText(1, 0)
    }

    /**
     * Delete backward until (but not including) the last space before the cursor,
     * effectively removing the last word.  Trailing whitespace is skipped first,
     * then word characters are consumed, so "Hello world " → "Hello ".
     */
    fun deleteWord() {
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
}

