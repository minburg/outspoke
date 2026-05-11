package dev.brgr.outspoke.ime.correction

import android.util.Log
import dev.brgr.outspoke.ime.correction.WordCorrector.Companion.TOP_K
import java.io.File

private const val TAG = "WordCorrector"

/**
 * Orchestrates the full correction pipeline for a single garbled ASR word.
 *
 * Loaded from files on internal storage (downloaded by [SuggestionFileDownloader]).
 */
class WordCorrector(dictFile: File, lmFile: File, private val language: String) {

    companion object {
        private const val TOP_K = 5
        private const val LM_WEIGHT = 0.7f
    }

    private val candidateGen = CandidateGenerator(dictFile, language)
    private val lm = ArpaLanguageModel(lmFile, language)

    @Volatile
    var isReady = false
        private set

    /**
     * Loads both the dictionary and the language model from internal storage.
     * Must be called from a background thread; blocks until both are loaded.
     */
    fun load() {
        candidateGen.load()
        lm.load()
        isReady = candidateGen.isReady && lm.isReady
        if (isReady) Log.d(TAG, "[$language] WordCorrector ready")
    }

    /**
     * Returns up to [TOP_K] correction candidates for [word] in [leftContext], ranked
     * by combined frequency + language-model score.
     */
    fun correct(word: String, leftContext: List<String>): List<String> {
        if (!isReady || word.length < 2) return emptyList()
        return try {
            runCorrection(word, leftContext)
        } catch (e: Exception) {
            Log.e(TAG, "correct(\"$word\") failed unexpectedly", e)
            emptyList()
        }
    }

    private fun runCorrection(word: String, leftContext: List<String>): List<String> {
        val query = word.lowercase()
        val candidates = candidateGen.getCandidates(query)
        if (candidates.isEmpty()) return emptyList()

        val total = candidates.size.toFloat()

        val scored = candidates.mapIndexed { rank, candidate ->
            val freqScore = 1f - (rank / total)
            val lmScore = lm.scoreInContext(candidate, leftContext).let { lp ->
                ((lp + 5f) / 5f).coerceIn(0f, 1f)
            }
            val combined = LM_WEIGHT * lmScore + (1f - LM_WEIGHT) * freqScore
            candidate to combined
        }

        return scored
            .sortedByDescending { it.second }
            .take(TOP_K)
            .map { it.first }
            .also { Log.d(TAG, "correct(\"$word\", ctx=$leftContext) → $it") }
    }
}
