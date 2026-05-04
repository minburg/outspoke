package dev.brgr.outspoke.ime.correction

import android.util.Log
import dev.brgr.outspoke.ime.correction.ArpaLanguageModel.Companion.EOS
import dev.brgr.outspoke.ime.correction.ArpaLanguageModel.Companion.SOS
import java.io.File

private const val TAG = "ArpaLanguageModel"

/**
 * Pure-Kotlin reader and scorer for ARPA-format n-gram language models (up to trigrams).
 *
 * Reads from a [File] on internal storage (downloaded by [SuggestionFileDownloader]).
 */
class ArpaLanguageModel(
    private val lmFile: File,
    private val language: String,   // "de" or "en"
) {

    // Unigram: word → (log10_prob, log10_backoff)
    private val unigrams = HashMap<String, FloatArray>(50_000)

    // Bigram: "w1 w2" → (log10_prob, log10_backoff)
    private val bigrams = HashMap<String, FloatArray>(300_000)

    @Volatile
    var isReady = false
        private set

    companion object {
        private const val UNK_LOG_PROB = -4f
        private const val UNK_BACKOFF = 0f
        private const val SOS = "<s>"
        private const val EOS = "</s>"
    }

    /**
     * Loads the ARPA file from internal storage.  Must be called from a background thread.
     * Safe to call multiple times; subsequent calls are no-ops once loaded.
     */
    fun load() {
        if (isReady) return
        if (!lmFile.exists()) {
            Log.w(TAG, "[$language] LM file not found: ${lmFile.absolutePath}")
            return
        }
        try {
            var section = 0  // 0=header, 1=unigrams, 2=bigrams, 3=done
            lmFile.bufferedReader(Charsets.UTF_8).use { reader ->
                reader.forEachLine { rawLine ->
                    val line = rawLine.trim()
                    when {
                        line.isEmpty() || line.startsWith("\\data\\") -> Unit
                        line.startsWith("ngram ") -> Unit
                        line == "\\1-grams:" -> section = 1
                        line == "\\2-grams:" -> section = 2
                        line.startsWith("\\") -> section = 3
                        section == 1 -> parseUnigram(line)
                        section == 2 -> parseBigram(line)
                    }
                }
            }
            isReady = true
            Log.d(TAG, "[$language] Loaded ${unigrams.size} unigrams, ${bigrams.size} bigrams")
        } catch (e: Exception) {
            Log.e(TAG, "[$language] Failed to load ${lmFile.absolutePath}", e)
        }
    }

    private fun parseUnigram(line: String) {
        val parts = line.split('\t')
        if (parts.size < 2) return
        val lp = parts[0].toFloatOrNull() ?: return
        val word = parts[1]
        val bow = if (parts.size >= 3) parts[2].toFloatOrNull() ?: 0f else 0f
        unigrams[word] = floatArrayOf(lp, bow)
    }

    private fun parseBigram(line: String) {
        val parts = line.split('\t')
        if (parts.size < 2) return
        val lp = parts[0].toFloatOrNull() ?: return
        val key = parts[1]  // "w1 w2" stored as single tab-field
        val bow = if (parts.size >= 3) parts[2].toFloatOrNull() ?: 0f else 0f
        bigrams[key] = floatArrayOf(lp, bow)
    }

    /**
     * Scores [candidate] in the context of [leftContext] (the 1-2 words preceding the
     * candidate in the text).
     *
     * Returns a log10 probability; higher is better.  Unknown words receive a small
     * constant penalty so they are still comparable.
     *
     * The context window is the last 1 word from [leftContext] (bigram model).
     */
    fun scoreInContext(candidate: String, leftContext: List<String>): Float {
        if (!isReady) return 0f
        return try {
            val prev = leftContext.lastOrNull() ?: SOS
            val biKey = "$prev $candidate"
            val bi = bigrams[biKey]
            if (bi != null) return bi[0]
            val prevEntry = unigrams[prev]
            val bow = prevEntry?.get(1) ?: UNK_BACKOFF
            val uni = unigrams[candidate]
            val uniLp = uni?.get(0) ?: UNK_LOG_PROB
            bow + uniLp
        } catch (e: Exception) {
            Log.e(TAG, "scoreInContext failed", e)
            UNK_LOG_PROB
        }
    }

    /**
     * Returns the sum of log10 bigram probabilities for the token sequence
     * [SOS] + [words] + [EOS], normalised by sequence length.
     */
    fun scoreSequence(words: List<String>): Float {
        if (!isReady || words.isEmpty()) return UNK_LOG_PROB
        return try {
            var total = 0f
            val tokens = listOf(SOS) + words + listOf(EOS)
            for (i in 1 until tokens.size) {
                total += scoreInContext(tokens[i], tokens.subList(0, i))
            }
            total / words.size
        } catch (e: Exception) {
            Log.e(TAG, "scoreSequence failed", e)
            UNK_LOG_PROB
        }
    }
}
