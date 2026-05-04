package dev.brgr.outspoke.inference

import android.util.Log
import dev.brgr.outspoke.audio.AudioChunk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
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
private val MISSING_SENTENCE_SPACE_RE = Regex("""([.!?])([A-Za-zÀ-ÖØ-öø-ÿ])""")

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
}

/**
 * Collapses consecutive repeated phrases (including single words) that the model
 * hallucinated or duplicated.
 *
 * Works left-to-right: at each position it finds the longest phrase (length ≥ 1)
 * starting there that is immediately followed by one or more identical copies
 * (case-insensitive, punctuation-stripped comparison via [normalizedForComparison]).
 * All copies beyond the first are dropped.
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
        for (len in (remaining / 2) downTo 1) {
            if (i + len * 2 > words.size) continue

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
                    Log.d(TAG, "[DEDUP] \"${phrase.joinToString(" ")}\" ×$total → kept 1")
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
            Log.d(TAG, "[STUTTER] \"$currentWord\" ×$count → kept 1")
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
internal fun String.filterSpuriousPeriods(): String {
    if (!contains('.')) return this

    // Split into tokens, preserving whitespace attachment by splitting on spaces.
    val words = trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return this

    val result = mutableListOf<String>()
    // Words accumulated since the start of the utterance or since the last kept period.
    var wordCountSinceLastPeriod = 0

    for (i in words.indices) {
        val word = words[i]
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
            Log.d(TAG, "[CLEAN:SPURIOUS_PERIOD] removed period after \"$base\" ($reason)")
            // Emit the word without its trailing period.
            // base.isEmpty() is unreachable in practice (a word that is just "." would be
            // filtered by hasPeriod's !word.endsWith("..") check and normalised away
            // upstream) but is kept as a safety guard so we never emit an empty token.
            result.add(if (base.isEmpty()) word else base)
            // Do NOT reset wordCountSinceLastPeriod — the removed period was not a real
            // sentence boundary, so counting continues from where it was.
            wordCountSinceLastPeriod = segmentWordCount
        } else {
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
 */
internal fun String.applySentenceCapitalization(skipInitialCapitalize: Boolean = false): String {
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
            if (shouldGuard) {
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
 */
internal fun String.cleanTranscriptStructural(isContinuation: Boolean = false): String {
    if (isBlank()) {
        Log.d(TAG, "[CLEAN] blank input → discarded")
        return ""
    }
    val input = trim()

    val afterLeadDots = input.replace(LEADING_DOTS_RE, "")
    if (afterLeadDots != input) Log.d(TAG, "[CLEAN:LEAD_DOTS]   \"$input\" → \"$afterLeadDots\"")

    val afterLeadPunct = afterLeadDots.replace(LEADING_PUNCT_RE, "")
    if (afterLeadPunct != afterLeadDots) Log.d(TAG, "[CLEAN:LEAD_PUNCT]  \"$afterLeadDots\" → \"$afterLeadPunct\"")

    val afterTrailDots = afterLeadPunct.replace(TRAILING_DOTS_RE, ".")
    if (afterTrailDots != afterLeadPunct) Log.d(TAG, "[CLEAN:TRAIL_DOTS]  \"$afterLeadPunct\" → \"$afterTrailDots\"")

    val afterSentSpace = afterTrailDots.replace(MISSING_SENTENCE_SPACE_RE, "$1 $2").trim()
    if (afterSentSpace != afterTrailDots.trim()) Log.d(
        TAG,
        "[CLEAN:SENT_SPACE]  \"${afterTrailDots.trim()}\" → \"$afterSentSpace\""
    )

    val afterCaps = afterSentSpace.applySentenceCapitalization(skipInitialCapitalize = isContinuation)
    if (afterCaps != afterSentSpace) Log.d(TAG, "[CLEAN:CAPITALIZE]  \"$afterSentSpace\" → \"$afterCaps\"")

    return if (afterCaps.none { it.isLetterOrDigit() }) {
        Log.d(TAG, "[CLEAN] \"$input\" → discarded (no alphanumeric content)")
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
 */
internal fun String.cleanTranscript(
    isContinuation: Boolean = false,
    language: String = "en",
    formatNumbersAsDigits: Boolean = true,
): String {
    if (isBlank()) {
        Log.d(TAG, "[CLEAN] blank input → discarded")
        return ""
    }
    val input = trim()

    val afterFillers = input.removeFillerWords(language)
    if (afterFillers != input) Log.d(TAG, "[CLEAN:FILLERS]     \"$input\" → \"$afterFillers\"")

    val afterStutters = afterFillers.collapseStutters()
    if (afterStutters != afterFillers) Log.d(TAG, "[CLEAN:STUTTER]     \"$afterFillers\" → \"$afterStutters\"")

    val afterNumbers = if (formatNumbersAsDigits) {
        val words = afterStutters.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val normalised = NumberNormaliser.normalise(words, language)
        val joined = normalised.joinToString(" ")
        if (joined != afterStutters) Log.d(TAG, "[CLEAN:NUMBERS]     \"$afterStutters\" → \"$joined\"")
        joined
    } else {
        afterStutters
    }

    val afterDedup = afterNumbers.collapseRepeatedPhrases()
    if (afterDedup != afterStutters) Log.d(TAG, "[CLEAN:PHRASES]     \"$afterStutters\" → \"$afterDedup\"")

    val afterSpuriousPeriods = afterDedup.filterSpuriousPeriods()
    if (afterSpuriousPeriods != afterDedup) Log.d(
        TAG,
        "[CLEAN:SPURIOUS_P]  \"$afterDedup\" → \"$afterSpuriousPeriods\""
    )

    return afterSpuriousPeriods.cleanTranscriptStructural(isContinuation)
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
 * The encoder needs at least 2 s of frames to reliably produce tokens; anything
 * shorter causes the TDT decoder to return blank or hallucinate. Zero-padding
 * ([ShortArray.copyOf] fills new positions with 0) is decoded as silence — the TDT
 * decoder advances through blank frames at the tail without emitting speech tokens.
 */
private const val MIN_PADDING_SAMPLES = SAMPLE_RATE * 2  // 2 s = 32 000 samples

/** Emit a fresh partial inference every time this many new samples arrive (1 s). */
private const val STRIDE_SAMPLES = SAMPLE_RATE            // 1 s = 16 000 samples

/** Maximum audio context kept in the rolling window (30 s). */
private const val MAX_WINDOW_SAMPLES = SAMPLE_RATE * 30   // 30 s = 480 000 samples

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
 * Scans [window] chunk boundaries for a VAD-quiet (low-energy) cut point within
 * [[minDrop], [maxDrop]], anchored to [targetDrop].
 *
 * Priority:
 *  1. Latest silence at or before [targetDrop] — ensures we never slice into the speech
 *     that immediately follows the stable-text boundary.
 *  2. Earliest silence after [targetDrop] up to [maxDrop] — secondary fallback when no
 *     silence precedes the target; trimming a little past the estimate is still better
 *     than cutting mid-phoneme.
 *  3. [targetDrop] itself — proportional estimate used verbatim only when no silence at
 *     all is found in the valid range.
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
    var cumulative = 0
    var lastSilenceBeforeTarget = -1
    var firstSilenceAfterTarget = -1

    for (chunk in window) {
        cumulative += chunk.size
        if (cumulative < minDrop) continue
        if (cumulative > maxDrop) break
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
        lastSilenceBeforeTarget >= minDrop -> lastSilenceBeforeTarget
        firstSilenceAfterTarget in minDrop..maxDrop -> firstSilenceAfterTarget
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
internal const val CONFIDENCE_THRESHOLD = 0.55f

/**
 * Estimates a 0.0–1.0 confidence score for a short-utterance [TranscriptResult].
 *
 * **Confidence metric used: word-character count proxy**
 *
 * The current Parakeet TDT ONNX pipeline (`greedyDecode`) returns only the final text
 * string — individual token log-probabilities are discarded after the argmax step inside
 * the decoder loop and are never surfaced through `transcribe()`.  Extracting true
 * per-token probabilities would require:
 *   1. Running `softmax` over the 8 198-dim joint logit vector at each decoder step.
 *   2. Accumulating `log(softmax[argmax_token])` across all non-blank emissions.
 *   3. Returning those log-probs alongside the text (new return type / wrapper class).
 *
 * Until that refactor is done we use a conservative text-length proxy:
 *   - If [result] is not a [TranscriptResult.Final] or [TranscriptResult.Partial],
 *     confidence = 0.0 (unknown/failure — always gate).
 *   - If the cleaned text contains **fewer than 2 word characters** (letters or digits)
 *     after stripping punctuation and whitespace, confidence = 0.0.
 *     This catches the common hallucination patterns on very short/silent clips:
 *     empty string, a lone period, a single letter, or a lone digit.
 *   - Otherwise confidence = 1.0 — at least two real characters were decoded, which
 *     represents a plausible word fragment or a complete short word.
 *
 * The [audioSamples] parameter is accepted for API symmetry and future use (e.g. a
 * density ratio once per-token log-probs become available), but is not used in this
 * implementation.
 *
 * @param result The cleaned [TranscriptResult] from the engine.
 * @param audioSamples Number of actual (pre-padding) audio samples in the utterance.
 *   Not used by the current heuristic but retained for future per-token log-prob upgrade.
 */
internal fun estimateConfidence(result: TranscriptResult, audioSamples: Int): Float {
    val text = when (result) {
        is TranscriptResult.Final -> result.text
        is TranscriptResult.Partial -> result.text
        else -> return 0.0f
    }

    // Count word characters (letters + digits), ignoring punctuation and whitespace.
    val wordCharCount = text.count { it.isLetterOrDigit() }

    // Fewer than 2 meaningful characters → treat as empty / single-character hallucination.
    // This is the primary gate: empty strings, single punctuation chars, lone digits or
    // single letters produced when the encoder sees near-silent padded audio.
    if (wordCharCount < 2) return 0.0f

    // At least 2 real word characters decoded → treat as plausible (confidence = 1.0).
    // Future upgrade: replace with exp(mean(log_probs)) over non-blank tokens once the
    // ONNX greedyDecode loop surfaces per-token log-probabilities.
    @Suppress("UNUSED_PARAMETER")
    val unused = audioSamples   // retained for future per-token log-prob upgrade
    return 1.0f
}

/**
 * Returns `true` when [text] contains more than [HALLUCINATION_SCRIPT_THRESHOLD] (20%) of
 * characters that are outside the script range expected for [language].
 *
 * **Algorithm (O(N) scan):**
 * 1. Iterate every character in [text].
 * 2. Skip whitespace and punctuation (these are always neutral and never counted).
 * 3. For each remaining character, check whether it falls in the allowed Unicode range.
 * 4. If `outOfRange / total > HALLUCINATION_SCRIPT_THRESHOLD`, return `true`.
 *
 * For `"en"`, `"de"` and all currently unsupported languages the allowed range is
 * [LATIN_RANGE] (U+0000–U+024F) which covers every Latin-script language.
 *
 * An empty or whitespace-only string is never a hallucination (returns `false`).
 */
internal fun isScriptHallucination(text: String, language: String = "en"): Boolean {
    // Allowed code-point range for the given language — extend this when adding non-Latin langs.
    @Suppress("UNUSED_PARAMETER")
    val allowedRange = LATIN_RANGE  // same for all currently supported languages

    var total = 0
    var outOfRange = 0

    for (ch in text) {
        // Whitespace is neutral — skip.
        if (ch.isWhitespace()) continue
        // Common punctuation is always allowed — skip.
        if (ch in ALLOWED_PUNCTUATION) continue

        total++
        if (ch !in allowedRange) outOfRange++
    }

    if (total == 0) return false
    return (outOfRange.toFloat() / total) > HALLUCINATION_SCRIPT_THRESHOLD
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
                    Log.d(TAG, "[GRAMMAR] corrected: \"${result.text}\" → \"$corrected\"")
                    result.copy(text = corrected)
                } else {
                    result
                }
            }

            else -> result
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
                    val cleaned: TranscriptResult = when (result) {
                        is TranscriptResult.Partial -> TranscriptResult.Final(
                            text = if (postprocessingEnabled) result.text.cleanTranscriptStructural(isContinuation) else result.text,
                            isUtteranceBoundary = true,
                        )

                        is TranscriptResult.Final -> result.copy(
                            text = if (postprocessingEnabled) result.text.cleanTranscriptStructural(isContinuation) else result.text,
                            isUtteranceBoundary = true,
                        )

                        else -> result
                    }
                    Log.d(TAG, "[BOUNDARY] Final = ${cleaned.logLabel()}")
                    val cleanedText = when (cleaned) {
                        is TranscriptResult.Final -> cleaned.text
                        is TranscriptResult.Partial -> cleaned.text
                        else -> null
                    }
                    val toSend = if (cleanedText != null && isScriptHallucination(
                            cleanedText,
                            language = engine.currentLanguage
                        )
                    ) {
                        Log.w(TAG, "[HALLUCINATION] Non-Latin script detected in boundary final — suppressing")
                        TranscriptResult.Failure(RuntimeException("Non-Latin script detected — likely hallucination"))
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
                val structuralText = if (postprocessingEnabled)
                    rawText.cleanTranscriptStructural(isContinuation) else rawText
                val fullCleanedText = if (postprocessingEnabled)
                    rawText.cleanTranscript(
                        isContinuation,
                        language = engine.currentLanguage,
                        formatNumbersAsDigits = formatNumbersAsDigits
                    ) else rawText

                val cleaned: TranscriptResult = when (result) {
                    is TranscriptResult.Partial -> result.copy(text = structuralText)
                    is TranscriptResult.Final -> TranscriptResult.Partial(structuralText)
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
                    Log.d(TAG, "[PARTIAL] clean  = \"${cleaned.text}\"")
                    consecutiveBlankStrides = 0  // silence streak broken
                    // The flag was consumed by a real partial - reset it now.
                    // A trim later in this same stride can re-arm it for the *next* stride.
                    isContinuationAfterTrim = false

                    if (isScriptHallucination(cleaned.text, language = engine.currentLanguage)) {
                        Log.w(TAG, "[HALLUCINATION] Non-Latin script detected in partial — suppressing")
                        send(TranscriptResult.Failure(RuntimeException("Non-Latin script detected — likely hallucination")))
                    } else {
                        send(cleaned)
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
                    // compact (≤ TRIGGER_WINDOW_SAMPLES), emit a Final immediately.
                    // We do NOT reset the window here — the stable-chunk trim logic below
                    // manages window size.  Resetting the window in SENTENCE_FINAL would
                    // prevent the window from ever growing large enough for a trim, which
                    // causes the trim tests to fail.  We only skip running the trim on
                    // THIS stride (return@collect) because the Final already committed the text.
                    if (endsWithSentence && recentPartialWords.size >= STABLE_STRIDES && windowSamples <= TRIGGER_WINDOW_SAMPLES) {
                        val terminalWord = words.lastOrNull()?.normalizedForComparison()
                        val prevTerminalWord = recentPartialWords[recentPartialWords.size - 2]
                            .lastOrNull()?.normalizedForComparison()
                        if (terminalWord != null && terminalWord == prevTerminalWord) {
                            Log.d(TAG, "[SENTENCE_FINAL] stable punctuation endpoint → \"${cleaned.text}\"")
                            val sentenceFinal = TranscriptResult.Final(cleaned.text, isUtteranceBoundary = true)
                            send(applyGrammarCorrection(sentenceFinal, engine.currentLanguage))
                            // Do NOT reset the window or recentPartialWords — let the
                            // stable-chunk trim logic manage both on subsequent strides.
                            // Clearing recentPartialWords here would reset the divergence
                            // counter and delay force-trim detection.
                            isContinuationAfterTrim = false
                            consecutiveBlankStrides = 0
                            dynamicStrideSamples = STRIDE_SAMPLES
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
            // inference on a buffer zero-padded to at least MIN_PADDING_SAMPLES (2 s).
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

            val cleaned: TranscriptResult = when (result) {
                is TranscriptResult.Partial -> TranscriptResult.Final(
                    text = if (postprocessingEnabled) result.text.cleanTranscriptStructural(isContinuationAfterTrim) else result.text,
                    isUtteranceBoundary = isShortUtterance,
                )

                is TranscriptResult.Final -> result.copy(
                    text = if (postprocessingEnabled) result.text.cleanTranscriptStructural(isContinuationAfterTrim) else result.text,
                    isUtteranceBoundary = isShortUtterance || result.isUtteranceBoundary,
                )

                else -> result
            }
            Log.d(TAG, "[FINAL] clean = ${cleaned.logLabel()}")

            // ── Short-utterance confidence gate ───────────────────────────────────────
            // Apply ONLY on the short-utterance path to suppress low-confidence results
            // (empty text, single punctuation characters, or implausible single-char words)
            // that are characteristic of hallucinations after zero-padding.
            // Long-utterance results are never gated — gating partials in continuous
            // dictation would cause unacceptable silent drops in the middle of sentences.
            // Engine failures (Failure, WindowTrimmed) are passed through unchanged.
            if (isShortUtterance && (cleaned is TranscriptResult.Final || cleaned is TranscriptResult.Partial)) {
                val confidence = estimateConfidence(cleaned, rawChunk.samples.size)
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

            val finalText = when (cleaned) {
                is TranscriptResult.Final -> cleaned.text
                is TranscriptResult.Partial -> cleaned.text
                else -> null
            }
            val finalToSend =
                if (finalText != null && isScriptHallucination(finalText, language = engine.currentLanguage)) {
                    Log.w(TAG, "[HALLUCINATION] Non-Latin script detected in final — suppressing")
                    TranscriptResult.Failure(RuntimeException("Non-Latin script detected — likely hallucination"))
                } else {
                    applyGrammarCorrection(cleaned, engine.currentLanguage)
                }
            send(finalToSend)
        }
    }.flowOn(Dispatchers.Default)
}
