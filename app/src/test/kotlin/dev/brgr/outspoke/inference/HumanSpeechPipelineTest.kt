package dev.brgr.outspoke.inference

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

/**
 * Integration tests for realistic "human speech" patterns in [InferenceRepository].
 *
 * Real speakers rarely produce clean continuous sentences. They say 2–3 words, pause to
 * think, continue, trail off mid-clause, hesitate with filler sounds, or restart from the
 * beginning. This file models those patterns - all with scripted [FakeSpeechEngine]
 * responses - and verifies that the window-management, trim, and continuation-flag
 * machinery holds up under each scenario.
 *
 * ## Key timing constants (from production code)
 *
 * | Constant                  | Value       | Meaning                                   |
 * |---------------------------|-------------|-------------------------------------------|
 * | MIN_SAMPLES               | 32 000 (2 s)  | first stride waits until window is this  |
 * | STRIDE_SAMPLES            | 16 000 (1 s)  | one stride per additional second         |
 * | TRIGGER_WINDOW_SAMPLES    | 96 000 (6 s)  | stable-prefix trim activates above this  |
 * | FORCE_TRIM_WINDOW_SAMPLES | 192 000 (12 s)| force-trim fires when strides diverge    |
 * | STABLE_STRIDES            | 3             | consecutive matching strides to trim     |
 * | MIN_CONTEXT_SAMPLES       | 64 000 (4 s)  | tail kept after any trim                 |
 * | MIN_TRIM_SAMPLES          | 16 000 (1 s)  | minimum audio saving for a trim to fire  |
 *
 * ## Call-index arithmetic for `silentAudioFlow(N)`
 *
 * Each chunk is 1 s = 16 000 samples.
 *
 * ```
 * chunk 1       : window = 1 s → below MIN_SAMPLES, no stride
 * chunk k ≥ 2   : stride fires, engine call at index k − 2, window = k seconds
 * end-of-stream : final inference at call index N − 1
 * total engine calls = N
 * ```
 *
 * ## Trim formula reference
 *
 * **Regular trim** (stableCount > 0, window > TRIGGER):
 * ```
 * safeStableCount  = if (stableCount < totalWords) max(1, stableCount - 1) else stableCount
 * stableAudioEst   = (safeStableCount / totalWords) * windowSamples
 * dropSamples      = max(0, stableAudioEst - MIN_CONTEXT_SAMPLES)
 * fires when dropSamples ≥ MIN_TRIM_SAMPLES
 * ```
 *
 * **Force trim** (stableCount == 0, window > FORCE_TRIM_WINDOW):
 * ```
 * dropSamples = windowSamples - (TRIGGER_WINDOW_SAMPLES + MIN_CONTEXT_SAMPLES)
 *             = windowSamples - 160 000  (10 s)
 * ```
 */
class HumanSpeechPipelineTest {

    /**
     * Five German phrases whose *normalised* first words ("das", "nein", "gut",
     * "einfach", "wirklich") are all distinct.  Cycling through them ensures that
     * any three consecutive strides disagree on their leading word, keeping
     * `stableCount = 0` and preventing stable-prefix trims from firing.
     */
    private val divergentPhrases = listOf(
        TranscriptResult.Final("Das klingt wirklich gut so."),           // [0] first: "das"
        TranscriptResult.Final("Nein, das war ganz falsch."),            // [1] first: "nein"
        TranscriptResult.Final("Gut, ich wollte das nicht."),            // [2] first: "gut"
        TranscriptResult.Final("Einfach weiter machen jetzt."),          // [3] first: "einfach"
        TranscriptResult.Final("Wirklich interessant dieses Problem."),  // [4] first: "wirklich"
    )

    /** Returns [n] engine responses cycling through [divergentPhrases]. */
    private fun divergentResponses(n: Int): List<TranscriptResult> =
        List(n) { divergentPhrases[it % divergentPhrases.size] }

    /**
     * **Scenario**: prolonged hesitation - the model returns completely different
     * text on every stride because boundary audio is acoustically ambiguous during
     * a pause (attention drift).  No two consecutive strides share a leading word
     * so `stableCount` stays 0 and no regular trim fires.
     *
     * **Expected**: once the window exceeds 12 s the repository must force-trim and
     * emit [TranscriptResult.WindowTrimmed].
     *
     * **Timing** (`silentAudioFlow(14)` = 13 strides + 1 final = 14 calls):
     * ```
     * stride  0 (window  2 s): size = 1 < STABLE_STRIDES   → waiting
     * stride  1 (window  3 s): size = 2 < STABLE_STRIDES   → waiting
     * stride  2 (window  4 s): size = 3, window ≤ 6 s      → no trim
     * strides 3–4              window 5–6 s                 → no trim (≤ TRIGGER)
     * strides 5–9              window 7–11 s, all diverge   → no force trim (≤ 12 s)
     * stride 10 (window 12 s): 192 000 > 192 000? NO        → no force trim (strict >)
     * stride 11 (window 13 s): 208 000 > 192 000? YES       → FORCE TRIM ✓
     * ```
     */
    @Test
    fun `force trim fires when window exceeds 12s during divergent strides`() = runTest {
        // 14 calls: strides at window 2 s–14 s plus one final inference.
        val engine = FakeSpeechEngine(divergentResponses(14))
        val repo = InferenceRepository(engine)

        val results = repo.transcribe(silentAudioFlow(14)).toList()

        assertThat(results)
            .describedAs("A WindowTrimmed event must fire once the window exceeds 12 s with divergent strides")
            .anyMatch { it is TranscriptResult.WindowTrimmed }
    }

    /**
     * **Scenario**: same divergent-stride pattern as above, but now we care about
     * what happens *immediately after* the force trim.
     *
     * The engine returns `"sind vielleicht noch falsch."` (lowercase 's') at call
     * index 12 - the first stride after the force trim at index 11.
     *
     * **Expected**: the repository sets `isContinuationAfterTrim = true` when the
     * force trim fires.  On the very next stride this becomes `isContinuation = true`,
     * which passes `skipInitialCapitalize = true` to [cleanTranscript], preserving
     * the lowercase 's'.  Without the flag the output would be `"Sind vielleicht
     * noch falsch."` - an incorrect sentence-start capitalisation mid-clause.
     */
    @Test
    fun `first partial after force trim preserves lowercase opening letter`() = runTest {
        // Responses 0–11: cycle of 5 divergent phrases → force trim fires at stride 11.
        // Response 12: lowercase-starting continuation to probe the isContinuation flag.
        // Response 13: final inference (unused content, just needs to be non-blank).
        val responses = divergentResponses(12) + listOf(
            TranscriptResult.Final("sind vielleicht noch falsch."),  // index 12: post-trim
            TranscriptResult.Final("jetzt weiter gemacht."),         // index 13: final
        )
        val engine = FakeSpeechEngine(responses)
        val repo = InferenceRepository(engine)

        val results = repo.transcribe(silentAudioFlow(14)).toList()

        val trimIndex = results.indexOfLast { it is TranscriptResult.WindowTrimmed }
        assertThat(trimIndex)
            .describedAs("A WindowTrimmed event must be present in the results")
            .isGreaterThanOrEqualTo(0)

        // The first Partial emitted after WindowTrimmed is the post-trim continuation.
        val postTrimPartial = results
            .drop(trimIndex + 1)
            .filterIsInstance<TranscriptResult.Partial>()
            .firstOrNull()

        assertThat(postTrimPartial)
            .describedAs("A Partial must be emitted right after the force trim")
            .isNotNull

        assertThat(postTrimPartial!!.text)
            .describedAs(
                "Post-force-trim partial must preserve the lowercase 's' - " +
                        "isContinuation=true → skipInitialCapitalize=true in cleanTranscript"
            )
            .startsWith("sind")
    }

    /**
     * **Scenario**: a very long hesitant recording where the speaker keeps stopping
     * and restarting.  Strides keep diverging so the window grows toward 12 s,
     * triggering a force trim, resetting to 10 s, then growing again - repeatedly.
     *
     * **Expected**: force trims fire roughly every 3 strides once the window first
     * exceeds 12 s, keeping the context window bounded instead of ballooning to 30 s.
     *
     * **Timing** (`silentAudioFlow(25)` = 24 strides + 1 final = 25 calls):
     * ```
     * Force trim 1 at stride 11 (window 13 s → 10 s), clears buffer
     * Force trim 2 at stride 14 (window 13 s → 10 s), clears buffer
     * Force trim 3 at stride 17 (window 13 s → 10 s), clears buffer
     * Force trim 4 at stride 20, Force trim 5 at stride 23
     * ```
     * The test asserts ≥ 3 to stay robust against minor timing drift.
     */
    @Test
    fun `multiple force trims keep window bounded across a long hesitant recording`() = runTest {
        val engine = FakeSpeechEngine(divergentResponses(25))
        val repo = InferenceRepository(engine)

        val results = repo.transcribe(silentAudioFlow(25)).toList()

        val trimCount = results.count { it is TranscriptResult.WindowTrimmed }
        assertThat(trimCount)
            .describedAs(
                "At least 3 force trims must fire over 25 s of fully-divergent speech " +
                        "to cap the window (got $trimCount WindowTrimmed events)"
            )
            .isGreaterThanOrEqualTo(3)
    }

    /**
     * **Scenario**: the user speaks a complete sentence that the model recognises
     * identically for ≥ 3 strides while the window exceeds 6 s.  A regular trim
     * fires, the `isContinuationAfterTrim` flag is set for exactly **one** stride,
     * and then the flag resets automatically.
     *
     * **Timing** (`silentAudioFlow(11)` = 10 strides + 1 final = 11 calls):
     * ```
     * stride  0 (window  2 s): history size 1  → waiting
     * stride  1 (window  3 s): size 2           → waiting
     * stride  2 (window  4 s): size 3, w ≤ 6 s → no trim
     * strides 3–4              w 5–6 s          → no trim (≤ TRIGGER)
     * stride  5 (window  7 s): size 3, all same →
     *   stableCount = 13 (all 13 words match),  safeStableCount = 13
     *   stableAudioEst = 7 s,  dropSamples = 3 s ≥ 1 s → TRIM
     *   window: 7 s → 4 s,  WindowTrimmed emitted,  isContinuationAfterTrim = true
     * stride  6 (window  5 s): isContinuation = true  → "nochmal…" stays lowercase
     * stride  7 (window  6 s): isContinuation = false → "dann…" gets initial capital
     * strides 8–9              divergent → prevent second trim
     * ```
     *
     * This validates that capitalisation suppression is a **one-shot** mechanism: it
     * fires for the single stride immediately after the trim and then resets.
     */
    @Test
    fun `continuation flag is single-use -- first post-trim partial lowercase, second capitalised`() = runTest {
        val stableSentence = TranscriptResult.Final(
            // 13 words - wide enough that stableAudioEst covers the full 7 s window.
            "Ich habe gerade gesehen dass das sehr gut funktioniert mit allen Wörtern hier."
        )
        val responses: List<TranscriptResult> = List(6) { stableSentence } + listOf(
            TranscriptResult.Final("nochmal von vorne bitte."),   // [6] 1st post-trim: isContinuation=true
            TranscriptResult.Final("dann geht es wirklich los."), // [7] 2nd post-trim: isContinuation=false
            TranscriptResult.Final("ganz andere Sache hier."),    // [8] divergent - blocks 2nd trim
            TranscriptResult.Final("super unterschiedlich hier."),// [9] divergent
            TranscriptResult.Final("fertig jetzt hier."),         // [10] final inference
        )
        val engine = FakeSpeechEngine(responses)
        val repo = InferenceRepository(engine)

        val results = repo.transcribe(silentAudioFlow(11)).toList()

        val trimIndex = results.indexOfFirst { it is TranscriptResult.WindowTrimmed }
        assertThat(trimIndex)
            .describedAs("Regular trim must fire once the full sentence is stable above 6 s")
            .isGreaterThanOrEqualTo(0)

        val partialsAfterTrim = results
            .drop(trimIndex + 1)
            .filterIsInstance<TranscriptResult.Partial>()

        assertThat(partialsAfterTrim.size)
            .describedAs("At least two Partials must be emitted after the trim to compare the two strides")
            .isGreaterThanOrEqualTo(2)

        assertThat(partialsAfterTrim[0].text)
            .describedAs(
                "1st post-trim partial: isContinuation=true suppresses initial capitalisation " +
                        "→ 'nochmal' must stay lowercase"
            )
            .startsWith("nochmal")

        assertThat(partialsAfterTrim[1].text)
            .describedAs(
                "2nd post-trim partial: isContinuation flag was already consumed → " +
                        "normal capitalisation applies, 'dann' → 'Dann'"
            )
            .startsWith("Dann")
    }

    /**
     * **Scenario**: the recording opens with background noise or microphone hiss
     * before the first real word is spoken.  The model returns blank/dot tokens
     * (`".."`, `"..."`) for those initial strides.
     *
     * **Expected**: [cleanTranscript] strips all-dot strings to `""`, the repository
     * discards them (no blank Partial is ever emitted), and the first genuine word
     * still reaches the TextInjector.  The silence at the start does NOT corrupt the
     * `recentPartialWords` ring-buffer - real words can still trigger a trim later.
     *
     * **Timing** (`silentAudioFlow(7)` = 6 strides + 1 final = 7 calls):
     * ```
     * strides 0–2: "..", "...", "."   → cleaned → "" → discarded
     * strides 3–5: real German sentence → Partial emitted
     * stride  5 (window 7 s): stable ×3, w > 6 s → regular trim fires
     * call  6: final inference
     * ```
     */
    @Test
    fun `initial noise and blank strides before first real word are discarded`() = runTest {
        val realSpeech = TranscriptResult.Final("Ich war gerade kurz abgelenkt.")
        val engine = FakeSpeechEngine(
            listOf(
                TranscriptResult.Final(".."),  // [0] opening noise → cleaned to "" → discarded
                TranscriptResult.Final("..."), // [1] noise
                TranscriptResult.Final("."),   // [2] noise
                realSpeech,                    // [3] first real content
                realSpeech,                    // [4]
                realSpeech,                    // [5] window = 7 s, trim may fire here
                realSpeech,                    // [6] final inference
            )
        )
        val repo = InferenceRepository(engine)

        val results = repo.transcribe(silentAudioFlow(7)).toList()
        val partials = results.filterIsInstance<TranscriptResult.Partial>()

        // No blank Partial must ever be emitted.
        partials.forEach { partial ->
            assertThat(partial.text)
                .describedAs("Every emitted Partial must contain real alphanumeric content")
                .isNotBlank()
                .matches(".*\\p{L}.*")
        }

        // The real sentence must still get through despite the noisy start.
        assertThat(partials)
            .describedAs("At least one Partial with real speech must appear after the noise strides")
            .anyMatch { "Ich" in it.text }
    }

    /**
     * **Scenario**: a brief model hiccup in the *middle* of an otherwise clean
     * recording.  The engine returns dot-only responses for two strides sandwiched
     * between real speech.
     *
     * **Expected**: blank strides are discarded without being added to
     * `recentPartialWords`, so the stability ring-buffer is not corrupted and the
     * surrounding real Partials continue to be emitted normally.
     *
     * **Timing** (`silentAudioFlow(6)` = 5 strides + 1 final = 6 calls):
     * ```
     * stride 0 (window 2 s): real speech → Partial emitted
     * stride 1 (window 3 s): ".."        → discarded
     * stride 2 (window 4 s): real speech → Partial emitted
     * stride 3 (window 5 s): "..."       → discarded
     * stride 4 (window 6 s): real speech → Partial emitted, w ≤ 6 s → no trim
     * call  5: final inference
     * ```
     */
    @Test
    fun `mid-session blank strides are discarded without corrupting partial tracking`() = runTest {
        val realSpeech = TranscriptResult.Final("Das klingt wirklich gut.")
        val engine = FakeSpeechEngine(
            listOf(
                realSpeech,                    // [0] real
                TranscriptResult.Final(".."),  // [1] mid-session blank
                realSpeech,                    // [2] real
                TranscriptResult.Final("..."), // [3] mid-session blank
                realSpeech,                    // [4] real, window = 6 s (≤ TRIGGER → no trim)
                realSpeech,                    // [5] final inference
            )
        )
        val repo = InferenceRepository(engine)

        val results = repo.transcribe(silentAudioFlow(6)).toList()
        val partials = results.filterIsInstance<TranscriptResult.Partial>()

        partials.forEach { partial ->
            assertThat(partial.text)
                .describedAs("Blank mid-session strides must not produce emitted Partials")
                .isNotBlank()
        }

        assertThat(partials)
            .describedAs("Real-speech Partials must still reach the output despite the intervening blanks")
            .anyMatch { "Das" in it.text }

        assertThat(partials.size)
            .describedAs("All three real-speech strides (indices 0, 2, 4) must produce Partials")
            .isGreaterThanOrEqualTo(3)
    }

    /**
     * **Scenario**: the canonical "thinking speaker" pattern - user says a short
     * opening burst ("Ich bin jetzt", 3 words), then goes silent for several seconds
     * while the window grows and strides diverge (simulating a long mid-sentence
     * pause), and finally completes the sentence.
     *
     * This exercises the full lifecycle in a single recording:
     *   1. Short burst stabilises → **regular trim fires**, `WindowTrimmed` emitted.
     *   2. Post-trim partial respects `isContinuationAfterTrim` - lowercase opening.
     *   3. Divergent strides during the "pause" do not block subsequent real speech.
     *   4. Once the complete sentence arrives the **Final** transcript captures it.
     *
     * **Timing** (`silentAudioFlow(12)` = 11 strides + 1 final = 12 calls):
     * ```
     * strides 0–5  (window 2–7 s):
     *   "Ich bin jetzt" stable ×3 at window 7 s
     *   → stableCount=3, safeStableCount=3, stableAudioEst=7 s,
     *     dropSamples=3 s ≥ 1 s → TRIM; window: 7 s → 4 s
     *   → WindowTrimmed, isContinuationAfterTrim=true
     *
     * stride  6 (window 5 s): "nein das ist irgendwie falsch." (isContinuation=true)
     *   → stays "nein das ist irgendwie falsch." (no initial capital)
     *
     * strides 7–8: divergent → no second trim (window < FORCE_TRIM_WINDOW)
     *
     * strides 9–10 (window 8–9 s): completed sentence arrives
     *
     * call 11: final inference → Final("Ich bin jetzt wirklich sehr müde.")
     * ```
     */
    @Test
    fun `word burst then long pause then completion -- trim fires and final captures full sentence`() = runTest {
        val completeSentence = TranscriptResult.Final("Ich bin jetzt wirklich sehr müde.")
        val engine = FakeSpeechEngine(
            listOf(
                TranscriptResult.Final("Ich bin jetzt"),                       // [0] burst
                TranscriptResult.Final("Ich bin jetzt"),                       // [1]
                TranscriptResult.Final("Ich bin jetzt"),                       // [2]
                TranscriptResult.Final("Ich bin jetzt"),                       // [3]
                TranscriptResult.Final("Ich bin jetzt"),                       // [4]
                TranscriptResult.Final("Ich bin jetzt"),                       // [5] → TRIM fires here
                TranscriptResult.Final("nein das ist irgendwie falsch."),      // [6] post-trim continuation
                TranscriptResult.Final("wann kommt das eigentlich."),          // [7] pause/divergent
                TranscriptResult.Final("so weiter dann hier."),                // [8] pause/divergent
                completeSentence,                                               // [9] speech resumes
                completeSentence,                                               // [10]
                completeSentence,                                               // [11] final inference
            )
        )
        val repo = InferenceRepository(engine)

        val results = repo.transcribe(silentAudioFlow(12)).toList()

        // 1. Regular trim must fire when the 3-word burst stabilises.
        assertThat(results)
            .describedAs("WindowTrimmed must be emitted when the opening burst stabilises above 6 s")
            .anyMatch { it is TranscriptResult.WindowTrimmed }

        // 2. First post-trim partial must use lowercase opening (isContinuation=true).
        val trimIndex = results.indexOfFirst { it is TranscriptResult.WindowTrimmed }
        val firstPostTrimPartial = results
            .drop(trimIndex + 1)
            .filterIsInstance<TranscriptResult.Partial>()
            .firstOrNull()

        assertThat(firstPostTrimPartial)
            .describedAs("A Partial must follow the WindowTrimmed event")
            .isNotNull

        assertThat(firstPostTrimPartial!!.text)
            .describedAs(
                "The first stride after a trim starts mid-sentence - initial capitalisation " +
                        "must be suppressed (isContinuation=true)"
            )
            .startsWith("nein")

        // 3. All emitted Partials must be non-blank (divergent pause strides may fire).
        results.filterIsInstance<TranscriptResult.Partial>().forEach { partial ->
            assertThat(partial.text)
                .describedAs("No blank Partial must ever reach the output")
                .isNotBlank()
        }

        // 4. Final transcript must contain the completed sentence.
        val finalResult = results.filterIsInstance<TranscriptResult.Final>().lastOrNull()
        assertThat(finalResult)
            .describedAs("A Final result must be emitted at end of stream")
            .isNotNull
        assertThat(finalResult!!.text)
            .describedAs("Final transcript must include 'müde' from the completed sentence")
            .contains("müde")
    }

    /**
     * **Scenario**: "three words, four-second silence, three more words, silence,
     * then the punchline" - the kind of halting delivery common in real dictation.
     * The model sees a growing window with repeatedly half-stable text, eventually
     * triggering both a regular trim (after the first burst) and a force trim (after
     * a sustained divergence run during the second pause).
     *
     * This test verifies that **both** trim types can fire in a single recording
     * without corrupting the result stream.
     *
     * **Timing** (`silentAudioFlow(16)` = 15 strides + 1 final = 16 calls):
     * ```
     * strides 0–5: "Ja, das stimmt." stable →
     *   regular trim at stride 5 (window 7 s → 4 s), WindowTrimmed #1
     *
     * strides 6–8: three divergent responses (post-trim pause simulation)
     *
     * strides 9–14: divergent cycle continues, window rebuilds to 13 s
     *   → force trim fires around stride 13 (window 13 s → 10 s), WindowTrimmed #2
     *
     * call 15: final inference
     * ```
     * Asserts ≥ 2 WindowTrimmed events in a single recording session.
     */
    @Test
    fun `halting multi-burst delivery triggers both regular and force trim in one session`() = runTest {
        val burstPhrase = TranscriptResult.Final("Ja, das stimmt.")
        // Responses 0–5: stable burst → regular trim fires at stride 5 (window 7 s)
        // Responses 6–15: fully divergent cycle → window rebuilds and force trim fires
        val responses: List<TranscriptResult> = List(6) { burstPhrase } + divergentResponses(10)
        val engine = FakeSpeechEngine(responses)
        val repo = InferenceRepository(engine)

        val results = repo.transcribe(silentAudioFlow(16)).toList()

        val trimCount = results.count { it is TranscriptResult.WindowTrimmed }
        assertThat(trimCount)
            .describedAs(
                "Both a regular trim (burst stabilises) and a force trim (sustained divergence) " +
                        "must fire in a single halting recording (got $trimCount WindowTrimmed events)"
            )
            .isGreaterThanOrEqualTo(2)
    }

    // --- Bug 1A: continuation flag consumed by a blank stride: FIXED
    //
    // Root cause (was): `isContinuationAfterTrim` was snapshotted and reset at the TOP of
    // every stride regardless of whether the cleaned result was blank.  When the very
    // first stride after a trim produced blank output (e.g. "..", ",", "...") the
    // flag was consumed without any partial being emitted.  The NEXT real stride then
    // saw `isContinuation = false` and incorrectly capitalised the first letter of
    // what was still a mid-sentence continuation.
    //
    // Fix: the flag is now only consumed (reset to false) when a non-blank partial
    // actually uses it.  Blank strides leave it armed for the next real stride.
    //
    // All tests in this section assert the CORRECT behaviour and PASS against the
    // fixed implementation.

    /**
     * **Bug 1A-1** - A single `".."` stride immediately after a **regular trim**
     * silently consumes `isContinuationAfterTrim`.
     *
     * Timing (`silentAudioFlow(11)` = 10 strides + 1 final = 11 calls):
     * ```
     * strides 0–5 (window 2–7 s): 13-word stable sentence
     *   → regular trim fires at stride 5 (window 7 s → 4 s)
     *   → isContinuationAfterTrim = true
     * stride 6 (window 5 s): ".." → cleaned "" → discarded
     *   → isContinuation was true, isContinuationAfterTrim now false - FLAG EATEN
     * stride 7 (window 6 s): "weiter geht es dann."
     *   BUG:     isContinuation=false → "Weiter geht es dann."  ← wrong capital
     *   CORRECT: isContinuation=true  → "weiter geht es dann."  ← stays lowercase
     * ```
     */
    @Test
    fun `1A-1 blank dot-stride after regular trim silently consumes continuation flag`() = runTest {
        val stableSentence = TranscriptResult.Final(
            // 13 words - stableAudioEst at window 7 s = 7 s → dropSamples = 3 s ≥ MIN_TRIM
            "Ich habe gerade gesehen dass das sehr gut funktioniert mit allen Wörtern hier."
        )
        val responses: List<TranscriptResult> = List(6) { stableSentence } + listOf(
            TranscriptResult.Final(".."),                           // [6] blank - eats flag
            TranscriptResult.Final("weiter geht es dann."),        // [7] should stay lowercase
            TranscriptResult.Final("ganz andere Sache jetzt."),    // [8] divergent - blocks 2nd trim
            TranscriptResult.Final("super unterschiedlich hier."), // [9] divergent
            TranscriptResult.Final("fertig jetzt hier."),          // [10] final inference
        )
        val engine = FakeSpeechEngine(responses)
        val repo = InferenceRepository(engine)
        val results = repo.transcribe(silentAudioFlow(11)).toList()

        val trimIndex = results.indexOfFirst { it is TranscriptResult.WindowTrimmed }
        assertThat(trimIndex)
            .describedAs("Regular trim must fire when the 13-word sentence is stable above 6 s")
            .isGreaterThanOrEqualTo(0)

        // The blank stride emits no Partial, so the first Partial after WindowTrimmed
        // comes from response[7].  With the bug it starts with capital "W".
        val firstRealPartialAfterTrim = results
            .drop(trimIndex + 1)
            .filterIsInstance<TranscriptResult.Partial>()
            .firstOrNull()

        assertThat(firstRealPartialAfterTrim)
            .describedAs("A real Partial must be emitted after the blank stride")
            .isNotNull

        assertThat(firstRealPartialAfterTrim!!.text)
            .describedAs(
                "1A-1 regression: blank '..' stride must NOT consume isContinuationAfterTrim. " +
                        "The following real stride must get isContinuation=true → lowercase 'weiter'."
            )
            .startsWith("weiter")
    }

    /**
     * **Bug 1A-2** - A bare **comma artefact** (`","`) stride after a **regular trim**
     * consumes the flag.
     *
     * Parakeet's SentencePiece tokenizer sometimes emits a bare `","` when it has no
     * new speech content to decode.  `cleanTranscript` strips it via `LEADING_PUNCT_RE`
     * → `""` → partial is discarded.  Same flag-eating outcome as 1A-1.
     *
     * Same trim timing as 1A-1; only response[6] changes.
     */
    @Test
    fun `1A-2 bare comma artefact stride after regular trim silently consumes continuation flag`() = runTest {
        val stableSentence = TranscriptResult.Final(
            "Ich habe gerade gesehen dass das sehr gut funktioniert mit allen Wörtern hier."
        )
        val responses: List<TranscriptResult> = List(6) { stableSentence } + listOf(
            TranscriptResult.Final(","),                            // [6] bare comma → cleaned "" → eats flag
            TranscriptResult.Final("weiter geht es dann."),        // [7] should stay lowercase
            TranscriptResult.Final("ganz andere Sache jetzt."),    // [8] divergent
            TranscriptResult.Final("super unterschiedlich hier."), // [9] divergent
            TranscriptResult.Final("fertig jetzt hier."),          // [10] final inference
        )
        val engine = FakeSpeechEngine(responses)
        val repo = InferenceRepository(engine)
        val results = repo.transcribe(silentAudioFlow(11)).toList()

        val trimIndex = results.indexOfFirst { it is TranscriptResult.WindowTrimmed }
        assertThat(trimIndex)
            .describedAs("Regular trim must fire")
            .isGreaterThanOrEqualTo(0)

        val firstRealPartialAfterTrim = results
            .drop(trimIndex + 1)
            .filterIsInstance<TranscriptResult.Partial>()
            .firstOrNull()

        assertThat(firstRealPartialAfterTrim).isNotNull
        assertThat(firstRealPartialAfterTrim!!.text)
            .describedAs(
                "1A-2 regression: bare ',' stride cleaned to '' must NOT consume isContinuationAfterTrim. " +
                        "'weiter' must stay lowercase."
            )
            .startsWith("weiter")
    }

    /**
     * **Bug 1A-3** - A blank stride after a **force trim** consumes the flag.
     *
     * Force trim fires at stride 11 (window 13 s, all divergent).  The very next
     * stride returns `".."` → blank → flag consumed.  The following real stride is
     * incorrectly capitalised.
     *
     * Timing (`silentAudioFlow(15)` = 14 strides + 1 final = 15 calls):
     * ```
     * calls  0–11: 12 divergent → force trim at call 11 (window 13 s → 10 s)
     *              isContinuationAfterTrim = true
     * call  12 (window 11 s): ".." → blank → flag consumed - FLAG EATEN
     * call  13 (window 12 s): "weiter geht es dann."
     *   BUG:     isContinuation=false → "Weiter geht es dann."
     *   CORRECT: isContinuation=true  → "weiter geht es dann."
     * call  14: final inference
     * ```
     */
    @Test
    fun `1A-3 blank stride after force trim silently consumes continuation flag`() = runTest {
        val responses = divergentResponses(12) + listOf(
            TranscriptResult.Final(".."),                    // [12] blank after force trim - eats flag
            TranscriptResult.Final("weiter geht es dann."), // [13] should stay lowercase
            TranscriptResult.Final("fertig jetzt."),         // [14] final inference
        )
        val engine = FakeSpeechEngine(responses)
        val repo = InferenceRepository(engine)
        val results = repo.transcribe(silentAudioFlow(15)).toList()

        // Use indexOfLast in case earlier divergent strides coincidentally produce a trim.
        val trimIndex = results.indexOfLast { it is TranscriptResult.WindowTrimmed }
        assertThat(trimIndex)
            .describedAs("A force trim must appear in the results")
            .isGreaterThanOrEqualTo(0)

        val firstRealPartialAfterTrim = results
            .drop(trimIndex + 1)
            .filterIsInstance<TranscriptResult.Partial>()
            .firstOrNull()

        assertThat(firstRealPartialAfterTrim).isNotNull
        assertThat(firstRealPartialAfterTrim!!.text)
            .describedAs(
                "1A-3 regression: blank '..' stride after force trim must NOT consume isContinuationAfterTrim. " +
                        "'weiter' must stay lowercase."
            )
            .startsWith("weiter")
    }

    /**
     * **Bug 1A-4** - Two consecutive blank strides after a **regular trim**.
     *
     * The first blank stride consumes the flag (`isContinuation=true` but discarded).
     * The second blank stride sees `isContinuation=false` (already reset) and is also
     * discarded.  The first real stride likewise sees `false` - incorrectly capitalised.
     *
     * This models a 2-second silence gap right after the trim: the model emits `".."` and
     * `"..."` on two consecutive strides before the speaker continues.
     *
     * Same trim timing as 1A-1; responses[6] and [7] are both blank.
     */
    @Test
    fun `1A-4 two consecutive blank strides after trim -- first consumes flag second and real partial both see no-continuation`() =
        runTest {
            val stableSentence = TranscriptResult.Final(
                "Ich habe gerade gesehen dass das sehr gut funktioniert mit allen Wörtern hier."
            )
            val responses: List<TranscriptResult> = List(6) { stableSentence } + listOf(
                TranscriptResult.Final(".."),                           // [6] 1st blank - consumes flag
                TranscriptResult.Final("..."),                          // [7] 2nd blank - isContinuation already false
                TranscriptResult.Final("weiter geht es dann."),        // [8] should stay lowercase
                TranscriptResult.Final("super unterschiedlich jetzt."),// [9] divergent - blocks 2nd trim
                TranscriptResult.Final("fertig jetzt hier."),          // [10] final inference
            )
            val engine = FakeSpeechEngine(responses)
            val repo = InferenceRepository(engine)
            val results = repo.transcribe(silentAudioFlow(11)).toList()

            val trimIndex = results.indexOfFirst { it is TranscriptResult.WindowTrimmed }
            assertThat(trimIndex)
                .describedAs("Regular trim must fire")
                .isGreaterThanOrEqualTo(0)

            val firstRealPartialAfterTrim = results
                .drop(trimIndex + 1)
                .filterIsInstance<TranscriptResult.Partial>()
                .firstOrNull()

            assertThat(firstRealPartialAfterTrim).isNotNull
            assertThat(firstRealPartialAfterTrim!!.text)
                .describedAs(
                    "1A-4 regression: two blank strides must NOT consume the continuation flag. " +
                            "'weiter' must stay lowercase on the first real stride."
                )
                .startsWith("weiter")
        }

    // --- Bug 3A: collapseRepeatedPhrases - all adjacent repeats collapsed: FIXED
    //
    // `collapseRepeatedPhrases` now collapses ALL adjacent repeated phrases
    // (including single-word ×2). This is the simplest correct behaviour that
    // eliminates hallucination loops without a fragile threshold constant.
    //
    // All tests below assert the CORRECT behaviour and PASS against the
    // current implementation.

    /**
     * **3A-1** - Two identical consecutive emphasis words are collapsed.
     *
     * `"Das ist sehr sehr gut."` - "sehr"/"sehr" is an adjacent repeat →
     * `collapseRepeatedPhrases` collapses to a single occurrence.
     */
    @Test
    fun `3A-1 two identical consecutive emphasis words are collapsed`() {
        assertThat("Das ist sehr sehr gut.".cleanTranscript())
            .describedAs(
                "3A-1: 'sehr sehr' is collapsed to 'sehr' - all adjacent repeats are removed."
            )
            .isEqualTo("Das ist sehr gut.")
    }

    /**
     * **3A-2** - `"Nein nein,"` collapsed by collapseRepeatedPhrases.
     *
     * "Nein"/"nein," normalise to the same value → adjacent single-word repeat → collapsed.
     */
    @Test
    fun `3A-2 nein nein collapsed by phrase dedup`() {
        assertThat("Nein nein, das stimmt nicht.".cleanTranscript())
            .describedAs(
                "3A-2: 'Nein nein,' collapsed - adjacent single-word repeat after normalisation."
            )
            .isEqualTo("Nein das stimmt nicht.")
    }

    /**
     * **3A-3** - Two identical intensity adverbs mid-sentence collapsed.
     *
     * "wirklich"/"wirklich" is an adjacent single-word repeat → collapsed.
     */
    @Test
    fun `3A-3 two identical intensity adverbs mid-sentence are collapsed`() {
        assertThat("Das ist wirklich wirklich wichtig.".cleanTranscript())
            .describedAs(
                "3A-3: 'wirklich wirklich' collapsed to 'wirklich'."
            )
            .isEqualTo("Das ist wirklich wichtig.")
    }

    /**
     * **3A-4** - A repeated complete sentence is collapsed.
     *
     * `"Das klingt gut. Das klingt gut."` - the 3-word phrase repeats ×2 →
     * `collapseRepeatedPhrases` keeps only the first occurrence.
     */
    @Test
    fun `3A-4 repeated sentence is collapsed`() {
        assertThat("Das klingt gut. Das klingt gut.".cleanTranscript())
            .describedAs(
                "3A-4: repeated sentence (×2) collapsed to a single occurrence."
            )
            .isEqualTo("Das klingt gut.")
    }

    /**
     * **3A-5** - Mixed-case single-word repetition collapsed.
     *
     * `"So so geht das nicht."` - "So"/"so" normalise to "so" → adjacent repeat → collapsed.
     */
    @Test
    fun `3A-5 mixed-case single-word repetition collapsed`() {
        assertThat("So so geht das nicht.".cleanTranscript())
            .describedAs(
                "3A-5: 'So so' collapsed - adjacent single-word repeat after normalisation."
            )
            .isEqualTo("So geht das nicht.")
    }

    /**
     * **Control 3A** - A genuine model hallucination loop (3× repeat) MUST still be
     * collapsed.
     *
     * This test is expected to **PASS** and documents the correct behaviour that any
     * fix to `collapseRepeatedPhrases` must not break: a phrase the model emits three
     * or more consecutive times is correctly deduplicated to a single occurrence.
     *
     * `"ich wollte sagen ich wollte sagen ich wollte sagen dass es gut ist."`
     * → `collapseRepeatedPhrases` detects 3× "ich wollte sagen"
     * → `"Ich wollte sagen dass es gut ist."`  ✓
     */
    @Test
    fun `3A-control genuine triple-repeat hallucination loop is correctly collapsed`() {
        assertThat(
            "ich wollte sagen ich wollte sagen ich wollte sagen dass es gut ist.".cleanTranscript()
        )
            .describedAs(
                "A genuine 3× model loop must still be collapsed - this is the correct behaviour " +
                        "that any fix to the 2× threshold must preserve."
            )
            .isEqualTo("Ich wollte sagen dass es gut ist.")
    }
}





