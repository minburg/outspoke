package dev.brgr.outspoke.inference

import dev.brgr.outspoke.audio.AudioChunk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.AfterClass
import org.junit.Assume
import org.junit.BeforeClass
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * Real-audio regression anchor for number-sequence normalisation (PR "Keep countdowns
 * and number lists separate during formatting").
 *
 * `num-countdown2.wav` is a Piper-spoken countdown with inter-digit pauses. The decoder
 * emits the number words as separate tokens; before the phrase-gated normaliser they were
 * greedily accumulated into one run and arithmetically summed, collapsing the whole
 * countdown to "55". The digits must survive as individual digits.
 *
 * **Skipping:** like [RealAudioPipelineTest], skipped when the model directory is unavailable.
 */
class NumbersRealAudioTest {

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
        val stream = javaClass.classLoader.getResourceAsStream("audio/$name")
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

    /**
     * Regression anchor for the detokenize digit-stripping bug: the model decodes dictated
     * numbers as byte-level digit tokens ("2" + "0"), which the index-padding strip in
     * `ParakeetEngine.detokenize` used to delete wholesale, producing "I was buying items."
     * The digits must survive to the committed text as the digit run "20".
     */
    @Test
    fun `dictated digits survive detokenization into the final text`() {
        Assume.assumeTrue(modelAvailable)
        engine.setLanguage("en")
        val finalText = pipelineFinal("num-buying.wav")
        assertThat(finalText)
            .describedAs("Digit emission must not be stripped (got '%s')", finalText)
            .contains("20 items")
    }

    @Test
    fun `dictated digit string survives as consecutive digits`() {
        Assume.assumeTrue(modelAvailable)
        engine.setLanguage("en")
        val finalText = pipelineFinal("num-code2.wav")
        assertThat(finalText)
            .describedAs("Code digits must survive (got '%s')", finalText)
            .contains("55123")
    }

    private fun pipelineFinal(fixture: String): String {
        val samples = wavSamples(fixture)
        val repo = InferenceRepository(engine)
        val results = runBlocking {
            repo.transcribe(flowOf(*chunkify(samples).toTypedArray()), postprocessingEnabled = true)
                .toList()
        }
        return results.filterIsInstance<TranscriptResult.Final>().lastOrNull()?.text
            ?: results.filterIsInstance<TranscriptResult.Partial>().lastOrNull()?.text
            ?: ""
    }

    @Test
    fun `spoken countdown normalises to separate digits not a sum`() {
        Assume.assumeTrue(modelAvailable)
        engine.setLanguage("en")
        val samples = wavSamples("num-countdown2.wav")

        val repo = InferenceRepository(engine)
        val results = runBlocking {
            repo.transcribe(flowOf(*chunkify(samples).toTypedArray()), postprocessingEnabled = true)
                .toList()
        }
        val finalText = results.filterIsInstance<TranscriptResult.Final>().lastOrNull()?.text
            ?: results.filterIsInstance<TranscriptResult.Partial>().lastOrNull()?.text
            ?: ""

        // Model-independent contract (v0.4.0 regression): every countdown digit must
        // survive individually, never summed into "55". Digit-sequence equality is
        // asserted instead of spacing because punctuation between digits (e.g. "10, 9, …")
        // is a model formatting choice, not part of the contract.
        assertThat(finalText.filter { it.isDigit() })
            .describedAs("Countdown digits must survive individually (got '%s')", finalText)
            .isEqualTo("10987654321")
    }
}
