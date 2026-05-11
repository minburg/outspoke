package dev.brgr.outspoke.inference

/**
 * Applies grammar corrections to a transcript string.
 *
 * Implementations should be idempotent and thread-safe. The [correct] function must
 * return the original [text] unchanged if no corrections are applicable — never `null`
 * or an empty string unless the input itself was empty.
 *
 * Lifecycle: callers are responsible for calling [close] when the corrector is no
 * longer needed. Implementations that hold no resources may leave [close] as a no-op.
 */
interface GrammarCorrector {
    /**
     * Applies grammar corrections to [text].
     *
     * @param text     The post-processed transcript text to correct.
     * @param language BCP-47 language tag (e.g. `"en"`, `"de"`). Implementations that
     *                 only support a single language should treat unsupported tags as a
     *                 no-op rather than throwing.
     * @return The corrected text, or [text] unchanged if no corrections were needed.
     */
    fun correct(text: String, language: String = "en"): String

    /**
     * Releases any resources held by this corrector (e.g. JLanguageTool instances,
     * loaded rule sets). Safe to call multiple times.
     */
    fun close()
}

/**
 * No-op [GrammarCorrector] used when grammar correction is disabled or unavailable.
 *
 * Returns every input string unchanged and holds no resources.
 *
 * **Why LanguageToolCorrector is not yet implemented:**
 *
 * LanguageTool Core (`org.languagetool:languagetool-core:6.4`, JAR = ~1.7 MB) has a
 * large transitive dependency tree that blows well past the 20 MB APK-size budget:
 *
 *   | Artifact                          | JAR size  |
 *   |-----------------------------------|-----------|
 *   | languagetool-core                 |  ~1.7 MB  |
 *   | language-en                       |  ~2.7 MB  |
 *   | grpc-netty-shaded                 |  ~9.7 MB  |
 *   | guava (android)                   |  ~2.9 MB  |
 *   | lucene-core                       |  ~3.8 MB  |
 *   | (+ morfologik, jackson, commons…) |  ~5 MB+   |
 *   | **Total (rough minimum)**         | **~26 MB**|
 *
 * ProGuard/R8 shrinking helps, but LanguageTool actively uses reflection, resource
 * loading, and dynamic rule registration, so the shrunk footprint remains 15–20 MB.
 * The `language-de` module alone adds another ~20 MB.
 *
 * **TODO**: Once the APK-size budget for grammar correction is confirmed, implement
 * `LanguageToolCorrector` as follows:
 *
 * ```kotlin
 * class LanguageToolCorrector : GrammarCorrector {
 *     // Lazily initialize JLanguageTool per language to avoid startup cost.
 *     private val tools = ConcurrentHashMap<String, JLanguageTool>()
 *
 *     override fun correct(text: String, language: String): String {
 *         val lang = Languages.getLanguageForShortCode(language) ?: return text
 *         val tool = tools.getOrPut(language) { JLanguageTool(lang) }
 *         val matches = tool.check(text)
 *         return TextChecker.applyMatches(text, matches)
 *     }
 *
 *     override fun close() {
 *         tools.values.forEach { it.shutdown() }
 *         tools.clear()
 *     }
 * }
 * ```
 *
 * Add these dependencies to `app/build.gradle.kts` (verify sizes first):
 * ```
 * implementation("org.languagetool:languagetool-core:6.4")
 * implementation("org.languagetool:language-en:6.4")
 * ```
 * and wire `LanguageToolCorrector()` into [dev.brgr.outspoke.inference.InferenceService]
 * in place of [NoOpGrammarCorrector].
 */
object NoOpGrammarCorrector : GrammarCorrector {
    override fun correct(text: String, language: String): String = text
    override fun close() = Unit
}
