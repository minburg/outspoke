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

private const val TAG = "InferenceRepository"
private const val SAMPLE_RATE = 16_000

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

    val fillers = when {
        language.startsWith("en", ignoreCase = true) -> enFillers
        else -> emptyList() // Extension point for multi-language
    }

    if (fillers.isEmpty()) return this

    val regexStr = "\\b(?:${fillers.joinToString("|")})\\b[,.]?"
    val regex = Regex(regexStr, RegexOption.IGNORE_CASE)

    return this.replace(regex, "").replace(Regex(" {2,}"), " ").trim()
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

    for (i in text.indices) {
        val c = text[i]
        if (capitalizeNext && c.isLetter()) {
            builder.append(c.uppercaseChar())
            capitalizeNext = false
        } else {
            builder.append(c)
        }

        if (c == '.' || c == '!' || c == '?') {
            capitalizeNext = true
        }
    }
    return builder.toString()
}

/**
 * Removes common model artefacts from a raw transcript:
 *  - Filler words
 *  - single word stutters (>= 3 repeats)
 *  - Consecutive repeated phrases (model hallucination loops)
 *  - Leading dots / ellipsis: `"...Hello"` → `"Hello"`
 *  - Leading commas / semicolons: `", jetzt sehr gut"` → `"jetzt sehr gut"` (mid-sentence start artefact)
 *  - Trailing multiple dots: `"Hello..."` → `"Hello."`
 *  - Missing space after sentence-ending punctuation: `"gut.Ich"` → `"gut. Ich"`
 *  - Enforces sentence boundary capitalization.
 *  - Strings that contain no alphanumeric content are discarded entirely.
 *
 * @param isContinuation When `true`, the very first letter of the result is **not**
 *   capitalized.  Pass `true` immediately after an audio window trim, because the new
 *   partial starts mid-sentence and capitalizing the first word (e.g. `"Sind vielleicht
 *   noch falsch…"`) would be incorrect.  Sentence-internal capitalization (letters after
 *   `.`, `!`, `?`) is unaffected.
 *
 * Each transformation step is logged as `[CLEAN:*]` when it actually changes the
 * text, making it easy to trace which artefacts the model introduced in a given run.
 */
internal fun String.cleanTranscript(isContinuation: Boolean = false): String {
    if (isBlank()) {
        Log.d(TAG, "[CLEAN] blank input → discarded")
        return ""
    }
    val input = trim()

    // Step 1: remove filler words (language aware, defaults to English)
    val afterFillers = input.removeFillerWords()
    if (afterFillers != input) Log.d(TAG, "[CLEAN:FILLERS]     \"$input\" → \"$afterFillers\"")

    // Step 2: collapse >=3 single word stutters
    val afterStutters = afterFillers.collapseStutters()
    if (afterStutters != afterFillers) Log.d(TAG, "[CLEAN:STUTTER]     \"$afterFillers\" → \"$afterStutters\"")

    // Step 3: collapse consecutive repeated phrases (hallucination loops)
    val afterDedup = afterStutters.collapseRepeatedPhrases()
    if (afterDedup != afterStutters) Log.d(TAG, "[CLEAN:PHRASES]     \"$afterStutters\" → \"$afterDedup\"")

    // Step 4: strip artefact leading dots / ellipsis.
    val afterLeadDots = afterDedup.replace(LEADING_DOTS_RE, "")
    if (afterLeadDots != afterDedup) Log.d(TAG, "[CLEAN:LEAD_DOTS]   \"$afterDedup\" → \"$afterLeadDots\"")

    // Step 5: strip leading standalone punctuation (comma/semicolon mid-sentence artefact).
    // A leading comma normalises to "" in TextInjector and permanently breaks alignment
    // for every subsequent stride once it is frozen as a committed word.
    val afterLeadPunct = afterLeadDots.replace(LEADING_PUNCT_RE, "")
    if (afterLeadPunct != afterLeadDots) Log.d(TAG, "[CLEAN:LEAD_PUNCT]  \"$afterLeadDots\" → \"$afterLeadPunct\"")

    // Step 6: reduce trailing 2+ dots to a single dot.
    val afterTrailDots = afterLeadPunct.replace(TRAILING_DOTS_RE, ".")
    if (afterTrailDots != afterLeadPunct) Log.d(TAG, "[CLEAN:TRAIL_DOTS]  \"$afterLeadPunct\" → \"$afterTrailDots\"")

    // Step 7: restore missing space after sentence-ending punctuation before a letter.
    val afterSentSpace = afterTrailDots.replace(MISSING_SENTENCE_SPACE_RE, "$1 $2").trim()
    if (afterSentSpace != afterTrailDots.trim()) Log.d(
        TAG,
        "[CLEAN:SENT_SPACE]  \"${afterTrailDots.trim()}\" → \"$afterSentSpace\""
    )

    // Step 8: capitalize first word of sentences.
    // When isContinuation=true the partial starts mid-sentence (right after an audio trim),
    // so skip capitalizing the very first letter to avoid e.g. "Sind vielleicht…".
    // Sentence-internal capitalization (after '.', '!', '?') is always applied so that
    // a new sentence beginning within the same partial is correctly capitalized regardless.
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
 * Minimum audio before the **first** partial inference is emitted (2 s).
 *
 * Parakeet TDT needs ≈1 s of context before the TDT decoder stabilises. Using 2 s
 * gives the encoder enough frames to correctly anchor the very first words (e.g.
 * "Now I lifted…") instead of returning noise or a mid-sentence fragment on the first
 * stride.
 */
private const val MIN_SAMPLES = SAMPLE_RATE * 2          // 2 s = 32 000 samples

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
class InferenceRepository(private val engine: SpeechEngine) {

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
     * @param postprocessingEnabled When `false` all transcript-cleaning steps (filler removal,
     *   stutter collapse, repetition deduplication, capitalisation) are skipped and the raw
     *   model output is emitted as-is.  Useful for debugging whether cleaning causes drops.
     *   Defaults to `true` (post-processing active).
     */
    fun transcribe(
        audio: Flow<AudioChunk>,
        postprocessingEnabled: Boolean = true,
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
            if (strideAccum >= STRIDE_SAMPLES && windowSamples < MIN_SAMPLES) {
                if (!strideWaitLogged) {
                    Log.d(
                        TAG, "[STRIDE] stride ready (strideAccum=${strideAccum.toSec()})" +
                                " but window=${windowSamples.toSec()} < MIN_SAMPLES=${MIN_SAMPLES.toSec()}" +
                                " - still accumulating"
                    )
                    strideWaitLogged = true
                }
            }

            if (windowSamples >= MIN_SAMPLES && strideAccum >= STRIDE_SAMPLES) {
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

                val cleaned: TranscriptResult = when (result) {
                    is TranscriptResult.Partial -> result.copy(
                        text = if (postprocessingEnabled) result.text.cleanTranscript(isContinuation) else result.text
                    )
                    is TranscriptResult.Final -> TranscriptResult.Partial(
                        if (postprocessingEnabled) result.text.cleanTranscript(isContinuation) else result.text
                    )
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

                    send(cleaned)

                    // -- Stable-chunk commit --
                    // Track the word list for this stride and check whether the
                    // leading words have been stable across the last STABLE_STRIDES
                    // consecutive partials.
                    val words = cleaned.text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                    recentPartialWords.addLast(words)
                    if (recentPartialWords.size > STABLE_STRIDES) recentPartialWords.removeFirst()

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
                            // still within reasonable bounds just log and wait - the next stride
                            // may converge.  But if the window has grown past FORCE_TRIM_WINDOW_SAMPLES
                            // we're in a divergence loop (typically caused by a long pause or very
                            // hesitant speech): trim aggressively back to TRIGGER + MIN_CONTEXT so
                            // the model gets a shorter, cleaner context to re-anchor on.
                            if (windowSamples > FORCE_TRIM_WINDOW_SAMPLES) {
                                // dropSamples = window - (TRIGGER + MIN_CONTEXT) ≥ FORCE_TRIM - 10s = 2s > MIN_TRIM (1s)
                                val dropSamples = windowSamples - (TRIGGER_WINDOW_SAMPLES + MIN_CONTEXT_SAMPLES)
                                val windowBefore = windowSamples.toSec()
                                trimWindowFront(dropSamples)
                                Log.d(TAG, "[STABLE] FORCE TRIM (diverged) - window $windowBefore → ${windowSamples.toSec()}")
                                send(TranscriptResult.WindowTrimmed())
                                recentPartialWords.clear()
                                isContinuationAfterTrim = true
                            } else {
                                Log.d(
                                    TAG, "[STABLE] no common prefix across last $STABLE_STRIDES" +
                                            " strides (words diverged) - no trim"
                                )
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
                            // Speech is not perfectly uniform, so we are conservative
                            // and always retain MIN_CONTEXT_SAMPLES of tail audio.
                            val stableAudioEst = (safeStableCount.toFloat() / totalWords * windowSamples).toInt()
                            val dropSamples = maxOf(0, stableAudioEst - MIN_CONTEXT_SAMPLES)

                            Log.d(
                                TAG, "[STABLE] prefix=$stableCount/$totalWords words stable" +
                                        " (safe=$safeStableCount, ≈${stableAudioEst.toSec()})," +
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
            // Pad to at least MIN_FINAL_SAMPLES so the encoder has enough frames for
            // single-word recognition.  ShortArray.copyOf fills added positions with 0
            // (silence); the TDT decoder advances through blank frames at the tail
            // without emitting spurious tokens.
            val rawChunk = buildChunk()
            val finalChunk = if (rawChunk.samples.size < MIN_FINAL_SAMPLES)
                AudioChunk(rawChunk.samples.copyOf(MIN_FINAL_SAMPLES))
            else rawChunk

            val padded = finalChunk.samples.size != rawChunk.samples.size
            Log.d(
                TAG, "[FINAL] window=${rawChunk.samples.size.toSec()}" +
                        if (padded) " → padded to ${finalChunk.samples.size.toSec()}" else " (no padding needed)"
            )

            val result = engine.transcribe(finalChunk)
            Log.d(TAG, "[FINAL] raw   = ${result.logLabel()}")

            val cleaned: TranscriptResult = when (result) {
                is TranscriptResult.Partial -> TranscriptResult.Final(
                    if (postprocessingEnabled) result.text.cleanTranscript(isContinuationAfterTrim) else result.text
                )
                is TranscriptResult.Final -> result.copy(
                    text = if (postprocessingEnabled) result.text.cleanTranscript(isContinuationAfterTrim) else result.text
                )
                else -> result
            }
            Log.d(TAG, "[FINAL] clean = ${cleaned.logLabel()}")
            send(cleaned)
        }
    }.flowOn(Dispatchers.Default)
}
