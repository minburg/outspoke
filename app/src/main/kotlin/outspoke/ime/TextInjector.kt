package dev.brgr.outspoke.ime

import android.text.InputType
import android.util.Log
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import dev.brgr.outspoke.ime.TranscriptAligner.findNewContent
import dev.brgr.outspoke.ime.TranscriptAligner.normalizeWord
import dev.brgr.outspoke.ime.TranscriptAligner.splitToWords

private const val TAG = "TextInjector"

/**
 * How many words at the tail of the transcript remain editable (composing text).
 * Words earlier than this threshold are committed permanently so that model drift on
 * long utterances never rewrites already-settled text.
 *
 * Raised from 4 → 6 to avoid premature freezing of early-stride words that the model
 * has not yet had enough context to stabilise.  With 4, a 5-word first-stride transcript
 * already freezes 1 word; with 6, the first couple of short strides keep everything
 * composing (mutable) and a correction in the next stride can overwrite any error via
 * `commitText`, before it is locked in.  6 words ≈ 2.4 s at 150 wpm - a wide enough
 * window to cover the worst-case model rewrite observed empirically.
 */
private const val MUTABLE_WORD_COUNT = 6

/**
 * After a stable-chunk trim, [TextInjector.resetAfterTrim] re-reads the actual field content
 * and keeps at most this many tail words as the new [committedWords] alignment anchor.
 *
 * The retained tail (~4 s of audio at a generous 3 words/second) is almost always present
 * verbatim in the model's first post-trim partial, so the suffix-overlap algorithm in
 * [TextInjector.setPartial] finds the correct boundary immediately - without any recovery layer.
 *
 * Using field content (rather than the previously tracked [committedWords]) as the source
 * eliminates accumulated alignment drift: the field is always the single source of truth.
 */
private const val TAIL_COMMIT_WORDS = 12

/**
 * Maximum characters read from the field via [InputConnection.getTextBeforeCursor] when
 * attempting field-based realignment as a fallback.  800 chars ≈ 150 words, enough to
 * cover the longest realistic single-recording session.
 */
private const val FIELD_SCAN_CHARS = 800

/**
 * Number of words to drop from the *tail* of the composing span when [TextInjector.resetAfterTrim]
 * commits it to the field.
 *
 * Composing words are inherently provisional - the model has only a single stride of audio
 * context when it produces them and frequently revises the last 1–2 tokens in the very next
 * stride after the audio window shifts.  If those tail words are permanently committed
 * (via [InputConnection.finishComposingText]) and the model then changes them, the field
 * contains a word that no longer appears in any subsequent partial.  [findNewContent]'s
 * overlap search - which requires a minimum of 2 matching words - cannot bridge that gap,
 * and every subsequent stride triggers an alignment recovery.  In a long recording this
 * cascades into dozens of recoveries (observed: 21R / 10T) and causes all content generated
 * after the bad commit to be silently dropped.
 *
 * By committing only the first `(composingWords.size - TRIM_COMPOSING_DROP_TAIL)` words
 * and discarding the tail, we guarantee that:
 * 1. At least [TRIM_COMPOSING_DROP_TAIL] stable words remain as the re-anchor tail in
 *    [committedWords] after the field re-read.
 * 2. The model's next post-trim partial almost always contains those dropped words (re-emitted
 *    from the trimmed window), so [findNewContent] finds a 2-word overlap immediately.
 * 3. No permanent mismatch enters the field.
 *
 * The dropped words are *not* lost - the model re-transcribes them from the overlapping audio
 * context that InferenceRepository keeps after the trim.
 */
private const val TRIM_COMPOSING_DROP_TAIL = 2

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
     * which permanently commits whatever is in the composing span - without going through
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

    /**
     * Number of times an alignment recovery layer (field-scan or composing-commit) fired
     * during this recording session.  Exposed to [KeyboardViewModel] for the diagnostic
     * overlay - a non-zero value means the model drifted and the injector had to recover.
     * Reset by [clear].
     */
    var alignmentRecoveryCount: Int = 0
        private set

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

    /**
     * Show [text] as provisional composing text (underlined, uncommitted).
     *
     * If the editor does not support composing text this is a no-op - the full
     * transcript will be delivered via [commitFinal] when the utterance ends.
     * Duplicate calls with the same [text] value are skipped to reduce binder overhead.
     *
     * **Sliding freeze window + drift alignment**: new content relative to [committedWords]
     * is determined via the suffix-overlap algorithm. Words that push the tail beyond
     * [MUTABLE_WORD_COUNT] are permanently committed; the mutable tail stays as composing
     * text.
     *
     * **Alignment failure recovery (layer 1 - field scan)**: when the tracked committed
     * prefix diverges from the model's output (e.g. after a stable-chunk audio trim), the
     * injector reads the actual field content via [InputConnection.getTextBeforeCursor] and
     * re-anchors [committedWords] against it.  This ensures sentences that appear in the
     * UI preview are never silently dropped due to a stale tracking state.
     *
     * **Alignment failure recovery (layer 2 - composing commit)**: if even the field-based
     * scan finds no overlap, the composing span is committed as-is and those words are
     * absorbed into [committedWords] so the next successful partial does not re-commit them.
     */
    fun setPartial(text: String) {
        if (!supportsComposing || text == lastPartial) return
        lastPartial = text

        val words = text.splitToWords()
        if (words.isEmpty()) return

        // Inject separator space before first content of this session.
        ensureSessionSeparator()

        // Guard: when nothing has been permanently committed yet (committedWords is empty)
        // but the composing span already has content, use composingWords as the alignment
        // anchor.  Without this, a post-trim partial that starts mid-sentence causes
        // findNewContent([], fresh) to return ALL of fresh and setComposingText to replace
        // the entire composing span, silently erasing its beginning.
        // Example: composing="Ich möchte jetzt einen Satz sagen.", next post-trim partial=
        // "Einen Satz sagen." → without this guard "Ich möchte jetzt" is permanently lost.
        if (committedWords.isEmpty() && composingWords.isNotEmpty()) {
            val relativeNew = findNewContent(composingWords, words)
            if (relativeNew.isNotEmpty()) {
                // Fresh genuinely extends beyond the composing span - include existing
                // composing words so they are not overwritten by setComposingText.
                Log.d(TAG, "[COMPOSING_ANCHOR] extending composing span +${relativeNew.size} word(s)")
                val allNewDisplay = composingWords + relativeNew
                val freezeCount = maxOf(0, allNewDisplay.size - MUTABLE_WORD_COUNT)
                if (freezeCount > 0) {
                    val toFreeze = allNewDisplay.take(freezeCount)
                    inputConnection.commitText(toFreeze.joinToString(" ") + " ", 1)
                    committedWords.addAll(toFreeze)
                }
                composingWords = allNewDisplay.drop(freezeCount)
                if (composingWords.isNotEmpty()) {
                    inputConnection.setComposingText(composingWords.joinToString(" "), 1)
                } else {
                    composingWords = emptyList()
                    inputConnection.finishComposingText()
                }
                return
            }
            // relativeNew is empty - either:
            //   (a) fresh is a sub-sequence of composingWords (model drifted backward or
            //       plateaued after the trim) → keep the composing span unchanged.
            //   (b) fresh is completely unrelated (model correction/rewrite) → fall through
            //       to the normal path so the composing span can be replaced.
            // Heuristic: if fresh starts with a word that already exists in composingWords
            // the model is still within the current composing span → drift/plateau → skip.
            if (composingWords.any { it.normalizeWord() == words.first().normalizeWord() }) {
                Log.d(TAG, "[COMPOSING_ANCHOR] drift/plateau - \"${words.first()}\" already in composing, no update")
                return
            }
            // Fall through: model produced unrelated content - allow composing span replacement.
        }

        // Determine new content relative to what's already frozen.
        val newContent = findNewContent(committedWords, words)

        if (newContent.isEmpty()) {
            // Primary alignment failed (tracked committedWords diverged from the model's output).
            // Recovery layer 1: try aligning against the actual field content.
            val fieldChars = inputConnection.getTextBeforeCursor(FIELD_SCAN_CHARS, 0)?.toString() ?: ""
            if (fieldChars.isNotEmpty()) {
                val fieldWords = fieldChars.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                val fieldNewContent = findNewContent(fieldWords, words)
                if (fieldNewContent.isNotEmpty()) {
                    // Successfully realigned against the real field content.
                    // Reset committedWords to match and inject only the new tail.
                    Log.d(TAG, "[REALIGN] field-based recovery: re-anchored, +${fieldNewContent.size} words")
                    alignmentRecoveryCount++

                    // Save the active composing words BEFORE clearing - setComposingText
                    // REPLACES the entire composing region in the editor, so without this
                    // the words already showing in the composing span would be silently
                    // erased.  We prepend them to the new content so the field stays intact.
                    // (2026-04-06 regression: "und der ist nicht besonders lang." dropped.)
                    val savedComposing = composingWords

                    committedWords = fieldWords.toMutableList()
                    composingWords = emptyList()

                    // Full display content = the words that were already composing (still
                    // visible in the editor region that setComposingText will overwrite) PLUS
                    // the genuinely new words discovered by the field-content scan.
                    val allNewDisplay = savedComposing + fieldNewContent

                    val freezeCount = maxOf(0, allNewDisplay.size - MUTABLE_WORD_COUNT)
                    if (freezeCount > 0) {
                        val toFreeze = allNewDisplay.take(freezeCount)
                        inputConnection.commitText(toFreeze.joinToString(" ") + " ", 1)
                        // savedComposing words are already tracked inside committedWords
                        // (they came from fieldWords).  Only add the fieldNewContent words
                        // that were frozen to avoid double-counting.
                        val composingFrozenCount = minOf(freezeCount, savedComposing.size)
                        committedWords.addAll(fieldNewContent.take(freezeCount - composingFrozenCount))
                    }
                    composingWords = allNewDisplay.drop(freezeCount)
                    if (composingWords.isNotEmpty()) {
                        inputConnection.setComposingText(composingWords.joinToString(" "), 1)
                    } else {
                        composingWords = emptyList()
                        inputConnection.finishComposingText()
                    }
                    return
                }
            }

            // Recovery layer 2: complete alignment failure - commit composing span as-is
            // and re-anchor committedWords from the actual field content.  Previous versions
            // blindly appended composingWords to committedWords, which corrupted tracking and
            // caused cascading alignment failures on every subsequent stride (Bug 5A).
            // Reading the field content keeps committedWords synchronized with reality.
            Log.w(TAG, "[REALIGN] complete alignment failure (committed=${committedWords.size}, fresh=${words.size}) - committing composing span")
            alignmentRecoveryCount++
            val hadComposing = composingWords.isNotEmpty()
            composingWords = emptyList()
            inputConnection.finishComposingText()
            // Ensure the next composing span doesn't run directly into the committed text.
            if (hadComposing) {
                val preceding = inputConnection.getTextBeforeCursor(1, 0)
                if (!preceding.isNullOrEmpty() && !preceding.last().isWhitespace()) {
                    inputConnection.commitText(" ", 1)
                }
            }
            // Re-anchor committedWords from the actual field content to break the cascade.
            val reanchorChars = inputConnection.getTextBeforeCursor(FIELD_SCAN_CHARS, 0)?.toString() ?: ""
            committedWords = if (reanchorChars.isNotBlank()) {
                reanchorChars.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.toMutableList()
            } else {
                mutableListOf()
            }
            Log.d(TAG, "[REALIGN:FIELD_REANCHOR] committedWords re-anchored → ${committedWords.size} words from field")
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
            // All new content was frozen - nothing remains for the composing span.
            composingWords = emptyList()
            inputConnection.finishComposingText()
        }
    }

    /**
     * Commit [text] as final, confirmed text.
     *
     * Only the words not yet frozen (beyond [committedWords]) are written - the
     * permanently committed prefix is left untouched. The suffix-overlap algorithm
     * correctly finds the new tail even when the final inference result has drifted
     * and starts from a different word than the frozen prefix.
     *
     * **Field-content fallback**: when the primary alignment against [committedWords] fails
     * (e.g. because the model's attention drifted across multiple audio-window trims),
     * the injector reads the actual field content via [InputConnection.getTextBeforeCursor]
     * and retries alignment against that ground truth.  This prevents the "preview shows
     * correct text but the field stays empty" failure where the final inference result
     * was simply unreachable via the stale [committedWords].
     *
     * When both alignment attempts fail the composing span is committed as-is via
     * [InputConnection.finishComposingText] rather than being erased, so at minimum the
     * last visible partial stays in the field instead of being silently deleted.
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
        val savedComposing = composingWords          // Saved before reset - needed for composing-anchor fallback
        val wasSessionStarted = sessionStarted

        // Reset for the next recording session.
        committedWords = mutableListOf()
        composingWords = emptyList()
        sessionStarted = false

        val finalWords = text.splitToWords()
        val remaining = findNewContent(savedCommitted, finalWords)

        when {
            remaining.isNotEmpty() -> {
                val remainingText = remaining.joinToString(" ")
                if (!wasSessionStarted && remainingText.isNotEmpty() && supportsComposing) {
                    // No partials were shown; inject the separator here if needed.
                    val preceding = inputConnection.getTextBeforeCursor(1, 0)
                    val prefix = if (!preceding.isNullOrEmpty() && !preceding.last().isWhitespace()) " " else ""
                    inputConnection.commitText(prefix + remainingText, 1)
                } else {
                    inputConnection.commitText(remainingText, 1)
                }
            }

            finalWords.isNotEmpty() -> {
                // Primary alignment against tracked committed words failed.

                // Composing-anchor fallback: when nothing was permanently frozen
                // (savedCommitted is empty) but the composing span had words, use the
                // composing span as the alignment anchor instead of letting
                // findNewContent([], final) return ALL of final and then
                // commitText(final) replace the composing span - which would lose any
                // words that were visible in the spinner but not present in a
                // post-trim final inference window.
                //
                // Example failure without this fix:
                //   composing = "ich habe heute gut"  (4-word window, partial showed full sentence)
                //   final     = "heute gut"           (trimmed 2s window, only tail)
                //   Without fix: commitText("heute gut") replaces "ich habe heute gut" → "ich habe" lost.
                //   With fix:    final is a suffix of composing → finishComposingText() keeps all 4 words.
                if (savedCommitted.isEmpty() && savedComposing.isNotEmpty()) {
                    val composingRemaining = findNewContent(savedComposing, finalWords)

                    if (composingRemaining.isNotEmpty()) {
                        // Final text genuinely extends beyond the composing span.
                        // Commit composing + new words together so that commitText
                        // (which replaces the composing span) includes everything.
                        val fullText = (savedComposing + composingRemaining).joinToString(" ")
                        Log.d(TAG, "[COMMIT_FINAL] composing-anchor: +${composingRemaining.size} word(s) beyond composing")
                        inputConnection.commitText(fullText, 1)
                        inputConnection.finishComposingText()
                        return
                    }

                    // composingRemaining is empty.  Two sub-cases:
                    //   (a) final is fully covered by a tail of composing (trim case) → keep composing
                    //   (b) final is completely unrelated → fall through to field recovery
                    //
                    // Distinguish by checking if the last finalWords.size words of composing
                    // match all of finalWords (case a), vs no overlap at all (case b).
                    val finalCoveredByComposing = finalWords.isNotEmpty() &&
                        savedComposing.size >= finalWords.size &&
                        savedComposing.takeLast(finalWords.size).zip(finalWords).all { (a, b) ->
                            a.normalizeWord() == b.normalizeWord()
                        }

                    if (finalCoveredByComposing) {
                        // Case (a): the trimmed final window is a suffix of what was already
                        // composing - preserve the full composing span so no words are lost.
                        Log.d(TAG, "[COMMIT_FINAL] composing-anchor: final covered by composing tail → finishComposing")
                        inputConnection.finishComposingText()
                        return
                    }
                    // Case (b): completely unrelated - fall through to field-content recovery.
                    Log.d(TAG, "[COMMIT_FINAL] composing-anchor: final unrelated to composing, falling through to field recovery")
                }

                // Recovery layer 1: align against the actual field content so the final
                // result is never silently dropped due to a stale tracking state.
                val fieldChars = inputConnection.getTextBeforeCursor(FIELD_SCAN_CHARS, 0)?.toString() ?: ""
                val fieldWords = fieldChars.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                val fieldRemaining = findNewContent(fieldWords, finalWords)
                if (fieldRemaining.isNotEmpty()) {
                    Log.d(TAG, "[COMMIT_FINAL] field-based recovery: +${fieldRemaining.size} new words")
                    inputConnection.commitText(fieldRemaining.joinToString(" "), 1)
                } else {
                    // Recovery layer 2: complete alignment failure - preserve the composing
                    // span via finishComposingText() instead of erasing it with commitText("").
                    // This ensures whatever the user saw in the UI preview stays in the field.
                    Log.w(TAG, "[COMMIT_FINAL] complete alignment failure - preserving composing span")
                    // Fall through to the finishComposingText() call below.
                }
            }
        }

        // Always close the composing span.  When commitText() was called above it already
        // cleared any composing region; when we skipped it (recovery layers), this call
        // commits whatever partial text was showing so nothing is erased.
        inputConnection.finishComposingText()
    }

    /**
     * Called when [InferenceRepository] has trimmed the rolling audio window.
     *
     * **Three-step reset** that ensures the composing zone never loses words during a trim:
     *
     * 1. **Partially freeze composing span** - the *stable* leading words of the composing
     *    span are committed permanently while the last [TRIM_COMPOSING_DROP_TAIL] words are
     *    silently discarded.  [InputConnection.commitText] replaces the entire composing span,
     *    so the dropped tail is never written to the field.  Without this partial commit the
     *    old behaviour called [InputConnection.finishComposingText] which committed *all*
     *    composing words - including the uncertain tail that the model frequently revises on
     *    the very next stride.  When the model corrects those tail words the field is left
     *    with a token ("just", "cut.", …) that appears in no subsequent partial, permanently
     *    breaking [findNewContent]'s 2-word overlap requirement and cascading into dozens of
     *    alignment recoveries for the rest of the session (observed: 21R / 10T, stress test).
     *    The dropped words are not lost - InferenceRepository retains a short overlapping
     *    audio context after every trim so the model re-emits them in the first post-trim
     *    partial, and [setPartial] then anchors them correctly.
     *
     * 2. **Clear [lastPartial]** - after the composing span is committed the text the editor
     *    shows is no longer composing; the next [setPartial] must re-establish it even if the
     *    first post-trim partial text happens to equal [lastPartial] (which would otherwise
     *    be swallowed by the duplicate guard).
     *
     * 3. **Re-anchor [committedWords] from the field** - instead of trusting the tracked list
     *    (which may have accumulated alignment drift over multiple strides), read the actual
     *    text before the cursor as the ground truth.  The last [TAIL_COMMIT_WORDS] words of
     *    that content become the new alignment anchor, guaranteeing the suffix-overlap
     *    algorithm in [setPartial] can find the correct boundary without recovery.
     */
    fun resetAfterTrim() {
        // Step 1: commit the stable portion of the composing span, dropping the uncertain tail.
        //
        // commitText() replaces the entire composing span with the provided text.  By passing
        // only the first (size - TRIM_COMPOSING_DROP_TAIL) words we effectively erase the tail
        // from the field before it can be permanently locked in.  The trailing " " ensures the
        // next composing span doesn't run directly into the committed text.
        if (composingWords.isNotEmpty()) {
            val safeCount = maxOf(0, composingWords.size - TRIM_COMPOSING_DROP_TAIL)
            if (safeCount > 0) {
                // Commit the stable leading words (trailing space included) and erase the tail.
                val safeText = composingWords.take(safeCount).joinToString(" ") + " "
                inputConnection.commitText(safeText, 1)
            } else {
                // Fewer composing words than the drop count - nothing safe to keep.
                // Erase the composing span entirely, then guard against a missing separator space.
                inputConnection.commitText("", 1)
                val preceding = inputConnection.getTextBeforeCursor(1, 0)
                if (!preceding.isNullOrEmpty() && !preceding.last().isWhitespace()) {
                    inputConnection.commitText(" ", 1)
                }
            }
            Log.d(TAG, "[TRIM_RESET] froze $safeCount/${composingWords.size} composing word(s) before trim reset (dropped last $TRIM_COMPOSING_DROP_TAIL)")
        }
        composingWords = emptyList()

        // Step 2: allow the next setPartial to re-establish the composing span unconditionally.
        lastPartial = ""

        // Step 3: re-read field content as the authoritative committed baseline.
        val fieldChars = inputConnection.getTextBeforeCursor(FIELD_SCAN_CHARS, 0)?.toString() ?: ""
        committedWords = if (fieldChars.isNotBlank()) {
            fieldChars.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                .takeLast(TAIL_COMMIT_WORDS)
                .toMutableList()
        } else {
            mutableListOf()
        }
        Log.d(TAG, "[TRIM_RESET] committedWords re-anchored from field → ${committedWords.size} tail word(s)")
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
        alignmentRecoveryCount = 0
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

    // splitToWords() is now in TranscriptAligner - imported at the top.
}
