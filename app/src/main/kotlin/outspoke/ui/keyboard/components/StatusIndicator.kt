package dev.brgr.outspoke.ui.keyboard.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.brgr.outspoke.ui.keyboard.KeyboardUiState
import dev.brgr.outspoke.ui.theme.OutspokeKeyboardTheme

/**
 * Crossfades between six distinct visual states driven by [uiState].
 *
 * - [KeyboardUiState.Idle]          → small grey mic icon
 * - [KeyboardUiState.Listening]     → pulsing filled circle (accent colour)
 * - [KeyboardUiState.Processing]    → spinner + partial transcript text
 * - [KeyboardUiState.Transcribing]  → spinner + "Transcribing…" label (mic off, engine busy)
 * - [KeyboardUiState.Error]         → warning icon + error message + "Open Outspoke" action
 * - [KeyboardUiState.EngineLoading] → spinner + loading message + "Open Outspoke" action
 *
 * @param onOpenCompanionApp Called when the user taps the "Open Outspoke" action button shown
 *                           in [KeyboardUiState.Error] and [KeyboardUiState.EngineLoading] states.
 */
@Composable
fun StatusIndicator(
    uiState: KeyboardUiState,
    modifier: Modifier = Modifier,
    onOpenCompanionApp: (() -> Unit)? = null,
) {
    AnimatedContent(
        targetState = uiState,
        transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(150)) },
        contentAlignment = Alignment.Center,
        label = "statusIndicatorContent",
        modifier = modifier,
    ) { state ->
        when (state) {
            is KeyboardUiState.Idle          -> IdleIndicator()
            is KeyboardUiState.Listening     -> ListeningIndicator()
            is KeyboardUiState.Processing    -> ProcessingIndicator(partial = state.partial)
            is KeyboardUiState.Transcribing  -> TranscribingIndicator()
            is KeyboardUiState.Error         -> ErrorIndicator(
                message = state.message,
                onOpenCompanionApp = onOpenCompanionApp,
            )
            is KeyboardUiState.EngineLoading -> EngineLoadingIndicator(
                message = state.message,
                onOpenCompanionApp = onOpenCompanionApp,
            )
        }
    }
}

@Composable
private fun IdleIndicator() {
    Icon(
        imageVector = Icons.Filled.Mic,
        contentDescription = "Idle - tap the button to start dictating",
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(20.dp),
    )
}

@Composable
private fun ListeningIndicator() {
    val infiniteTransition = rememberInfiniteTransition(label = "listeningPulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseScale",
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(20.dp)
            .scale(pulseScale)
            .background(MaterialTheme.colorScheme.primary, CircleShape),
    ) { /* pulsing filled circle - no inner content needed */ }
}

@Composable
private fun ProcessingIndicator(partial: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        GradientArcSpinner(modifier = Modifier.size(16.dp))
        if (partial.isNotEmpty()) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = partial,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
        }
    }
}

/** Shown after the mic stops while the engine is still running its final inference pass. */
@Composable
private fun TranscribingIndicator() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        GradientArcSpinner(modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "Transcribing…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun ErrorIndicator(
    message: String,
    onOpenCompanionApp: (() -> Unit)? = null,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = "Error",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 2,
            )
        }
        if (onOpenCompanionApp != null) {
            Spacer(modifier = Modifier.height(2.dp))
            TextButton(
                onClick = onOpenCompanionApp,
                modifier = Modifier.height(28.dp),
            ) {
                Text(
                    text = "Open Outspoke",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun EngineLoadingIndicator(
    message: String,
    onOpenCompanionApp: (() -> Unit)? = null,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GradientArcSpinner(modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
        if (onOpenCompanionApp != null) {
            Spacer(modifier = Modifier.height(2.dp))
            TextButton(
                onClick = onOpenCompanionApp,
                modifier = Modifier.height(28.dp),
            ) {
                Text(
                    text = "Open Outspoke",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/**
 * Indeterminate spinner that draws a 270° arc rotating continuously.
 *
 * The arc is painted with a sweep gradient that fades from transparent at the tail,
 * blends through [MaterialTheme.colorScheme.tertiary] in the middle, and reaches full
 * [MaterialTheme.colorScheme.primary] at the head - giving a comet-tail appearance.
 * Both the gradient colours and the arc react to theme changes at runtime.
 */
@Composable
private fun GradientArcSpinner(
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 2.dp,
) {
    val head = MaterialTheme.colorScheme.primary
    val mid  = MaterialTheme.colorScheme.tertiary

    val infiniteTransition = rememberInfiniteTransition(label = "arcSpinnerRotation")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue  = 360f,
        animationSpec = infiniteRepeatable(
            animation  = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "arcSpinnerAngle",
    )

    Canvas(modifier = modifier) {
        val strokePx = strokeWidth.toPx()
        rotate(rotation) {
            drawArc(
                brush = Brush.sweepGradient(
                    0f    to head.copy(alpha = 0f),      // tail  - fully transparent
                    0.55f to mid.copy(alpha = 0.65f),    // mid   - tertiary, half-visible
                    0.75f to head,                       // head  - full primary
                    1f    to head.copy(alpha = 0f),      // close - fade to transparent so the
                                                         //         seam at 360°/0° is invisible
                ),
                startAngle = 0f,
                sweepAngle = 270f,
                useCenter  = false,
                style      = Stroke(width = strokePx, cap = StrokeCap.Round),
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF111111)
@Composable
private fun StatusIdlePreview() {
    OutspokeKeyboardTheme { StatusIndicator(uiState = KeyboardUiState.Idle) }
}

@Preview(showBackground = true, backgroundColor = 0xFF111111)
@Composable
private fun StatusListeningPreview() {
    OutspokeKeyboardTheme { StatusIndicator(uiState = KeyboardUiState.Listening) }
}

@Preview(showBackground = true, backgroundColor = 0xFF111111)
@Composable
private fun StatusProcessingPreview() {
    OutspokeKeyboardTheme {
        StatusIndicator(uiState = KeyboardUiState.Processing("The quick brown fox…"))
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF111111)
@Composable
private fun StatusTranscribingPreview() {
    OutspokeKeyboardTheme {
        StatusIndicator(uiState = KeyboardUiState.Transcribing)
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF111111)
@Composable
private fun StatusErrorPreview() {
    OutspokeKeyboardTheme {
        StatusIndicator(uiState = KeyboardUiState.Error("Microphone permission denied"))
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF111111)
@Composable
private fun StatusEngineLoadingPreview() {
    OutspokeKeyboardTheme {
        StatusIndicator(uiState = KeyboardUiState.EngineLoading("Loading transcription engine…"))
    }
}
