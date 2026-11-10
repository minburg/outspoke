package dev.brgr.outspoke.inference

import ai.onnxruntime.OnnxTensor
import android.util.Log
import dev.brgr.outspoke.audio.AudioChunk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.takeWhile
import java.util.Locale
import kotlin.collections.ArrayDeque
import kotlin.collections.List
import kotlin.collections.all
import kotlin.collections.copyInto
import kotlin.collections.copyOf
import kotlin.collections.copyOfRange
import kotlin.collections.emptyList
import kotlin.collections.filter
import kotlin.collections.fold
import kotlin.collections.indices
import kotlin.collections.isEmpty
import kotlin.collections.isNotEmpty
import kotlin.collections.joinToString
import kotlin.collections.lastIndex
import kotlin.collections.lastOrNull
import kotlin.collections.listOf
import kotlin.collections.minOf
import kotlin.collections.mutableListOf
import kotlin.collections.setOf
import kotlin.collections.sortedByDescending
import kotlin.collections.take
import kotlin.collections.toList
import kotlin.collections.zip
import kotlin.ranges.coerceAtLeast
import kotlin.ranges.downTo
import kotlin.text.Regex
import kotlin.text.RegexOption
import kotlin.text.StringBuilder
import kotlin.text.contains
import kotlin.text.count
import kotlin.text.dropLast
import kotlin.text.endsWith
import kotlin.text.format
import kotlin.text.isBlank
import kotlin.text.isEmpty
import kotlin.text.isLetter
import kotlin.text.isLetterOrDigit
import kotlin.text.isNotBlank
import kotlin.text.isNotEmpty
import kotlin.text.isWhitespace
import kotlin.text.iterator
import kotlin.text.lastOrNull
import kotlin.text.lowercase
import kotlin.text.none
import kotlin.text.replace
import kotlin.text.split
import kotlin.text.startsWith
import kotlin.text.trim
import kotlin.text.trimEnd
import kotlin.text.uppercaseChar

private const val TAG = "InferenceRepository"
private const val SAMPLE_RATE = 16_000

/**
 * Fraction of non-whitespace, non-punctuation characters that may be outside the allowed
 * script range before the text is classified as a hallucination.
 */
private const val HALLUCINATION_SCRIPT_THRESHOLD = 0.20f

/**
 * Allowed Unicode range for Latin-script languages (Basic Latin through Latin Extended-B).
 * Covers English, German, French, Spanish and all other Latin-script languages including
 * umlauts (ä, ö, ü) and accented characters.
 */
private val LATIN_RANGE = '\u0000'..'\u024F'

/**
 * Allowed Unicode range for Cyrillic-script languages (Basic Cyrillic + Cyrillic Supplement).
 * Covers Russian, Ukrainian, Bulgarian and other Slavic languages written in Cyrillic.
 */
private val CYRILLIC_RANGE = '\u0400'..'\u052F'

/**
 * Allowed Unicode range for Greek script.
 * Covers modern Greek (U+0370–U+03FF) plus Greek Extended (U+1F00–U+1FFF) for polytonic.
 */
private val GREEK_RANGE_1 = '\u0370'..'\u03FF'
private val GREEK_RANGE_2 = '\u1F00'..'\u1FFF'

/**
 * Standard punctuation characters that are always allowed regardless of language.
 * Includes common ASCII punctuation plus a handful of non-ASCII typographic characters
 * used in Latin-script writing (€, „, ", «, »).
 */
private val ALLOWED_PUNCTUATION = setOf(
    '!', '"', '#', '$', '%', '&', '\'', '(', ')', '*', '+', ',', '-', '.', '/',
    ':', ';', '<', '=', '>', '?', '@', '[', '\\', ']', '^', '_', '`', '{', '|', '}', '~',
    '€', '„', '\u201C', '\u201D', '«', '»',
)

private val LEADING_DOTS_RE = Regex("""^\.+\s*""")

/**
 * Collapses any run of two or more consecutive dots anywhere in the text down to a single dot.
 * Previously anchored to end-of-string (`$`) which left mid-text artefacts like "warte.. Also"
 * untouched. Making the match global fixes those mid-sentence double-dots produced by
 * Parakeet's SentencePiece tokenizer when the model is uncertain between words.
 */
private val TRAILING_DOTS_RE = Regex("""\.{2,}""")

/**
 * Matches a leading comma or semicolon (optionally followed by whitespace) at the very
 * start of a transcript.  This artefact appears when Parakeet starts a partial inference
 * mid-sentence - the SentencePiece tokenizer emits a leading `,` to signal "this is a
 * continuation" (e.g. `", jetzt sehr gut aussieht"`).  That comma is syntactically
 * meaningless at the start of an output and, worse, causes cascading TextInjector
 * alignment failures because `",".normalizeWord()` returns `""`, which can never match
 * any real word in a subsequent stride.
 */
private val LEADING_PUNCT_RE = Regex("""^[,;]+\s*""")

/**
 * Matches a sentence-ending punctuation mark followed immediately (no space) by a letter.
 * This catches the case where Parakeet's SentencePiece tokenizer emits a period token with
 * its own `▁` word-boundary marker (so the space-before-punctuation regex eats the space),
 * but the next word token lacks a `▁` marker (so no space is added after the period either),
 * producing "gut.Ich" instead of "gut. Ich".
 *
 * Only matches letters (not digits) so that decimals like "3.14" are unaffected.
 * Covers basic Latin, German umlauts, and common extended Latin characters.
 */
private val MISSING_SENTENCE_SPACE_RE = Regex("""([.!?])(\p{L})""")

/**
 * Strips all leading/trailing non-alphanumeric characters from a single word token
 * for comparison purposes only - the original word is kept in the output.
 * e.g. "Sachen," → "sachen", "kann.." → "kann", "gut!" → "gut"
 */
private fun String.normalizedForComparison(): String =
    lowercase().trim { !it.isLetterOrDigit() }

/**
 * Formats a sample count as a human-readable seconds string for log messages,
 * e.g. `32000.toSec()` → `"2.00s"`.
 */
private fun Int.toSec(): String = "%.2fs".format(Locale.ROOT, this / SAMPLE_RATE.toFloat())

/**
 * Short one-liner representation of a [TranscriptResult] for logcat output.
 * Shows the type name and text/error so both the raw and cleaned versions can
 * be compared on the same log line without truncation.
 */
private fun TranscriptResult.logLabel(): String = when (this) {
    is TranscriptResult.Partial -> "Partial(\"${text}\")"
    is TranscriptResult.Final -> "Final(\"${text}\")"
    is TranscriptResult.Failure -> "Failure(${cause.message})"
    is TranscriptResult.WindowTrimmed -> "WindowTrimmed"
    is TranscriptResult.NoSpeech -> "NoSpeech"
}

/**
 * Longest phrase (in words) considered a model stutter/duplication. Genuine
 * repeated speech of 9+ words is treated as content and kept. Capping the
 * candidate length also keeps [collapseRepeatedPhrases] near-linear in the
 * transcript length (bounded candidate lengths × bounded zip per position), so
 * re-running the cleaning pipeline over the whole utterance on every chunk of a
 * long dictation does not grow cubic and break the streaming path's real-time
 * budget.
 */
private const val MAX_REPEATED_PHRASE_WORDS = 8

/**
 * Collapses consecutive repeated phrases (including single words) that the model
 * hallucinated or duplicated.
 *
 * Works left-to-right: at each position it finds the longest phrase (length 1..
 * [MAX_REPEATED_PHRASE_WORDS]) starting there that is immediately followed by one
 * or more identical copies (case-insensitive, punctuation-stripped comparison via
 * [normalizedForComparison]). All copies beyond the first are dropped. Repetitions
 * of longer phrases are kept (see [MAX_REPEATED_PHRASE_WORDS]).
 *
 * Examples:
 *   "gut gut aus"                                     → "gut aus"
 *   "zwei Sachen zwei Sachen die"                     → "zwei Sachen die"
 *   "ganz gut ganz gut ganz gut aus"                  → "ganz gut aus"
 *   "gut und gut"                                     → "gut und gut"  (non-adjacent - kept)
 */
internal fun String.collapseRepeatedPhrases(): String {
    val words = trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.size < 2) return this

    val result = mutableListOf<String>()
    var i = 0

    while (i < words.size) {
        val remaining = words.size - i
        var foundRepeat = false

        // Try phrase lengths from largest possible down to 1 (single word).
        // Bounded by [MAX_REPEATED_PHRASE_WORDS] so the scan stays near-linear.
        for (len in minOf(MAX_REPEATED_PHRASE_WORDS, remaining / 2) downTo 1) {
            val phrase = words.subList(i, i + len)
            val nextPhrase = words.subList(i + len, i + len * 2)

            val matches = phrase.size == nextPhrase.size &&
                    phrase.zip(nextPhrase).all { (a, b) ->
                        a.normalizedForComparison() == b.normalizedForComparison()
                    }

            if (matches) {
                // Skip all consecutive copies of this phrase, keep only the first.
                var j = i + len
                while (j + len <= words.size) {
                    val copy = words.subList(j, j + len)
                    val isCopy = phrase.zip(copy).all { (a, b) ->
                        a.normalizedForComparison() == b.normalizedForComparison()
                    }
                    if (!isCopy) break
                    j += len
                }
                val total = (j - i) / len
                result.addAll(phrase)
                if (total > 1) {
                    Log.d(TAG, "[DEDUP] phrase ×$total → kept 1")
                }
                i = j
                foundRepeat = true
                break
            }
        }

        if (!foundRepeat) {
            result.add(words[i])
            i++
        }
    }

    return result.joinToString(" ")
}

/**
 * Collapses single words that repeat 3 or more times consecutively.
 * E.g., "no no no" -> "no", but "no no" -> "no no".
 */
internal fun String.collapseStutters(): String {
    val words = this.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.size < 3) return this

    val result = mutableListOf<String>()
    var i = 0

    while (i < words.size) {
        val currentWord = words[i]
        val normCurrent = currentWord.normalizedForComparison()
        if (normCurrent.isEmpty()) {
            result.add(currentWord)
            i++
            continue
        }

        var count = 1
        var j = i + 1
        while (j < words.size) {
            val nextWord = words[j]
            if (nextWord.normalizedForComparison() == normCurrent) {
                count++
                j++
            } else {
                break
            }
        }

        if (count >= 3) {
            // Keep only 1
            result.add(currentWord)
            Log.d(TAG, "[STUTTER] collapsed $count repeats → kept 1")
        } else if (count == 2) {
            // Keep both
            result.add(words[i])
            result.add(words[i + 1])
        } else {
            // Keep 1
            result.add(currentWord)
        }
        i += count
    }
    return result.joinToString(" ")
}

/**
 * Removes language-specific filler words using a word-boundary regex.
 * Also consumes any trailing comma or period attached to the filler.
 */
internal fun String.removeFillerWords(language: String = "en"): String {
    val enFillers = listOf("uh", "um", "uhm", "umm", "uhh", "uhhh", "ah", "hmm", "hm", "mmm", "mm", "mh", "eh", "ehh")

    // German-specific disfluencies only — valid German content words such as "um"
    // (around/at), "ja" (yes), "na" (well), "ne" (no/right?), "halt" (just/stop),
    // "eben" (exactly/just) are intentionally excluded.
    val deFillers = listOf("äh", "ähm", "hm", "hmm", "mh", "ehm")

    val fillers = when {
        language.startsWith("en", ignoreCase = true) -> enFillers
        language.startsWith("de", ignoreCase = true) -> deFillers
        else -> emptyList() // Extension point for additional languages
    }

    if (fillers.isEmpty()) return this

    // Sort longest first so that "ähm" is tried before "äh" and we never get a
    // partial match that leaves a dangling suffix.  Use Unicode-aware word boundaries
    // (lookbehind/lookahead for letter-or-digit) because Java's \b does not consider
    // accented characters (ä, ö, ü, …) as word characters, causing partial matches.
    // The inline flag (?U) enables UNICODE_CHARACTER_CLASS so that \p{L} and \p{N}
    // cover the full Unicode range (including umlauts).
    val sortedFillers = fillers.sortedByDescending { it.length }
    // Android's ICU regex engine does not support the (?U) inline flag for
    // UNICODE_CHARACTER_CLASS. Use explicit character ranges instead:
    //   \u00C0-\u024F covers Latin Extended-A/B (umlauts, accents, etc.)
    // This is sufficient for all Latin-script languages (EN, DE, FR, ES, …).
    val wordChar = "[a-zA-Z0-9\u00C0-\u024F]"
    val regexStr = "(?<!$wordChar)(?:${sortedFillers.joinToString("|")})(?!$wordChar)[,.]?"
    val regex = Regex(regexStr, RegexOption.IGNORE_CASE)

    return this.replace(regex, "").replace(Regex(" {2,}"), " ").trim()
}

/**
 * Words that are unlikely to appear at the end of a complete sentence in English or German.
 * A period immediately following one of these words is treated as a prosodic-pause artefact
 * produced by Parakeet TDT and is removed by [filterSpuriousPeriods].
 */
private val NON_SENTENCE_CLOSING_WORDS = setOf(
    // English conjunctions, prepositions, articles, determiners
    "and", "but", "or", "the", "a", "an", "of", "to", "in", "for", "with", "at", "by",
    "from", "on", "as", "into", "through", "during",
    // German conjunctions, prepositions, articles, determiners
    "und", "aber", "oder", "die", "der", "das", "ein", "eine", "von", "zu", "für",
    "mit", "bei", "durch",
)

/**
 * Removes spurious periods emitted by Parakeet TDT on prosodic pauses within a sentence.
 *
 * A period is considered spurious when **either** of the following is true — and the period
 * is not the final token in the utterance:
 *  1. The word immediately before it is in [NON_SENTENCE_CLOSING_WORDS] (a conjunction,
 *     preposition, article, or determiner that cannot grammatically end a sentence).
 *  2. The sentence segment before that period contains **fewer than 5 words** (a very short
 *     fragment is almost certainly a mid-utterance prosodic pause artefact, not a real
 *     sentence boundary).
 *
 * Word counting resets to 0 only when a period is **kept** (i.e. considered a real sentence
 * boundary). Periods that are removed do not reset the counter — the short-segment check
 * accumulates words across removals so that a run of short spurious segments is caught
 * individually.
 *
 * Must run **before** [applySentenceCapitalization] so that removing spurious periods
 * prevents false capitalisation of the words that follow them.
 */
/**
 * Returns [word] with its first letter lower-cased, unless it is a token that must stay
 * capitalised mid-sentence in a Latin-script language that does not capitalise nouns
 * (e.g. English): the pronoun "I" and its contractions ("I'm", "I'll", …), and all-caps
 * acronyms (≥2 letters, e.g. "NASA", "IBM").
 *
 * Used by [filterSpuriousPeriods] to undo the model's sentence-start capitalisation of the
 * word that followed a spurious period which has just been removed — without this, removing
 * the period strands the capital mid-sentence (e.g. "…Otherwise. The encoder…" → removing
 * the period yields "…Otherwise The encoder…" with a stray capital "The").
 *
 * Conservative guards so it never touches legitimate capitalisation:
 *  - Lone characters (length < 2) are left alone (covers standalone "I", "A", initials).
 *  - All-uppercase words (acronyms) are kept.
 *  - Words beginning with "I" + apostrophe (English pronoun contractions) are kept.
 *  - Already-lowercase / digit / punctuation starts are returned unchanged.
 */
private fun lowercaseFirstIfMidSentence(word: String): String {
    if (word.length < 2) return word
    val first = word[0]
    if (!first.isUpperCase()) return word
    // Acronym: every letter is uppercase (≥2 letters) → keep.
    val letters = word.filter { it.isLetter() }
    if (letters.length >= 2 && letters.all { it.isUpperCase() }) return word
    // English pronoun contraction: "I'm", "I'll", "I'd", "I've", … → keep.
    if (first == 'I' && (word[1] == '\'' || word[1] == '\u2019')) return word
    return first.lowercaseChar() + word.substring(1)
}

/**
 * Languages whose orthography capitalises nouns mid-sentence. For these, the
 * [filterSpuriousPeriods] lowercase-fix is SKIPPED: a capitalised word following a removed
 * spurious period may be a correctly-capitalised noun, and lowercasing it would introduce a
 * real spelling error. Stranded capitals on non-nouns are a minor cosmetic issue and the
 * lesser evil.
 */
private val LANGUAGES_WITH_NOUN_CAPITALISATION = setOf("de")

internal fun String.filterSpuriousPeriods(language: String = "en"): String {
    if (!contains('.')) return this

    // Split into tokens, preserving whitespace attachment by splitting on spaces.
    val words = trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return this

    val result = mutableListOf<String>()
    // Words accumulated since the start of the utterance or since the last kept period.
    var wordCountSinceLastPeriod = 0
    // Whether the next non-period-bearing word's first letter should be lower-cased because
    // the immediately preceding spurious period was removed (Step 3). Only applies to
    // languages that do not capitalise nouns mid-sentence; for German the model's
    // capitalisation may be a legitimately capitalised noun, so the fix is skipped.
    val lowerCaseFixEnabled = language.substringBefore('-').lowercase() !in LANGUAGES_WITH_NOUN_CAPITALISATION
    var lowercaseNextFirstLetter = false

    for (i in words.indices) {
        var word = words[i]
        // Step 3: if the immediately preceding spurious period was removed, undo the
        // model's sentence-start capitalisation on THIS word (whatever it is — plain word,
        // or a period-bearing word whose own period may also be removed next). Apply at
        // the top of the loop so every emit path sees the de-capitalised form. Acronyms
        // and the English pronoun "I"/its contractions are preserved by
        // [lowercaseFirstIfMidSentence]; German is skipped entirely via
        // [lowerCaseFixEnabled] because a capitalised word there may be a legitimate noun.
        if (lowercaseNextFirstLetter && lowerCaseFixEnabled) {
            val fixed = lowercaseFirstIfMidSentence(word)
            if (fixed != word) {
                Log.d(TAG, "[CLEAN:SPURIOUS_PERIOD] lower-cased word after removed period")
                word = fixed
            }
        }
        lowercaseNextFirstLetter = false

        // A word "carries" a period when it ends with exactly one period (not ellipsis).
        val hasPeriod = word.endsWith('.') && !word.endsWith("..")
        if (!hasPeriod) {
            result.add(word)
            wordCountSinceLastPeriod++
            continue
        }

        // The base word without its trailing period.
        val base = word.dropLast(1)
        // normalised form of the base for set lookup.
        val normBase = base.lowercase().trim { !it.isLetterOrDigit() }

        // Count the period-bearing token itself as part of this segment.
        val segmentWordCount = wordCountSinceLastPeriod + 1

        val isNonClosingWord = normBase in NON_SENTENCE_CLOSING_WORDS
        val isShortSegment = segmentWordCount < 5
        // Never strip a trailing period — the last token's period is always real.
        val isLastToken = i == words.lastIndex

        if (!isLastToken && (isNonClosingWord || isShortSegment)) {
            val reason = when {
                isNonClosingWord && isShortSegment -> "non-closing word + short segment ($segmentWordCount words)"
                isNonClosingWord -> "non-closing word"
                else -> "short segment ($segmentWordCount words)"
            }
            Log.d(TAG, "[CLEAN:SPURIOUS_PERIOD] removed period after a word ($reason)")
            // Emit the word without its trailing period.
            // base.isEmpty() is unreachable in practice (a word that is just "." would be
            // filtered by hasPeriod's !word.endsWith("..") check and normalised away
            // upstream) but is kept as a safety guard so we never emit an empty token.
            result.add(if (base.isEmpty()) word else base)
            // Step 3: the spurious period we just removed was the model's signal for a
            // sentence start, so the following word was capitalised by the model. Flag it
            // for first-letter lower-casing at the top of the next iteration (subject to
            // the acronym / pronoun guards in [lowercaseFirstIfMidSentence]) so removing
            // the period does not strand the capital mid-sentence.
            lowercaseNextFirstLetter = lowerCaseFixEnabled
            // Do NOT reset wordCountSinceLastPeriod — the removed period was not a real
            // sentence boundary, so counting continues from where it was.
            wordCountSinceLastPeriod = segmentWordCount
        } else {
            // A kept period is a real sentence boundary — the following word stays
            // capitalised (applySentenceCapitalization handles that), so do NOT flag it.
            result.add(word)
            // This period is a real sentence boundary: reset the word counter.
            wordCountSinceLastPeriod = 0
        }
    }

    return result.joinToString(" ")
}

/**
 * Capitalizes the first letter of the text and any letter that immediately follows
 * a sentence-ending punctuation mark ('.', '!', '?') and optional whitespace.
 *
 * @param skipInitialCapitalize When `true`, the very first letter of the string is **not**
 *   capitalized.  This is used for post-trim continuations: after an audio window trim the
 *   model's next partial starts mid-sentence (e.g. `"sind vielleicht noch falsch…"`), so
 *   capitalizing the first letter would produce `"Sind…"` which is wrong.  Sentence-internal
 *   capitalizations (letters following `.`, `!`, `?`) are always applied regardless.
 * @param modelFirstWord The model's *original* first word (lowercased) of the raw transcript
 *   before any upstream pipeline stage (filler removal, stutter collapse, leading-punct /
 *   leading-dot strip) modified the prefix. When non-null, the [shouldRespectModelLowercase]
 *   guard only fires if the first word currently being capitalised still equals this value —
 *   i.e. nothing was stripped from the front. When the current first word differs (a leading
 *   filler like "um" was removed, exposing "so" as the new utterance start), the guard is
 *   suppressed so the exposed word IS capitalised as a genuine sentence start. `null` (the
 *   default for direct / structural-only callers) means "assume the first word is the
 *   model's first word" — the guard fires as before.
 */
internal fun String.applySentenceCapitalization(
    skipInitialCapitalize: Boolean = false,
    modelFirstWord: String? = null,
): String {
    if (this.isBlank()) return this
    val text = this.trim()
    val builder = StringBuilder(text.length)
    // When skipInitialCapitalize=true start with capitalizeNext=false so the first letter
    // of a mid-sentence continuation is not upper-cased.
    var capitalizeNext = !skipInitialCapitalize
    // Tracks whether the pending capitalisation was triggered by a sentence-boundary
    // period (vs. the very start of the utterance or ! / ?).
    var capitalizeTriggeredByPeriod = false
    // Whether this is still the very first capitalisation opportunity (utterance start).
    var isFirstCapitalize = !skipInitialCapitalize

    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (capitalizeNext && c.isLetter()) {
            // When triggered by a period, peek ahead to collect the full candidate word
            // and skip capitalisation if it belongs to the lowercase guard set.
            val shouldGuard = capitalizeTriggeredByPeriod && !isFirstCapitalize &&
                    peekWord(text, i).lowercase() in SHOULD_STAY_LOWERCASE
            // Respect the model's own casing at the utterance start: Parakeet capitalises
            // genuine sentence starts (every real utterance start in practice comes out of
            // the model already capitalised). When the model emits the FIRST word of a
            // partial in LOWERCASE and that word is a mid-sentence function word
            // (article / preposition / conjunction), it is a drifted continuation (the
            // model dropped the prefix), NOT a real sentence start — so do NOT capitalise
            // it. This prevents stranded capitals like "…do it. To be able…" where the
            // model emitted "to" lowercase but the previous partial's utterance-final
            // period fooled the capitaliser into treating "to" as a new sentence.
            //
            // The lowercase check (c.isLowerCase()) is the key signal: a genuine utterance
            // start arrives capitalised, so this guard never suppresses a real sentence
            // start — only the drifted-continuation case the model itself flagged as
            // non-sentence-initial by emitting it lowercase.
            //
            // modelFirstWord gate: only apply this guard when the first word being
            // capitalised is STILL the model's original first word (i.e. no upstream stage
            // stripped leading content). If filler removal ("um so I…" → "so I…") or any
            // other prefix-removing stage exposed a new first word, that word IS the real
            // utterance start now and must be capitalised even though the model emitted it
            // lowercase (it was mid-sentence in the model's raw output). Without this gate
            // the guard would wrongly leave "so I was saying hello." lowercase.
            val currentFirstWord = peekWord(text, i).lowercase()
            val isFirstWordIntact = modelFirstWord == null || currentFirstWord == modelFirstWord
            val shouldRespectModelLowercase = isFirstCapitalize && c.isLowerCase() &&
                    isFirstWordIntact &&
                    currentFirstWord in SHOULD_STAY_LOWERCASE
            if (shouldGuard || shouldRespectModelLowercase) {
                builder.append(c)
            } else {
                builder.append(c.uppercaseChar())
            }
            capitalizeNext = false
            capitalizeTriggeredByPeriod = false
            isFirstCapitalize = false
        } else {
            builder.append(c)
        }

        if (c == '.') {
            capitalizeNext = true
            capitalizeTriggeredByPeriod = true
        } else if (c == '!' || c == '?') {
            capitalizeNext = true
            capitalizeTriggeredByPeriod = false
        }
        i++
    }
    return builder.toString()
}

/** Extracts the word starting at [start] (letter run only, stops at first non-letter). */
private fun peekWord(text: String, start: Int): String {
    val sb = StringBuilder()
    var j = start
    while (j < text.length && text[j].isLetter()) {
        sb.append(text[j])
        j++
    }
    return sb.toString()
}

/**
 * Returns the first letter-word of [s], lowercased — skipping any leading non-letter
 * characters (punctuation, whitespace, digits). Empty when [s] has no letter word.
 *
 * Used to capture the model's *original* first word at the top of the cleaning pipeline
 * (before filler removal / stutter collapse / leading-punct strip) so that
 * [applySentenceCapitalization] can tell whether the word it is about to (not) capitalise
 * is still the model's genuine first word or one exposed by upstream leading-content
 * removal. See [applySentenceCapitalization]'s `modelFirstWord` parameter.
 */
private fun firstWordLowercased(s: String): String {
    val sb = StringBuilder()
    var i = 0
    while (i < s.length && !s[i].isLetter()) i++
    while (i < s.length && s[i].isLetter()) {
        sb.append(s[i])
        i++
    }
    return sb.toString().lowercase()
}

/**
 * Words that should NOT be capitalised when they follow a sentence-boundary period,
 * because they are almost certainly mid-sentence (prepositions, articles, conjunctions,
 * common adverbs — English + German).
 */
private val SHOULD_STAY_LOWERCASE = setOf(
    // English articles / conjunctions / prepositions
    "the", "a", "an", "and", "but", "or", "nor", "for", "yet", "so",
    "in", "on", "at", "to", "of", "by", "up", "as", "if",
    "with", "from", "into", "onto", "upon", "over", "under", "between", "through",
    "because", "although", "while", "when", "where", "that",
    // German articles / conjunctions / prepositions
    "die", "der", "das", "ein", "eine", "und", "aber", "oder",
    "an", "auf", "zu", "von", "mit", "bei", "aus", "durch", "für",
)

/**
 * Applies only structural (alignment-safe) transforms to a raw transcript — no word
 * removal.  Safe to use as the alignment anchor because it never changes the word count:
 *  - Leading dots / ellipsis: `"...Hello"` → `"Hello"`
 *  - Leading commas / semicolons: `", jetzt sehr gut"` → `"jetzt sehr gut"`
 *  - Trailing multiple dots: `"Hello..."` → `"Hello."`
 *  - Missing space after sentence-ending punctuation: `"gut.Ich"` → `"gut. Ich"`
 *  - Sentence boundary capitalization.
 *  - Strings with no alphanumeric content are discarded entirely.
 *
 * @param isContinuation When `true`, the very first letter of the result is **not**
 *   capitalized (used after an audio window trim).
 * @param modelFirstWord The model's original first word (lowercased) captured before any
 *   prefix-removing stage, threaded through to [applySentenceCapitalization] so its
 *   model-lowercase guard only fires when the first word is still the model's genuine
 *   first word. `null` (default for direct / partial-path callers) means "assume the first
 *   word is the model's first word" — the guard fires as before. The full
 *   [cleanTranscript] pipeline passes a real value so that leading-filler removal does not
 *   wrongly suppress capitalisation of the exposed sentence start.
 */
internal fun String.cleanTranscriptStructural(
    isContinuation: Boolean = false,
    modelFirstWord: String? = null,
): String {
    if (isBlank()) {
        Log.d(TAG, "[CLEAN] blank input → discarded")
        return ""
    }
    val input = trim()

    val afterLeadDots = input.replace(LEADING_DOTS_RE, "")
    if (afterLeadDots != input) Log.d(TAG, "[CLEAN:LEAD_DOTS]   ${input.length} → ${afterLeadDots.length} chars")

    val afterLeadPunct = afterLeadDots.replace(LEADING_PUNCT_RE, "")
    if (afterLeadPunct != afterLeadDots) Log.d(TAG, "[CLEAN:LEAD_PUNCT]  ${afterLeadDots.length} → ${afterLeadPunct.length} chars")

    val afterTrailDots = afterLeadPunct.replace(TRAILING_DOTS_RE, ".")
    if (afterTrailDots != afterLeadPunct) Log.d(TAG, "[CLEAN:TRAIL_DOTS]  ${afterLeadPunct.length} → ${afterTrailDots.length} chars")

    val afterSentSpace = afterTrailDots.replace(MISSING_SENTENCE_SPACE_RE, "$1 $2").trim()
    if (afterSentSpace != afterTrailDots.trim()) Log.d(
        TAG,
        "[CLEAN:SENT_SPACE]  ${afterTrailDots.trim().length} → ${afterSentSpace.length} chars"
    )

    val afterCaps = afterSentSpace.applySentenceCapitalization(
        skipInitialCapitalize = isContinuation,
        modelFirstWord = modelFirstWord,
    )
    if (afterCaps != afterSentSpace) Log.d(TAG, "[CLEAN:CAPITALIZE]  ${afterSentSpace.length} → ${afterCaps.length} chars")

    return if (afterCaps.none { it.isLetterOrDigit() }) {
        Log.d(TAG, "[CLEAN] ${input.length} chars → discarded (no alphanumeric content)")
        ""
    } else {
        afterCaps
    }
}

/**
 * Full transcript cleaning: applies word-count-changing display transforms (filler word
 * removal, stutter collapse, phrase deduplication) followed by structural transforms via
 * [cleanTranscriptStructural].
 *
 * Use this for stable-chunk word tracking and for final display in the text field.
 * Do NOT use it as the alignment anchor inside [InferenceRepository.transcribe] — the
 * word-count changes it introduces can cause alignment divergence in [TextInjector] when
 * the model inconsistently emits filler words across consecutive strides.
 *
 * @param isContinuation Passed through to [cleanTranscriptStructural] to suppress
 *   initial-letter capitalisation after an audio window trim.
 * @param skipSpuriousPeriods When `true`, the [filterSpuriousPeriods] step is skipped.
 *   The spurious-period filter is a one-shot transform on the model's raw output: its
 *   short-segment heuristic relies on the word count since the previous period, which is
 *   only complete on the full transcript. Re-running it on a frozen substring (the
 *   display re-clean in [TextInjector]) would misfire on the substring's first segment —
 *   the tail of a longer sentence — and delete a genuine sentence-final period. Callers
 *   that re-clean text already cleaned from the full transcript (the production
 *   displayCleanFn) must set this to `true` so the authoritative full-text periods are
 *   preserved.
 */
internal fun String.cleanTranscript(
    isContinuation: Boolean = false,
    language: String = "en",
    formatNumbersAsDigits: Boolean = true,
    skipSpuriousPeriods: Boolean = false,
): String {
    if (isBlank()) {
        Log.d(TAG, "[CLEAN] blank input → discarded")
        return ""
    }
    val input = trim()

    // Capture the model's original first word BEFORE any prefix-removing stage (filler
    // removal, stutter collapse, phrase dedup, spurious-period filter, leading-punct /
    // leading-dot strip) so that applySentenceCapitalization's model-lowercase guard only
    // fires when the first word is still the model's genuine first word. Without this, a
    // leading filler like "um so I was saying…" would have "um" stripped, exposing "so" —
    // and the guard would wrongly keep "so" lowercase instead of capitalising the now-real
    // sentence start.
    val modelFirstWord = firstWordLowercased(input)

    val afterFillers = input.removeFillerWords(language)
    if (afterFillers != input) Log.d(TAG, "[CLEAN:FILLERS]     ${input.length} → ${afterFillers.length} chars")

    val afterStutters = afterFillers.collapseStutters()
    if (afterStutters != afterFillers) Log.d(TAG, "[CLEAN:STUTTER]     ${afterFillers.length} → ${afterStutters.length} chars")

    val afterNumbers = if (formatNumbersAsDigits) {
        val words = afterStutters.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val normalised = NumberNormaliser.normalise(words, language)
        val joined = normalised.joinToString(" ")
        if (joined != afterStutters) Log.d(TAG, "[CLEAN:NUMBERS]     ${afterStutters.length} → ${joined.length} chars")
        joined
    } else {
        afterStutters
    }

    val afterDedup = afterNumbers.collapseRepeatedPhrases()
    if (afterDedup != afterStutters) Log.d(TAG, "[CLEAN:PHRASES]     ${afterStutters.length} → ${afterDedup.length} chars")

    val afterSpuriousPeriods = if (skipSpuriousPeriods) {
        afterDedup
    } else {
        val filtered = afterDedup.filterSpuriousPeriods(language)
        if (filtered != afterDedup) Log.d(
            TAG,
            "[CLEAN:SPURIOUS_P]  \"$afterDedup\" → \"$filtered\""
        )
        filtered
    }

    return afterSpuriousPeriods.cleanTranscriptStructural(isContinuation, modelFirstWord)
}

/**
 * Minimum audio before the **first** partial inference is emitted (2 s).
 *
 * Parakeet TDT needs ≈1 s of context before the TDT decoder stabilises. Using 2 s
 * gives the encoder enough frames to correctly anchor the very first words (e.g.
 * "Now I lifted…") instead of returning noise or a mid-sentence fragment on the first
 * stride.
 */
private const val MIN_SAMPLES = SAMPLE_RATE * 2          // 2 s = 32 000 samples

/**
 * Utterances whose total VAD-active audio is below this threshold are handled by
 * the short-utterance path: the rolling-window final pass is skipped and a single
 * zero-padded inference is run instead.
 *
 * 2.5 s = 40 000 samples — chosen to cover single-word and short-phrase utterances
 * that end before the first partial stride fires (< [MIN_SAMPLES]) as well as
 * utterances that just barely produced one stride but have too little tail audio for
 * the full rolling-window logic to produce reliable results.
 */
private const val SHORT_UTTERANCE_THRESHOLD_SAMPLES = (SAMPLE_RATE * 2.5f).toInt()  // 2.5 s = 40 000 samples

/**
 * Minimum sample count passed to the engine on the short-utterance path.
 *
 * 1.25 s matches the reference NeMo pipeline (which pads < 1 s clips to 1.25 s).
 * Zero-padding ([ShortArray.copyOf] fills new positions with 0) is decoded as silence —
 * the TDT decoder advances through blank frames at the tail without emitting speech tokens.
 *
 * NOTE: this was 2 s. Real-model experiments showed the longer pad actively breaks
 * single-word utterances: "no" (0.5 s) decodes as `No` when padded to 1.25 s but returns
 * blank when padded to 2 s; "yes" (0.7 s) decodes as `Yes` at 1.25 s with the VAD's
 * 600 ms lead-in but never at 2 s. The extra trailing silence pushes the TDT decoder's
 * blank-run past the token boundary instead of anchoring it.
 */
private const val MIN_PADDING_SAMPLES = SAMPLE_RATE * 5 / 4  // 1.25 s = 20 000 samples

/**
 * Leading silence prepended to short-utterance decodes (600 ms = 9 600 samples).
 *
 * Real-model probes show the TDT decoder is extremely context-sensitive on short
 * clips: 600 ms of leading silence (matching the Silero VAD lead-in) flips blank
 * decodes into correct words ("Yes" decodes blank without it, correctly with it),
 * while trailing digital silence is the harmful direction (a 1 s zero tail makes
 * "No" decode blank). The VAD's own lead-in already provides up to 600 ms when the
 * user pauses before speaking, but a user who starts talking right after pressing
 * the talk button gives the buffer little or none — so the short-utterance decode
 * always prepends this silence.
 */
private const val SHORT_UTT_LEAD_SILENCE_SAMPLES = SAMPLE_RATE * 60 / 100  // 600 ms = 9 600 samples

/** Emit a fresh partial inference every time this many new samples arrive (1 s). */
private const val STRIDE_SAMPLES = SAMPLE_RATE            // 1 s = 16 000 samples

/** Maximum audio context kept in the rolling window (30 s). */
private const val MAX_WINDOW_SAMPLES = SAMPLE_RATE * 30   // 30 s = 480 000 samples

// ── Chunked-TDT streaming parameters ────────────────────────────────────────────────
// The streaming path processes audio in fixed [CHUNK_SAMPLES] strides. For each chunk the
// encoder is run on a [LEFT_CONTEXT | chunk | RIGHT_CONTEXT] buffer (the Conformer is
// bidirectional, so the right context improves the chunk's encoder features), and only the
// chunk's frames are decoded, carrying the TDT decoder state from the previous chunk. This
// yields offline-equivalent accuracy with no re-emission or duplication. Measured by
// StreamingParityTest (18 real fixtures): the frozen field text is byte-identical to the
// one-shot final (WER 0.0), and the residual delta vs the one-shot reference is the model's
// 2 s-context approximation (worst fixture 1 word / 34 ≈ 0.03). Latency to the first
// committed chunk is CHUNK + RIGHT (≈ 4 s); each subsequent chunk commits RIGHT after it ends.

/** Size of each processed chunk (2 s). */
private const val CHUNK_SAMPLES = SAMPLE_RATE * 2            // 2 s = 32 000 samples

/** Lookahead (right context) required before a chunk is decoded (2 s). */
private const val RIGHT_CONTEXT_SAMPLES = SAMPLE_RATE * 2    // 2 s = 32 000 samples

/** Trailing (left) context kept before each chunk for encoder anchoring (2 s). */
private const val LEFT_CONTEXT_SAMPLES = SAMPLE_RATE * 2     // 2 s = 32 000 samples

// ── Acoustic word-alternative capture (word-correction overhaul) ──────────────────────
// Per-word confidence below which the bounded local beam runs. Words above the gate are
// ones the model is confident about (correction unlikely) and skip the beam entirely —
// the common case, so the added decode cost is near zero.
private const val ACUSTIC_CONF_GATE = 0.6f

// Maximum local beams per decoded chunk, so a pathological chunk (many low-confidence
// words) cannot blow up decode time.
private const val MAX_BEAMS_PER_CHUNK = 3

// Frames of slack past a word's last token that the local beam may search — a genuine
// alternative word can be slightly longer (more frames) than the emitted one.
private const val BEAM_FRAME_MARGIN = 2

// Number of acoustic alternatives kept per word in the cache (the correction layer
// rescores these with the LM and returns the top 5 to the UI).
private const val MAX_CACHED_ALTERNATIVES = 5

/**
 * A plausible single-word candidate for the suggestion bar: at least 2 characters, no
 * internal whitespace (the beam can occasionally emit a two-word fragment), and at least
 * one letter (drops pure-digit / pure-punctuation detokenisation artefacts).
 */
private fun isValidCandidateWord(w: String): Boolean =
    w.length >= 2 && !w.contains(' ') && w.any { it.isLetter() }

/**
 * Deterministic encoder frame offset for a sample position.
 *
 * The nemo128 preprocessor emits one feature frame per 160 samples (10 ms hop) plus one
 * padding frame; the encoder subsamples by 8 (ceiling). So the encoder frame index
 * corresponding to sample position [n] is `(n / 160 + 1) / 8`. Verified empirically
 * against the int8 ONNX export (frame counts are deterministic given the input length,
 * even though the frame *values* are non-causal).
 *
 * **This is a position→frame map, NOT a total frame count.** For a buffer of length [n]
 * the encoder produces `ceil((n/160 + 1)/8)` frames, which is one MORE than
 * [frameOffset](n) for most [n] (floor vs ceiling). Use the encoder's actual length
 * output ([ParakeetEngine.encodeBuffer]'s second component) as the frame end when
 * decoding a whole buffer — see [decodeChunkRange].
 */
private fun frameOffset(n: Int): Int = (n / 160 + 1) / 8

/**
 * When consecutive strides all diverge (no common prefix at all), the window can grow
 * unboundedly - each new stride makes the context longer, which causes even more attention
 * drift and more divergence (a vicious cycle).  Once the window exceeds this size during
 * a divergence run, force-trim it back to [TRIGGER_WINDOW_SAMPLES] + [MIN_CONTEXT_SAMPLES]
 * (≈ 10 s) regardless of stability.  This caps the cycle and gives the model a shorter,
 * cleaner context to re-anchor on for the next pass.
 */
private const val FORCE_TRIM_WINDOW_SAMPLES = SAMPLE_RATE * 12  // 12 s = 192 000 samples

/**
 * Minimum audio passed to the final inference pass.
 * Clips shorter than 1 second are padded to 1.25 seconds (20 000 samples). Very short
 * inputs give the encoder too few frames and Parakeet returns blank or spurious tokens.
 * Zero-padding is decoded as silence - TDT advances through blank frames without emitting
 * speech tokens. (Reference pipeline: < 1 s → padded to 1.25 s.)
 */
private const val MIN_FINAL_SAMPLES = SAMPLE_RATE * 5 / 4  // 1.25 s = 20 000 samples

/**
 * Number of consecutive strides whose leading words must agree before that prefix
 * is considered "stable" and the corresponding audio is eligible for trimming.
 *
 * N=3 means three back-to-back partials must produce the same leading words.
 * Raised from 2 → 3 to reduce premature trims on transiently-stable words
 * (e.g. "Mitpush" produced for 2 strides before the model corrects to "Push to talk").
 * The extra stride costs ~1 s of latency before a trim fires, which is acceptable
 * given the TRIGGER_WINDOW is already 6 s.
 */
private const val STABLE_STRIDES = 3

/**
 * Audio samples always kept at the tail of the window after a stable-chunk trim.
 *
 * Four seconds of tail context (up from 3 s) gives the encoder more frames to
 * correctly anchor the first words of the post-trim transcript. With only 3 s of
 * context the model sometimes starts mid-word (e.g. "Angabe" instead of
 * "Spracheingabe"), producing a prefix fragment that breaks drift-alignment in
 * TextInjector and causes the following sentence to be silently dropped.
 */
private const val MIN_CONTEXT_SAMPLES = SAMPLE_RATE * 4   // 4 s = 64 000 samples

/**
 * The rolling window must exceed this size before a stable-chunk trim is attempted.
 *
 * Below 6 s the attention-drift problem rarely manifests and trimming would only
 * discard useful acoustic context. The trim logic activates gradually as the window
 * grows beyond this threshold.
 */
private const val TRIGGER_WINDOW_SAMPLES = SAMPLE_RATE * 6 // 6 s = 96 000 samples

/**
 * Minimum audio saving required for a stable-chunk trim to be worth executing.
 * Trims that free less than 1 s of audio are skipped to avoid churn.
 */
private const val MIN_TRIM_SAMPLES = SAMPLE_RATE           // 1 s = 16 000 samples

/**
 * Number of consecutive blank (silent) strides that triggers a proactive trim.
 *
 * During a pause the model returns blank output, so [recentPartialWords] never accumulates
 * enough entries for the normal stable-chunk logic to fire.  Without intervention the window
 * balloons well past 2 × [TRIGGER_WINDOW_SAMPLES] before the next stable trim can occur -
 * the resulting aggressive drop causes the model to lose entire sentences.
 *
 * After this many back-to-back blank strides we trim the window back to
 * [TRIGGER_WINDOW_SAMPLES] + [MIN_CONTEXT_SAMPLES] proactively, so speech that resumes
 * afterwards is anchored on a clean, compact context.
 */
private const val SILENCE_TRIM_STRIDES = 2  // 2 s of silence → proactive trim

/**
 * Finds the length of the longest word prefix that all [wordLists] share.
 *
 * Words are compared case-insensitively with leading/trailing punctuation stripped via
 * [normalizedForComparison], matching the same normalisation used in [TextInjector].
 *
 * Example:
 * ```
 * [["Now","I","lifted","the","record"], ["the","record","button"]]
 *   → "the" and "record" are NOT a common prefix (lists differ at index 0)
 *   → returns 0
 *
 * [["Now","I","lifted","the"], ["Now","I","lifted","the","record"]]
 *   → returns 4
 * ```
 */
private fun longestCommonPrefixLength(wordLists: List<List<String>>): Int {
    if (wordLists.isEmpty()) return 0
    val minLen = wordLists.minOf { it.size }
    var count = 0
    while (count < minLen) {
        val norm = wordLists[0][count].normalizedForComparison()
        if (wordLists.all { it[count].normalizedForComparison() == norm }) count++
        else break
    }
    return count
}

private fun ShortArray.rms(): Float {
    if (isEmpty()) return 0f
    val sum = fold(0.0) { acc, s -> acc + s.toDouble() * s }
    return (Math.sqrt(sum / size) / Short.MAX_VALUE).toFloat()
}

/**
 * Maximum distance from [targetDrop] that [findSilenceCutPoint] will search for a
 * low-energy cut point (1.5 s).
 *
 * The proportional word→time estimate is biased: pauses inside the stable region and
 * still-decoding tail words make it land before the true stable boundary. Bounding the
 * search to a band around the estimate lets the cut snap forward to the real pause
 * (the usual case) without latching onto an unrelated silence seconds away — which
 * would discard anchor context the next partial still needs to re-emit the tail words
 * that [dev.brgr.outspoke.ime.TextInjector] drops from the composing span on trim.
 */
private const val SILENCE_CUT_SEARCH_RADIUS_SAMPLES = SAMPLE_RATE * 3 / 2  // 1.5 s = 24 000 samples

/**
 * Scans [window] chunk boundaries for a VAD-quiet (low-energy) cut point near
 * [targetDrop], restricted to the band
 * `[max(minDrop, targetDrop − radius), min(maxDrop, targetDrop + radius)]`.
 *
 * Priority:
 *  1. Latest silence at or before [targetDrop] — ensures we never slice into the speech
 *     that immediately follows the stable-text boundary.
 *  2. Earliest silence after [targetDrop] within the band — secondary fallback when no
 *     silence precedes the target; trimming a little past the estimate is still better
 *     than cutting mid-phoneme.
 *  3. [targetDrop] itself — proportional estimate used verbatim only when no silence at
 *     all is found in the band.
 *
 * Cutting at a silence boundary prevents slicing an active phoneme and eliminates the
 * hallucinations Parakeet produces when its window starts mid-consonant.
 */
private fun findSilenceCutPoint(
    window: ArrayDeque<ShortArray>,
    targetDrop: Int,
    minDrop: Int,
    maxDrop: Int,
): Int {
    val silenceThreshold = 0.02f
    val searchStart = maxOf(minDrop, targetDrop - SILENCE_CUT_SEARCH_RADIUS_SAMPLES)
    val searchEnd = minOf(maxDrop, targetDrop + SILENCE_CUT_SEARCH_RADIUS_SAMPLES)
    var cumulative = 0
    var lastSilenceBeforeTarget = -1
    var firstSilenceAfterTarget = -1

    for (chunk in window) {
        cumulative += chunk.size
        if (cumulative < searchStart) continue
        if (cumulative > searchEnd) break
        val rms = chunk.rms()
        if (rms < silenceThreshold) {
            if (cumulative <= targetDrop) {
                lastSilenceBeforeTarget = cumulative
            } else if (firstSilenceAfterTarget < 0) {
                firstSilenceAfterTarget = cumulative
            }
        }
    }

    return when {
        lastSilenceBeforeTarget >= searchStart -> lastSilenceBeforeTarget
        firstSilenceAfterTarget in searchStart..searchEnd -> firstSilenceAfterTarget
        else -> targetDrop
    }
}

/**
 * Zero-pads [samples] to at least [minSamples] by appending zeroes (silence).
 *
 * If [samples] is already at least [minSamples] long it is returned unchanged.
 * [ShortArray.copyOf] fills any added positions with 0 (PCM silence), which the TDT
 * decoder advances through without emitting spurious tokens.
 *
 * This is the canonical short-utterance padding helper and is intentionally a
 * package-level function so it can be unit-tested in isolation.
 */
internal fun zeroPadToMinimum(samples: ShortArray, minSamples: Int): ShortArray =
    if (samples.size >= minSamples) samples else samples.copyOf(minSamples)

/**
 * Minimum confidence score required to accept a short-utterance result.
 *
 * Applied **only** on the short-utterance path (utterances < [SHORT_UTTERANCE_THRESHOLD_SAMPLES]).
 * Long-utterance results are never gated — continuous dictation relies on many consecutive
 * partials and a false-negative gate would cause large silent drops.
 *
 * Value chosen empirically: 0.55 is high enough to reject hallucinations (single punctuation
 * characters, lone digits, or implausible words produced on very short/silent clips) while
 * accepting a wide range of real one-word and short-phrase results.
 */

/**
 * Minimum per-token geometric-mean probability required to emit the *first* non-blank
 * partial of a session (cold-stride confidence gate).
 *
 * On the first 1–2 strides (cold strides) Parakeet sometimes hallucinates words from
 * ambient noise or room tone.  These cold hallucinations are characterised by low per-token
 * softmax probabilities (the model is uncertain), whereas real speech — even on the first
 * stride — produces confidently peaked distributions.
 *
 * When the first non-blank partial has a confidence below this threshold it is suppressed
 * (treated as a blank stride).  The audio stays in the window, so the next stride
 * re-transcribes the same audio with +1 s of context.  If the output was a real word it
 * will reappear with higher confidence and be emitted normally.  If it was a hallucination
 * it will not reappear — or will again be suppressed until a high-confidence stride arrives.
 *
 * The gate is **removed** as soon as any one partial passes it (`hasCommittedAnyPartial =
 * true`), so mid-dictation partials are never gated regardless of confidence.
 *
 * Starting threshold: 0.40 (geometric mean ≥ 40 %).  Empirical; can be tuned.
 */
internal const val COLD_STRIDE_CONFIDENCE_THRESHOLD = 0.40f

/** Maximum number of consecutive low-confidence cold strides before giving up and
 * emitting anyway, to prevent the gate from permanently silencing a user who whispers
 * or speaks in a very noisy environment.  After this many suppressed strides the next
 * partial is emitted unconditionally regardless of confidence. */
internal const val COLD_STRIDE_MAX_SUPPRESSED = 4
internal const val CONFIDENCE_THRESHOLD = 0.55f

/**
 * Estimates a 0.0–1.0 confidence score for a short-utterance [TranscriptResult].
 *
 * **Confidence metric: engine per-token confidence with a plausibility floor**
 *
 * The score is the engine's real per-token geometric-mean probability
 * ([TranscriptResult.Final.confidence] / [TranscriptResult.Partial.confidence]) —
 * `exp(mean(log_softmax(argmax)))` over all non-blank emissions, computed inside
 * `ParakeetEngine.greedyDecode`. Low values flag hallucinations: on noise, silence,
 * or cold strides the TDT decoder emits tokens with flat (uncertain) distributions,
 * whereas real speech produces confidently peaked ones.
 *
 * A plausibility floor is applied first: results with **fewer than 2 word characters**
 * (letters or digits) after stripping punctuation and whitespace score 0.0 regardless
 * of the engine's confidence. This catches the common hallucination patterns on very
 * short/silent clips — empty string, a lone period, a single letter, or a lone digit —
 * which the model can (and does) emit with high token confidence.
 *
 * Non [Final]/[Partial] results (Failure, WindowTrimmed) score 0.0.
 *
 * @param result The cleaned [TranscriptResult] from the engine.
 */
internal fun estimateConfidence(result: TranscriptResult): Float {
    val text: String
    val engineConfidence: Float
    when (result) {
        is TranscriptResult.Final -> {
            text = result.text
            engineConfidence = result.confidence
        }

        is TranscriptResult.Partial -> {
            text = result.text
            engineConfidence = result.confidence
        }

        else -> return 0.0f
    }

    // Count word characters (letters + digits), ignoring punctuation and whitespace.
    val wordCharCount = text.count { it.isLetterOrDigit() }

    // Fewer than 2 meaningful characters → treat as empty / single-character hallucination.
    // This is the primary gate: empty strings, single punctuation chars, lone digits or
    // single letters produced when the encoder sees near-silent padded audio.
    if (wordCharCount < 2) return 0.0f

    return engineConfidence
}

/**
 * Returns `true` when [text] is likely a model hallucination based on Unicode script
 * consistency — regardless of the user-configured [language].
 *
 * **Why script-consistency rather than language-gating:**
 * Parakeet's ONNX export has no language input tensor; [language] reflects only the
 * user's UI setting, not what Parakeet actually decoded.  A user dictating Russian with
 * the UI set to "en" (or "auto") would be wrongly blocked if the check were tied to the
 * language setting.  Instead we check whether the text is *internally consistent*: real
 * transcripts are almost entirely one script (Latin, Cyrillic, or Greek), while Parakeet
 * hallucinations on silence or noise tend to be random punctuation, symbols, CJK
 * characters, or mixed-script gibberish.
 *
 * **Algorithm:**
 * 1. Classify every non-whitespace, non-punctuation character into one of four buckets:
 *    Latin (U+0000–U+024F), Cyrillic (U+0400–U+052F), Greek (U+0370–U+03FF / U+1F00–U+1FFF),
 *    or Other (everything else — CJK, symbols, emoji, etc.).
 * 2. If the "Other" bucket exceeds [HALLUCINATION_SCRIPT_THRESHOLD] (20%) of all
 *    letter/digit characters, classify the text as a hallucination.
 * 3. Texts that are coherently Latin, Cyrillic, or Greek are never flagged — the
 *    dominant-script check is intentionally NOT performed.
 *
 * The [language] parameter is retained for API compatibility and future use (e.g. once
 * a language-conditioned ONNX export becomes available), but is not used by this check.
 *
 * An empty or whitespace-only string is never a hallucination (returns `false`).
 */
internal fun isScriptHallucination(text: String, language: String = "en"): Boolean {
    var total = 0
    var other = 0

    for (ch in text) {
        if (ch.isWhitespace()) continue
        if (ch in ALLOWED_PUNCTUATION) continue
        if (!ch.isLetterOrDigit()) {
            // Non-letter, non-digit, non-whitespace, non-punctuation (e.g. emoji, symbols)
            total++
            other++
            continue
        }

        total++
        val known = ch in LATIN_RANGE || ch in CYRILLIC_RANGE ||
                ch in GREEK_RANGE_1 || ch in GREEK_RANGE_2
        if (!known) other++
    }

    if (total == 0) return false
    return (other.toFloat() / total) > HALLUCINATION_SCRIPT_THRESHOLD
}

/**
 * Soft counterpart to [isScriptHallucination]: instead of discarding an entire transcript
 * because a single warm-up artefact token landed outside the allowed script ranges, this
 * removes only the offending characters and keeps the legitimate Latin / Cyrillic / Greek
 * content.
 *
 * This matters most on the **cold first stride**, where Parakeet's TDT decoder
 * occasionally emits one spurious CJK / symbol token alongside the real first words.
 * The old behaviour called [isScriptHallucination] on the whole partial and, on a hit,
 * replaced it with [TranscriptResult.Failure] — aborting the recording and losing the
 * first words even though most of the partial was correct. Stripping preserves the real
 * words so the user sees them and the sliding window can continue.
 *
 * Whitespace and [ALLOWED_PUNCTUATION] are always preserved. Letters/digits inside the
 * allowed ranges are preserved; everything else (out-of-range letters/digits, symbols,
 * emoji) is dropped.
 *
 * @return the stripped string, or `null` when nothing alphanumeric survives — meaning the
 *   input was effectively entirely a hallucination and the caller should treat it as a
 *   failure (for finals) or a blank stride (for partials) rather than emitting empty text.
 */
internal fun stripScriptHallucinations(text: String, language: String = "en"): String? {
    if (text.isBlank()) return null
    val sb = StringBuilder(text.length)
    for (ch in text) {
        if (ch.isWhitespace()) { sb.append(ch); continue }
        if (ch in ALLOWED_PUNCTUATION) { sb.append(ch); continue }
        if (ch.isLetterOrDigit()) {
            val known = ch in LATIN_RANGE || ch in CYRILLIC_RANGE ||
                    ch in GREEK_RANGE_1 || ch in GREEK_RANGE_2
            if (known) sb.append(ch)
            // else: out-of-range letter/digit (e.g. CJK) — drop
            continue
        }
        // Non-letter, non-digit, non-whitespace, non-punctuation (symbols, emoji) — drop
    }
    val result = sb.toString().trim()
    return if (result.none { it.isLetterOrDigit() }) null else result
}

/**
 * Bridges the audio capture pipeline to any [SpeechEngine] with a sliding-window
 * strategy that keeps the window from growing long enough to cause Parakeet attention
 * drift.
 *
 * **Sequential inference**: each partial inference runs directly inside the collect
 * loop (no concurrent [kotlinx.coroutines.launch]). The AudioRecord is unaffected
 * because it writes into the [Channel.UNLIMITED] buffer that sits between the capture
 * flow and this collect loop - the recorder never suspends while inference runs.
 *
 * **Stable-chunk commits**: after each partial the last [STABLE_STRIDES] word lists are
 * compared. If their longest common prefix is long enough, the corresponding audio at
 * the front of the window is trimmed, keeping [MIN_CONTEXT_SAMPLES] of tail context.
 * This prevents the window from ever growing long enough to trigger attention drift
 * without waiting for the hard [MAX_WINDOW_SAMPLES] ceiling.
 */
class InferenceRepository(
    private val engine: SpeechEngine,
    private val grammarCorrector: GrammarCorrector = NoOpGrammarCorrector,
) {

    /**
     * Bounded cache of per-word acoustic alternatives, populated at decode time by the
     * Parakeet streaming path (top-K token swaps + bounded local beam over low-confidence
     * words) and read by the word-correction layer via [getAcousticAlternatives].
     * Process-local to this repository (the long-lived bound inference service), so it
     * persists across keyboard hide/show cycles and is cleared at the start of each new
     * recording session.
     */
    val acousticCache: AcousticCandidateCache = AcousticCandidateCache()

    /**
     * Forwards a language tag to the underlying engine.
     * No-op for engines that do not support language selection (Parakeet, Voxtral).
     */
    fun setLanguage(tag: String) = engine.setLanguage(tag)

    /**
     * Forwards a language constraint set to the underlying engine.
     * No-op for engines that do not support language selection (Parakeet, Voxtral).
     */
    fun setLanguageConstraints(tags: List<String>) = engine.setLanguageConstraints(tags)

    /**
     * Returns the cached acoustic alternatives for [word] — the ASR model's own
     * word-level hypotheses captured at decode time (top-K token swaps and/or a bounded
     * local beam) — or an empty list when the word has no acoustic evidence (decoded
     * before capture was active, evicted from the bounded cache, or manually typed).
     * The word-correction layer rescores these with the language model; an empty result
     * falls back to dictionary candidates.
     *
     * Safe to call from any thread (the IME main / Default threads).
     */
    fun getAcousticAlternatives(word: String): List<WordAlternative> = acousticCache.get(word)

    /**
     * Applies grammar correction to [result] if it is a [TranscriptResult.Final].
     *
     * Correction is intentionally skipped for [TranscriptResult.Partial] results — applying
     * grammar rules to mid-stream partial transcripts wastes CPU and may introduce artefacts
     * into the composing span that [dev.brgr.outspoke.ime.TextInjector] is still tracking.
     *
     * [TranscriptResult.Failure] and [TranscriptResult.WindowTrimmed] are passed through
     * unchanged.
     */
    private fun applyGrammarCorrection(result: TranscriptResult, language: String): TranscriptResult =
        when (result) {
            is TranscriptResult.Final -> {
                val corrected = grammarCorrector.correct(result.text, language)
                if (corrected != result.text) {
                    Log.d(TAG, "[GRAMMAR] grammar correction applied")
                    result.copy(text = corrected)
                } else {
                    result
                }
            }

            else -> result
        }

    private val sessionMutex = Mutex()
    private var closed = false

    /** Model teardown must not race a recording or its final decode. */
    suspend fun closeEngine(onlyIfIdle: Boolean = false): Boolean {
        if (onlyIfIdle) {
            if (!sessionMutex.tryLock()) return false
        } else {
            sessionMutex.lock()
        }
        try {
            if (!closed) {
                closed = true
                engine.close()
            }
            return true
        } finally {
            sessionMutex.unlock()
        }
    }

    /**
     * @param postprocessingEnabled When `false` all transcript-cleaning steps (filler removal,
     *   stutter collapse, repetition deduplication, capitalisation) are skipped and the raw
     *   model output is emitted as-is.  Useful for debugging whether cleaning causes drops.
     *   Defaults to `true` (post-processing active).
     */
    fun transcribe(
        audio: Flow<AudioChunk>,
        postprocessingEnabled: Boolean = true,
        formatNumbersAsDigits: Boolean = true,
    ): Flow<TranscriptResult> = flow {
        sessionMutex.withLock {
            check(!closed) { "Speech model is no longer available. Open the keyboard again to reload it." }
            emitAll(transcribeSession(audio, postprocessingEnabled, formatNumbersAsDigits))
        }
    }

    private fun transcribeSession(
        audio: Flow<AudioChunk>,
        postprocessingEnabled: Boolean = true,
        formatNumbersAsDigits: Boolean = true,
    ): Flow<TranscriptResult> = channelFlow<TranscriptResult> {
        // The chunked-TDT streaming path requires the [ChunkStreamingEngine] stateful
        // decode primitives (implemented by ParakeetEngine). If a non-streaming engine is
        // configured we fall back to the legacy whole-window re-transcription path (kept
        // below) so the repository stays functional.
        val streaming = engine as? ChunkStreamingEngine
        if (streaming == null) {
            legacyTranscribe(audio, postprocessingEnabled, formatNumbersAsDigits).collect { send(it) }
            return@channelFlow
        }

        // Each transcribe() call is a fresh recording session: drop stale acoustic
        // alternatives from the previous session so the correction layer never rescores
        // words against evidence from a different utterance.
        acousticCache.clear()

        // ── Chunked-TDT streaming state ────────────────────────────────────────────────
        // The audio buffer (growing list of PCM chunks) plus the TDT decoder state. Audio is
        // processed in fixed [CHUNK_SAMPLES] strides with [RIGHT_CONTEXT_SAMPLES] of lookahead
        // and [LEFT_CONTEXT_SAMPLES] of trailing context. The TDT decoder state is carried
        // across chunks so each chunk emits exactly its own new tokens — no re-emission, no
        // duplication, and offline-equivalent accuracy (see StreamingParityTest for the
        // measured field-vs-one-shot delta).
        val buffer = ArrayDeque<ShortArray>()
        var totalSamples = 0
        // Absolute sample position of buffer.first() (0 until the front is trimmed). The
        // buffer is bounded: once a chunk is decoded, audio older than the next chunk's
        // left-context start is never read again and is trimmed from the front so a long
        // dictation does not grow the buffer without bound (30 min ≈ 57 MB unbounded).
        var bufferStart = 0
        var state = streaming.initialTdtState()
        val allTokens = mutableListOf<Int>()
        var nextChunkStart = 0
        // Per-utterance confidence accumulators: the sum of per-emission log-softmax
        // probabilities and the count of non-blank emissions across all decoded chunks.
        // The utterance confidence is the geometric mean exp(sum / count) — chunking-
        // independent because log-probs are summed, not averaged.
        var totalLogProbSum = 0.0
        var totalEmissions = 0
        // First ONNX/streaming failure for this utterance, if any. When non-null the
        // decode loop stops and a single TranscriptResult.Failure is emitted at the end
        // so an engine error surfaces as a contained result (the IME shows it) instead of
        // an uncaught exception that the keyboard's catch-all mislabels.
        var streamFailure: Exception? = null
        // The last word segment of the most recent decoded chunk, held back because it is
        // only known to be complete when the next chunk (or the utterance end) arrives —
        // a word may continue across the chunk boundary. Consumed by
        // [captureAcousticCandidates] on the next chunk and flushed by [flushAsFinal].
        var pendingEmissions: List<TokenEmission> = emptyList()

        // Geometric-mean confidence over every non-blank emission this utterance.
        fun utteranceConfidence(): Float = if (totalEmissions > 0) {
            Math.exp(totalLogProbSum / totalEmissions).toFloat().coerceIn(0f, 1f)
        } else 1.0f

        // Trim the buffer front so it keeps only samples from [keepFrom) onward. Audio
        // before keepFrom is never read again (the next chunk's left context starts there).
        fun trimBufferFront(keepFrom: Int) {
            val target = keepFrom.coerceAtLeast(bufferStart)
            while (buffer.isNotEmpty() && bufferStart < target) {
                val head = buffer.first()
                val headEnd = bufferStart + head.size
                if (headEnd <= target) {
                    bufferStart = headEnd
                    buffer.removeFirst()
                } else {
                    buffer[0] = head.copyOfRange(target - bufferStart, head.size)
                    bufferStart = target
                }
            }
        }

        // Extract samples [start, end) (absolute positions) from the buffer, normalised to
        // [-1, 1]. The buffer may have been front-trimmed, so positions are offset by
        // [bufferStart]; anything before bufferStart has already been decoded and is
        // clamped (callers never request it).
        fun extractRange(start: Int, end: Int): FloatArray {
            val relStart = (start - bufferStart).coerceAtLeast(0)
            val relEnd = (end - bufferStart).coerceAtLeast(relStart)
            val len = relEnd - relStart
            val out = FloatArray(len)
            var outPos = 0
            var cursor = 0
            for (arr in buffer) {
                val arrEnd = cursor + arr.size
                if (arrEnd <= relStart) { cursor = arrEnd; continue }
                if (cursor >= relEnd) break
                val from = maxOf(0, relStart - cursor)
                val to = minOf(arr.size, relEnd - cursor)
                for (i in from until to) out[outPos++] = arr[i] / 32768f
                cursor = arrEnd
            }
            return out
        }

        // Records the first streaming failure (subsequent failures are ignored) so the
        // caller can emit a single contained TranscriptResult.Failure.
        fun recordStreamFailure(ex: Exception, chunkStart: Int, chunkEnd: Int) {
            if (streamFailure == null) {
                Log.e(TAG, "[STREAM] Parakeet decode failed at chunk [$chunkStart,$chunkEnd)", ex)
                streamFailure = ex
            }
        }

        // ── Acoustic word-alternative capture (word-correction overhaul) ────────────────
        // Top-K token-swap candidates for a completed word: swap each runner-up token into
        // each position, detokenise, dedupe, keep the best length-normalised log-prob per
        // resulting word (including the word itself). The logits were in memory at decode
        // time, so this is near-free.
        fun tokenSwapCandidates(wordEmissions: List<TokenEmission>): HashMap<String, Double> {
            val tokenCount = wordEmissions.size
            val logProbSum = wordEmissions.sumOf { it.logProb }
            val candidates = HashMap<String, Double>()
            candidates[streaming.detokenizeTokens(wordEmissions.map { it.token })] = logProbSum / tokenCount
            val baseTokens = wordEmissions.map { it.token }.toMutableList()
            for (i in wordEmissions.indices) {
                for (alt in wordEmissions[i].topTokens) {
                    if (alt.token == wordEmissions[i].token) continue
                    baseTokens[i] = alt.token
                    val swappedText = streaming.detokenizeTokens(baseTokens)
                    baseTokens[i] = wordEmissions[i].token
                    if (swappedText.isBlank()) continue
                    val swappedNorm = (logProbSum - wordEmissions[i].logProb + alt.logProb) / tokenCount
                    if (swappedNorm > candidates.getOrDefault(swappedText, Double.NEGATIVE_INFINITY)) {
                        candidates[swappedText] = swappedNorm
                    }
                }
            }
            return candidates
        }

        // Filters [candidates] to plausible single words (dropping [emittedWord] itself —
        // the bar replaces it, so suggesting it is pointless) and writes the top
        // [MAX_CACHED_ALTERNATIVES] to the acoustic cache.
        fun cacheCandidates(emittedWord: String, candidates: Map<String, Double>) {
            val emittedKey = emittedWord.lowercase()
            val alternatives = candidates.entries
                .filter { (w, _) -> isValidCandidateWord(w) && w.lowercase() != emittedKey }
                .sortedByDescending { it.value }
                .take(MAX_CACHED_ALTERNATIVES)
                .map { (w, lp) -> WordAlternative(w, lp.toFloat()) }
            if (alternatives.isNotEmpty()) {
                acousticCache.put(emittedWord, alternatives)
                Log.d(TAG, "[ACOUSTIC] captured ${alternatives.size} alternative(s)")
            }
        }

        // The utterance is over: the pending word (if any) is now complete. Only top-K
        // token swaps are possible here — the local beam needs the chunk's encoder tensor,
        // which is already closed.
        fun flushPendingWordCandidates() {
            val wordEmissions = pendingEmissions
            if (wordEmissions.isEmpty()) return
            pendingEmissions = emptyList()
            val wordText = streaming.detokenizeTokens(wordEmissions.map { it.token })
            if (wordText.isBlank() || wordText.length < 2) return
            cacheCandidates(wordText, tokenSwapCandidates(wordEmissions))
        }

        // Captures the ASR model's own word-level alternatives while the chunk's encoder
        // tensor is still open: top-K token swaps for every completed word (cheap — the
        // logits were in memory at decode time) and a bounded local beam for
        // low-confidence words (the quality step, gated + capped). Results are written to
        // [acousticCache] keyed by the detokenised word and read by the word-correction
        // layer when the user places the cursor on a word.
        //
        // Word segmentation: a SentencePiece word starts at a word-initial token (▁) and
        // ends at the next word-initial token or the utterance end. The chunk's final
        // segment is deferred ([pendingEmissions]) — it is only known to be complete when
        // the next chunk (or the utterance end) arrives, because a word may continue
        // across the chunk boundary.
        //
        // A word is *beamable* only when fully contained in this chunk (its first
        // token's decoder-state snapshot lives in this chunk's [decoded.stateSnapshots]);
        // words continued from a previous chunk get token-swap candidates only.
        fun captureAcousticCandidates(
            decoded: ChunkDecodeResult,
            encOut: OnnxTensor,
            encLen: Int,
        ) {
            if (decoded.emissions.isEmpty()) return   // no new tokens; pending stays pending

            // 1. Segment this chunk's emissions into word segments (▁ starts a new word).
            val segments = ArrayList<List<TokenEmission>>()
            val current = ArrayList<TokenEmission>()
            for (em in decoded.emissions) {
                if (current.isNotEmpty() && streaming.tokenStartsWord(em.token)) {
                    segments.add(current.toList())   // copy — [current] is reused below
                    current.clear()
                }
                current.add(em)
            }
            if (current.isNotEmpty()) segments.add(current)

            // 2. Resolve which segments complete words and which stay pending.
            val completed = ArrayList<List<TokenEmission>>()
            val beamable = ArrayList<Boolean>()
            val pending = pendingEmissions
            if (pending.isNotEmpty()) {
                if (segments.size >= 2) {
                    completed.add(pending + segments[0])
                    beamable.add(false)   // continuation — start state is in a previous chunk
                    for (i in 1 until segments.size - 1) {
                        completed.add(segments[i])
                        beamable.add(true)
                    }
                    pendingEmissions = segments.last()
                } else {
                    pendingEmissions = pending + segments[0]
                }
            } else {
                for (i in 0 until segments.size - 1) {
                    completed.add(segments[i])
                    beamable.add(true)
                }
                pendingEmissions = segments.last()
            }

            // 3. Build candidates for each completed word and write them to the cache.
            var beamsUsed = 0
            for (idx in completed.indices) {
                val wordEmissions = completed[idx]
                val wordText = streaming.detokenizeTokens(wordEmissions.map { it.token })
                if (wordText.isBlank() || wordText.length < 2) continue
                val tokenCount = wordEmissions.size
                val logProbSum = wordEmissions.sumOf { it.logProb }
                // Per-word confidence: geometric mean of token probabilities (the same
                // formula as the utterance confidence). Words below the gate are the ones
                // the model itself is unsure about — the likely mis-hearings worth
                // correcting; confident words skip the beam (the common case, zero cost).
                val confidence = Math.exp(logProbSum / tokenCount).toFloat().coerceIn(0f, 1f)

                // Phase 1 — top-K token swaps (every word, near-free).
                val candidates = tokenSwapCandidates(wordEmissions)

                // Phase 2 — bounded local beam for low-confidence words (gated + capped):
                // finds genuine alternative *words* (different token sequences) that
                // single-token swapping cannot reach. Beam results supersede swap results
                // for the same word when they score higher.
                if (beamable[idx] && confidence < ACUSTIC_CONF_GATE && beamsUsed < MAX_BEAMS_PER_CHUNK) {
                    val firstFrame = wordEmissions.first().frame
                    val lastFrame = wordEmissions.last().frame
                    val snapshot = decoded.stateSnapshots.firstOrNull { it.frame == firstFrame }
                    if (snapshot != null) {
                        beamsUsed++
                        val beamEnd = (lastFrame + BEAM_FRAME_MARGIN).coerceAtMost(encLen - 1)
                        try {
                            for (alt in streaming.localWordBeam(encOut, encLen, firstFrame, beamEnd, snapshot)) {
                                if (alt.acousticLogProb > candidates.getOrDefault(alt.word, Double.NEGATIVE_INFINITY)) {
                                    candidates[alt.word] = alt.acousticLogProb.toDouble()
                                }
                            }
                        } catch (ex: Exception) {
                            Log.w(TAG, "[ACOUSTIC] local beam failed", ex)
                        }
                    }
                }

                cacheCandidates(wordText, candidates)
            }
        }

        // Decode the chunk [chunkStart, chunkEnd) using [rightEnd) as the right-context limit,
        // appending its tokens to [allTokens] and updating [state].
        fun decodeChunkRange(chunkStart: Int, chunkEnd: Int, rightEnd: Int) {
            if (streamFailure != null) return
            val bufStart = maxOf(0, chunkStart - LEFT_CONTEXT_SAMPLES)
            val bufEnd = minOf(rightEnd, totalSamples)
            if (bufEnd <= bufStart) return
            val (encOut, encLen) = try {
                streaming.encodeBuffer(extractRange(bufStart, bufEnd))
            } catch (ex: Exception) {
                recordStreamFailure(ex, chunkStart, chunkEnd)
                return
            }
            try {
                // The nominal chunk start, shifted by the previous chunk's frameDelta: a
                // positive delta means the TDT duration jump at the previous boundary
                // skipped frames past the nominal start (the one-shot decoder never
                // visits them — decoding them here would emit phantom tokens); a negative
                // delta resumes a few frames early after a safety-cap termination.
                val fL = (frameOffset(chunkStart - bufStart) + state.frameDelta).coerceIn(0, encLen)
                // Frame end: when the chunk extends to the end of the encoded buffer (no
                // right context beyond it — the short-utterance flush's final chunk), the
                // end must be the true encoder frame count [encLen]. [frameOffset] is a
                // position→frame map, not a total count: it underestimates the frame count
                // for a buffer of length n by 1 for most n (floor vs the encoder's ceiling
                // subsample), so using it here would drop the last encoder frame — the tail
                // of the word. For interior chunks (streaming, with right context) the
                // position map correctly selects the chunk's end frame within the buffer.
                val chunkReachesBufferEnd = (chunkEnd - bufStart) >= (bufEnd - bufStart)
                val fLC = if (chunkReachesBufferEnd) encLen else minOf(frameOffset(chunkEnd - bufStart), encLen)
                val decoded = streaming.decodeChunk(
                    encOut, encLen, fL, if (fLC <= fL) encLen else fLC, state
                )
                state = decoded.state
                allTokens += decoded.tokens
                totalLogProbSum += decoded.logProbSum
                totalEmissions += decoded.emissionCount
                // Capture acoustic word alternatives while the encoder tensor is open.
                captureAcousticCandidates(decoded, encOut, encLen)
            } catch (ex: Exception) {
                recordStreamFailure(ex, chunkStart, chunkEnd)
            } finally {
                encOut.close()
            }
        }

        // Emit the current merged text as a Partial (fully cleaned + hallucination-checked).
        //
        // The partial is cleaned with the SAME full pipeline as the final (filler removal,
        // stutter collapse, number normalisation, phrase dedup, spurious-period filter,
        // structural). This is safe here because the Parakeet path's tokens are append-only
        // (each token is emitted once and never re-decoded), so every display transform is
        // prefix-stable: the cleaned partial is always a prefix of the cleaned final, and
        // the words frozen into the field never change across strides.
        //
        // Cleaning the partial (not just structurally) is what makes the frozen field text
        // byte-identical to the final: the spurious-period filter runs on the FULL text,
        // where its short-segment heuristic has the complete word count since the previous
        // period. If the partial were only structurally cleaned, the display re-clean in
        // TextInjector would run the filter on a frozen SUBSTRING, where the first segment
        // is the tail of a longer sentence and the heuristic misfires, deleting a genuine
        // sentence-final period and lower-casing the next word.
        suspend fun emitPartial() {
            if (allTokens.isEmpty()) return
            val raw = streaming.detokenizeTokens(allTokens)
            if (raw.isBlank()) return
            val cleaned = if (postprocessingEnabled)
                raw.cleanTranscript(
                    language = engine.currentLanguage,
                    formatNumbersAsDigits = formatNumbersAsDigits,
                )
            else raw
            if (cleaned.isBlank()) return
            val confidence = utteranceConfidence()
            val toSend = if (isScriptHallucination(cleaned, engine.currentLanguage)) {
                val stripped = stripScriptHallucinations(cleaned, engine.currentLanguage)
                if (stripped == null) return
                TranscriptResult.Partial(stripped, confidence = confidence)
            } else TranscriptResult.Partial(cleaned, confidence = confidence)
            send(toSend)
        }

        // Decode every chunk whose full right context is now available, emitting a Partial each.
        // Stops immediately if a streaming failure was recorded. After decoding, trims the
        // buffer front to the next chunk's left-context start (audio older than that is never
        // read again) so the buffer stays bounded during long dictations.
        suspend fun decodeReadyChunks() {
            while (streamFailure == null && nextChunkStart + CHUNK_SAMPLES + RIGHT_CONTEXT_SAMPLES <= totalSamples) {
                val chunkEnd = nextChunkStart + CHUNK_SAMPLES
                decodeChunkRange(nextChunkStart, chunkEnd, chunkEnd + RIGHT_CONTEXT_SAMPLES)
                nextChunkStart = chunkEnd
                if (streamFailure != null) break
                emitPartial()
            }
            // Keep only what the next chunk's left context (or a flush) can still read.
            trimBufferFront(nextChunkStart - LEFT_CONTEXT_SAMPLES)
        }

        // Flush the remaining audio (decode all leftover chunks with whatever right context is
        // left) and emit a Final.
        //
        // Short utterances get a decode-context retry: the TDT decoder is extremely
        // context-sensitive on short clips (real-model probes: 600 ms of leading silence
        // flips blank decodes into correct words, while trailing digital silence can flip
        // a correct decode into blank). The primary attempt re-decodes the buffer from
        // frame 0 with [SHORT_UTT_LEAD_SILENCE_SAMPLES] of silence prepended; when that
        // yields blank text or a confidence below [CONFIDENCE_THRESHOLD], one retry runs
        // the original context (no lead). The best non-empty attempt wins even below the
        // threshold; only when every attempt is blank/garbage is a "Low confidence"
        // Failure emitted. Long utterances keep the plain continue-from-state decode.
        suspend fun flushAsFinal(isUtteranceBoundary: Boolean) {
            val isShort = totalSamples < SHORT_UTTERANCE_THRESHOLD_SAMPLES

            // Snapshot the session-start decode state so a short-utterance attempt can
            // re-decode the original buffer from frame 0. For < 2.5 s utterances the
            // buffer is never front-trimmed (the first chunk needs 2.04 s of audio before
            // decodeReadyChunks runs), so the snapshot is the complete utterance.
            val savedBuffer = if (isShort) buffer.toList() else null
            val savedTotalSamples = totalSamples

            // Decodes the buffer from [nextChunkStart] with whatever right context
            suspend fun decodeRemaining(padTail: Boolean = true): Pair<String, Float> {
                if (padTail && totalSamples < MIN_PADDING_SAMPLES) {
                    val padSamples = MIN_PADDING_SAMPLES - totalSamples
                    buffer.addLast(ShortArray(padSamples))
                    totalSamples += padSamples
                }
                while (streamFailure == null && nextChunkStart < totalSamples) {
                    val chunkEnd = minOf(nextChunkStart + CHUNK_SAMPLES, totalSamples)
                    decodeChunkRange(nextChunkStart, chunkEnd, totalSamples)
                    nextChunkStart = chunkEnd
                }
                // The utterance is over — complete the pending word (token swaps only).
                flushPendingWordCandidates()
                if (streamFailure != null) return "" to 0f
                return streaming.detokenizeTokens(allTokens) to utteranceConfidence()
            }

            // Restores the session-start decode state and re-decodes the original buffer
            // from frame 0, optionally with the lead silence prepended. The lead occupies
            // absolute positions [0, SHORT_UTT_LEAD_SILENCE_SAMPLES) (bufferStart = 0);
            // the decoder consumes it as blanks before reaching the speech. Returns
            // (raw text, engine confidence, gated confidence).
            suspend fun shortAttempt(withLead: Boolean, padTail: Boolean = true): Triple<String, Float, Float> {
                checkNotNull(savedBuffer)
                buffer.clear()
                if (withLead) buffer.addFirst(ShortArray(SHORT_UTT_LEAD_SILENCE_SAMPLES))
                buffer.addAll(savedBuffer)
                bufferStart = 0
                totalSamples = savedTotalSamples + if (withLead) SHORT_UTT_LEAD_SILENCE_SAMPLES else 0
                state = streaming.initialTdtState()
                allTokens.clear()
                totalLogProbSum = 0.0
                totalEmissions = 0
                nextChunkStart = 0
                pendingEmissions = emptyList()
                val (raw, confidence) = decodeRemaining(padTail = padTail)
                if (streamFailure != null) return Triple("", 0f, 0f)
                val gated = estimateConfidence(TranscriptResult.Final(raw, confidence = confidence))
                Log.d(TAG, "[FINAL] SHORT-UTT lead=$withLead padTail=$padTail len=${raw.length} confidence=%.2f gated=%.2f threshold=%.2f"
                    .format(confidence, gated, CONFIDENCE_THRESHOLD))
                return Triple(raw, confidence, gated)
            }

            var result: Pair<String, Float>? = null
            if (isShort) {
                // A plausible word has >= 2 word characters (letters/digits). This is the
                // plausibility floor that rejects no-word outputs — blank, lone
                // punctuation, a single letter or digit — that the TDT decoder emits on
                // very short or near-silent clips. Unlike the old 0.55 engine-confidence
                // gate, it never suppresses a correct-but-low-confidence decode: the
                // geometric-mean confidence of a single short word is fragile (one low-
                // probability token — often the trailing period — can drag it below any
                // fixed threshold), and other Parakeet tools emit whatever the model
                // decodes rather than gating on confidence.
                fun hasWord(raw: String): Boolean = raw.count { it.isLetterOrDigit() } >= 2

                // Short-utterance final decode: lead-silence context anchors the first phoneme
                // on short clips. If the lead attempt yields a plausible word, emit it;
                // otherwise retry with the original context (no lead) — the lead silence can
                // occasionally turn a decodable word into blank, and the plain context recovers it.
                val (bestRaw, bestConf) = run {
                    val (rawLead, confLead, _) = shortAttempt(withLead = true)
                    if (streamFailure != null) return   // contained failure emitted at end of stream
                    if (hasWord(rawLead)) {
                        rawLead to confLead
                    } else {
                        Log.d(TAG, "[SHORT-UTT] lead context yielded no word — retrying without lead silence")
                        val (rawPlain, confPlain, _) = shortAttempt(withLead = false)
                        if (streamFailure != null) return
                        if (hasWord(rawPlain)) rawPlain to confPlain else rawLead to confLead
                    }
                }
                if (!hasWord(bestRaw)) {
                    // No plausible word in either context — the clip is too short or
                    // ambiguous for the model to resolve a word (typically weak
                    // high-frequency fricatives that collapse to punctuation). Surface a
                    // "didn't catch that" cue instead of committing a hallucination or
                    // staying silent.
                    Log.d(TAG, "[FINAL] short utterance decoded no word in either context — emitting NoSpeech")
                    send(TranscriptResult.NoSpeech)
                    return
                }
                result = bestRaw to bestConf
            } else {
                val pair = decodeRemaining()
                if (streamFailure != null) return
                if (pair.first.isBlank()) {
                    Log.d(TAG, "[FINAL] flush produced no text")
                    return
                }
                result = pair
            }
            val (raw, confidence) = checkNotNull(result)
            val cleaned = if (postprocessingEnabled)
                raw.cleanTranscript(language = engine.currentLanguage, formatNumbersAsDigits = formatNumbersAsDigits)
            else raw
            val finalText = if (cleaned.isBlank()) raw else cleaned
            val base = TranscriptResult.Final(finalText, isUtteranceBoundary = isUtteranceBoundary, confidence = confidence)
            val toSend = if (isScriptHallucination(finalText, engine.currentLanguage)) {
                val stripped = stripScriptHallucinations(finalText, engine.currentLanguage)
                if (stripped == null) {
                    Log.w(TAG, "[FINAL] entirely non-script after strip — suppressing")
                    TranscriptResult.Failure(RuntimeException("Non-Latin script detected — likely hallucination"))
                } else {
                    Log.w(TAG, "[HALLUCINATION] stripped non-script chars from final")
                    applyGrammarCorrection(base.copy(text = stripped), engine.currentLanguage)
                }
            } else {
                applyGrammarCorrection(base, engine.currentLanguage)
            }
            send(toSend)
        }

        audio.buffer(Channel.UNLIMITED).takeWhile { streamFailure == null }.collect { incoming ->
            if (incoming.isSilenceBoundary) {
                // Flush any non-empty utterance at the boundary. Short utterances are
                // zero-padded to 1.25 s inside flushAsFinal (matching the stream-end gate),
                // and the short-utterance confidence gate suppresses low-confidence output,
                // so a brief noise burst is not committed. This keeps the boundary and
                // stream-end gates aligned — both pad + flush + confidence-gate.
                if (streamFailure == null && totalSamples > 0) {
                    Log.d(TAG, "[BOUNDARY] utterance boundary — flushing ${totalSamples.toSec()} as Final")
                    flushAsFinal(isUtteranceBoundary = true)
                }
                // Reset for the next utterance.
                buffer.clear()
                totalSamples = 0
                bufferStart = 0
                state = streaming.initialTdtState()
                allTokens.clear()
                nextChunkStart = 0
                totalLogProbSum = 0.0
                totalEmissions = 0
                pendingEmissions = emptyList()
                return@collect
            }

            buffer.addLast(incoming.samples)
            totalSamples += incoming.samples.size
            decodeReadyChunks()
        }

        // End of stream: emit the contained streaming failure (if any), otherwise flush the
        // remaining audio as a Final.
        val failure = streamFailure
        if (failure != null) {
            Log.e(TAG, "[STREAM] emitting contained failure at end of stream")
            send(TranscriptResult.Failure(failure))
        } else if (totalSamples > 0) {
            Log.d(TAG, "[FINAL] end-of-stream flush ${totalSamples.toSec()}")
            flushAsFinal(isUtteranceBoundary = false)
            streamFailure?.let { send(TranscriptResult.Failure(it)) }
        }
    }.flowOn(Dispatchers.Default)

    /**
     * Legacy whole-window re-transcription path. Used when the configured engine is not a
     * [ParakeetEngine] (the Whisper/Voxtral engines, or test fakes). The Parakeet path
     * ([transcribe]) uses chunked TDT with decoder-state carry-over; this path re-transcribes
     * the rolling window each stride and trims on a stable prefix.
     */
    private fun legacyTranscribe(
        audio: Flow<AudioChunk>,
        postprocessingEnabled: Boolean,
        formatNumbersAsDigits: Boolean,
    ): Flow<TranscriptResult> = channelFlow<TranscriptResult> {
        val window = ArrayDeque<ShortArray>()
        var windowSamples = 0
        var strideAccum = 0

        // Stable-chunk: ring buffer of the last STABLE_STRIDES cleaned word lists.
        val recentPartialWords = ArrayDeque<List<String>>()

        // Set to true whenever the audio window is trimmed (regular or force).  The very
        // next stride will start mid-sentence, so we skip all sentence capitalisation for
        // that one pass to avoid producing e.g. "Sind vielleicht…" instead of "sind vielleicht…".
        var isContinuationAfterTrim = false

        // Counts consecutive blank (silent) strides so we can fire a proactive silence trim
        // before the window balloons past 2 × TRIGGER_WINDOW_SAMPLES.  Reset to 0 whenever
        // a non-blank partial is received.
        var consecutiveBlankStrides = 0

        // Guard that suppresses the per-chunk [STRIDE] accumulating log after it has
        // already fired once for the current wait period (fires every 40 ms otherwise).
        var strideWaitLogged = false

        // Dynamic stride length (Fix 6): doubled to 2 s when consecutive strides diverge
        // without a stable prefix.  Giving the model a larger audio bite before the next
        // inference often resolves uncertainty caused by hesitant speech or a long sentence,
        // allowing a natural stable-chunk trim to fire.  Reset to baseline on every trim.
        var dynamicStrideSamples = STRIDE_SAMPLES

        // Cold-stride confidence gate: track whether we have ever emitted a partial in
        // this session and how many consecutive low-confidence cold strides have been
        // suppressed so far.  Reset at every silence-boundary utterance boundary so each
        // utterance within a continuous session gets its own cold-stride gate.
        var hasCommittedAnyPartial = false
        var coldStridesSuppressed = 0

        // P3 (final-pass re-emission guard): the last non-blank partial emitted in this
        // window session, with its engine confidence. When the final pass re-emits only
        // already-seen words (pattern continuation on a trimmed window) at lower
        // confidence than the partials, [guardFinalAgainstReemission] substitutes the
        // last partial's text so the committed output is never a truncation of what the
        // user already saw.
        var lastPartialText = ""
        var lastPartialConfidence = 1.0f

        fun buildChunk(): AudioChunk {
            val merged = ShortArray(windowSamples)
            var pos = 0
            for (arr in window) {
                arr.copyInto(merged, pos); pos += arr.size
            }
            return AudioChunk(merged)
        }

        /**
         * Removes [dropSamples] worth of audio from the front of [window].
         *
         * Handles chunk-boundary misalignment: if [dropSamples] falls in the middle of
         * a chunk, that chunk is sliced and its surviving tail is kept as the new head.
         */
        fun trimWindowFront(dropSamples: Int) {
            var toProcess = dropSamples
            while (toProcess > 0 && window.isNotEmpty()) {
                val head = window.first()
                if (head.size <= toProcess) {
                    toProcess -= head.size
                    window.removeFirst()
                } else {
                    // Slice: keep only the portion after the drop point.
                    window[0] = head.copyOfRange(toProcess, head.size)
                    toProcess = 0
                }
            }
            // toProcess > 0 only if the window was already smaller than dropSamples,
            // which cannot happen given our guard (dropSamples < windowSamples).
            windowSamples -= (dropSamples - toProcess)
        }

        /**
         * P3: guards a final-pass result against pattern-continuation re-emission.
         *
         * After a window trim the model sometimes decodes the retained tail as a
         * continuation of the just-committed sentence — re-emitting committed words
         * instead of the new content (TDT has no memory of what was already emitted).
         * The tell-tale signature: the final's words are a strict prefix of the last
         * partial's words AND the final's engine confidence is lower than the partial's
         * (the re-emitted pattern is decoded less confidently than the live partial).
         *
         * When that signature matches, the last partial's text is returned instead: it
         * is at least as complete as the final and reflects what the user saw. All other
         * cases (final extends the partial, same-length refinement, truncated-leading
         * case handled by TextInjector's composing anchor) pass the final through.
         */
        fun guardFinalAgainstReemission(finalText: String, finalConfidence: Float): String {
            if (lastPartialText.isEmpty()) return finalText
            val finalWords = finalText.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            val partialWords = lastPartialText.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (finalWords.isEmpty() || partialWords.size <= finalWords.size) return finalText
            val isPrefixOfPartial = finalWords.zip(partialWords).all { (a, b) ->
                a.normalizedForComparison() == b.normalizedForComparison()
            }
            if (isPrefixOfPartial && finalConfidence < lastPartialConfidence) {
                Log.w(
                    TAG,
                    "[FINAL] re-emission guard: final (${finalWords.size}w, conf=%.2f) is a" +
                            " lower-confidence prefix of the last partial (${partialWords.size}w," +
                            " conf=%.2f) — keeping the partial text".format(finalConfidence, lastPartialConfidence)
                )
                return lastPartialText
            }
            return finalText
        }

        audio.buffer(Channel.UNLIMITED).collect { incoming ->
            if (incoming.isSilenceBoundary) {
                if (windowSamples >= MIN_FINAL_SAMPLES) {
                    Log.d(TAG, "[BOUNDARY] utterance boundary — flushing ${windowSamples.toSec()} as Final")
                    val rawChunk = buildChunk()
                    val finalChunk = if (rawChunk.samples.size < MIN_FINAL_SAMPLES)
                        AudioChunk(rawChunk.samples.copyOf(MIN_FINAL_SAMPLES))
                    else rawChunk
                    val result = engine.transcribe(finalChunk)
                    val isContinuation = isContinuationAfterTrim
                    val baseCleaned: TranscriptResult = when (result) {
                        is TranscriptResult.Partial -> TranscriptResult.Final(
                            text = if (postprocessingEnabled) result.text.cleanTranscriptStructural(isContinuation) else result.text,
                            isUtteranceBoundary = true,
                            confidence = result.confidence,
                        )

                        is TranscriptResult.Final -> result.copy(
                            text = if (postprocessingEnabled) result.text.cleanTranscriptStructural(isContinuation) else result.text,
                            isUtteranceBoundary = true,
                        )

                        else -> result
                    }
                    // P3: guard against pattern-continuation re-emission on the boundary final.
                    val cleaned: TranscriptResult = (baseCleaned as? TranscriptResult.Final)?.let { final ->
                        final.copy(text = guardFinalAgainstReemission(final.text, final.confidence))
                    } ?: baseCleaned
                    Log.d(TAG, "[BOUNDARY] Final = ${cleaned.logLabel()}")
                    val cleanedText = (cleaned as? TranscriptResult.Final)?.text
                    // E3: strip non-script artefacts from the final instead of discarding the
                    // whole sentence. Only emit a Failure when nothing alphanumeric survives.
                    val toSend = if (cleanedText != null && isScriptHallucination(
                            cleanedText,
                            language = engine.currentLanguage
                        )
                    ) {
                        val stripped = stripScriptHallucinations(cleanedText, language = engine.currentLanguage)
                        if (stripped != null) {
                            Log.w(TAG, "[HALLUCINATION] stripped non-script chars from boundary final (${cleanedText.length} → ${stripped.length} chars)")
                            applyGrammarCorrection(cleaned.copy(text = stripped), engine.currentLanguage)
                        } else {
                            Log.w(TAG, "[HALLUCINATION] boundary final entirely non-script after strip — suppressing")
                            TranscriptResult.Failure(RuntimeException("Unexpected script detected — likely hallucination"))
                        }
                    } else {
                        applyGrammarCorrection(cleaned, engine.currentLanguage)
                    }
                    send(toSend)
                } else if (windowSamples > 0) {
                    Log.d(
                        TAG,
                        "[BOUNDARY] utterance boundary — window too short (${windowSamples.toSec()}), discarding"
                    )
                }
                window.clear()
                windowSamples = 0
                strideAccum = 0
                recentPartialWords.clear()
                isContinuationAfterTrim = false
                consecutiveBlankStrides = 0
                strideWaitLogged = false
                // Reset cold-stride gate for the next utterance within this session.
                hasCommittedAnyPartial = false
                coldStridesSuppressed = 0
                // Reset the P3 guard: the next utterance is a fresh window session.
                lastPartialText = ""
                lastPartialConfidence = 1.0f
                return@collect
            }

            window.addLast(incoming.samples)
            windowSamples += incoming.samples.size
            strideAccum += incoming.samples.size

            // Hard ceiling - evict oldest audio once the window hits 30 s.
            var evicted = false
            while (windowSamples > MAX_WINDOW_SAMPLES) {
                windowSamples -= window.removeFirst().size
                evicted = true
            }
            if (evicted) {
                Log.d(
                    TAG, "[WINDOW] MAX_WINDOW (${MAX_WINDOW_SAMPLES.toSec()}) ceiling hit" +
                            " → evicted oldest audio, window now ${windowSamples.toSec()}"
                )
            }

            // Log once (not every chunk) when a stride boundary is crossed but
            // MIN_SAMPLES is not yet met.
            if (strideAccum >= dynamicStrideSamples && windowSamples < MIN_SAMPLES) {
                if (!strideWaitLogged) {
                    Log.d(
                        TAG, "[STRIDE] stride ready (strideAccum=${strideAccum.toSec()})" +
                                " but window=${windowSamples.toSec()} < MIN_SAMPLES=${MIN_SAMPLES.toSec()}" +
                                " - still accumulating"
                    )
                    strideWaitLogged = true
                }
            }

            if (windowSamples >= MIN_SAMPLES && strideAccum >= dynamicStrideSamples) {
                strideWaitLogged = false  // reset for the next accumulation period
                strideAccum = 0
                val chunk = buildChunk()
                Log.d(TAG, "[STRIDE] firing - window=${chunk.samples.size.toSec()}")

                // Run inference synchronously.  The AudioRecord coroutine is not
                // blocked because it writes into the Channel.UNLIMITED buffer above.
                val result = engine.transcribe(chunk)
                Log.d(TAG, "[PARTIAL] raw   = ${result.logLabel()}")

                // Snapshot the continuation flag but do NOT reset it yet - if this
                // stride produces blank output after cleaning the flag must survive
                // to the next stride that actually emits a real partial (Bug 1A fix).
                val isContinuation = isContinuationAfterTrim

                // Structural cleaning is used for the emitted text so that TextInjector
                // aligns on text whose word count matches what the model produced.
                // Full cleaning (including filler/stutter/dedup) is computed separately
                // for the stable-chunk word tracking, which benefits from consistent
                // word-count reduction across consecutive strides.
                val rawText = when (result) {
                    is TranscriptResult.Partial -> result.text
                    is TranscriptResult.Final -> result.text
                    else -> ""
                }
                val structuralTextRaw = if (postprocessingEnabled)
                    rawText.cleanTranscriptStructural(isContinuation) else rawText
                val fullCleanedTextRaw = if (postprocessingEnabled)
                    rawText.cleanTranscript(
                        isContinuation,
                        language = engine.currentLanguage,
                        formatNumbersAsDigits = formatNumbersAsDigits
                    ) else rawText

                // E3: soft script-hallucination handling. Instead of discarding an entire
                // partial when a single warm-up artefact token lands outside the
                // Latin/Cyrillic/Greek ranges (common on the cold first stride), strip only
                // the offending characters and keep the legitimate script content. If
                // nothing alphanumeric survives the strip, the partial collapses to blank
                // and is handled by the blank-stride branch below — never as a Failure,
                // which would abort the whole recording on one bad token.
                val hallucinated = isScriptHallucination(structuralTextRaw, language = engine.currentLanguage)
                val structuralText = if (hallucinated)
                    stripScriptHallucinations(structuralTextRaw, language = engine.currentLanguage) ?: ""
                else structuralTextRaw
                val fullCleanedText = if (hallucinated)
                    stripScriptHallucinations(fullCleanedTextRaw, language = engine.currentLanguage) ?: ""
                else fullCleanedTextRaw
                if (hallucinated) {
                    if (structuralText.isBlank()) {
                        Log.w(TAG, "[HALLUCINATION] partial entirely non-script after strip — treating as blank (${structuralTextRaw.length} chars)")
                    } else {
                        Log.w(TAG, "[HALLUCINATION] stripped non-script chars from partial (${structuralTextRaw.length} → ${structuralText.length} chars)")
                    }
                }

                val cleaned: TranscriptResult = when (result) {
                    is TranscriptResult.Partial -> result.copy(text = structuralText)
                    // Engine returned Final for this audio window (Parakeet can do this);
                    // treat as a streaming Partial and carry the confidence through so the
                    // cold-stride gate still works correctly on such results.
                    is TranscriptResult.Final -> TranscriptResult.Partial(structuralText, confidence = result.confidence)
                    else -> result
                }

                if (cleaned is TranscriptResult.Partial && cleaned.text.isBlank()) {
                    Log.d(TAG, "[PARTIAL] discarded - blank after cleaning")

                    // Count consecutive silent strides and fire a proactive trim if the window
                    // has grown past the trigger threshold during the silence.  Normal
                    // stable-chunk logic never fires on blank strides (no words to compare),
                    // so without this the window can balloon to 2× TRIGGER before the next
                    // stable trim can occur - causing an aggressive drop that loses sentences.
                    consecutiveBlankStrides++
                    Log.d(TAG, "[SILENCE] blank stride $consecutiveBlankStrides/$SILENCE_TRIM_STRIDES")
                    if (consecutiveBlankStrides >= SILENCE_TRIM_STRIDES &&
                        windowSamples > TRIGGER_WINDOW_SAMPLES
                    ) {
                        val dropSamples = windowSamples - (TRIGGER_WINDOW_SAMPLES + MIN_CONTEXT_SAMPLES)
                        if (dropSamples >= MIN_TRIM_SAMPLES) {
                            val windowBefore = windowSamples.toSec()
                            trimWindowFront(dropSamples)
                            Log.d(
                                TAG, "[STABLE] SILENCE TRIM ($SILENCE_TRIM_STRIDES blank strides)" +
                                        " - window $windowBefore → ${windowSamples.toSec()}"
                            )
                            send(TranscriptResult.WindowTrimmed())
                            recentPartialWords.clear()
                            isContinuationAfterTrim = true
                            consecutiveBlankStrides = 0
                            dynamicStrideSamples = STRIDE_SAMPLES
                        }
                    }
                }

                if (cleaned is TranscriptResult.Failure) {
                    Log.w(TAG, "[PARTIAL] inference failure", cleaned.cause)
                }

                if (cleaned is TranscriptResult.Partial && cleaned.text.isNotBlank()) {
                    Log.d(TAG, "[PARTIAL] clean = ${cleaned.text.length} chars")
                    consecutiveBlankStrides = 0  // silence streak broken
                    // The flag was consumed by a real partial - reset it now.
                    // A trim later in this same stride can re-arm it for the *next* stride.
                    isContinuationAfterTrim = false

                    // E3: hallucinated characters were already stripped above (and an
                    // all-hallucination partial collapsed to blank, handled by the blank
                    // branch). Emit the cleaned partial directly — no Failure, so a single
                    // bad token can no longer abort the recording or nuke the first words.

                    // ── Cold-stride confidence gate ───────────────────────────────────
                    // On the very first non-blank partial of a session (or of an utterance
                    // within a session), Parakeet sometimes emits a hallucination driven by
                    // room tone / ambient noise.  These cold-stride hallucinations have
                    // characteristically low per-token softmax probabilities.
                    //
                    // Gate: if no partial has been committed yet AND the engine-reported
                    // confidence is below COLD_STRIDE_CONFIDENCE_THRESHOLD, suppress this
                    // partial (treat it like a blank stride).  The audio is NOT discarded —
                    // it stays in the window and will be re-transcribed on the next stride
                    // with +1 s of additional context.  If the output was real speech the
                    // next stride will confirm it with higher confidence.  If it was a
                    // hallucination it will not reappear.
                    //
                    // Safety valve: after COLD_STRIDE_MAX_SUPPRESSED consecutive suppressed
                    // cold strides we give up suppressing so a genuine low-energy whisper
                    // or noisy environment doesn't permanently block output.
                    val partialConfidence = (cleaned as? TranscriptResult.Partial)?.confidence ?: 1.0f
                    val isColdStrideSuppressed = !hasCommittedAnyPartial &&
                            coldStridesSuppressed < COLD_STRIDE_MAX_SUPPRESSED &&
                            partialConfidence < COLD_STRIDE_CONFIDENCE_THRESHOLD
                    if (isColdStrideSuppressed) {
                        coldStridesSuppressed++
                        Log.w(
                            TAG,
                            "[CONFIDENCE] cold-stride suppressed (confidence=%.2f < %.2f, streak=%d/%d): \"%s\""
                                .format(
                                    partialConfidence,
                                    COLD_STRIDE_CONFIDENCE_THRESHOLD,
                                    coldStridesSuppressed,
                                    COLD_STRIDE_MAX_SUPPRESSED,
                                    cleaned.text
                                )
                        )
                        // Do NOT send.  Treat this stride as blank for consecutive-blank
                        // counting so the silence-trim logic still fires if needed.
                        consecutiveBlankStrides++
                    } else {
                        if (!hasCommittedAnyPartial) {
                            Log.d(
                                TAG,
                                "[CONFIDENCE] cold-stride passed (confidence=%.2f >= %.2f)"
                                    .format(partialConfidence, COLD_STRIDE_CONFIDENCE_THRESHOLD)
                            )
                        }
                        hasCommittedAnyPartial = true
                        coldStridesSuppressed = 0
                        send(cleaned)
                        // Record for the P3 final-pass re-emission guard.
                        lastPartialText = cleaned.text
                        lastPartialConfidence = partialConfidence
                    }

                    // Use fully-cleaned word list (filler/stutter/dedup applied) for
                    // stable-chunk tracking so consecutive strides normalise to the same
                    // words regardless of filler inconsistency.
                    val words = fullCleanedText.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                    recentPartialWords.addLast(words)
                    if (recentPartialWords.size > STABLE_STRIDES) recentPartialWords.removeFirst()

                    val endsWithSentence = cleaned.text.trimEnd().lastOrNull()
                        ?.let { it == '.' || it == '!' || it == '?' } == true
                    // Sentence-final shortcut: when the last word of the transcript has been
                    // stable for STABLE_STRIDES consecutive strides AND the window is still
                    // compact (≤ TRIGGER_WINDOW_SAMPLES), emit a Final immediately and reset
                    // the audio window.
                    //
                    // The reset is what makes streaming output match non-streaming quality:
                    // the committed sentence's audio is dropped, so the next sentence is
                    // transcribed from a clean window exactly like a standalone utterance.
                    // Without the reset the next partial re-emits the committed sentence
                    // (the model still sees its audio) and TextInjector re-commits it as a
                    // new session — visible duplication (verified with the real model on a
                    // 9.5 s two-sentence recording: sentence 1 appeared twice).
                    //
                    // WindowTrimmed(stableWords = words) re-anchors TextInjector's
                    // committed-word tracking to the committed sentence so the next
                    // partial's suffix-overlap alignment finds the correct boundary.
                    if (endsWithSentence && recentPartialWords.size >= STABLE_STRIDES && windowSamples <= TRIGGER_WINDOW_SAMPLES) {
                        val terminalWord = words.lastOrNull()?.normalizedForComparison()
                        val prevTerminalWord = recentPartialWords[recentPartialWords.size - 2]
                            .lastOrNull()?.normalizedForComparison()
                        if (terminalWord != null && terminalWord == prevTerminalWord) {
                        Log.d(TAG, "[SENTENCE_FINAL] stable punctuation endpoint (${cleaned.text.length} chars)")
                            val sentenceFinal = TranscriptResult.Final(cleaned.text, isUtteranceBoundary = true)
                            send(applyGrammarCorrection(sentenceFinal, engine.currentLanguage))
                            // Reset the audio window: the sentence's audio has been fully
                            // committed and is no longer needed. The next sentence starts
                            // from a clean window (non-streaming-equivalent conditions).
                            window.clear()
                            windowSamples = 0
                            strideAccum = 0
                            recentPartialWords.clear()
                            isContinuationAfterTrim = false
                            consecutiveBlankStrides = 0
                            strideWaitLogged = false
                            // Reset the cold-stride gate: the next sentence is a fresh
                            // utterance and gets its own cold-stride confidence budget.
                            hasCommittedAnyPartial = false
                            coldStridesSuppressed = 0
                            // Reset the P3 guard: the next sentence is a fresh window session.
                            lastPartialText = ""
                            lastPartialConfidence = 1.0f
                            dynamicStrideSamples = STRIDE_SAMPLES
                            send(TranscriptResult.WindowTrimmed(stableWords = words))
                            return@collect
                        }
                    }

                    if (recentPartialWords.size < STABLE_STRIDES) {
                        Log.d(
                            TAG, "[STABLE] need $STABLE_STRIDES strides of history," +
                                    " have ${recentPartialWords.size} - waiting"
                        )
                    } else if (windowSamples <= TRIGGER_WINDOW_SAMPLES) {
                        Log.d(
                            TAG, "[STABLE] window=${windowSamples.toSec()}" +
                                    " ≤ TRIGGER=${TRIGGER_WINDOW_SAMPLES.toSec()} - no trim needed"
                        )
                    } else {
                        val stableCount = longestCommonPrefixLength(recentPartialWords.toList())
                        val totalWords = words.size

                        if (stableCount == 0) {
                            // No stride agrees on even a single leading word.  If the window is
                            // still within reasonable bounds, double the stride so the next
                            // inference sees a larger audio bite — often this resolves the
                            // uncertainty without a forced trim.  If the window has grown past
                            // FORCE_TRIM_WINDOW_SAMPLES we're in a divergence loop: trim
                            // aggressively back to TRIGGER + MIN_CONTEXT.
                            if (windowSamples > FORCE_TRIM_WINDOW_SAMPLES) {
                                val dropSamples = windowSamples - (TRIGGER_WINDOW_SAMPLES + MIN_CONTEXT_SAMPLES)
                                val windowBefore = windowSamples.toSec()
                                trimWindowFront(dropSamples)
                                Log.d(
                                    TAG,
                                    "[STABLE] FORCE TRIM (diverged) - window $windowBefore → ${windowSamples.toSec()}"
                                )
                                send(TranscriptResult.WindowTrimmed())
                                recentPartialWords.clear()
                                isContinuationAfterTrim = true
                                dynamicStrideSamples = STRIDE_SAMPLES
                            } else {
                                if (dynamicStrideSamples == STRIDE_SAMPLES) {
                                    dynamicStrideSamples = STRIDE_SAMPLES * 2
                                    Log.d(
                                        TAG, "[STABLE] no common prefix - doubling stride to" +
                                                " ${dynamicStrideSamples.toSec()} to give model more context"
                                    )
                                } else {
                                    Log.d(
                                        TAG, "[STABLE] no common prefix across last $STABLE_STRIDES" +
                                                " strides (words diverged) - stride already at ${dynamicStrideSamples.toSec()}"
                                    )
                                }
                            }
                        } else {
                            // When stableCount < totalWords the model is still decoding the
                            // (stableCount+1)-th word; back off by 1 so that unstable word's
                            // audio stays inside the context window and can be corrected on the
                            // next stride.  This avoids trimming at a position where a partial
                            // token like "Mitpush" becomes permanently committed before the
                            // model settles on "mit Push to talk".
                            val safeStableCount = if (stableCount < totalWords)
                                maxOf(1, stableCount - 1) else stableCount

                            // Proportional estimate: stable words occupy roughly
                            // (safeStableCount / totalWords) of the window duration.
                            // We then search near that estimate for a low-energy chunk boundary
                            // so the model's next window always starts on clean audio rather
                            // than mid-phoneme. Falls back to the proportional estimate if no
                            // silence boundary is found within the valid trim range.
                            val proportionalEst = (safeStableCount.toFloat() / totalWords * windowSamples).toInt()
                            val maxDrop = (windowSamples - MIN_CONTEXT_SAMPLES).coerceAtLeast(0)
                            val targetDrop = maxOf(0, proportionalEst - MIN_CONTEXT_SAMPLES)
                            val dropSamples = if (targetDrop >= MIN_TRIM_SAMPLES) {
                                findSilenceCutPoint(window, targetDrop, MIN_TRIM_SAMPLES, maxDrop)
                            } else {
                                targetDrop
                            }

                            Log.d(
                                TAG, "[STABLE] prefix=$stableCount/$totalWords words stable" +
                                        " (safe=$safeStableCount, ≈${proportionalEst.toSec()})," +
                                        " keeping ${MIN_CONTEXT_SAMPLES.toSec()} context" +
                                        " → drop=${dropSamples.toSec()}, min_required=${MIN_TRIM_SAMPLES.toSec()}"
                            )

                            if (dropSamples >= MIN_TRIM_SAMPLES) {
                                val windowBefore = windowSamples.toSec()
                                trimWindowFront(dropSamples)
                                Log.d(TAG, "[STABLE] TRIM - window $windowBefore → ${windowSamples.toSec()}")

                                // Notify the TextInjector so it can shrink its committed-word
                                // tracking to the tail words still inside the new window.
                                // Without this, the suffix-overlap alignment fails for every
                                // stride after the trim, silently dropping middle sentences.
                                // P4: carry the confirmed-stable leading words so TextInjector
                                // can anchor directly without relying on a stale field re-read.
                                val stableWordList = words.take(safeStableCount)
                                send(TranscriptResult.WindowTrimmed(stableWords = stableWordList))

                                // Clear history so we don't immediately retrigger on
                                // the same stable prefix in the next stride.
                                recentPartialWords.clear()

                                // The next partial starts mid-sentence - suppress initial-letter
                                // capitalisation for that one stride.
                                isContinuationAfterTrim = true

                                // Trim succeeded: restore baseline stride for the new window.
                                dynamicStrideSamples = STRIDE_SAMPLES
                            } else {
                                Log.d(
                                    TAG, "[STABLE] drop=${dropSamples.toSec()}" +
                                            " < MIN_TRIM=${MIN_TRIM_SAMPLES.toSec()} - skipping trim"
                                )
                            }
                        }
                    }
                }
            }
        }

        // No partialJobs to join - inference is sequential inside the collect loop above.

        if (windowSamples > 0) {
            val rawChunk = buildChunk()

            // ── Short-utterance path ──────────────────────────────────────────────────
            // When the total VAD-active audio is below SHORT_UTTERANCE_THRESHOLD_SAMPLES
            // (2.5 s) the rolling-window logic has had little or no opportunity to fire,
            // so the normal MIN_FINAL_SAMPLES pad is too small to anchor the encoder.
            // Instead we skip the rolling-window final pass entirely and run a single
            // inference on a buffer zero-padded to at least MIN_PADDING_SAMPLES (1.25 s).
            // This correctly handles:
            //   • Utterances that ended before the first stride fired (< MIN_SAMPLES).
            //   • Very short phrases where the partial pipeline produced nothing useful.
            //
            // Long recordings (≥ SHORT_UTTERANCE_THRESHOLD_SAMPLES) follow the original
            // path unchanged.
            val isShortUtterance = windowSamples < SHORT_UTTERANCE_THRESHOLD_SAMPLES
            val finalChunk: AudioChunk
            if (isShortUtterance) {
                val paddedSamples = zeroPadToMinimum(rawChunk.samples, MIN_PADDING_SAMPLES)
                finalChunk = AudioChunk(paddedSamples)
                val padded = paddedSamples.size != rawChunk.samples.size
                Log.d(
                    TAG, "[FINAL] SHORT-UTT window=${rawChunk.samples.size.toSec()}" +
                            if (padded) " → zero-padded to ${finalChunk.samples.size.toSec()}" else " (no padding needed)"
                )
            } else {
                // Normal path: pad to MIN_FINAL_SAMPLES (1.25 s) only if needed.
                // ShortArray.copyOf fills added positions with 0 (silence); the TDT
                // decoder advances through blank frames at the tail without emitting
                // spurious tokens.
                finalChunk = if (rawChunk.samples.size < MIN_FINAL_SAMPLES)
                    AudioChunk(rawChunk.samples.copyOf(MIN_FINAL_SAMPLES))
                else rawChunk
                val padded = finalChunk.samples.size != rawChunk.samples.size
                Log.d(
                    TAG, "[FINAL] window=${rawChunk.samples.size.toSec()}" +
                            if (padded) " → padded to ${finalChunk.samples.size.toSec()}" else " (no padding needed)"
                )
            }

            val result = engine.transcribe(finalChunk)
            Log.d(TAG, "[FINAL] raw   = ${result.logLabel()}")

            val baseCleaned: TranscriptResult = when (result) {
                is TranscriptResult.Partial -> TranscriptResult.Final(
                    text = if (postprocessingEnabled) result.text.cleanTranscriptStructural(isContinuationAfterTrim) else result.text,
                    isUtteranceBoundary = isShortUtterance,
                    confidence = result.confidence,
                )

                is TranscriptResult.Final -> result.copy(
                    text = if (postprocessingEnabled) result.text.cleanTranscriptStructural(isContinuationAfterTrim) else result.text,
                    isUtteranceBoundary = isShortUtterance || result.isUtteranceBoundary,
                )

                else -> result
            }
            // P3: guard against pattern-continuation re-emission on the final pass.
            val cleaned: TranscriptResult = (baseCleaned as? TranscriptResult.Final)?.let { final ->
                final.copy(text = guardFinalAgainstReemission(final.text, final.confidence))
            } ?: baseCleaned
            Log.d(TAG, "[FINAL] clean = ${cleaned.logLabel()}")

            // ── Short-utterance confidence gate ───────────────────────────────────────
            // Apply ONLY on the short-utterance path to suppress low-confidence results
            // (empty text, single punctuation characters, or implausible single-char words)
            // that are characteristic of hallucinations after zero-padding.
            // Long-utterance results are never gated — gating partials in continuous
            // dictation would cause unacceptable silent drops in the middle of sentences.
            // Engine failures (Failure, WindowTrimmed) are passed through unchanged.
            if (isShortUtterance && (cleaned is TranscriptResult.Final)) {
                val confidence = estimateConfidence(cleaned)
                Log.d(TAG, "[FINAL] SHORT-UTT confidence=%.2f threshold=%.2f".format(confidence, CONFIDENCE_THRESHOLD))
                if (confidence < CONFIDENCE_THRESHOLD) {
                    Log.w(
                        TAG,
                        "[CONFIDENCE] Short utterance below threshold ($confidence < $CONFIDENCE_THRESHOLD) — suppressing"
                    )
                    send(TranscriptResult.Failure(RuntimeException("Low confidence — could not understand")))
                    return@channelFlow
                }
            }

            val finalText = (cleaned as? TranscriptResult.Final)?.text
            // E3: strip non-script artefacts from the final instead of discarding the whole
            // sentence. Only emit a Failure when nothing alphanumeric survives the strip.
            val finalToSend =
                if (finalText != null && isScriptHallucination(finalText, language = engine.currentLanguage)) {
                    val stripped = stripScriptHallucinations(finalText, language = engine.currentLanguage)
                    if (stripped != null) {
                        Log.w(TAG, "[HALLUCINATION] stripped non-script chars from final (${finalText.length} → ${stripped.length} chars)")
                        applyGrammarCorrection(cleaned.copy(text = stripped), engine.currentLanguage)
                    } else {
                        Log.w(TAG, "[HALLUCINATION] final entirely non-script after strip — suppressing")
                        TranscriptResult.Failure(RuntimeException("Non-Latin script detected — likely hallucination"))
                    }
                } else {
                    applyGrammarCorrection(cleaned, engine.currentLanguage)
                }
            send(finalToSend)
        }

    }.flowOn(Dispatchers.Default)
}
