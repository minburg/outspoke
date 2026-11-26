package dev.brgr.outspoke.ui.keyboard.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.brgr.outspoke.audio.SpectrumFrame
import dev.brgr.outspoke.ui.theme.OutspokeKeyboardTheme
import kotlin.math.exp
import kotlin.math.pow

/**
 * Bar count of the RMS fallback row. Deliberately `2 * 20 + 1`, matching the mirrored meter's
 * width (one centre bar plus one per band per side), so the row never changes length the moment
 * capture starts and real bands arrive.
 */
private const val BAR_COUNT = 41

/** Shortest bar the meter ever draws, so a silent room still reads as a line, not a gap. */
private const val MIN_BAR_FRACTION = 0.06f

/** Row height of the level meter; [KeyboardScreen] budgets the strip against it. */
internal val WaveformBarHeight = 18.dp

/**
 * The microphone level meter: one bar per frequency band, **mirrored about the centre** -
 * bass in the middle, treble at both edges. Vowels swell at the centre; sibilants (`s`, `f`,
 * `th`) bloom at both ends, which is the feedback a dictation user actually wants.
 *
 * Mirroring costs nothing: the signal is mono, the two halves read the same band array, and the
 * row stays as symmetric as the gaussian envelope it replaces. Near-DC energy is filtered out in
 * [dev.brgr.outspoke.audio.SpectrumAnalyzer] so rumble cannot dominate the centre.
 *
 * Everything is drawn in one [Canvas] pass, and the temporal smoothing lives in the analyzer, so
 * a frame costs one draw - no per-bar animation state.
 *
 * @param spectrum Band energies from [dev.brgr.outspoke.audio.SpectrumAnalyzer]; `null` (no
 *                 recording yet, or an engine whose path has no spectrum) falls back to [amplitude].
 * @param amplitude Normalised RMS amplitude in the range [0.0, 1.0], used for the fallback
 *                  gaussian row. Passing 0f collapses all bars to a flat minimum line.
 * @param maxBarHeight The tallest height a single bar can reach at full energy.
 * @param barWidth     Width of each bar column.
 * @param barSpacing   Gap between adjacent bars.
 */
@Composable
fun WaveformBar(
    amplitude: Float,
    modifier: Modifier = Modifier,
    spectrum: SpectrumFrame? = null,
    maxBarHeight: Dp = WaveformBarHeight,
    barWidth: Dp = 3.dp,
    barSpacing: Dp = 2.dp,
) {
    val bands = spectrum?.bands?.takeIf { it.isNotEmpty() }

    // Single animation state for the fallback row - one tick per frame instead of 60.
    val animatedAmplitude by animateFloatAsState(
        targetValue = amplitude,
        animationSpec = tween(durationMillis = 80),
        label = "waveformAmplitude",
    )

    val quietColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
    val loudColor = MaterialTheme.colorScheme.primary
    val fallbackQuietColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.4f)

    // Mirrored layout: band 0 sits at the centre, band i is drawn at centre +/- i steps.
    val barCount = if (bands != null) bands.size * 2 + 1 else BAR_COUNT

    Canvas(
        modifier = modifier
            .height(maxBarHeight)
            .width((barWidth + barSpacing) * barCount - barSpacing)
            // Decorative: the status pill next to it already announces Listening/Processing.
            .semantics { hideFromAccessibility() },
    ) {
        if (bands != null) {
            drawBands(
                bands = bands,
                barWidthPx = barWidth.toPx(),
                barSpacingPx = barSpacing.toPx(),
                quietColor = quietColor,
                loudColor = loudColor,
            )
        } else {
            drawBars(
                amplitude = animatedAmplitude,
                barWidthPx = barWidth.toPx(),
                barSpacingPx = barSpacing.toPx(),
                barColorOne = fallbackQuietColor,
                barColorTwo = loudColor,
            )
        }
    }
}

/**
 * Draws the mirrored band meter: every band twice (left and right of centre) except the lowest,
 * which is the centre bar. Called inside the Canvas lambda - no allocations.
 */
private fun DrawScope.drawBands(
    bands: FloatArray,
    barWidthPx: Float,
    barSpacingPx: Float,
    quietColor: Color,
    loudColor: Color,
) {
    val maxHeightPx = size.height
    val stride = barWidthPx + barSpacingPx
    val centre = size.width / 2f

    for (index in bands.indices) {
        val energy = bands[index].coerceIn(0f, 1f)
        val barHeight = maxHeightPx * (MIN_BAR_FRACTION + (1f - MIN_BAR_FRACTION) * energy)
        val top = (maxHeightPx - barHeight) / 2f
        val color = lerp(quietColor, loudColor, energy)
        val offset = index * stride

        drawBar(centre - barWidthPx / 2f, top, barHeight, barWidthPx, color)  // centre bar = lowest band
        if (index > 0) {
            drawBar(centre - offset - barWidthPx / 2f, top, barHeight, barWidthPx, color)
            drawBar(centre + offset - barWidthPx / 2f, top, barHeight, barWidthPx, color)
        }
    }
}

private fun DrawScope.drawBar(
    left: Float,
    top: Float,
    barHeight: Float,
    barWidthPx: Float,
    color: Color,
) {
    drawRoundRect(
        color = color,
        topLeft = Offset(left, top),
        size = Size(barWidthPx, barHeight),
        cornerRadius = CornerRadius(barWidthPx / 2f),
    )
}

/**
 * Fallback row for paths without spectrum data: [BAR_COUNT] bars under a gaussian envelope whose
 * height tracks the RMS amplitude. Kept so non-streaming engines still show a live meter.
 */
private fun DrawScope.drawBars(
    amplitude: Float,
    barWidthPx: Float,
    barSpacingPx: Float,
    barColorOne: Color,
    barColorTwo: Color,
) {
    val maxHeightPx = size.height
    val stride = barWidthPx + barSpacingPx

    for (index in 0 until BAR_COUNT) {
        // Normalise index to [0, 1] and compute a Gaussian envelope centred at 0.5.
        val x = index.toDouble() / (BAR_COUNT - 1)
        val sigma = 0.2
        val mu = 0.5
        val gaussianEnvelope = exp(-(x - mu).pow(2) / (2 * sigma.pow(2))).toFloat()

        val heightFraction = if (amplitude > 0f) {
            (amplitude * gaussianEnvelope).coerceIn(MIN_BAR_FRACTION, 1f)
        } else {
            MIN_BAR_FRACTION // Minimum flat line when silent
        }

        val barHeight = maxHeightPx * heightFraction
        val left = index * stride
        val top = (maxHeightPx - barHeight) / 2f

        // Interpolate colour: edges → barColorOne, centre → barColorTwo,
        // using the same gaussian envelope that drives bar height.
        val barColor = lerp(barColorOne, barColorTwo, gaussianEnvelope)

        drawBar(left, top, barHeight, barWidthPx, barColor)
    }
}

/** Loud vowel-ish centre with sibilant tips at both ends, for previews. */
private val previewBands = floatArrayOf(
    0.95f, 0.85f, 0.7f, 0.55f, 0.45f, 0.4f, 0.34f, 0.3f, 0.26f, 0.24f,
    0.22f, 0.2f, 0.19f, 0.2f, 0.24f, 0.3f, 0.38f, 0.5f, 0.62f, 0.74f,
)

@Preview(showBackground = true, backgroundColor = 0xFF111111, name = "Meter · silent")
@Composable
private fun WaveformBarSilentPreview() {
    OutspokeKeyboardTheme { WaveformBar(amplitude = 0f, spectrum = SpectrumFrame(FloatArray(20))) }
}

@Preview(showBackground = true, backgroundColor = 0xFF111111, name = "Meter · speech")
@Composable
private fun WaveformBarSpeechPreview() {
    OutspokeKeyboardTheme { WaveformBar(amplitude = 0.6f, spectrum = SpectrumFrame(previewBands)) }
}

@Preview(showBackground = true, backgroundColor = 0xFF111111, name = "Meter · RMS fallback")
@Composable
private fun WaveformBarFallbackPreview() {
    OutspokeKeyboardTheme { WaveformBar(amplitude = 0.6f) }
}
