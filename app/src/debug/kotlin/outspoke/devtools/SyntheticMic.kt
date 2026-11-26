package dev.brgr.outspoke.devtools

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.brgr.outspoke.audio.SpectrumAnalyzer
import dev.brgr.outspoke.audio.SpectrumFrame
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

private const val CHUNK_SAMPLES = 480
private const val SAMPLE_RATE = 16_000.0
private const val HARMONICS = 30

/**
 * Debug-only fake microphone for the level meter: synthesises voiced chunks with moving formants
 * plus a periodic sibilant burst, pushes them through the **real** [SpectrumAnalyzer], and emits
 * frames on the capture cadence (30 ms).
 *
 * The point is to make the mirrored meter observable - screenshots, band-floor tuning and the
 * treble-bloom behaviour for `s`/`f`/`th` can all be checked without a human talking into the
 * emulator microphone.
 */
@Composable
fun rememberSyntheticSpectrum(active: Boolean): SpectrumFrame? {
    var frame by remember { mutableStateOf<SpectrumFrame?>(null) }

    LaunchedEffect(active) {
        frame = null
        if (!active) return@LaunchedEffect

        val analyzer = SpectrumAnalyzer()
        val pcm = ShortArray(CHUNK_SAMPLES)
        val phases = DoubleArray(HARMONICS + 1)
        val noise = Random(7)
        var chunkIndex = 0

        while (true) {
            val seconds = chunkIndex * CHUNK_SAMPLES / SAMPLE_RATE
            val sibilant = chunkIndex % 46 in 30..36
            fillChunk(pcm, phases, seconds, sibilant, noise)
            frame = SpectrumFrame(analyzer.process(pcm).copyOf())
            chunkIndex++
            delay(30)
        }
    }

    return frame
}

/** One 30 ms chunk: harmonic stack weighted by two formants, optionally plus a sibilant hiss. */
private fun fillChunk(
    pcm: ShortArray,
    phases: DoubleArray,
    seconds: Double,
    sibilant: Boolean,
    noise: Random,
) {
    val fundamental = 105.0
    // Formants wander on a ~1.7 s cycle so the meter visibly "speaks".
    val first = 320.0 + 620.0 * (0.5 + 0.5 * sin(2 * PI * seconds / 1.7))
    val second = 900.0 + 1_500.0 * (0.5 + 0.5 * sin(2 * PI * (seconds / 1.7 + 0.35)))
    // Syllable envelope: silence-ish dips between words.
    val voiceGain = 0.35 + 0.55 * (0.5 + 0.5 * sin(2 * PI * (seconds / 0.45)))

    for (index in pcm.indices) {
        var sample = 0.0
        for (harmonic in 1..HARMONICS) {
            val frequency = fundamental * harmonic
            val formant = exp(-((frequency - first) / 260.0).pow(2)) +
                0.75 * exp(-((frequency - second) / 320.0).pow(2))
            val weight = formant / harmonic.toDouble().pow(0.9)
            phases[harmonic] = (phases[harmonic] + 2 * PI * frequency / SAMPLE_RATE) % (2 * PI)
            sample += weight * sin(phases[harmonic])
        }
        sample *= voiceGain * 0.18

        if (sibilant) {
            // Hiss shaped around 5.5 kHz - the band that should light up the meter's outer bars.
            val hiss = (noise.nextDouble() * 2.0 - 1.0) * 0.055 *
                cos(2 * PI * 5_500.0 * index / SAMPLE_RATE)
            sample += hiss
        }

        pcm[index] = (sample.coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt().toShort()
    }
}
