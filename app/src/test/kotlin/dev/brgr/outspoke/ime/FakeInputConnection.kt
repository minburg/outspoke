package dev.brgr.outspoke.ime

import android.os.Bundle
import android.view.KeyEvent
import android.view.inputmethod.*

/**
 * In-memory fake [InputConnection] for unit-testing [TextInjector].
 *
 * Tracks the full field state including the composing span without requiring a real
 * Android editor.  The composing text is stored separately from the committed buffer;
 * [fieldText] exposes their concatenation (matching what a user would see on screen).
 *
 * Only the methods actually called by [TextInjector] have real implementations:
 *   - [commitText]         - replaces the composing span and appends committed text
 *   - [setComposingText]   - replaces the composing region content
 *   - [finishComposingText]- moves the composing span into the permanent buffer
 *   - [getTextBeforeCursor]- returns the tail of [fieldText]
 *   - [getTextAfterCursor] - returns empty (cursor always at end in this fake)
 *   - [getSelectedText]    - returns null (no selection)
 *   - [deleteSurroundingText] - removes characters before the cursor
 *
 * All other [InputConnection] methods are no-op stubs that return false / null / 0.
 */
class FakeInputConnection : InputConnection {

    /** Permanently committed text - never rewritten once written. */
    private val buffer = StringBuilder()

    /** The current composing (underlined) text, or empty string when no span is active. */
    private var composing: String = ""

    /** Buffer offset at which the composing span begins, or -1 when no span is active. */
    private var composingStart: Int = -1

    /**
     * Cursor position within [buffer] (not including composing text).
     * Defaults to end-of-buffer (cursor always at end unless [setSelection] is called).
     */
    private var cursorPos: Int = 0

    /**
     * Start of the active selection (inclusive), or -1 when there is no selection.
     * When a selection is active, [commitText] replaces the selected range.
     */
    private var selStart: Int = -1

    /**
     * End of the active selection (exclusive), or -1 when there is no selection.
     */
    private var selEnd: Int = -1

    /** Full text visible in the field (committed portion + active composing span). */
    val fieldText: String get() = buffer.toString() + composing

    /** Only the permanently committed portion (composing span excluded). */
    val committedText: String get() = buffer.toString()

    /**
     * Commits [text] into the field.
     *
     * If an active selection exists ([selStart] != -1), the selected range is replaced
     * with [text] and the cursor is placed after the inserted text.
     *
     * Otherwise, the current composing span is replaced by [text] (matching Android's
     * [commitText] semantics where the composing region is cleared first).
     */
    override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
        // Replace active selection if one exists.
        if (selStart >= 0 && selEnd >= selStart) {
            val s = selStart.coerceIn(0, buffer.length)
            val e = selEnd.coerceIn(s, buffer.length)
            buffer.replace(s, e, text.toString())
            cursorPos = s + text.length
            selStart = -1
            selEnd = -1
            composing = ""
            composingStart = -1
            return true
        }
        // No selection: clear composing span and append.
        composing = ""
        composingStart = -1
        buffer.append(text)
        cursorPos = buffer.length
        return true
    }

    /**
     * Sets [text] as the new composing (underlined) region.
     *
     * If no composing span is active a new one starts at the current end of [buffer].
     * If a composing span already exists its content is replaced in-place (the start
     * position is unchanged).
     */
    override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
        if (composingStart < 0) composingStart = buffer.length
        composing = text.toString()
        return true
    }

    /**
     * Makes the composing span permanent by moving its content into [buffer].
     * If no composing span is active this is a no-op.
     */
    override fun finishComposingText(): Boolean {
        if (composing.isNotEmpty()) {
            buffer.append(composing)
        }
        composing = ""
        composingStart = -1
        cursorPos = buffer.length
        return true
    }

    /**
     * Returns the last [n] characters before the cursor within [fieldText].
     * When a selection is active the cursor is treated as being at [selStart].
     */
    override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence {
        val full = fieldText
        val cursor = if (selStart >= 0) selStart else full.length
        val from = maxOf(0, cursor - n)
        return full.substring(from, cursor)
    }

    /**
     * Returns up to [n] characters after the cursor within [fieldText].
     * When a selection is active the text after [selEnd] is returned.
     */
    override fun getTextAfterCursor(n: Int, flags: Int): CharSequence {
        val full = fieldText
        val cursor = if (selEnd >= 0) selEnd else full.length
        return full.substring(cursor, minOf(full.length, cursor + n))
    }

    /** No selection is ever active in this fake. */
    override fun getSelectedText(flags: Int): CharSequence? = null

    /**
     * Sets the selection range.  When [start] == [end] this positions the cursor
     * without a visual selection.
     */
    override fun setSelection(start: Int, end: Int): Boolean {
        selStart = start
        selEnd = end
        return true
    }

    /**
     * Deletes [beforeLength] characters immediately before the cursor.
     *
     * The composing span is committed first (matching Android behaviour), then characters
     * are removed from the tail of [buffer].  [afterLength] is ignored because the cursor
     * is always at the end of the field in this fake.
     */
    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        finishComposingText()
        val deleteCount = minOf(beforeLength, buffer.length)
        if (deleteCount > 0) {
            buffer.delete(buffer.length - deleteCount, buffer.length)
        }
        cursorPos = buffer.length
        return true
    }

    override fun getCursorCapsMode(reqModes: Int): Int = 0
    override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? = null
    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean = false
    override fun setComposingRegion(start: Int, end: Int): Boolean = false
    override fun commitCompletion(text: CompletionInfo?): Boolean = false
    override fun commitCorrection(correctionInfo: CorrectionInfo?): Boolean = false
    override fun performEditorAction(editorAction: Int): Boolean = false
    override fun performContextMenuAction(id: Int): Boolean = false
    override fun beginBatchEdit(): Boolean = true
    override fun endBatchEdit(): Boolean = true
    override fun sendKeyEvent(event: KeyEvent?): Boolean = false
    override fun clearMetaKeyStates(states: Int): Boolean = false
    override fun reportFullscreenMode(enabled: Boolean): Boolean = false
    override fun performPrivateCommand(action: String?, data: Bundle?): Boolean = false
    override fun requestCursorUpdates(cursorUpdateMode: Int): Boolean = false
    override fun getHandler(): android.os.Handler? = null
    override fun closeConnection() = Unit
    override fun commitContent(
        inputContentInfo: InputContentInfo,
        flags: Int,
        opts: Bundle?,
    ): Boolean = false
}


