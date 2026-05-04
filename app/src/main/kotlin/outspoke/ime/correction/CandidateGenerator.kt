package dev.brgr.outspoke.ime.correction

import android.util.Log
import dev.brgr.outspoke.ime.correction.CandidateGenerator.Companion.MAX_CANDIDATES
import java.io.File

private const val TAG = "CandidateGenerator"

/**
 * Generates spelling-correction candidates for a garbled ASR word.
 *
 * Strategy:
 * 1. Load the frequency-sorted word list from a [File] on internal storage.
 * 2. Build a phonetic index: each entry is keyed by its primary phonetic code
 *    (Kölner Phonetik for DE words; Double Metaphone primary for EN words) so that
 *    words sounding alike are grouped together.
 * 3. On [getCandidates]:
 *    a. Compute phonetic code of the query word.
 *    b. Collect all words sharing that code (phonetic neighbours).
 *    c. Supplement with all dictionary words within Damerau-Levenshtein distance ≤ 2
 *       (scanning only candidates with ≤ 3 character length difference to bound the scan
 *       to at most ~5-10 k words in the worst case on a 100k dict).
 *    d. Return the union — up to [MAX_CANDIDATES] entries, ranked by corpus log-frequency.
 *
 * Loaded lazily on first use from a background thread; [isReady] indicates when ready.
 */
class CandidateGenerator(
    private val dictFile: File,
    private val language: String,   // "de" or "en"
) {

    companion object {
        private const val MAX_CANDIDATES = 50
        private const val MAX_EDIT_DISTANCE = 2
    }

    // word → log10(frequency/total)
    private val dictionary = ArrayList<Pair<String, Float>>(100_000)

    // phonetic code → list of indices into [dictionary]
    private val phoneticIndex = HashMap<String, MutableList<Int>>(60_000)

    @Volatile
    var isReady = false
        private set

    /**
     * Loads the dictionary from the file on internal storage.
     * Must be called from a background thread.
     * Safe to call multiple times — subsequent calls are no-ops once loaded.
     */
    fun load() {
        if (isReady) return
        if (!dictFile.exists()) {
            Log.w(TAG, "[$language] Dict file not found: ${dictFile.absolutePath}")
            return
        }
        try {
            dictFile.bufferedReader(Charsets.UTF_8).use { reader ->
                var idx = 0
                reader.forEachLine { line ->
                    val tab = line.indexOf('\t')
                    if (tab > 0) {
                        val word = line.substring(0, tab)
                        val lp = line.substring(tab + 1).toFloatOrNull() ?: -5f
                        dictionary.add(word to lp)
                        val code = phoneticCode(word)
                        phoneticIndex.getOrPut(code) { ArrayList(4) }.add(idx)
                        idx++
                    }
                }
            }
            isReady = true
            Log.d(TAG, "[$language] Loaded ${dictionary.size} words, ${phoneticIndex.size} phonetic buckets")
        } catch (e: Exception) {
            Log.e(TAG, "[$language] Failed to load ${dictFile.absolutePath}", e)
        }
    }

    /**
     * Returns up to [MAX_CANDIDATES] candidate words for [word], sorted by descending
     * corpus frequency (i.e. most common first, least edit-distance-like last).
     *
     * Returns empty list if the dictionary is not yet loaded.
     */
    fun getCandidates(word: String): List<String> {
        if (!isReady || word.length < 2) return emptyList()
        return try {
            queryCandidates(word)
        } catch (e: Exception) {
            Log.e(TAG, "getCandidates failed for \"$word\"", e)
            emptyList()
        }
    }

    private fun queryCandidates(word: String): List<String> {
        val query = word.lowercase()
        val qCode = phoneticCode(query)
        val qLen = query.length

        val seen = HashSet<String>(MAX_CANDIDATES * 2)
        val candidates = ArrayList<Pair<String, Float>>(MAX_CANDIDATES)  // word, freq

        // 1. Phonetic neighbours
        phoneticIndex[qCode]?.forEach { idx ->
            val (w, lp) = dictionary[idx]
            if (seen.add(w)) candidates.add(w to lp)
        }

        // 2. Edit-distance sweep — only words within ±3 chars in length
        val lenLo = (qLen - MAX_EDIT_DISTANCE).coerceAtLeast(2)
        val lenHi = qLen + MAX_EDIT_DISTANCE

        for ((w, lp) in dictionary) {
            if (w.length !in lenLo..lenHi) continue
            if (seen.contains(w)) continue
            val d = EditDistance.distance(query, w, earlyExitThreshold = MAX_EDIT_DISTANCE)
            if (d <= MAX_EDIT_DISTANCE) {
                seen.add(w)
                candidates.add(w to lp)
            }
        }

        // Sort by frequency descending (log-prob, higher = more frequent)
        candidates.sortByDescending { it.second }

        return candidates.take(MAX_CANDIDATES).map { it.first }
    }

    private fun phoneticCode(word: String): String = when (language) {
        "de" -> KolnerPhonetik.encode(word)
        else -> DoubleMetaphone.encode(word).primary
    }.ifEmpty { word.take(2).uppercase() }
}
