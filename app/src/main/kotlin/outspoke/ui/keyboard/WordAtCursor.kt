package dev.brgr.outspoke.ui.keyboard

/**
 * Describes the word currently under the text cursor.
 *
 * Used by the suggestion bar (Track F.3) to surface spelling and grammar candidates
 * for the word the user's cursor is positioned within.
 *
 * @property word             The word under the cursor, with leading/trailing punctuation
 *                            stripped (e.g. `"hello"` for cursor inside `"hello,"`)
 * @property sentenceContext  The full sentence that contains the word, up to 200 characters
 *                            ending at the cursor position (for multi-word candidate ranking).
 * @property wordStartOffset  Zero-based character offset of [word] from the start of
 *                            [sentenceContext].
 */
data class WordAtCursor(
    val word: String,
    val sentenceContext: String,
    val wordStartOffset: Int,
)
