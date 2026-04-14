package dev.brgr.outspoke.ui.keyboard.components

import androidx.annotation.StringRes
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.brgr.outspoke.R
import dev.brgr.outspoke.ui.theme.OutspokeKeyboardTheme


/** Identifies each keyboard button in the first-run tutorial walk-through. */
enum class TutorialButtonId {
    TALK, SWITCH_KEYBOARD, DELETE_ALL, DELETE_WORD, DELETE_CHAR, ENTER
}

/**
 * Holds live [LayoutCoordinates] for every tutorial button and for the overlay root.
 *
 * Buttons in [KeyboardScreen] call [record] via `Modifier.onGloballyPositioned`.
 * The overlay root sets [overlayCoords] the same way.
 * [localBounds] converts any button's coords into the overlay's local space so the
 * spotlight can be positioned correctly.
 */
@Stable
class TutorialPositions {
    val buttonCoords = mutableStateMapOf<TutorialButtonId, LayoutCoordinates>()
    var overlayCoords: LayoutCoordinates? by mutableStateOf(null)

    fun record(id: TutorialButtonId, lc: LayoutCoordinates) {
        buttonCoords[id] = lc
    }

    /**
     * Returns the [Rect] of [id] in the overlay's local coordinate space,
     * or `null` if either set of coordinates is not yet available.
     */
    fun localBounds(id: TutorialButtonId): Rect? {
        val oc = overlayCoords ?: return null
        val bc = buttonCoords[id] ?: return null
        return try {
            oc.localBoundingBoxOf(bc, clipBounds = false)
        } catch (_: Exception) {
            null
        }
    }
}

private data class TutorialStep(
    val buttonId: TutorialButtonId,
    @StringRes val titleRes: Int,
    @StringRes val descRes: Int,
)

private val TUTORIAL_STEPS = listOf(
    TutorialStep(TutorialButtonId.TALK,            R.string.tutorial_talk_button_title,    R.string.tutorial_talk_button_desc),
    TutorialStep(TutorialButtonId.SWITCH_KEYBOARD, R.string.tutorial_switch_keyboard_title, R.string.tutorial_switch_keyboard_desc),
    TutorialStep(TutorialButtonId.DELETE_ALL,      R.string.tutorial_delete_all_title,     R.string.tutorial_delete_all_desc),
    TutorialStep(TutorialButtonId.DELETE_WORD,     R.string.tutorial_delete_word_title,    R.string.tutorial_delete_word_desc),
    TutorialStep(TutorialButtonId.DELETE_CHAR,     R.string.tutorial_delete_char_title,    R.string.tutorial_delete_char_desc),
    TutorialStep(TutorialButtonId.ENTER,           R.string.tutorial_enter_title,          R.string.tutorial_enter_desc),
)

/**
 * Step-by-step spotlight tutorial that overlays the live keyboard.
 *
 * A dark translucent scrim covers the keyboard; one button at a time is punched
 * through with a circular spotlight and highlighted by a pulsing coloured ring.
 * An explanation card appears in the open area above (or below when the spotlight
 * is in the upper half, e.g. the Switch Keyboard button).
 * "Skip" dismisses immediately; "Next" advances through all six steps; on the
 * final step the button reads "Got it!" and dismisses the tutorial permanently.
 *
 * @param positions  Shared [TutorialPositions] populated by [KeyboardScreen]'s buttons.
 * @param onDismiss  Called when the user finishes or skips all steps.
 */
@Composable
fun KeyboardTutorialOverlay(
    positions: TutorialPositions,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var currentStep by remember { mutableIntStateOf(0) }
    val totalSteps = TUTORIAL_STEPS.size
    val step = TUTORIAL_STEPS[currentStep]

    val primaryColor = MaterialTheme.colorScheme.primary

    // Pulsing glow for the spotlight ring
    val infiniteTransition = rememberInfiniteTransition(label = "tutorialGlow")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.50f,
        targetValue = 1.00f,
        animationSpec = infiniteRepeatable(
            animation = tween(850, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "tutorialGlowAlpha",
    )
    val glowRadius by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 4f, // extra dp added to ring radius at peak glow
        animationSpec = infiniteRepeatable(
            animation = tween(850, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "tutorialGlowRadius",
    )

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { positions.overlayCoords = it },
    ) {
        val overlayHeightPx = constraints.maxHeight.toFloat()
        val spotlightBounds: Rect? = positions.localBounds(step.buttonId)

        // Layer 1: dark scrim with spotlight cutout
        // CompositingStrategy.Offscreen is required so BlendMode.Clear punches a
        // real transparent hole through the opaque scrim layer.
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen),
        ) {
            // Draw opaque dark scrim
            drawRect(Color(0xBB000000))

            // Punch a circular hole where the current button sits.
            // Average width+height rather than taking the max so wide buttons
            // (delete row) don't produce an oversized circle.
            spotlightBounds?.let { bounds ->
                val cx = bounds.center.x
                val cy = bounds.center.y
                val radius = (bounds.width + bounds.height) / 4f + 8.dp.toPx()
                drawCircle(
                    color = Color.Transparent,
                    center = Offset(cx, cy),
                    radius = radius,
                    blendMode = BlendMode.Clear,
                )
            }
        }

        // Layer 2: pulsing primary-colour ring around the spotlight
        spotlightBounds?.let { bounds ->
            Canvas(modifier = Modifier.fillMaxSize()) {
                val cx = bounds.center.x
                val cy = bounds.center.y
                val baseRadius = (bounds.width + bounds.height) / 4f + 8.dp.toPx()
                drawCircle(
                    color = primaryColor.copy(alpha = glowAlpha),
                    center = Offset(cx, cy),
                    radius = baseRadius + glowRadius.dp.toPx(),
                    style = Stroke(width = 3.dp.toPx()),
                )
            }
        }

        // Layer 3: explanation card + navigation (crossfades on step change)
        // Place the card at the top when the spotlight is in the lower ~55 % of
        // the overlay, and at the bottom when it is in the upper ~45 % (e.g. the
        // Switch Keyboard button lives in the top row).
        val cardAtBottom = spotlightBounds != null &&
                spotlightBounds.center.y < overlayHeightPx * 0.45f

        // Resolve shared labels here so StepContent stays free of stringResource()
        // calls and the previews can supply plain hardcoded strings instead.
        val skipLabel    = stringResource(R.string.tutorial_skip)
        val nextLabel    = stringResource(R.string.tutorial_next)
        val dismissLabel = stringResource(R.string.tutorial_dismiss)

        Crossfade(
            targetState = currentStep,
            animationSpec = tween(200),
            modifier = Modifier.fillMaxSize(),
            label = "tutorialStepCrossfade",
        ) { idx ->
            val s = TUTORIAL_STEPS[idx]
            StepContent(
                title        = stringResource(s.titleRes),
                description  = stringResource(s.descRes),
                skipLabel    = skipLabel,
                nextLabel    = if (idx == totalSteps - 1) dismissLabel else nextLabel,
                stepIndex    = idx,
                totalSteps   = totalSteps,
                cardAtBottom = cardAtBottom,
                onSkip       = onDismiss,
                onNext       = {
                    if (currentStep < totalSteps - 1) currentStep++ else onDismiss()
                },
            )
        }
    }
}

@Composable
private fun StepContent(
    title: String,
    description: String,
    stepIndex: Int,
    totalSteps: Int,
    cardAtBottom: Boolean,
    skipLabel: String,
    nextLabel: String,
    onSkip: () -> Unit,
    onNext: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {

        // Explanation card at the top - used when the spotlight is in the lower half
        if (!cardAtBottom) {
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .padding(horizontal = 16.dp)
                    .padding(top = 8.dp),
            ) {
                ExplanationCardContent(title = title, description = description, stepIndex = stepIndex, totalSteps = totalSteps)
            }
        }

        // Bottom section: nav row stacked below the card when cardAtBottom.
        // Using a Column removes the need for a hardcoded gap between card and nav.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 12.dp)
                .padding(bottom = 4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (cardAtBottom) {
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    ExplanationCardContent(title = title, description = description, stepIndex = stepIndex, totalSteps = totalSteps)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = onSkip,
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 6.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) {
                    Text(
                        text = skipLabel,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                Button(
                    onClick = onNext,
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = nextLabel,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun ExplanationCardContent(
    title: String,
    description: String,
    stepIndex: Int,
    totalSteps: Int,
) {
    Column(
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "${stepIndex + 1} / $totalSteps",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** Card at the top - spotlight is in the lower half (e.g. TALK button). */
@Preview(showBackground = true, backgroundColor = 0xFF111111, widthDp = 360, heightDp = 280, name = "Tutorial - card top")
@Composable
private fun StepContentTopPreview() {
    OutspokeKeyboardTheme {
        StepContent(
            title = "Talk",
            description = "This is the talk button.",
            stepIndex = 0,
            totalSteps = TUTORIAL_STEPS.size,
            cardAtBottom = false,
            skipLabel = "Skip",
            nextLabel = "Next",
            onSkip = {},
            onNext = {},
        )
    }
}

/** Card at the bottom - spotlight is in the upper area (e.g. SWITCH_KEYBOARD button). */
@Preview(showBackground = true, backgroundColor = 0xFF111111, widthDp = 360, heightDp = 280, name = "Tutorial - card bottom")
@Composable
private fun StepContentBottomPreview() {
    OutspokeKeyboardTheme {
        StepContent(
            title = "Switch Keyboard",
            description = "This is the switch keyboard button.",
            stepIndex = 1,
            totalSteps = TUTORIAL_STEPS.size,
            cardAtBottom = true,
            skipLabel = "Skip",
            nextLabel = "Next",
            onSkip = {},
            onNext = {},
        )
    }
}
