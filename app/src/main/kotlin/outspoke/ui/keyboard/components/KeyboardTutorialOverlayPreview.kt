package dev.brgr.outspoke.ui.keyboard.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.brgr.outspoke.ui.keyboard.KeyboardScreen
import dev.brgr.outspoke.ui.keyboard.KeyboardUiState
import dev.brgr.outspoke.ui.theme.OutspokeKeyboardTheme


/**
 * Simplified stand-in for the real keyboard.
 * Registers button bounds into [positions] via [onGloballyPositioned].
 *
 */
@Composable
private fun FakeKeyboard(positions: TutorialPositions) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .onGloballyPositioned { positions.record(TutorialButtonId.SWITCH_KEYBOARD, it) },
            )
        }
        Spacer(Modifier.weight(1f))
        Box(
            modifier = Modifier
                .size(72.dp)
                .align(Alignment.CenterHorizontally)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                .onGloballyPositioned { positions.record(TutorialButtonId.TALK, it) },
        )
        Spacer(Modifier.weight(1f))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf(
                TutorialButtonId.DELETE_ALL,
                TutorialButtonId.DELETE_WORD,
                TutorialButtonId.DELETE_CHAR,
                TutorialButtonId.ENTER,
            ).forEach { id ->
                Box(
                    modifier = Modifier
                        .size(width = 72.dp, height = 44.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .onGloballyPositioned { positions.record(id, it) },
                )
            }
        }
    }
}

/**
 * Draws the scrim + spotlight cutout + pulsing ring for [highlightId].
 * Reads coordinates from the supplied [positions] - does not register any buttons
 * itself, so it can be layered on top of either [FakeKeyboard] or the real
 * [KeyboardScreen].
 *
 * No R references anywhere in this composable.
 */
@Composable
private fun BoxScope.TutorialSpotlightLayer(
    positions: TutorialPositions,
    highlightId: TutorialButtonId,
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val infiniteTransition = rememberInfiniteTransition(label = "tutorialGlow")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.50f, targetValue = 1.00f,
        animationSpec = infiniteRepeatable(tween(850, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "glowAlpha",
    )
    val glowRadius by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 4f,
        animationSpec = infiniteRepeatable(tween(850, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "glowRadius",
    )

    val spotBounds = positions.localBounds(highlightId)

    // Layer 1: dark scrim with transparent spotlight cutout
    Canvas(
        modifier = Modifier
            .matchParentSize()
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen),
    ) {
        drawRect(Color(0xBB000000))
        spotBounds?.let { b ->
            val radius = (b.width + b.height) / 4f + 8.dp.toPx()
            drawCircle(
                color = Color.Transparent,
                center = Offset(b.center.x, b.center.y),
                radius = radius,
                blendMode = BlendMode.Clear,
            )
        }
    }

    // Layer 2: pulsing primary-colour ring
    spotBounds?.let { b ->
        Canvas(modifier = Modifier.matchParentSize()) {
            val baseRadius = (b.width + b.height) / 4f + 8.dp.toPx()
            drawCircle(
                color = primaryColor.copy(alpha = glowAlpha),
                center = Offset(b.center.x, b.center.y),
                radius = baseRadius + glowRadius.dp.toPx(),
                style = Stroke(width = 3.dp.toPx()),
            )
        }
    }
}

/**
 * Mirrors the card + nav layout of the real [KeyboardTutorialOverlay].
 * Uses only plain strings - no R references - so it works with a stale R class.
 */
@Composable
private fun BoxScope.TutorialCardLayer(
    title: String,
    description: String,
    stepIndex: Int,
    totalSteps: Int,
    cardAtBottom: Boolean,
) {
    // Card + nav row: stacked in a Column when cardAtBottom, otherwise card floats at top
    if (!cardAtBottom) {
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            elevation = CardDefaults.cardElevation(0.dp),
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp),
        ) {
            TutorialCardBody(title, description, stepIndex, totalSteps)
        }
    }

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
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                elevation = CardDefaults.cardElevation(0.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                TutorialCardBody(title, description, stepIndex, totalSteps)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = {}, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 6.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onSurfaceVariant)
            ) {
                Text(
                    text = "Skip",
                    style = MaterialTheme.typography.labelMedium
                )
            }

            Button(
                onClick = {}, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 6.dp)
            ) {
                Text(
                    text = if (stepIndex == totalSteps - 1) "Got it!" else "Next",
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
}

@Composable
private fun TutorialCardBody(title: String, description: String, stepIndex: Int, totalSteps: Int) {
    Column(
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(
                "${stepIndex + 1} / $totalSteps",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
    }
}

private data class TutorialPreviewStep(
    val buttonId: TutorialButtonId,
    val title: String,
    val description: String,
    val stepIndex: Int,
    /** `true` when the spotlight is in the upper portion of the keyboard (card goes below). */
    val cardAtBottom: Boolean,
)

private val PREVIEW_STEPS = listOf(
    TutorialPreviewStep(
        TutorialButtonId.TALK,
        "Talk Button (centre)",
        "Hold to record, release to transcribe. In Tap-to-Toggle mode, tap once to start and again to stop.",
        0,
        cardAtBottom = false
    ),
    TutorialPreviewStep(
        TutorialButtonId.SWITCH_KEYBOARD,
        "Switch Keyboard (top right)",
        "Returns to your previous keyboard.",
        1,
        cardAtBottom = true
    ),
    TutorialPreviewStep(
        TutorialButtonId.DELETE_ALL,
        "Delete All (far left)",
        "Clears the entire text field at once.",
        2,
        cardAtBottom = false
    ),
    TutorialPreviewStep(
        TutorialButtonId.DELETE_WORD,
        "Delete Word (left of mic)",
        "Removes the last word.",
        3,
        cardAtBottom = false
    ),
    TutorialPreviewStep(
        TutorialButtonId.DELETE_CHAR,
        "Delete Character (right of mic)",
        "Removes the last character.",
        4,
        cardAtBottom = false
    ),
    TutorialPreviewStep(
        TutorialButtonId.ENTER,
        "Enter / Action (far right)",
        "Performs the context-aware action: send, search, go, or insert a newline.",
        5,
        cardAtBottom = false
    ),
)

/**
 * Full tutorial preview: real [KeyboardScreen] buttons + spotlight + explanation card.
 * Uses [KeyboardScreen]'s [tutorialPositions] parameter so the spotlight targets the
 * exact same layout coordinates as on a real device.
 */
@Composable
private fun TutorialFullPreview(step: TutorialPreviewStep) {
    val positions = remember { TutorialPositions() }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .onGloballyPositioned { positions.overlayCoords = it },
    ) {
        // Real keyboard - registers true button LayoutCoordinates into positions
        KeyboardScreen(
            uiState = KeyboardUiState.Idle,
            amplitude = 0f,
            isContinuous = false,
            triggerMode = "HOLD",
            isWhisperEngine = false,
            whisperLanguage = "auto",
            onWhisperLanguageSelected = {},
            onRecordStart = {},
            onRecordStop = {},
            onContinuousModeEnabled = {},
            onDeleteChar = {},
            onDeleteWord = {},
            onDeleteAll = {},
            onEnterAction = {},
            onSwitchKeyboard = {},
            onOpenCompanionApp = {},
            tutorialPositions = positions,
        )
        // Scrim + spotlight ring
        TutorialSpotlightLayer(positions = positions, highlightId = step.buttonId)
        // Explanation card + nav buttons
        TutorialCardLayer(
            title = step.title,
            description = step.description,
            stepIndex = step.stepIndex,
            totalSteps = PREVIEW_STEPS.size,
            cardAtBottom = step.cardAtBottom,
        )
    }
}

@Preview(name = "Tutorial - 1 Talk", showBackground = true, backgroundColor = 0xFF111111, widthDp = 360)
@Composable
private fun TutorialFullTalkPreview() {
    OutspokeKeyboardTheme { TutorialFullPreview(PREVIEW_STEPS[0]) }
}

@Preview(name = "Tutorial - 2 Switch Keyboard", showBackground = true, backgroundColor = 0xFF111111, widthDp = 360)
@Composable
private fun TutorialFullSwitchPreview() {
    OutspokeKeyboardTheme { TutorialFullPreview(PREVIEW_STEPS[1]) }
}

@Preview(name = "Tutorial - 3 Delete All", showBackground = true, backgroundColor = 0xFF111111, widthDp = 360)
@Composable
private fun TutorialFullDeleteAllPreview() {
    OutspokeKeyboardTheme { TutorialFullPreview(PREVIEW_STEPS[2]) }
}

@Preview(name = "Tutorial - 4 Delete Word", showBackground = true, backgroundColor = 0xFF111111, widthDp = 360)
@Composable
private fun TutorialFullDeleteWordPreview() {
    OutspokeKeyboardTheme { TutorialFullPreview(PREVIEW_STEPS[3]) }
}

@Preview(name = "Tutorial - 5 Delete Char", showBackground = true, backgroundColor = 0xFF111111, widthDp = 360)
@Composable
private fun TutorialFullDeleteCharPreview() {
    OutspokeKeyboardTheme { TutorialFullPreview(PREVIEW_STEPS[4]) }
}

@Preview(name = "Tutorial - 6 Enter", showBackground = true, backgroundColor = 0xFF111111, widthDp = 360)
@Composable
private fun TutorialFullEnterPreview() {
    OutspokeKeyboardTheme { TutorialFullPreview(PREVIEW_STEPS[5]) }
}

@Composable
private fun TutorialSpotlightFakePreview(highlightId: TutorialButtonId) {
    val positions = remember { TutorialPositions() }
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { positions.overlayCoords = it },
    ) {
        FakeKeyboard(positions = positions)
        TutorialSpotlightLayer(positions = positions, highlightId = highlightId)
    }
}

@Preview(name = "Spotlight - Talk (centre)", showBackground = true, widthDp = 360, heightDp = 260)
@Composable
private fun SpotlightTalkPreview() {
    OutspokeKeyboardTheme { TutorialSpotlightFakePreview(TutorialButtonId.TALK) }
}

@Preview(name = "Spotlight - Switch keyboard (top-right)", showBackground = true, widthDp = 360, heightDp = 260)
@Composable
private fun SpotlightSwitchKeyboardPreview() {
    OutspokeKeyboardTheme { TutorialSpotlightFakePreview(TutorialButtonId.SWITCH_KEYBOARD) }
}

@Preview(name = "Spotlight - Delete All (bottom-left)", showBackground = true, widthDp = 360, heightDp = 260)
@Composable
private fun SpotlightDeleteAllPreview() {
    OutspokeKeyboardTheme { TutorialSpotlightFakePreview(TutorialButtonId.DELETE_ALL) }
}
