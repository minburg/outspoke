package dev.brgr.outspoke.inference

import dev.brgr.outspoke.inference.NumberNormaliser.DE_ONES


/**
 * Converts number-word sequences in a word list to their digit representations.
 *
 * This is a pure, stateless object with no Android or I/O dependencies — safe to
 * unit-test on the JVM without instrumentation.
 *
 * Supported languages: `"en"` (English, default), `"de"` (German).
 *
 * **Algorithm (greedy left-to-right):**
 * 1. Scan the word list left to right.
 * 2. On encountering a number word, begin accumulating a "run".
 * 3. Continue while the next word is also a number word or a connector ("and"/"und").
 * 4. When the run ends, drop trailing connectors, parse the tokens into a [Long] and
 *    replace the run with its digit string.
 * 5. Return the modified word list.
 */
internal object NumberNormaliser {

    // ── English tables ────────────────────────────────────────────────────────

    private val EN_ONES: Map<String, Long> = mapOf(
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4,
        "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9,
        "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13,
        "fourteen" to 14, "fifteen" to 15, "sixteen" to 16, "seventeen" to 17,
        "eighteen" to 18, "nineteen" to 19,
    )

    private val EN_TENS: Map<String, Long> = mapOf(
        "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50,
        "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90,
    )

    private val EN_SCALES: Map<String, Long> = mapOf(
        "hundred" to 100, "thousand" to 1_000,
        "million" to 1_000_000, "billion" to 1_000_000_000,
    )

    /**
     * English ordinals: word → (cardinal value, ordinal suffix to append after digits).
     * E.g. "third" → (3, "rd") → "3rd".
     */
    private val EN_ORDINALS: Map<String, Pair<Long, String>> = mapOf(
        "first" to (1L to "st"), "second" to (2L to "nd"), "third" to (3L to "rd"),
        "fourth" to (4L to "th"), "fifth" to (5L to "th"), "sixth" to (6L to "th"),
        "seventh" to (7L to "th"), "eighth" to (8L to "th"), "ninth" to (9L to "th"),
        "tenth" to (10L to "th"), "twentieth" to (20L to "th"), "thirtieth" to (30L to "th"),
        "fortieth" to (40L to "th"), "fiftieth" to (50L to "th"),
        "sixtieth" to (60L to "th"), "seventieth" to (70L to "th"),
        "eightieth" to (80L to "th"), "ninetieth" to (90L to "th"),
        "hundredth" to (100L to "th"), "thousandth" to (1_000L to "th"),
        "millionth" to (1_000_000L to "th"), "billionth" to (1_000_000_000L to "th"),
    )

    private val EN_CONNECTORS = setOf("and", "a")

    // All EN number words (for fast membership checks).
    private val EN_ALL_WORDS: Set<String> =
        EN_ONES.keys + EN_TENS.keys + EN_SCALES.keys + EN_ORDINALS.keys

    // ── German tables ─────────────────────────────────────────────────────────

    private val DE_ONES: Map<String, Long> = mapOf(
        // Cardinal forms
        "null" to 0, "ein" to 1, "eins" to 1, "zwei" to 2, "drei" to 3,
        "vier" to 4, "fünf" to 5, "sechs" to 6, "sieben" to 7, "acht" to 8,
        "neun" to 9, "zehn" to 10, "elf" to 11, "zwölf" to 12,
        "dreizehn" to 13, "vierzehn" to 14, "fünfzehn" to 15,
        "sechzehn" to 16, "siebzehn" to 17, "achtzehn" to 18, "neunzehn" to 19,
        // Ordinal stems (unique — not identical to any cardinal form above)
        "erst" to 1, "zweit" to 2, "dritt" to 3, "viert" to 4, "fünft" to 5,
        "sechst" to 6, "siebt" to 7, "neunt" to 9, "zehnt" to 10,
        "elft" to 11, "zwölft" to 12,
    )

    /** Cardinal-only keys in [DE_ONES]: words that must NOT be treated as ordinal bases. */
    private val DE_CARDINAL_ONLY: Set<String> = setOf(
        "null", "ein", "eins", "zwei", "drei", "vier", "fünf", "sechs", "sieben", "acht",
        "neun", "zehn", "elf", "zwölf",
        "dreizehn", "vierzehn", "fünfzehn", "sechzehn", "siebzehn", "achtzehn", "neunzehn",
    )

    private val DE_TENS: Map<String, Long> = mapOf(
        "zwanzig" to 20, "dreißig" to 30, "vierzig" to 40, "fünfzig" to 50,
        "sechzig" to 60, "siebzig" to 70, "achtzig" to 80, "neunzig" to 90,
    )

    private val DE_SCALES: Map<String, Long> = mapOf(
        "hundert" to 100, "tausend" to 1_000,
        "million" to 1_000_000, "milliarde" to 1_000_000_000,
    )

    private val DE_CONNECTORS = setOf("und")

    /**
     * Regex matching German compound tens: <ones>und<tens>, e.g. "einundzwanzig" (21).
     */
    private val DE_COMPOUND_TENS_RE = Regex(
        """^(ein|zwei|drei|vier|fünf|sechs|sieben|acht|neun)und(zwanzig|dreißig|vierzig|fünfzig|sechzig|siebzig|achtzig|neunzig)$""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * German ordinal suffixes. To detect an ordinal we strip a suffix and verify that the
     * remaining base form is a recognised number word.
     * Ordered longest-first so "sten" is tried before "te".
     */
    private val DE_ORDINAL_SUFFIXES = listOf(
        "sten", "stem", "ster", "stes", "ste",
        "ten", "tem", "ter", "tes", "te",
        "en", "em", "er", "es", "e",
    )

    // All DE bare number words (compounds detected separately via regex).
    private val DE_ALL_WORDS: Set<String> =
        DE_ONES.keys + DE_TENS.keys + DE_SCALES.keys

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Replaces number-word runs in [words] with their digit equivalents.
     *
     * @param words The tokenised word list.  Words may carry punctuation
     *   (e.g. `"twelve,"`) — it is stripped before lookup and reattached to the
     *   resulting digit token.
     * @param language BCP-47 language prefix: `"en"` or `"de"`.  Defaults to `"en"`.
     * @return A new list with number-word runs replaced by digit strings.
     */
    fun normalise(words: List<String>, language: String = "en"): List<String> {
        if (words.isEmpty()) return words
        val isGerman = language.startsWith("de", ignoreCase = true)
        val result = mutableListOf<String>()
        var i = 0

        while (i < words.size) {
            val bare = stripPunctuation(words[i]).lowercase()

            if (isNumberWord(bare, isGerman)) {
                // Accumulate a run of number words + connectors.
                val runOrig = mutableListOf<String>()
                val runBare = mutableListOf<String>()

                runOrig.add(words[i])
                runBare.add(bare)
                i++

                while (i < words.size) {
                    val nextBare = stripPunctuation(words[i]).lowercase()
                    if (isNumberWord(nextBare, isGerman) || isConnector(nextBare, isGerman)) {
                        runOrig.add(words[i])
                        runBare.add(nextBare)
                        i++
                    } else {
                        break
                    }
                }

                // Drop trailing connectors (put them back into the main scan).
                while (runBare.isNotEmpty() && isConnector(runBare.last(), isGerman)) {
                    runOrig.removeAt(runOrig.lastIndex)
                    runBare.removeAt(runBare.lastIndex)
                    i--
                }

                if (runBare.isEmpty()) {
                    // Shouldn't happen — word that triggered the run must be a number word.
                    result.add(words[i - 1])
                    continue
                }

                // Detect ordinal on the last token.
                val ordinalResult = detectOrdinal(runBare, isGerman)
                val isOrdinal = ordinalResult.first
                val ordinalSuffix = ordinalResult.second
                val tokensForParsing = ordinalResult.third

                val value = parseNumberWords(tokensForParsing, isGerman)

                // Re-attach punctuation: leading punct from first original token,
                // trailing punct from last original token.
                val (_, leadPunct, _) = splitPunctuation(runOrig.first())
                val (_, _, trailPunct) = splitPunctuation(runOrig.last())

                val digitStr = if (isOrdinal) {
                    if (isGerman) "$leadPunct$value.$trailPunct"
                    else "$leadPunct$value$ordinalSuffix$trailPunct"
                } else {
                    "$leadPunct$value$trailPunct"
                }

                result.add(digitStr)
            } else {
                result.add(words[i])
                i++
            }
        }

        return result
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    /**
     * Splits [word] into `(bareWord, leadingPunct, trailingPunct)`.
     * Characters that are neither letter nor digit are considered punctuation.
     */
    private fun splitPunctuation(word: String): Triple<String, String, String> {
        if (word.isEmpty()) return Triple("", "", "")
        var start = 0
        var end = word.length
        while (start < end && !word[start].isLetterOrDigit()) start++
        while (end > start && !word[end - 1].isLetterOrDigit()) end--
        return Triple(
            word.substring(start, end),
            word.substring(0, start),
            word.substring(end),
        )
    }

    /** Returns only the bare (non-punctuation) portion of [word], lowercased. */
    private fun stripPunctuation(word: String): String = splitPunctuation(word).first

    private fun isNumberWord(lower: String, isGerman: Boolean): Boolean {
        if (lower.isEmpty()) return false
        return if (isGerman) {
            lower in DE_ALL_WORDS ||
                    DE_COMPOUND_TENS_RE.matches(lower) ||
                    isGermanOrdinal(lower)
        } else {
            lower in EN_ALL_WORDS
        }
    }

    private fun isConnector(lower: String, isGerman: Boolean): Boolean =
        if (isGerman) lower in DE_CONNECTORS else lower in EN_CONNECTORS

    /** Returns true when [lower] looks like a German ordinal (base + suffix). */
    private fun isGermanOrdinal(lower: String): Boolean {
        for (suffix in DE_ORDINAL_SUFFIXES) {
            if (lower.endsWith(suffix) && lower.length > suffix.length) {
                val base = lower.dropLast(suffix.length)
                if (base in DE_CARDINAL_ONLY) continue  // cardinal form, not an ordinal base
                if (base in DE_ALL_WORDS || DE_COMPOUND_TENS_RE.matches(base)) return true
            }
        }
        return false
    }

    /**
     * Checks whether the last word of [runLower] is an ordinal.
     *
     * @return `Triple(isOrdinal, suffixForEN, adjustedTokens)` where [adjustedTokens]
     *   has the ordinal replaced with a synthetic `"__ordinal_N"` token so the parser
     *   can handle the numeric value uniformly.
     */
    private fun detectOrdinal(
        runLower: List<String>,
        isGerman: Boolean,
    ): Triple<Boolean, String, List<String>> {
        if (runLower.isEmpty()) return Triple(false, "", runLower)
        val last = runLower.last()

        if (!isGerman) {
            val entry = EN_ORDINALS[last] ?: return Triple(false, "", runLower)
            val (cardinalValue, suffix) = entry
            val adjusted = runLower.dropLast(1) + listOf("__ordinal_$cardinalValue")
            return Triple(true, suffix, adjusted)
        }

        // German: strip ordinal suffix, check base is a number word (not a cardinal-only form).
        for (suffix in DE_ORDINAL_SUFFIXES) {
            if (last.endsWith(suffix) && last.length > suffix.length) {
                val base = last.dropLast(suffix.length)
                if (base in DE_CARDINAL_ONLY) continue  // e.g. "eine" → "ein" is cardinal, not ordinal
                if (base in DE_ALL_WORDS || DE_COMPOUND_TENS_RE.matches(base)) {
                    val adjusted = runLower.dropLast(1) + listOf(base)
                    return Triple(true, ".", adjusted)
                }
            }
        }
        return Triple(false, "", runLower)
    }

    /**
     * Parses a list of lowercase number-word tokens (connectors included — they are
     * filtered out internally) into a [Long] value.
     */
    private fun parseNumberWords(tokens: List<String>, isGerman: Boolean): Long {
        if (tokens.isEmpty()) return 0L

        // Handle synthetic ordinal shortcut.
        if (tokens.size == 1 && tokens[0].startsWith("__ordinal_")) {
            return tokens[0].removePrefix("__ordinal_").toLongOrNull() ?: 0L
        }

        val filtered = tokens.filter { !isConnector(it, isGerman) }
        return parseChunk(filtered, isGerman)
    }

    /**
     * Recursive descent parser: billions → millions → thousands → hundreds → tens+ones.
     */
    private fun parseChunk(tokens: List<String>, isGerman: Boolean): Long {
        if (tokens.isEmpty()) return 0L

        // Handle synthetic ordinal shortcut (may appear after ordinal detection).
        if (tokens.size == 1 && tokens[0].startsWith("__ordinal_")) {
            return tokens[0].removePrefix("__ordinal_").toLongOrNull() ?: 0L
        }

        val scales: List<Pair<String, Long>> = if (isGerman) {
            listOf(
                "milliarde" to 1_000_000_000L,
                "million" to 1_000_000L,
                "tausend" to 1_000L,
                "hundert" to 100L,
            )
        } else {
            listOf(
                "billion" to 1_000_000_000L,
                "million" to 1_000_000L,
                "thousand" to 1_000L,
                "hundred" to 100L,
            )
        }

        var result = 0L
        var remaining = tokens.toMutableList()

        for ((scaleWord, scaleValue) in scales) {
            val idx = remaining.indexOf(scaleWord)
            if (idx < 0) continue

            val before = remaining.subList(0, idx).toList()
            remaining = remaining.subList(idx + 1, remaining.size).toMutableList()

            val multiplier = if (before.isEmpty()) 1L else parseChunk(before, isGerman)
            result += multiplier * scaleValue
        }

        result += parseTensOnes(remaining, isGerman)
        return result
    }

    /** Parses the tail of a token list that is purely tens + ones. */
    private fun parseTensOnes(tokens: List<String>, isGerman: Boolean): Long {
        if (tokens.isEmpty()) return 0L
        val ones = if (isGerman) DE_ONES else EN_ONES
        val tens = if (isGerman) DE_TENS else EN_TENS

        var result = 0L
        for (token in tokens) {
            if (isConnector(token, isGerman)) continue
            if (token.startsWith("__ordinal_")) {
                result += token.removePrefix("__ordinal_").toLongOrNull() ?: 0L
                continue
            }
            if (isGerman) {
                val m = DE_COMPOUND_TENS_RE.matchEntire(token)
                if (m != null) {
                    val onesVal = DE_ONES[m.groupValues[1].lowercase()] ?: 0L
                    val tensVal = DE_TENS[m.groupValues[2].lowercase()] ?: 0L
                    result += onesVal + tensVal
                    continue
                }
            }
            result += ones[token] ?: tens[token] ?: 0L
        }
        return result
    }
}
