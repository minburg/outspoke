package dev.brgr.outspoke.ui.keyboard.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.brgr.outspoke.R
import dev.brgr.outspoke.inference.PipelineDiagnostics
import dev.brgr.outspoke.ui.keyboard.KeyboardUiState
import dev.brgr.outspoke.ui.theme.LocalOutspokeStatusColors
import dev.brgr.outspoke.ui.theme.MyIcons
import dev.brgr.outspoke.ui.theme.OutspokeKeyboardTheme
import dev.brgr.outspoke.ui.theme.OutspokeShapes
import dev.brgr.outspoke.ui.theme.tabularFigures

/**
 * Crossfades between six distinct visual states driven by [uiState].
 *
 * Every state renders inside a [StatusPill]: one tonal pill whose colour carries the tone and
 * whose icon, spinner or word carries the meaning — colour is never the only signal.
 *
 * - [KeyboardUiState.Idle]          → neutral pill, ready dot, small mic icon (+ diagnostics badge if non-clean)
 * - [KeyboardUiState.Listening]     → accent-toned pill with a pulsing dot and "Listening"
 * - [KeyboardUiState.Processing]    → accent-toned pill, spinner + partial transcript text
 * - [KeyboardUiState.Transcribing]  → neutral pill, spinner + "Transcribing…" label (mic off, engine busy)
 * - [KeyboardUiState.Error]         → error-toned pill: warning icon + error message + recovery action(s)
 * - [KeyboardUiState.NoSpeech]      → warning-toned pill (audio heard, nothing recognised)
 * - [KeyboardUiState.EngineLoading] → neutral pill, spinner + loading message + "Open Outspoke" action
 *
 * For transient errors ([KeyboardUiState.ErrorReason.TranscriptionFailed],
 * [KeyboardUiState.ErrorReason.AudioCaptureFailed], [KeyboardUiState.ErrorReason.MicInitFailed])
 * a "Try again" button is shown alongside the error so the user can retry immediately without
 * leaving the keyboard. For errors that require external action (permission denied, engine load
 * failed) the "Open Outspoke" button is shown instead.
 *
 * @param diagnostics Pipeline counters from the most recent recording session. When non-clean
 *                    a compact summary (e.g. "2T · 1R") is shown next to the idle mic icon,
 *                    giving immediate visibility into whether any trims or alignment recoveries
 *                    fired - without opening logcat.
 * @param onOpenCompanionApp Called when the user taps the "Open Outspoke" action button shown
 *                           in [KeyboardUiState.Error] and [KeyboardUiState.EngineLoading] states.
 * @param onRetry Called when the user taps "Try again" for transient errors. Should trigger
 *                a new recording attempt and transition the UI back to [KeyboardUiState.Listening].
 */
@Composable
fun StatusIndicator(
    uiState: KeyboardUiState,
    modifier: Modifier = Modifier,
    diagnostics: PipelineDiagnostics = PipelineDiagnostics(),
    onOpenCompanionApp: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
) {
    AnimatedContent(
        targetState = uiState,
        transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(150)) },
        contentAlignment = Alignment.Center,
        label = "statusIndicatorContent",
        modifier = modifier,
    ) { state ->
        when (state) {
            is KeyboardUiState.Idle -> IdleIndicator(diagnostics = diagnostics)
            is KeyboardUiState.Listening -> ListeningIndicator()
            is KeyboardUiState.Processing -> ProcessingIndicator(partial = state.partial)
            is KeyboardUiState.Transcribing -> TranscribingIndicator()
            is KeyboardUiState.Error -> {
                val isTransient = state.reason in setOf(
                    KeyboardUiState.ErrorReason.TranscriptionFailed,
                    KeyboardUiState.ErrorReason.AudioCaptureFailed,
                    KeyboardUiState.ErrorReason.MicInitFailed,
                )
                ErrorIndicator(
                    message = localizedErrorMessage(state),
                    onOpenCompanionApp = if (isTransient) null else onOpenCompanionApp,
                    onRetry = if (isTransient) onRetry else null,
                )
            }

            is KeyboardUiState.EngineLoading -> EngineLoadingIndicator(
                message = localizedLoadingMessage(state),
                onOpenCompanionApp = onOpenCompanionApp,
            )

            is KeyboardUiState.NoSpeech -> NoSpeechIndicator()
        }
    }
}

/** Maps [KeyboardUiState.Error] to a localized, user-facing message string. */
@Composable
private fun localizedErrorMessage(state: KeyboardUiState.Error): String {
    val primary = stringResource(
        when (state.reason) {
            KeyboardUiState.ErrorReason.MicPermissionDenied -> R.string.status_error_mic_permission
            KeyboardUiState.ErrorReason.MicInitFailed -> R.string.status_error_mic_init
            KeyboardUiState.ErrorReason.TranscriptionFailed -> R.string.status_error_transcription
            KeyboardUiState.ErrorReason.AudioCaptureFailed -> R.string.status_error_audio_capture
            KeyboardUiState.ErrorReason.EngineLoadFailed -> R.string.status_error_engine_load
        }
    )
    return if (state.detail != null) "$primary: ${state.detail}" else primary
}

/** Maps [KeyboardUiState.EngineLoading] to a localized, user-facing message string. */
@Composable
private fun localizedLoadingMessage(state: KeyboardUiState.EngineLoading): String =
    stringResource(
        when (state.reason) {
            KeyboardUiState.LoadingReason.ModelNotDownloaded -> R.string.status_engine_model_missing
            KeyboardUiState.LoadingReason.EngineStarting -> R.string.status_engine_loading
        }
    )

@Composable
private fun IdleIndicator(diagnostics: PipelineDiagnostics = PipelineDiagnostics()) {
    val statusColors = LocalOutspokeStatusColors.current
    StatusPill(containerColor = MaterialTheme.colorScheme.surfaceVariant) {
        // Success, not the accent: the accent means "the mic is live", this dot only says the
        // engine is loaded and waiting.
        StatusDot(color = statusColors.success)
        Icon(
            imageVector = MyIcons.Mic,
            contentDescription = stringResource(R.string.cd_status_idle),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        if (!diagnostics.isClean) {
            // Counters move while the user watches them, so they get tabular figures.
            Text(
                text = diagnostics.summary(),
                style = MaterialTheme.typography.labelSmall.tabularFigures,
                color = statusColors.warning,
                maxLines = 1,
            )
        }
    }
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

    StatusPill(containerColor = MaterialTheme.colorScheme.primaryContainer) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .scale(pulseScale)
                .background(MaterialTheme.colorScheme.primary, CircleShape),
        ) { /* pulsing live dot - no inner content needed */ }
        Text(
            text = stringResource(R.string.status_listening),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            maxLines = 1,
        )
    }
}

@Composable
private fun ProcessingIndicator(partial: String) {
    StatusPill(containerColor = MaterialTheme.colorScheme.primaryContainer) {
        GradientArcSpinner(modifier = Modifier.size(16.dp))
        if (partial.isNotEmpty()) {
            Text(
                text = partial,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
    }
}

/** Shown after the mic stops while the engine is still running its final inference pass. */
@Composable
private fun TranscribingIndicator() {
    StatusPill(containerColor = MaterialTheme.colorScheme.surfaceVariant) {
        GradientArcSpinner(modifier = Modifier.size(16.dp))
        Text(
            text = stringResource(R.string.status_transcribing),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/** Shown briefly when the model detected audio but couldn't resolve a word. */
@Composable
private fun NoSpeechIndicator() {
    val statusColors = LocalOutspokeStatusColors.current
    StatusPill(containerColor = statusColors.warningContainer) {
        StatusDot(color = statusColors.warning)
        Text(
            text = stringResource(R.string.status_no_speech),
            style = MaterialTheme.typography.bodySmall,
            color = statusColors.onWarningContainer,
            maxLines = 1,
        )
    }
}

@Composable
private fun ErrorIndicator(
    message: String,
    onOpenCompanionApp: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
) {
    // One pill, one line: the action sits inside it, because a second row would not fit
    // the fixed-height keyboard window and used to be clipped off its top edge.
    StatusPill(containerColor = MaterialTheme.colorScheme.errorContainer) {
        // No leading dot here: the warning glyph already carries the state, and a dot
        // next to it is three signals for one message.
        Icon(
            imageVector = MyIcons.Warning,
            contentDescription = stringResource(R.string.cd_status_error),
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        when {
            onRetry != null -> PillAction(stringResource(R.string.action_retry), onRetry)
            onOpenCompanionApp != null ->
                PillAction(stringResource(R.string.action_open_outspoke), onOpenCompanionApp)
        }
    }
}

@Composable
private fun EngineLoadingIndicator(
    message: String,
    onOpenCompanionApp: (() -> Unit)? = null,
) {
    StatusPill(containerColor = MaterialTheme.colorScheme.surfaceVariant) {
        GradientArcSpinner(modifier = Modifier.size(16.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (onOpenCompanionApp != null) {
            PillAction(stringResource(R.string.action_open_outspoke), onOpenCompanionApp)
        }
    }
}

/**
 * Text action inside a [StatusPill]. The keyboard cannot afford a second row for the action,
 * so the pill owns it: label in the accent colour, full pill height as the touch area.
 */
@Composable
private fun RowScope.PillAction(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        maxLines = 1,
        modifier = Modifier
            .fillMaxHeight()
            .clip(OutspokeShapes.chip)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp),
    )
}

/**
 * Tonal pill shared by every [StatusIndicator] state.
 *
 * The container colour carries the tone (neutral / accent / warning / error); the pill's own
 * content - dot, icon, spinner or word - carries the meaning, so a state never depends on
 * colour alone.
 */
@Composable
private fun StatusPill(
    containerColor: Color,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            // Fixed height: the keyboard is a fixed-height IME window, so a pill that grows
            // (long message, large font scale) would push the key row off the window. Text is
            // single-line and ellipsized instead.
            .height(32.dp)
            .clip(OutspokeShapes.chip)
            .background(containerColor, OutspokeShapes.chip)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** Leading tone dot inside a [StatusPill]; pairs with the state's icon or word. */
@Composable
private fun StatusDot(
    color: Color,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.size(8.dp).background(color, CircleShape))
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
    val mid = MaterialTheme.colorScheme.tertiary

    val infiniteTransition = rememberInfiniteTransition(label = "arcSpinnerRotation")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "arcSpinnerAngle",
    )

    Canvas(modifier = modifier) {
        val strokePx = strokeWidth.toPx()
        rotate(rotation) {
            drawArc(
                brush = Brush.sweepGradient(
                    0f to head.copy(alpha = 0f),      // tail  - fully transparent
                    0.55f to mid.copy(alpha = 0.65f),    // mid   - tertiary, half-visible
                    0.75f to head,                       // head  - full primary
                    1f to head.copy(alpha = 0f),      // close - fade to transparent so the
                    //         seam at 360°/0° is invisible
                ),
                startAngle = 0f,
                sweepAngle = 270f,
                useCenter = false,
                style = Stroke(width = strokePx, cap = StrokeCap.Round),
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
        StatusIndicator(uiState = KeyboardUiState.Error(KeyboardUiState.ErrorReason.MicPermissionDenied))
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF111111)
@Composable
private fun StatusErrorTransientPreview() {
    OutspokeKeyboardTheme {
        StatusIndicator(
            uiState = KeyboardUiState.Error(KeyboardUiState.ErrorReason.TranscriptionFailed, detail = "ONNX error"),
            onRetry = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF111111)
@Composable
private fun StatusEngineLoadingPreview() {
    OutspokeKeyboardTheme {
        StatusIndicator(uiState = KeyboardUiState.EngineLoading(KeyboardUiState.LoadingReason.EngineStarting))
    }
}
