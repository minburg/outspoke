package dev.brgr.outspoke.audio

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.ranges.ClosedFloatingPointRange
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One frame of log-spaced band energies for the keyboard level meter.
 *
 * [bands] is always `SpectrumAnalyzer.bandCount` long, ordered low frequency first, each value
 * dB-mapped to `[0.0, 1.0]`. Bands and [AudioCaptureManager.amplitude] share the mapping shape
 * but each has its own floor and display gain. The array is a private copy owned by the
 * receiver: the analyzer reuses its own buffer internally, so publishing it directly would let
 * the capture thread mutate what the UI is drawing.
 */
class SpectrumFrame(val bands: FloatArray)

/**
 * Turns one microphone chunk into log-spaced band energies, cheaply enough to run inside the
 * capture read loop next to [AudioCaptureManager.calculateRms].
 *
 * Pipeline per chunk: one-pole DC blocker (removes handling rumble and 50 Hz hum, which would
 * otherwise dominate the centre of the mirrored meter), Hann window, in-place radix-2 FFT of
 * [fftSize] samples (480-sample chunks are zero-padded), then band energies summed from the FFT
 * bins and dB-mapped with a floor and display gain tuned for band means (see `METER_FLOOR_DB`).
 *
 * All buffers and tables are allocated once in the constructor; [process] allocates nothing.
 * One instance belongs to one capture session - create it per session or call [reset].
 *
 * @param bandCount   Number of log-spaced bands between [lowHz] and [highHz].
 * @param highPassHz  DC-blocker corner; energy below this is filtered out before the FFT.
 * @param fftSize     FFT length; a power of two >= the chunk size (512 covers 480-sample chunks).
 * @param smooth      Applies a per-band attack/release filter so the meter falls slowly instead
 *                    of flickering with every chunk.
 */
class SpectrumAnalyzer(
    val bandCount: Int = DEFAULT_BAND_COUNT,
    highPassHz: Float = DEFAULT_HIGH_PASS_HZ,
    private val sampleRate: Int = DEFAULT_SAMPLE_RATE,
    private val fftSize: Int = DEFAULT_FFT_SIZE,
    private val lowHz: Float = DEFAULT_LOW_HZ,
    private val highHz: Float = DEFAULT_HIGH_HZ,
    private val smooth: Boolean = true,
) {

    private val half = fftSize / 2

    /** Hann window, precomputed: coherent gain 0.5, which the output scaling assumes. */
    private val window = FloatArray(fftSize) {
        0.5f - 0.5f * cos(2f * PI.toFloat() * it / (fftSize - 1))
    }

    private val bitReversed = IntArray(fftSize) { i ->
        var value = i
        var reversed = 0
        repeat(INTEGER_BITS) {
            reversed = (reversed shl 1) or (value and 1)
            value = value shr 1
        }
        reversed ushr (INTEGER_BITS - log2Of(fftSize))
    }

    /** Twiddle factors `e^(-2*pi*i*k/n)` for k in [0, fftSize/2). */
    private val twiddleCos = FloatArray(half) { cos(-2f * PI.toFloat() * it / fftSize) }
    private val twiddleSin = FloatArray(half) { sin(-2f * PI.toFloat() * it / fftSize) }

    private val real = FloatArray(fftSize)
    private val imaginary = FloatArray(fftSize)

    /** Bin width in Hz; 512 points at 16 kHz gives 31.25 Hz bins. */
    private val binWidthHz = sampleRate.toFloat() / fftSize

    /** Inclusive lower / exclusive upper FFT bin index per band (bands are log-spaced). */
    private val bandBinStart = IntArray(bandCount)
    private val bandBinEnd = IntArray(bandCount)

    private val bandEdgeHz = FloatArray(bandCount + 1) {
        lowHz * (highHz / lowHz).pow(it.toFloat() / bandCount)
    }

    /** DC blocker (`y[n] = x[n] - x[n-1] + r*y[n-1]`) state, carried across chunks. */
    private val dcPole = 1f - 2f * PI.toFloat() * highPassHz / sampleRate
    private var inputPrevious = 0f
    private var outputPrevious = 0f

    private val smoothed = FloatArray(bandCount)

    /**
     * Calibration: a full-scale sine concentrated in one bin reaches `fftSize * 0.5` (window sum) `* 0.5`
     * (positive/negative split) at the peak, so `0.25 * fftSize` maps amplitude 1.0 to 0 dBFS.
     */
    private val fullScale = 0.25 * fftSize

    init {
        require(fftSize > 1 && fftSize and (fftSize - 1) == 0) { "fftSize must be a power of two" }
        require(bandCount >= 2) { "need at least two bands" }
        require(highHz < sampleRate / 2f) { "band range must stay below Nyquist" }
        for (band in 0 until bandCount) {
            bandBinStart[band] = floor(bandEdgeHz[band] / binWidthHz).toInt().coerceIn(0, half)
            bandBinEnd[band] = ceil(bandEdgeHz[band + 1] / binWidthHz).toInt().coerceIn(0, half)
        }
    }

    /** Frequency span of [band], for labels and tests. */
    fun bandRange(band: Int): ClosedFloatingPointRange<Float> = bandEdgeHz[band]..bandEdgeHz[band + 1]

    /** Band energies in `[0.0, 1.0]`, low frequency first. The returned array is reused. */
    fun process(pcm: ShortArray): FloatArray {
        fillFftInput(pcm)
        transform()

        for (band in 0 until bandCount) {
            val start = bandBinStart[band]
            val end = bandBinEnd[band]
            val level = if (end <= start) {
                0f
            } else {
                var power = 0.0
                for (bin in start until end) {
                    val re = real[bin]
                    val im = imaginary[bin]
                    power += re * re + im * im
                }
                val amplitude = (sqrt(power / (end - start)) / fullScale).toFloat()
                normaliseBand(amplitude)
            }
            smoothed[band] = if (smooth) attackRelease(smoothed[band], level) else level
        }
        return smoothed
    }

    /** Clears the filter state and the smoothed meter (call when capture stops). */
    fun reset() {
        inputPrevious = 0f
        outputPrevious = 0f
        smoothed.fill(0f)
    }

    private fun fillFftInput(pcm: ShortArray) {
        val usable = minOf(pcm.size, fftSize)
        for (index in 0 until usable) {
            val sample = pcm[index].toFloat() / Short.MAX_VALUE
            val blocked = sample - inputPrevious + dcPole * outputPrevious
            inputPrevious = sample
            outputPrevious = blocked
            real[index] = blocked * window[index]
            imaginary[index] = 0f
        }
        for (index in usable until fftSize) {
            real[index] = 0f
            imaginary[index] = 0f
        }
    }

    /** Iterative in-place Cooley-Tukey FFT; no allocation. */
    private fun transform() {
        for (index in 0 until fftSize) {
            val swap = bitReversed[index]
            if (swap > index) {
                val re = real[index]
                real[index] = real[swap]
                real[swap] = re
                val im = imaginary[index]
                imaginary[index] = imaginary[swap]
                imaginary[swap] = im
            }
        }

        var span = 2
        while (span <= fftSize) {
            val halfSpan = span / 2
            val step = fftSize / span
            var start = 0
            while (start < fftSize) {
                var twiddle = 0
                for (offset in start until start + halfSpan) {
                    val pairIndex = offset + halfSpan
                    val cos = twiddleCos[twiddle]
                    val sin = twiddleSin[twiddle]
                    val twinnedRe = real[pairIndex] * cos - imaginary[pairIndex] * sin
                    val twinnedIm = real[pairIndex] * sin + imaginary[pairIndex] * cos
                    real[pairIndex] = real[offset] - twinnedRe
                    imaginary[pairIndex] = imaginary[offset] - twinnedIm
                    real[offset] += twinnedRe
                    imaginary[offset] += twinnedIm
                    twiddle += step
                }
                start += span
            }
            span = span shl 1
        }
    }

    /** dB mapping with the meter's floor and display gain; 0 dBFS band level reads 1.0. */
    private fun normaliseBand(amplitude: Float): Float {
        if (amplitude <= 0f) return 0f
        val db = 20f * log10(amplitude)
        return ((db - METER_FLOOR_DB) / -METER_FLOOR_DB * METER_DISPLAY_GAIN).coerceIn(0f, 1f)
    }

    /** Fast attack, slow release - the classic VU-meter response. */
    private fun attackRelease(previous: Float, next: Float): Float =
        if (next > previous) previous + ATTACK * (next - previous)
        else previous + RELEASE * (next - previous)

    private companion object {
        const val INTEGER_BITS = 32

        /** dBFS band level that reads 0.0; above the RMS meter's floor because a band is mean bin power. */
        const val METER_FLOOR_DB = -50f

        /** Display gain after the dB mapping; lifts conversational speech (-45..-20 dBFS per band) to the upper half. */
        const val METER_DISPLAY_GAIN = 1.6f
        const val ATTACK = 0.5f
        const val RELEASE = 0.15f

        val DEFAULT_BAND_COUNT = 20
        const val DEFAULT_HIGH_PASS_HZ = 70f
        const val DEFAULT_SAMPLE_RATE = 16_000
        const val DEFAULT_FFT_SIZE = 512

        /** Voice fundamental and first formants live below 1 kHz; sibilants sit at 4-8 kHz. */
        const val DEFAULT_LOW_HZ = 80f
        const val DEFAULT_HIGH_HZ = 7_600f

        fun log2Of(value: Int): Int = 31 - Integer.numberOfLeadingZeros(value)
    }
}
