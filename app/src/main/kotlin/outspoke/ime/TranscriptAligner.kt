package dev.brgr.outspoke.ime

/**
 * Pure-function alignment utilities extracted from [TextInjector].
 *
 * All methods are stateless and side-effect-free, making them trivially testable
 * without an [android.view.inputmethod.InputConnection] or any Android dependency.
 *
 * The core algorithm ([findNewContent]) determines which words in a fresh transcript
 * are genuinely *new* compared to the words already committed to the text field.
 * It tolerates Parakeet's attention drift (dropped prefix, leading junk tokens after
 * an audio-window trim) by performing a three-layer overlap search:
 *
 *  1. **Full prefix match** - the happy path where `fresh` starts exactly where
 *     `committed` left off.
 *  2. **Suffix-prefix overlap** - the model dropped the beginning of the utterance;
 *     the longest suffix of `committed` that is also a prefix of `fresh` is used as
 *     the anchor.  Requires ≥ 2 matching words to avoid single-word false positives
 *     on common tokens ("nicht", "und", "ich").
 *  3. **Interior scan** - after an audio trim the model may emit 1-3 garbage tokens
 *     before settling into text that overlaps with `committed`.  This layer scans
 *     every interior position in `fresh` looking for the committed tail.  Also
 *     requires ≥ 2 matching words.
 */
object TranscriptAligner {

    /**
     * Strips non-alphanumeric boundary characters from a single word token for
     * comparison purposes only - the original word is kept in all output.
     * e.g. `"again."` → `"again"`, `"I'm"` → `"i'm"`, `"And"` → `"and"`
     */
    fun String.normalizeWord(): String = lowercase().trim { !it.isLetterOrDigit() }

    /**
     * Splits a transcript string into individual word tokens by whitespace, discarding
     * empty segments caused by leading/trailing spaces or multiple consecutive spaces.
     */
    fun String.splitToWords(): List<String> =
        trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

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
    fun findNewContent(committed: List<String>, fresh: List<String>): List<String> {
        if (committed.isEmpty()) return fresh

        // Layer 1 - Happy path: fresh starts right where committed left off.
        if (fresh.size >= committed.size &&
            committed.indices.all { i ->
                committed[i].normalizeWord() == fresh[i].normalizeWord()
            }
        ) {
            return fresh.drop(committed.size)
        }

        // Layer 2 - Model drifted: find the longest suffix of committed that is a prefix of fresh.
        // Require overlap ≥ 2 words to avoid false-positive matches on common single
        // words ("nicht", "das", "ich") that appear frequently in German - a single-word
        // coincidence after an audio trim would corrupt the alignment and cascade into
        // repeated "complete alignment failure" commits.  The interior-scan fallback
        // below also uses ≥ 2 for the same reason.
        val maxOverlap = minOf(committed.size, fresh.size)
        for (overlap in maxOverlap downTo 2) {
            val committedTail = committed.takeLast(overlap)
            val freshHead = fresh.take(overlap)
            if (committedTail.indices.all { i ->
                    committedTail[i].normalizeWord() == freshHead[i].normalizeWord()
                }) {
                return fresh.drop(overlap)
            }
        }

        // Layer 3 - Interior-scan fallback: after an audio trim the model may start with
        // 1-3 garbage tokens (e.g. "Angabe" instead of "Spracheingabe") before settling
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

        // No alignment found - the transcript has completely diverged.
        // Return empty so we do not corrupt the already-committed text.
        return emptyList()
    }
}

