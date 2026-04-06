package dev.brgr.outspoke.ime

import android.os.Bundle
import android.view.KeyEvent
import android.view.inputmethod.CompletionInfo
import android.view.inputmethod.CorrectionInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection

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

    /** Full text visible in the field (committed portion + active composing span). */
    val fieldText: String get() = buffer.toString() + composing

    /** Only the permanently committed portion (composing span excluded). */
    val committedText: String get() = buffer.toString()

    /**
     * Commits [text] into the field, replacing the current composing span if one is active.
     *
     * In Android semantics, [commitText] always clears the composing region first, then
     * appends the supplied text.  In this fake the composing content is not part of
     * [buffer], so clearing it is just a matter of resetting the composing tracking fields
     * before appending.
     */
    override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
        composing = ""
        composingStart = -1
        buffer.append(text)
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
        return true
    }

    /**
     * Returns the last [n] characters of [fieldText] (committed + composing), matching
     * the Android contract where the cursor is always considered to be at the end.
     */
    override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence {
        val full = fieldText
        return full.takeLast(minOf(n, full.length))
    }

    /** Cursor is always at the end in this fake, so there is never any text after it. */
    override fun getTextAfterCursor(n: Int, flags: Int): CharSequence = ""

    /** No selection is ever active in this fake. */
    override fun getSelectedText(flags: Int): CharSequence? = null

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
        return true
    }

    override fun getCursorCapsMode(reqModes: Int): Int = 0
    override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? = null
    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean = false
    override fun setComposingRegion(start: Int, end: Int): Boolean = false
    override fun commitCompletion(text: CompletionInfo?): Boolean = false
    override fun commitCorrection(correctionInfo: CorrectionInfo?): Boolean = false
    override fun setSelection(start: Int, end: Int): Boolean = false
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
        inputContentInfo: android.view.inputmethod.InputContentInfo,
        flags: Int,
        opts: Bundle?,
    ): Boolean = false
}


