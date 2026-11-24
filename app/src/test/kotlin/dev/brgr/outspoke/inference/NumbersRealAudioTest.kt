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

        assertThat(finalText)
            .describedAs("Countdown must stream through as individual digits (got '%s')", finalText)
            .contains("10 9 8 7 6")
            .contains("5 4 3 2 1")
            .doesNotContain("55")
    }
}
