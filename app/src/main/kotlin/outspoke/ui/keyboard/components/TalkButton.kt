package dev.brgr.outspoke.ui.keyboard.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.brgr.outspoke.ui.theme.OutspokeKeyboardTheme

/** How many pixels upward the user must drag to engage continuous mode. */
private const val CONTINUOUS_DRAG_THRESHOLD_DP = 56

/**
 * The primary interaction element of the keyboard.
 *
 * **HOLD mode** - press-and-hold to record; release to stop.
 *
 * **Drag-up-to-lock** (HOLD mode only) - while holding, drag upward past [CONTINUOUS_DRAG_THRESHOLD_DP] dp
 * to engage continuous mode.  The button scales and turns red to confirm the lock.
 * Recording continues without the user needing to keep touching the screen.
 *
 * **Continuous mode** (HOLD) - button shows a pulsing [Stop] icon.  Tap once to stop recording.
 *
 * **TAP_TOGGLE mode** - single tap starts recording; another tap stops it.  No hold needed.
 *
 * @param triggerMode          `"HOLD"` (default) or `"TAP_TOGGLE"`.
 * @param isContinuous         `true` when continuous (locked) mode is active (HOLD mode only).
 * @param onContinuousModeEnabled Callback fired when the drag-up threshold is crossed (HOLD mode only).
 */
@Composable
fun TalkButton(
    isListening: Boolean,
    isContinuous: Boolean,
    onRecordStart: () -> Unit,
    onRecordStop: () -> Unit,
    onContinuousModeEnabled: () -> Unit,
    modifier: Modifier = Modifier,
    triggerMode: String = "HOLD",
    enabled: Boolean = true,
) {
    val effectiveListening = isListening && enabled
    val isContinuousActive = isContinuous && effectiveListening

    // ── Drag-progress state (0 = no drag, 1 = threshold reached) ─────────────
    var dragProgress by remember { mutableFloatStateOf(0f) }

    // ── Base scale: animates on press/release ─────────────────────────────────
    val baseScale by animateFloatAsState(
        targetValue = when {
            !enabled        -> 1f
            isContinuousActive || effectiveListening -> 1.12f
            else            -> 1f
        },
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "talkButtonBaseScale",
    )

    // ── Continuous-mode pulse ─────────────────────────────────────────────────
    val infiniteTransition = rememberInfiniteTransition(label = "talkButtonContinuousPulse")
    val pulse by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue  = 1.1f,
        animationSpec = infiniteRepeatable(
            animation  = tween(durationMillis = 650, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "talkButtonPulseScale",
    )

    // Combine base scale with drag-progress grow and continuous pulse.
    val finalScale = baseScale *
        (if (isContinuousActive) pulse else 1f) +
        (if (effectiveListening && !isContinuousActive) dragProgress * 0.05f else 0f)

    // ── Colours ───────────────────────────────────────────────────────────────
    val backgroundColor by animateColorAsState(
        targetValue = when {
            !enabled           -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            isContinuousActive -> MaterialTheme.colorScheme.error
            effectiveListening -> MaterialTheme.colorScheme.primary
            else               -> MaterialTheme.colorScheme.surfaceVariant
        },
        label = "talkButtonBackground",
    )
    val iconTint by animateColorAsState(
        targetValue = when {
            !enabled           -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
            isContinuousActive -> MaterialTheme.colorScheme.onError
            effectiveListening -> MaterialTheme.colorScheme.onPrimary
            else               -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        label = "talkButtonIconTint",
    )

    // ── Capture latest callbacks so pointerInput coroutine always calls the ──
    // ── current lambdas without restarting the gesture handler.             ──
    val currentOnRecordStart      by rememberUpdatedState(onRecordStart)
    val currentOnRecordStop       by rememberUpdatedState(onRecordStop)
    val currentOnContinuousMode   by rememberUpdatedState(onContinuousModeEnabled)
    val currentIsContinuous       by rememberUpdatedState(isContinuous)
    val currentIsListening        by rememberUpdatedState(isListening)

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(72.dp)
            .scale(finalScale)
            .clip(CircleShape)
            .background(backgroundColor)
            .then(
                if (enabled) Modifier.pointerInput(triggerMode, enabled) {
                    val thresholdPx = CONTINUOUS_DRAG_THRESHOLD_DP * density

                    awaitPointerEventScope {
                        while (true) {
                            if (triggerMode == "TAP_TOGGLE") {
                                // ── TAP_TOGGLE mode: tap once to start, tap again to stop ──
                                awaitFirstDown(requireUnconsumed = false)
                                waitForUpOrCancellation()
                                if (currentIsListening) {
                                    currentOnRecordStop()
                                } else {
                                    currentOnRecordStart()
                                }
                            } else {
                                // ── HOLD mode: hold to record, drag up to lock ────────────
                                val down = awaitFirstDown(requireUnconsumed = false)

                                if (currentIsContinuous) {
                                    // Continuous mode: single tap to stop
                                    waitForUpOrCancellation()
                                    dragProgress = 0f
                                    currentOnRecordStop()
                                } else {
                                    currentOnRecordStart()
                                    var locked = false
                                    val startY = down.position.y

                                    while (true) {
                                        val event  = awaitPointerEvent()
                                        val change = event.changes.firstOrNull() ?: break

                                        if (!change.pressed) {
                                            // Released - stop only if not locked
                                            dragProgress = 0f
                                            if (!locked) currentOnRecordStop()
                                            break
                                        }

                                        change.consume()
                                        val upDelta = startY - change.position.y
                                        dragProgress = (upDelta / thresholdPx).coerceIn(0f, 1f)

                                        if (!locked && upDelta > thresholdPx) {
                                            locked       = true
                                            dragProgress = 0f
                                            currentOnContinuousMode()
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else Modifier
            ),
    ) {
        Icon(
            imageVector = if (isContinuousActive) Icons.Filled.Stop else Icons.Filled.Mic,
            contentDescription = when {
                !enabled                                      -> "Talk button disabled - engine not ready"
                isContinuousActive                            -> "Stop continuous recording"
                isListening && triggerMode == "TAP_TOGGLE"   -> "Tap to stop recording"
                isListening                                   -> "Stop recording"
                triggerMode == "TAP_TOGGLE"                  -> "Tap to start recording"
                else                                          -> "Start recording (hold) · swipe up to lock"
            },
            tint = iconTint,
            modifier = Modifier.size(32.dp),
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF111111)
@Composable
private fun TalkButtonIdlePreview() {
    OutspokeKeyboardTheme {
        TalkButton(isListening = false, isContinuous = false,
            onRecordStart = {}, onRecordStop = {}, onContinuousModeEnabled = {})
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF111111)
@Composable
private fun TalkButtonListeningPreview() {
    OutspokeKeyboardTheme {
        TalkButton(isListening = true, isContinuous = false,
            onRecordStart = {}, onRecordStop = {}, onContinuousModeEnabled = {})
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF111111)
@Composable
private fun TalkButtonContinuousPreview() {
    OutspokeKeyboardTheme {
        TalkButton(isListening = true, isContinuous = true,
            onRecordStart = {}, onRecordStop = {}, onContinuousModeEnabled = {})
    }
}
