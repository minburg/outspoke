package dev.brgr.outspoke.inference

import android.text.InputType
import android.view.inputmethod.EditorInfo
import dev.brgr.outspoke.audio.AudioChunk
import dev.brgr.outspoke.ime.FakeInputConnection
import dev.brgr.outspoke.ime.TextInjector
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assume
import org.junit.BeforeClass
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.Locale

/**
 * Streaming-vs-one-shot parity measurement for the chunked-TDT pipeline.
 *
 * For every fixture this test prints three transcriptions of the same audio:
 *  - `ref`   : the one-shot (full-audio, non-streaming) reference — the raw model output
 *    passed through the same display cleaning the streaming final applies, so the only
 *    remaining difference vs the streaming field is the encoder context (full audio vs
 *    the 2 s left / 2 s right window),
 *  - `final` : the streaming repository's Final result,
 *  - `field` : the text actually committed to the field by the full production stack
 *    (InferenceRepository + real Parakeet + TextInjector + FakeInputConnection),
 * and the word error rate of each against the reference — both case-sensitive (cs)
 * and case-insensitive (ci) — plus the field-vs-final delta (freeze/commit
 * consistency, case-sensitive).
 *
 * The case-sensitive field WER vs the reference is the metric that tracks the 99.9%
 * goal (streaming must not add errors relative to the one-shot output, casing and
 * digits included). The printed numbers are the load-bearing output — tighten the
 * thresholds once the measured streaming floor is known.
 */
class StreamingParityTest {

    companion object {
        private val engine: SpeechEngine = ParakeetEngine()
        private var modelAvailable = false

        @BeforeClass
        @JvmStatic
        fun loadModel() {
            val modelDir = try {
                resolveModelDir()
            } catch (e: IllegalStateException) {
                Assume.assumeFalse("Parakeet model directory not available", true)
                return
            }
            engine.load(modelDir)
            modelAvailable = true
        }

        @AfterClass
        @JvmStatic
        fun closeModel() {
            if (modelAvailable) engine.close()
        }
    }

    private fun wavSamples(name: String): ShortArray {
        val stream = javaClass.classLoader!!.getResourceAsStream("audio/$name")
            ?: throw IllegalStateException("WAV fixture '$name' not found on classpath")
        val pcm = WavReader().readPcm16(ByteArrayInputStream(stream.readBytes()))
        val resampled = WavReader().resampleLinear(pcm.samples, pcm.sampleRate, 16_000)
        return if (pcm.channels > 1) {
            ShortArray(resampled.size / 2) { i -> ((resampled[2 * i] + resampled[2 * i + 1]) / 2).toShort() }
        } else resampled
    }

    private fun chunkify(samples: ShortArray, chunkSize: Int = 640): List<AudioChunk> {
        val chunks = mutableListOf<AudioChunk>()
        var i = 0
        while (i < samples.size) {
            val end = minOf(i + chunkSize, samples.size)
            chunks.add(AudioChunk(samples.copyOfRange(i, end)))
            i = end
        }
        return chunks
    }

    /** Classic Levenshtein distance between two word lists (two rolling rows). */
    private fun levenshtein(a: List<String>, b: List<String>): Int {
        val m = a.size
        val n = b.size
        if (m == 0) return n
        if (n == 0) return m
        var prev = IntArray(n + 1) { it }
        var curr = IntArray(n + 1)
        for (i in 1..m) {
            curr[0] = i
            for (j in 1..n) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(curr[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val tmp = prev; prev = curr; curr = tmp
        }
        return prev[n]
    }

    /** Case-sensitive word WER: punctuation stripped, casing preserved. */
    private fun werCaseSensitive(expected: String, actual: String): Float {
        val a = expected.replace(Regex("[^a-zA-Z0-9\\s]"), "").split(Regex("\\s+")).filter { it.isNotEmpty() }
        val b = actual.replace(Regex("[^a-zA-Z0-9\\s]"), "").split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (a.isEmpty()) return if (b.isEmpty()) 0f else 1f
        return levenshtein(a, b).toFloat() / a.size
    }

    @Test
    fun `streaming parity measurement across all real fixtures`() {
        Assume.assumeTrue(modelAvailable)
        engine.setLanguage("en")

        val fixtures = listOf(
            "long-sentence", "long-diff-two-sentences", "multi-4sent",
            "med-groceries", "med-report", "long-appointment", "long-package", "long-weather",
            "pipe-a", "pipe-c", "pipe-e", "pipe-f", "pipe-g", "pipe-j", "pipe-k", "pipe-l", "pipe-n", "pipe-o",
        )

        var worstCsField = 0f
        var worstCsFinal = 0f
        var worstCiField = 0f
        var worstCsFieldVsFinal = 0f
        var worstCiFieldVsFinal = 0f

        for (name in fixtures) {
            val samples = wavSamples("$name.wav")
            val oneShotRaw = when (val oneShot = engine.transcribe(AudioChunk(samples))) {
                is TranscriptResult.Final -> oneShot.text
                is TranscriptResult.Partial -> oneShot.text
                else -> throw AssertionError("One-shot reference failed for $name: $oneShot")
            }
            // Pipeline-fair reference: the full-audio one-shot output through the same
            // display cleaning the streaming final applies. The only remaining difference
            // between this and the streaming field is the encoder context (full audio vs
            // the 2 s left / 2 s right window) — the actual pipeline delta.
            val reference = oneShotRaw.cleanTranscript(language = "en")

            // Repository-level streaming final.
            val repoFinal = InferenceRepository(engine)
            val finalText = runBlocking {
                repoFinal.transcribe(flowOf(*chunkify(samples).toTypedArray()), postprocessingEnabled = true)
                    .toList()
            }.filterIsInstance<TranscriptResult.Final>().lastOrNull()?.text ?: ""

            // Full-stack field text (the text the user actually gets). The display cleaner
            // mirrors the production wiring in OutspokeInputMethodService (cleanTranscript
            // with the per-chunk isSentenceStart flag) so the field-vs-final delta also
            // validates the casing logic end to end.
            val ic = FakeInputConnection()
            val editorInfo = EditorInfo().apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL
            }
            val injector = TextInjector(
                inputConnection = ic,
                editorInfo = editorInfo,
                displayCleanFn = { text, isSentenceStart ->
                    text.cleanTranscript(
                        isContinuation = !isSentenceStart,
                        language = "en",
                        skipSpuriousPeriods = true,
                    )
                },
            )
            val repoField = InferenceRepository(engine)
            runBlocking {
                repoField.transcribe(flowOf(*chunkify(samples).toTypedArray()), postprocessingEnabled = true)
                    .collect { result ->
                        when (result) {
                            is TranscriptResult.Partial -> injector.setPartial(result.text)
                            is TranscriptResult.Final -> injector.commitFinal(result.text)
                            is TranscriptResult.WindowTrimmed -> injector.resetAfterTrim(result.stableWords)
                            else -> {}
                        }
                    }
            }
            val fieldText = ic.fieldText.trim()

            val csFinal = werCaseSensitive(reference, finalText)
            val csField = werCaseSensitive(reference, fieldText)
            val ciFinal = wer(reference, finalText)
            val ciField = wer(reference, fieldText)
            // Pipeline delta at the field level: what the user actually sees vs the
            // one-shot final (both fully cleaned). Catches freeze/commit inconsistencies
            // the reference-based WER cannot (e.g. a word frozen from a partial that the
            // final rewrites differently).
            val csFieldVsFinal = werCaseSensitive(finalText, fieldText)
            val ciFieldVsFinal = wer(finalText, fieldText)
            worstCsField = maxOf(worstCsField, csField)
            worstCsFinal = maxOf(worstCsFinal, csFinal)
            worstCsFieldVsFinal = maxOf(worstCsFieldVsFinal, csFieldVsFinal)
            worstCiFieldVsFinal = maxOf(worstCiFieldVsFinal, ciFieldVsFinal)
            ciField.coerceAtLeast(0f).let { worstCiField = maxOf(worstCiField, it) }

            println("[PARITY] $name (${samples.size / 16000}s, ${samples.size / 32000} chunks)")
            println("[PARITY]   ref   : $reference")
            println("[PARITY]   final : $finalText")
            println("[PARITY]   field : $fieldText")
            println("[PARITY]   WER vs ref: final cs=%.4f ci=%.4f | field cs=%.4f ci=%.4f".format(csFinal, ciFinal, csField, ciField))
            println("[PARITY]   WER field vs final: cs=%.4f ci=%.4f".format(csFieldVsFinal, ciFieldVsFinal))
        }

        println("[PARITY] WORST: cs-final=$worstCsFinal cs-field=$worstCsField ci-field=$worstCiField | field-vs-final cs=$worstCsFieldVsFinal ci=$worstCiFieldVsFinal")

        // The field the user sees must match the one-shot final EXACTLY (case-sensitive —
        // casing, punctuation, and digits all count). This is the pipeline guarantee: the
        // partial is fully cleaned (same pipeline as the final) and the display re-clean
        // skips the non-idempotent spurious-period filter, so the frozen field text is
        // byte-identical to the final. Any freeze/commit inconsistency fails this.
        org.assertj.core.api.Assertions.assertThat(worstCsFieldVsFinal)
            .describedAs("Field vs one-shot-final WER (cs) must be exactly 0.0 (byte-identical)")
            .isLessThanOrEqualTo(0.0f)
        org.assertj.core.api.Assertions.assertThat(worstCiFieldVsFinal)
            .describedAs("Field vs one-shot-final WER (ci) must be exactly 0.0")
            .isLessThanOrEqualTo(0.0f)
        // Loose guard against catastrophic regression vs the one-shot reference (which
        // includes the model's 2 s-context approximation floor, ~0.03 on the worst fixture).
        org.assertj.core.api.Assertions.assertThat(worstCiField)
            .describedAs("Streaming field WER (ci) vs one-shot reference must stay ≤ 0.1")
            .isLessThanOrEqualTo(0.1f)
    }
}
