package dev.brgr.outspoke.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Tests for [SpectrumAnalyzer]: that a tone lands in the band it should, that near-DC energy is
 * filtered away (it would otherwise dominate the centre of the mirrored meter), and that the meter
 * reads sensibly for silence and for broadband noise.
 */
class SpectrumAnalyzerTest {

    private fun sineChunk(frequencyHz: Double, amplitude: Double = 0.8): ShortArray =
        ShortArray(CHUNK_SAMPLES) { index ->
            val phase = 2 * PI * frequencyHz * index / SAMPLE_RATE
            (amplitude * sin(phase) * Short.MAX_VALUE).toInt().toShort()
        }

    private fun constantChunk(value: Int): ShortArray = ShortArray(CHUNK_SAMPLES) { value.toShort() }

    private fun noiseChunk(seed: Int): ShortArray {
        val random = Random(seed)
        return ShortArray(CHUNK_SAMPLES) { (random.nextDouble() * 2 - 1).times(0.5 * Short.MAX_VALUE).toInt().toShort() }
    }

    /** Feeds [chunks] frames and returns the last one (transients from the DC blocker settle out). */
    private fun SpectrumAnalyzer.processAll(chunks: List<ShortArray>): FloatArray {
        var last = FloatArray(bandCount)
        chunks.forEach { last = process(it) }
        return last
    }

    private fun analyzer(smooth: Boolean = false) = SpectrumAnalyzer(smooth = smooth)

    @Test
    fun `given silence, when analysed, then every band reads zero`() {
        val result = analyzer().processAll(listOf(ShortArray(CHUNK_SAMPLES)))

        assertEquals(BAND_COUNT, result.size)
        result.forEachIndexed { band, value ->
            assertTrue("band $band = $value", value == 0f)
        }
    }

    // Placement is checked well below full scale: with the meter's display gain a loud tone
    // legitimately pins its band (and a leaking neighbour) at 1.0, which would hide mis-binning.
    @Test
    fun `given a one kilohertz tone, when analysed, then the band covering one kilohertz is loudest`() {
        val subject = analyzer()
        val result = subject.processAll(List(3) { sineChunk(1_000.0, amplitude = 0.05) })

        val expectedBand = (0 until BAND_COUNT).first { 1_000f in subject.bandRange(it) }
        val loudestBand = result.withIndex().maxByOrNull { it.value }!!.index

        assertEquals(expectedBand, loudestBand)
        assertTrue("one kilohertz band should read loud: ${result[expectedBand]}", result[expectedBand] > 0.5f)
    }

    @Test
    fun `given a one kilohertz tone, when analysed, then distant bands stay quiet`() {
        val result = analyzer().processAll(List(3) { sineChunk(1_000.0, amplitude = 0.05) })
        val peak = result.max()

        // Mirrored meter: the eye reads the outer bars for sibilants and the centre for bass, so a
        // tone must not smear across the row. Three octaves away must be clearly below the peak.
        (0 until 6).forEach { band ->
            assertTrue("low band $band = ${result[band]}, peak = $peak", result[band] < 0.25f * peak)
        }
        (17 until BAND_COUNT).forEach { band ->
            assertTrue("high band $band = ${result[band]}, peak = $peak", result[band] < 0.25f * peak)
        }
    }

    @Test
    fun `given a constant dc offset, when analysed, then the high-pass filter leaves the meter flat`() {
        // Handling rumble and 50 Hz hum sit below the first band; in a mirrored meter they would
        // otherwise pin the centre bars to full height.
        val result = analyzer().processAll(List(4) { constantChunk(12_000) })

        result.forEachIndexed { band, value ->
            assertTrue("band $band = $value", value < 0.05f)
        }
    }

    @Test
    fun `given white noise, when analysed, then every band carries energy and none runs away`() {
        val result = analyzer().processAll(List(6) { noiseChunk(it) })

        val lowest = result.min()
        val highest = result.max()
        result.forEachIndexed { band, value ->
            assertTrue("band $band = $value", value > 0.15f)
        }
        assertTrue("spread too wide: low=$lowest high=$highest", highest / lowest < 3f)
        assertTrue("meter should not saturate on noise: high=$highest", highest < 0.98f)
    }

    @Test
    fun `given smoothing enabled, when level drops, then the meter falls slowly`() {
        val subject = analyzer(smooth = true)
        val loud = sineChunk(1_000.0)
        subject.process(loud)                                   // attack frame
        val held = subject.process(loud)                        // nearing steady state
        val decaying = subject.process(ShortArray(CHUNK_SAMPLES))

        val band = held.withIndex().maxByOrNull { it.value }!!.index
        assertTrue("attack should not jump straight to full: ${held[band]}", held[band] < 1f)
        assertTrue("release should lag the drop: held=${held[band]} decaying=${decaying[band]}",
            decaying[band] > 0.8f * held[band])
    }

    @Test
    fun `given a used analyzer, when reset, then the meter starts flat again`() {
        val subject = analyzer(smooth = true)
        subject.processAll(List(4) { sineChunk(1_000.0) })

        subject.reset()
        val result = subject.process(ShortArray(CHUNK_SAMPLES))

        result.forEachIndexed { band, value ->
            assertTrue("band $band = $value", value == 0f)
        }
    }

    @Test
    fun `given analysed audio, when read, then bands stay within the normalised range`() {
        val subject = analyzer()
        subject.processAll(List(3) { noiseChunk(100 + it) })
        val result = subject.processAll(List(3) { sineChunk(300.0, amplitude = 1.0) })

        result.forEachIndexed { band, value ->
            assertTrue("band $band = $value", value >= 0f && value <= 1f)
        }
    }

    private companion object {
        const val CHUNK_SAMPLES = 480
        const val SAMPLE_RATE = 16_000.0
        const val BAND_COUNT = 20
    }
}
