package dev.brgr.outspoke.ui.keyboard.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.brgr.outspoke.ui.theme.OutspokeKeyboardTheme

/** How many dp upward the user must drag to engage continuous mode. */
private const val CONTINUOUS_DRAG_THRESHOLD_DP = 56

/** Gap between the TalkButton top and the bottom of the lock hint indicator. */
private const val LOCK_HINT_GAP_DP = 8

/**
 * The primary interaction element of the keyboard.
 *
 * **HOLD mode** - press-and-hold to record; release to stop.
 *
 * **Drag-up-to-lock** (HOLD mode only) - while holding, drag upward past [CONTINUOUS_DRAG_THRESHOLD_DP] dp
 * to engage continuous mode.  A lock indicator floats above the button while the user holds,
 * similar to the WhatsApp voice-message lock UI: a bouncing upward chevron invites the swipe,
 * and a lock icon fills with colour as the drag threshold is approached.
 * The button scales and turns red to confirm the lock.
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
    previewForceLockHint: Boolean = false, // For previews: force lock hint visible
) {
    val effectiveListening = isListening && enabled
    val isContinuousActive = isContinuous && effectiveListening

    // True while the user's finger is down in HOLD mode (drives the lock hint visibility).
    var isHolding by remember { mutableStateOf(false) }
    // ── Drag-progress state (0 = no drag, 1 = threshold reached) ─────────────
    var dragProgress by remember { mutableFloatStateOf(0f) }
    // For preview: force lock hint visible
    if (previewForceLockHint) {
        isHolding = true
        dragProgress = 0.5f
    }

    // Reset holding state if the button becomes disabled mid-gesture.
    LaunchedEffect(enabled) {
        if (!enabled) {
            isHolding = false
            dragProgress = 0f
        }
    }

    // ── Base scale: animates on press/release ─────────────────────────────────
    val baseScale by animateFloatAsState(
        targetValue = when {
            !enabled -> 1f
            isContinuousActive || effectiveListening -> 1.12f
            else -> 1f
        },
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "talkButtonBaseScale",
    )

    // ── Continuous-mode pulse ─────────────────────────────────────────────────
    val infiniteTransition = rememberInfiniteTransition(label = "talkButtonContinuousPulse")
    val pulse by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 650, easing = LinearEasing),
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
            !enabled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            isContinuousActive -> MaterialTheme.colorScheme.error
            effectiveListening -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.surfaceVariant
        },
        label = "talkButtonBackground",
    )
    val iconTint by animateColorAsState(
        targetValue = when {
            !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
            isContinuousActive -> MaterialTheme.colorScheme.onError
            effectiveListening -> MaterialTheme.colorScheme.onPrimary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        label = "talkButtonIconTint",
    )

    // ── Capture latest callbacks so pointerInput coroutine always calls the ──
    // ── current lambdas without restarting the gesture handler.             ──
    val currentOnRecordStart by rememberUpdatedState(onRecordStart)
    val currentOnRecordStop by rememberUpdatedState(onRecordStop)
    val currentOnContinuousMode by rememberUpdatedState(onContinuousModeEnabled)
    val currentIsContinuous by rememberUpdatedState(isContinuous)
    val currentIsListening by rememberUpdatedState(isListening)

    // ── Root container: fixed 72dp, handles all gestures ─────────────────────
    // Box does NOT clip children by default, which lets the LockHint render
    // above the 72dp bounds without affecting layout measurement.
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(72.dp)
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
                                    isHolding = true
                                    currentOnRecordStart()
                                    var locked = false
                                    val startY = down.position.y

                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull() ?: break

                                        if (!change.pressed) {
                                            // Released - stop only if not locked
                                            dragProgress = 0f
                                            isHolding = false
                                            if (!locked) currentOnRecordStop()
                                            break
                                        }

                                        change.consume()
                                        val upDelta = startY - change.position.y
                                        dragProgress = (upDelta / thresholdPx).coerceIn(0f, 1f)

                                        if (!locked && upDelta > thresholdPx) {
                                            locked = true
                                            dragProgress = 0f
                                            isHolding = false
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
        // ── Visual circle (scaled independently of the lock hint) ─────────────
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .matchParentSize()
                .scale(finalScale)
                .clip(CircleShape)
                .background(backgroundColor),
        ) {
            Icon(
                imageVector = if (isContinuousActive) Icons.Filled.Stop else Icons.Filled.Mic,
                contentDescription = when {
                    !enabled -> "Talk button disabled - engine not ready"
                    isContinuousActive -> "Stop continuous recording"
                    isListening && triggerMode == "TAP_TOGGLE" -> "Tap to stop recording"
                    isListening -> "Stop recording"
                    triggerMode == "TAP_TOGGLE" -> "Tap to start recording"
                    else -> "Start recording (hold) · swipe up to lock"
                },
                tint = iconTint,
                modifier = Modifier.size(32.dp),
            )
        }

        // ── Lock hint: floats above without disturbing layout ─────────────────
        // Modifier.layout reports (0, 0) to the parent Box so the button's
        // measured position is never shifted.  The placeable is then placed
        // with a negative Y offset so it renders above the button bounds.
        if (triggerMode == "HOLD") {
            AnimatedVisibility(
                visible = isHolding && !isContinuousActive,
                enter = fadeIn(animationSpec = tween(150)) +
                        scaleIn(
                            initialScale = 0.75f,
                            transformOrigin = TransformOrigin(0.5f, 1f),
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessMedium,
                            ),
                        ),
                exit = fadeOut(animationSpec = tween(120)) +
                        scaleOut(
                            targetScale = 0.75f,
                            transformOrigin = TransformOrigin(0.5f, 1f),
                            animationSpec = tween(120),
                        ),
                modifier = Modifier.layout { measurable, constraints ->
                    // Allow the hint to be taller than the 72dp button by measuring
                    // without a height cap.
                    val placeable = measurable.measure(
                        constraints.copy(
                            minWidth = 0,
                            minHeight = 0,
                            maxHeight = Constraints.Infinity,
                        ),
                    )
                    val gapPx = LOCK_HINT_GAP_DP.dp.roundToPx()
                    // The zero-size child sits at the Box centre (constraints.maxHeight / 2
                    // from the top).  Subtract that offset so the hint bottom aligns with
                    // the button's top edge, not the centre.
                    val buttonHalfPx = constraints.maxHeight / 2
                    layout(0, 0) {
                        placeable.place(
                            x = -placeable.width / 2,
                            y = -placeable.height - gapPx - buttonHalfPx,
                        )
                    }
                },
            ) {
                LockHint(dragProgress = dragProgress)
            }
        }
    }
}

/**
 * Visual hint displayed above [TalkButton] while the user holds in HOLD mode.
 *
 * Mimics the WhatsApp voice-message lock indicator:
 * - A bouncing [KeyboardArrowUp] chevron (closest to the button) invites the upward swipe.
 * - A lock icon pill above it transitions from outlined → filled, and its colour lerps from
 *   `onSurfaceVariant` → `primary` as [dragProgress] approaches 1, confirming the lock is near.
 */
@Composable
private fun LockHint(
    dragProgress: Float,
    modifier: Modifier = Modifier,
) {
    val idleColor = MaterialTheme.colorScheme.onSurfaceVariant
    val activeColor = MaterialTheme.colorScheme.primary
    val lockColor = lerp(idleColor, activeColor, dragProgress)

    val lockScale = 0.65f + dragProgress * 0.35f
    val lockAlpha = 0.50f + dragProgress * 0.50f

    // Infinite bounce animation for the upward chevron.
    val arrowTransition = rememberInfiniteTransition(label = "lockHintArrow")
    val arrowOffsetDp by arrowTransition.animateFloat(
        initialValue = 0f,
        targetValue = -5f,
        animationSpec = infiniteRepeatable(
            animation = tween(480, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "lockArrowOffset",
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(0.dp),
        modifier = modifier,
    ) {
        // Lock icon inside a circular pill that scales and brightens with drag progress.
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(65.dp)
                .scale(lockScale)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = lockAlpha)),
        ) {
            Icon(
                imageVector = if (dragProgress >= 0.85f) Icons.Filled.Lock else Icons.Outlined.Lock,
                contentDescription = null,
                tint = lockColor,
                modifier = Modifier.size(40.dp),
            )
        }

        // Upward chevron: bounces to signal the swipe-up gesture.
        Icon(
            imageVector = Icons.Filled.KeyboardArrowUp,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
            modifier = Modifier
                .size(25.dp)
                .offset(y = arrowOffsetDp.dp),
        )
    }
}

// ── Previews ──────────────────────────────────────────────────────────────────

@Preview(showBackground = true, backgroundColor = 0xFF111111, name = "Idle")
@Composable
private fun TalkButtonIdlePreview() {
    OutspokeKeyboardTheme {
        TalkButton(
            isListening = false, isContinuous = false,
            onRecordStart = {}, onRecordStop = {}, onContinuousModeEnabled = {})
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF111111, name = "Listening (hold)")
@Composable
private fun TalkButtonListeningPreview() {
    OutspokeKeyboardTheme {
        TalkButton(
            isListening = true, isContinuous = false,
            onRecordStart = {}, onRecordStop = {}, onContinuousModeEnabled = {})
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF111111, name = "Continuous (locked)")
@Composable
private fun TalkButtonContinuousPreview() {
    OutspokeKeyboardTheme {
        TalkButton(
            isListening = true, isContinuous = true,
            onRecordStart = {}, onRecordStop = {}, onContinuousModeEnabled = {})
    }
}

/** Shows all three drag-progress states of [LockHint] side-by-side. */
@Preview(showBackground = true, backgroundColor = 0xFF111111, name = "LockHint – all states",
    widthDp = 200, heightDp = 120)
@Composable
private fun LockHintPreview() {
    OutspokeKeyboardTheme {
        Row(
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
        ) {
            // Just appeared (finger just touched down)
            LockHint(dragProgress = 0f)
            // Mid-drag
            LockHint(dragProgress = 0.5f)
            // Threshold nearly reached
            LockHint(dragProgress = 1f)
        }
    }
}
