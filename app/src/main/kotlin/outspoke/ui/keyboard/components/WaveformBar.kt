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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.brgr.outspoke.ui.theme.OutspokeKeyboardTheme
import kotlin.math.exp
import kotlin.math.pow

private const val BAR_COUNT = 60

/**
 * A row of animated vertical bars that visualise the current microphone amplitude.
 *
 * A single [animateFloatAsState] drives the whole row - all bars are drawn in one
 * [Canvas] pass using `drawRoundRect`, avoiding the 60 individual Compose animation
 * states and recompositions that the previous implementation incurred.
 *
 * @param amplitude Normalised RMS amplitude in the range [0.0, 1.0]. Passing 0f
 *                  collapses all bars to a flat minimum line.
 * @param maxBarHeight The tallest height a single bar can reach at full amplitude.
 * @param barWidth     Width of each bar column.
 * @param barSpacing   Gap between adjacent bars.
 */
@Composable
fun WaveformBar(
    amplitude: Float,
    modifier: Modifier = Modifier,
    maxBarHeight: Dp = 28.dp,
    barWidth: Dp = 3.dp,
    barSpacing: Dp = 2.dp,
) {
    // Single animation state for the whole waveform - one tick per frame instead of 60.
    val animatedAmplitude by animateFloatAsState(
        targetValue = amplitude,
        animationSpec = tween(durationMillis = 80),
        label = "waveformAmplitude",
    )

    val barColorOne = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.4f)
    val barColorTwo  = MaterialTheme.colorScheme.primary

    Canvas(
        modifier = modifier
            .height(maxBarHeight)
            .width((barWidth + barSpacing) * BAR_COUNT - barSpacing),
    ) {
        drawBars(
            amplitude    = animatedAmplitude,
            barWidthPx   = barWidth.toPx(),
            barSpacingPx = barSpacing.toPx(),
            barColorOne     = barColorOne,
            barColorTwo     = barColorTwo,
        )
    }
}

/**
 * Draws all [BAR_COUNT] bars onto the [DrawScope].
 * Called inside the Canvas lambda - no allocations other than the CornerRadius value type.
 */
private fun DrawScope.drawBars(
    amplitude: Float,
    barWidthPx: Float,
    barSpacingPx: Float,
    barColorOne: androidx.compose.ui.graphics.Color,
    barColorTwo: androidx.compose.ui.graphics.Color,
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
            (amplitude * gaussianEnvelope).coerceIn(0.05f, 1f)
        } else {
            0.05f // Minimum flat line when silent
        }

        val barHeight = maxHeightPx * heightFraction
        val left = index * stride
        val top  = (maxHeightPx - barHeight) / 2f

        // Interpolate colour: edges → barColorOne, centre → barColorTwo,
        // using the same gaussian envelope that drives bar height.
        val barColor = lerp(barColorOne, barColorTwo, gaussianEnvelope)

        drawRoundRect(
            color        = barColor,
            topLeft      = Offset(left, top),
            size         = Size(barWidthPx, barHeight),
            cornerRadius = CornerRadius(barWidthPx / 2f),
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF111111)
@Composable
private fun WaveformBarSilentPreview() {
    OutspokeKeyboardTheme { WaveformBar(amplitude = 0f) }
}

@Preview(showBackground = true, backgroundColor = 0xFF111111)
@Composable
private fun WaveformBarMidPreview() {
    OutspokeKeyboardTheme { WaveformBar(amplitude = 0.5f) }
}

@Preview(showBackground = true, backgroundColor = 0xFF111111)
@Composable
private fun WaveformBarLoudPreview() {
    OutspokeKeyboardTheme { WaveformBar(amplitude = 1f) }
}

