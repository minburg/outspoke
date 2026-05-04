package dev.brgr.outspoke.ime.correction

/**
 * Damerau-Levenshtein edit distance: counts insertions, deletions, substitutions,
 * and adjacent transpositions as single operations.
 *
 * Uses the full (unrestricted) algorithm so transpositions of non-adjacent characters
 * are still computed correctly (via the intermediate path).  Time O(n·m), Space O(n·m).
 */
object EditDistance {

    /**
     * Returns the Damerau-Levenshtein distance between [a] and [b].
     * Returns [earlyExitThreshold]+1 without completing the matrix if the minimum
     * possible distance in the current row already exceeds [earlyExitThreshold], which
     * avoids wasted work when only candidates within a fixed radius are wanted.
     *
     * Returns -1 on any unexpected error so callers can treat it as "no match" without crashing.
     */
    fun distance(a: String, b: String, earlyExitThreshold: Int = Int.MAX_VALUE): Int = try {
        distanceInternal(a, b, earlyExitThreshold)
    } catch (e: Exception) {
        earlyExitThreshold + 1
    }

    private fun distanceInternal(a: String, b: String, earlyExitThreshold: Int): Int {
        val n = a.length
        val m = b.length

        if (n == 0) return m
        if (m == 0) return n
        if (n > 32 || m > 32) return earlyExitThreshold + 1

        // d[i][j] = edit distance between a[0..i-1] and b[0..j-1]
        val d = Array(n + 1) { IntArray(m + 1) }
        for (i in 0..n) d[i][0] = i
        for (j in 0..m) d[0][j] = j

        val alphabet = HashMap<Char, Int>()

        for (i in 1..n) {
            var db = 0
            val ai = a[i - 1]

            for (j in 1..m) {
                val i1 = alphabet.getOrDefault(b[j - 1], 0)
                val j1 = db
                val cost = if (ai == b[j - 1]) {
                    db = j; 0
                } else 1

                // Transposition is only valid when both i1 and j1 are positive (a previous
                // occurrence of each character has been seen), otherwise indices go negative.
                val transpositionCost = if (i1 > 0 && j1 > 0) {
                    d[i1 - 1][j1 - 1] + (i - i1 - 1) + 1 + (j - j1 - 1)
                } else {
                    Int.MAX_VALUE
                }

                d[i][j] = minOf(
                    d[i - 1][j - 1] + cost,
                    d[i][j - 1] + 1,
                    d[i - 1][j] + 1,
                    transpositionCost
                )
            }

            alphabet[ai] = i

            if (earlyExitThreshold < Int.MAX_VALUE) {
                val rowMin = (1..m).minOf { d[i][it] }
                if (rowMin > earlyExitThreshold) return earlyExitThreshold + 1
            }
        }

        return d[n][m]
    }
}
