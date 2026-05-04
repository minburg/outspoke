package dev.brgr.outspoke.ime

import android.content.Context
import android.util.Log
import dev.brgr.outspoke.ime.correction.SuggestionFileManager
import dev.brgr.outspoke.ime.correction.SuggestionLanguage
import dev.brgr.outspoke.ime.correction.WordCorrector
import kotlinx.coroutines.*

private const val TAG = "WordSuggestionProvider"

/**
 * Provides spelling-correction suggestions for the word currently under the cursor,
 * using an on-device phonetic + edit-distance + n-gram pipeline.
 *
 * Works entirely offline — no system spell-checker, no permissions, no network.
 *
 * Only languages explicitly enabled via [setActiveLanguages] are loaded into memory.
 * When [activeLanguages] is empty the provider is a no-op (no suggestions, no background
 * work).  The feature can be turned off at the call site by never calling [open] or by
 * passing an empty set.
 *
 * Usage:
 * 1. Call [setActiveLanguages] whenever the preference changes (can be before [open]).
 * 2. Call [open] when the IME input view is created (idempotent).
 * 3. Set [onSuggestions] to receive results delivered on the **main thread**.
 * 4. Call [getSuggestions] with the word + its sentence context whenever the cursor
 *    word changes.
 * 5. Call [close] when the IME is destroyed.
 */
class WordSuggestionProvider(private val context: Context) {

    /** Invoked on the **main thread** with the top-5 correction suggestions. */
    var onSuggestions: (List<String>) -> Unit = {}

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // All possible correctors, keyed by BCP-47 tag. Created lazily per language.
    private val correctors = HashMap<String, WordCorrector>()

    // Tags that are currently selected by the user.
    @Volatile
    private var activeLanguages: Set<String> = emptySet()

    @Volatile
    private var opened = false

    /**
     * Updates the set of languages the corrector searches.
     * Newly added languages are loaded in the background on the next [getSuggestions] call
     * (or immediately if [open] has already been called).
     */
    fun setActiveLanguages(tags: Set<String>) {
        val filtered = tags.filter { it in SuggestionLanguage.TAG_SET }.toSet()
        activeLanguages = filtered
        if (opened) loadMissingLanguages(filtered)
    }

    /**
     * Kicks off background loading of all currently active language models.
     * Safe to call multiple times — already-loaded languages are skipped.
     */
    fun open() {
        if (opened) return
        opened = true
        loadMissingLanguages(activeLanguages)
    }

    /**
     * Requests correction candidates for [word] in [sentenceContext] asynchronously.
     * Results are delivered to [onSuggestions] on the main thread.
     *
     * No-op when [activeLanguages] is empty.
     */
    fun getSuggestions(word: String, sentenceContext: String = "") {
        val langs = activeLanguages
        if (langs.isEmpty() || word.isBlank()) return
        Log.d(TAG, "getSuggestions(\"$word\", langs=$langs)")

        scope.launch {
            val leftContext = extractLeftContext(sentenceContext, word)
            val merged = HashMap<String, Float>()   // candidate → best score

            for (tag in langs) {
                val corrector = correctors[tag] ?: continue
                if (!corrector.isReady) continue
                // correct() returns candidates already ranked; assign a score 1/(rank+1)
                // and keep the best score across languages so duplicates are deduplicated.
                corrector.correct(word, leftContext).forEachIndexed { rank, candidate ->
                    val score = 1f / (rank + 1)
                    merged[candidate] = maxOf(merged.getOrDefault(candidate, 0f), score)
                }
            }

            val suggestions = merged.entries
                .sortedByDescending { it.value }
                .take(5)
                .map { it.key }

            withContext(Dispatchers.Main) { onSuggestions(suggestions) }
        }
    }

    /** Cancels background coroutines and releases resources. */
    fun close() {
        scope.cancel()
        correctors.clear()
        opened = false
        Log.d(TAG, "closed")
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun loadMissingLanguages(tags: Set<String>) {
        for (tag in tags) {
            if (correctors.containsKey(tag)) continue
            val corrector = WordCorrector(
                dictFile = SuggestionFileManager.getDictFile(context, tag),
                lmFile = SuggestionFileManager.getLmFile(context, tag),
                language = tag,
            )
            correctors[tag] = corrector
            scope.launch {
                Log.d(TAG, "Loading corrector for [$tag]…")
                corrector.load()
                Log.d(TAG, "Corrector [$tag] ready")
            }
        }
    }

    /**
     * Extracts the 1-2 words immediately preceding [word] in [sentenceContext]
     * as the left-context window for bigram scoring.
     */
    private fun extractLeftContext(sentenceContext: String, word: String): List<String> {
        if (sentenceContext.isBlank()) return emptyList()
        val trimmed = sentenceContext.trimEnd()
        val withoutWord = if (trimmed.endsWith(word, ignoreCase = true))
            trimmed.dropLast(word.length).trimEnd()
        else trimmed
        return withoutWord.split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .map { it.lowercase().trimEnd { c -> !c.isLetter() } }
            .filter { it.isNotBlank() }
            .takeLast(2)
    }
}
