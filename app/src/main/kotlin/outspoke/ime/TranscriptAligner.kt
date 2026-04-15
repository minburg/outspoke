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
     * Computes Levenshtein edit distance between two strings using two rolling rows (O(n) space).
     */
    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..b.length) {
                curr[j] = if (a[i - 1] == b[j - 1]) prev[j - 1]
                          else 1 + minOf(prev[j], curr[j - 1], prev[j - 1])
            }
            val tmp = prev; prev = curr; curr = tmp
        }
        return prev[b.length]
    }

    /**
     * Returns true when two word tokens should be treated as the same word during overlap
     * detection. Words are first normalized via [normalizeWord]. Exact matches always pass.
     * For longer words a small number of character edits is also accepted, covering cases
     * where the model refines a word slightly across strides (e.g. "schreibt" vs "schreibe").
     *
     * Allowed edits by normalized length:
     *  - 0-3 chars: exact match only (prevents "und"/"ins", "der"/"die" false positives)
     *  - 4-5 chars: 1 edit
     *  - 6+ chars: 2 edits
     */
    internal fun wordsMatch(a: String, b: String): Boolean {
        val na = a.normalizeWord()
        val nb = b.normalizeWord()
        if (na == nb) return true
        val maxEdits = when {
            minOf(na.length, nb.length) <= 3 -> 0
            minOf(na.length, nb.length) <= 5 -> 1
            else -> 2
        }
        return maxEdits > 0 && levenshtein(na, nb) <= maxEdits
    }

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
     * Word comparison uses [wordsMatch], which allows 1-2 character edits for longer
     * words so that minor model refinements (e.g. "schreibt" vs "schreibe") do not
     * break overlap detection.
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
            committed.indices.all { i -> wordsMatch(committed[i], fresh[i]) }
        ) {
            return fresh.drop(committed.size)
        }

        // Layer 2 - Model drifted: find the longest suffix of committed that is a prefix of fresh.
        // Require overlap >= 2 words to avoid false-positive matches on common single
        // words ("nicht", "das", "ich") that appear frequently in German - a single-word
        // coincidence after an audio trim would corrupt the alignment and cascade into
        // repeated "complete alignment failure" commits.  The interior-scan fallback
        // below also uses >= 2 for the same reason.
        val maxOverlap = minOf(committed.size, fresh.size)
        for (overlap in maxOverlap downTo 2) {
            val committedTail = committed.takeLast(overlap)
            val freshHead = fresh.take(overlap)
            if (committedTail.indices.all { i -> wordsMatch(committedTail[i], freshHead[i]) }) {
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
                if (tail.indices.all { i -> wordsMatch(tail[i], fresh[startPos + i]) }) {
                    return fresh.drop(startPos + overlapLen)
                }
            }
        }

        // No alignment found - the transcript has completely diverged.
        // Return empty so we do not corrupt the already-committed text.
        return emptyList()
    }
}

