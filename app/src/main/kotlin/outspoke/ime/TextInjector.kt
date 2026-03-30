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
 * **Drift-resilient alignment**: frozen words are tracked as an actual word list, not
 * just a count. When Parakeet's attention drifts and a later partial starts from a
 * different word offset, a suffix-overlap algorithm locates the correct boundary between
 * committed and new content so no words are silently dropped or duplicated.
 *
 * **Session separator**: before injecting the first text of a new recording session the
 * injector checks whether the cursor is immediately preceded by a non-whitespace character.
 * If so, a single space is committed so the new transcript doesn't run together with
 * previously committed text (e.g. `"go.Now I lifted"` → `"go. Now I lifted"`).
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
     * Password fields are excluded - most password `EditText` implementations ignore
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
     * Words permanently committed to the field during the current recording session.
     * Tracked as the actual word list (not just a count) so that the suffix-overlap
     * algorithm can correctly align drifted partial/final transcripts against the
     * committed prefix.
     * Reset by [commitFinal] and [clear].
     */
    private var committedWords: MutableList<String> = mutableListOf()

    /**
     * Words currently sitting in the *composing span* (underlined, not yet permanent).
     *
     * When [findNewContent] returns empty (the model drifted past the committed prefix
     * in a way we cannot reconcile), [setPartial] calls [InputConnection.finishComposingText]
     * which permanently commits whatever is in the composing span — without going through
     * the normal freeze path.  Without this tracker, [committedWords] would be stale and
     * the next successful partial would re-commit those same words, producing visible
     * duplication.  Updating [committedWords] from this list on every forced-finish call
     * keeps the two in sync.
     *
     * Reset whenever the composing span is cleared or fully replaced.
     */
    private var composingWords: List<String> = emptyList()

    /**
     * True once the first text has been injected in the current recording session.
     * Used to ensure a separator space is committed exactly once per session when the
     * preceding character in the field is not already whitespace.
     */
    private var sessionStarted: Boolean = false

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Strips non-alphanumeric boundary characters from a single word token for
     * comparison purposes only — the original word is kept in all output.
     * e.g. `"again."` → `"again"`, `"I'm"` → `"i'm"`, `"And"` → `"and"`
     */
    private fun String.normalizeWord(): String = lowercase().trim { !it.isLetterOrDigit() }

    /**
     * Given the words already committed to the field and the words in the newest
     * transcript, returns only the *new* content that follows the committed prefix.
     *
     * Handles Parakeet context drift: as the rolling audio window grows the model's
     * attention can drop the very beginning of the utterance, producing a partial that
     * starts later than the committed prefix. This function finds the longest suffix of
     * [committed] that is also a prefix of [fresh] (the overlap), then returns everything
     * in [fresh] that comes after that overlap.
     *
     * Examples:
     * ```
     * committed = ["Now","I","lifted","the","record","button"]
     * fresh     = ["the","record","button","and","I'm","pressing"]
     *   → overlap of length 3 ("the","record","button")
     *   → returns ["and","I'm","pressing"]
     *
     * committed = ["Now","I","lifted"]
     * fresh     = ["Now","I","lifted","the","record"]
     *   → simple prefix match (length 3)
     *   → returns ["the","record"]
     * ```
     *
     * Returns an empty list if [fresh] has completely diverged and no overlap is found,
     * preventing corruption of committed text.
     */
    private fun findNewContent(committed: List<String>, fresh: List<String>): List<String> {
        if (committed.isEmpty()) return fresh

        // Happy path: fresh starts right where committed left off.
        if (fresh.size >= committed.size &&
            committed.indices.all { i ->
                committed[i].normalizeWord() == fresh[i].normalizeWord()
            }
        ) {
            return fresh.drop(committed.size)
        }

        // Model drifted: find the longest suffix of committed that is a prefix of fresh.
        val maxOverlap = minOf(committed.size, fresh.size)
        for (overlap in maxOverlap downTo 1) {
            val committedTail = committed.takeLast(overlap)
            val freshHead = fresh.take(overlap)
            if (committedTail.indices.all { i ->
                    committedTail[i].normalizeWord() == freshHead[i].normalizeWord()
                }) {
                return fresh.drop(overlap)
            }
        }

        // No alignment found — the transcript has completely diverged.
        // Return empty so we do not corrupt the already-committed text.

        // Interior-scan fallback: after an audio trim the model may start with 1–3
        // garbage tokens (e.g. "Angabe" instead of "Spracheingabe") before settling
        // into a transcript whose tail overlaps with the committed prefix.  The loop
        // above only checks whether the committed suffix is a *prefix* of fresh (i.e.
        // position 0). Here we scan every interior position in fresh so those leading
        // junk tokens are skipped and the real new content that follows is recovered.
        //
        // Require at least overlapLen=2 to avoid spurious single-word coincidences
        // (common words like "und", "ich", "die" appear frequently in any sentence).
        val maxScanLen = minOf(committed.size, 6)
        for (overlapLen in maxScanLen downTo 2) {
            val tail = committed.takeLast(overlapLen)
            val scanLimit = fresh.size - overlapLen
            for (startPos in 1..scanLimit) {
                if (tail.indices.all { i ->
                        tail[i].normalizeWord() == fresh[startPos + i].normalizeWord()
                    }) {
                    return fresh.drop(startPos + overlapLen)
                }
            }
        }

        return emptyList()
    }

    /**
     * Commits a single space character to the [InputConnection] if the character
     * immediately before the cursor is not already whitespace and we haven't yet
     * injected anything in this recording session.
     *
     * Only fires once per session ([sessionStarted] guards repeated calls) and only
     * for composing-capable editors to avoid inserting spaces in password or number
     * fields.
     */
    private fun ensureSessionSeparator() {
        if (sessionStarted || !supportsComposing) return
        sessionStarted = true
        val preceding = inputConnection.getTextBeforeCursor(1, 0)
        if (!preceding.isNullOrEmpty() && !preceding.last().isWhitespace()) {
            inputConnection.commitText(" ", 1)
        }
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Show [text] as provisional composing text (underlined, uncommitted).
     *
     * If the editor does not support composing text this is a no-op — the full
     * transcript will be delivered via [commitFinal] when the utterance ends.
     * Duplicate calls with the same [text] value are skipped to reduce binder overhead.
     *
     * **Sliding freeze window + drift alignment**: new content relative to [committedWords]
     * is determined via the suffix-overlap algorithm. Words that push the tail beyond
     * [MUTABLE_WORD_COUNT] are permanently committed; the mutable tail stays as composing
     * text.
     *
     * **Alignment failure recovery**: when the model drifts so far that no overlap can be
     * found, the composing span is committed as-is via [InputConnection.finishComposingText]
     * and those words are absorbed into [committedWords] so the next successful partial
     * does **not** re-commit them (which would produce visible duplication).  A trailing
     * space is also inserted so the following composing span does not run directly into
     * the now-committed text (e.g. `"good.how"` → `"good. how"`).
     */
    fun setPartial(text: String) {
        if (!supportsComposing || text == lastPartial) return
        lastPartial = text

        val words = text.splitToWords()
        if (words.isEmpty()) return

        // Inject separator space before first content of this session.
        ensureSessionSeparator()

        // Determine new content relative to what's already frozen.
        val newContent = findNewContent(committedWords, words)

        if (newContent.isEmpty()) {
            // The model drifted past the committed prefix in a way we cannot align.
            // Commit the composing span as-is, and absorb those words into committedWords
            // so the next successful partial does not re-commit them.
            val hadComposing = composingWords.isNotEmpty()
            committedWords.addAll(composingWords)
            composingWords = emptyList()
            inputConnection.finishComposingText()
            // Ensure the next composing span doesn't run directly into the committed text
            // (e.g. prevent "good.how" when "how" is the first word of the next partial).
            if (hadComposing) {
                val preceding = inputConnection.getTextBeforeCursor(1, 0)
                if (!preceding.isNullOrEmpty() && !preceding.last().isWhitespace()) {
                    inputConnection.commitText(" ", 1)
                }
            }
            return
        }

        // Freeze words that move beyond the mutable tail.
        val freezeCount = maxOf(0, newContent.size - MUTABLE_WORD_COUNT)
        if (freezeCount > 0) {
            val toFreeze = newContent.take(freezeCount)
            // commitText replaces the current composing span with the frozen words.
            inputConnection.commitText(toFreeze.joinToString(" ") + " ", 1)
            committedWords.addAll(toFreeze)
        }

        // Re-establish composing span over the mutable tail.
        val mutableTail = newContent.drop(freezeCount)
        if (mutableTail.isNotEmpty()) {
            composingWords = mutableTail
            inputConnection.setComposingText(mutableTail.joinToString(" "), 1)
        } else {
            // All new content was frozen — nothing remains for the composing span.
            composingWords = emptyList()
            inputConnection.finishComposingText()
        }
    }

    /**
     * Commit [text] as final, confirmed text.
     *
     * Only the words not yet frozen (beyond [committedWords]) are written — the
     * permanently committed prefix is left untouched. The suffix-overlap algorithm
     * correctly finds the new tail even when the final inference result has drifted
     * and starts from a different word than the frozen prefix.
     *
     * If no partials were shown at all during this session (e.g. very short recording),
     * the session separator space is injected here before committing the final text.
     *
     * [committedWords] and [sessionStarted] are reset so the injector is ready for the
     * next recording session within the same input field.
     */
    fun commitFinal(text: String) {
        lastPartial = ""
        val savedCommitted = committedWords.toList()
        val wasSessionStarted = sessionStarted

        // Reset for the next recording session.
        committedWords = mutableListOf()
        composingWords = emptyList()
        sessionStarted = false

        val finalWords = text.splitToWords()
        val remaining = findNewContent(savedCommitted, finalWords)
        val remainingText = remaining.joinToString(" ")

        if (!wasSessionStarted && remainingText.isNotEmpty() && supportsComposing) {
            // No partials were shown; inject the separator here if needed.
            val preceding = inputConnection.getTextBeforeCursor(1, 0)
            val prefix = if (!preceding.isNullOrEmpty() && !preceding.last().isWhitespace()) " " else ""
            inputConnection.commitText(prefix + remainingText, 1)
        } else {
            inputConnection.commitText(remainingText, 1)
        }
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
     * [committedWords] and [sessionStarted] are reset so the injector is clean for the
     * next session.
     */
    fun clear() {
        lastPartial = ""
        committedWords = mutableListOf()
        composingWords = emptyList()
        sessionStarted = false
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
            // Replace the selection with nothing - equivalent to the Delete key
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
            // Only whitespace - delete one character
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
        val after = inputConnection.getTextAfterCursor(100_000, 0)?.length ?: 0
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
