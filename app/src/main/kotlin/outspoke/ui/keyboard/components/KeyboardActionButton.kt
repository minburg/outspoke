package dev.brgr.outspoke.ui.keyboard.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.brgr.outspoke.ui.theme.MyIcons
import dev.brgr.outspoke.ui.theme.OutspokeKeyboardTheme
import dev.brgr.outspoke.ui.theme.OutspokeShapes
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

/**
 * One function key of the keyboard (delete all / delete word / delete char / enter /
 * switch keyboard).
 *
 * The key is drawn 40 dp wide as a `surface` panel with a 1 dp `outlineVariant` outline, so it
 * separates from the strip without a shadow; the touch target around it is 42 dp, the smallest
 * tap target allowed inside a keyboard row.
 *
 * @param icon Glyph drawn in the key.
 * @param contentDescription Accessibility label for the key.
 * @param onClick Fired on press, then repeatedly while held when [repeatEnabled] is set.
 * @param tint Icon colour; defaults to the neutral secondary-foreground role.
 * @param repeatEnabled When false the button fires once on press and does not auto-repeat while held.
 */
@Composable
fun KeyboardActionButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    repeatEnabled: Boolean = true,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    // This effect handles the "auto-repeat" logic
    LaunchedEffect(isPressed) {
        if (isPressed) {
            // First click happens immediately
            onClick()

            if (repeatEnabled) {
                // Initial delay before starting the rapid fire (standard keyboard behavior)
                delay(500.milliseconds)

                // Continuous loop while button is held
                while (true) {
                    onClick()
                    delay(60.milliseconds) // Speed of deletion (60ms is roughly standard)
                }
            }
        }
    }

    // Pressed keys sink to surfaceVariant: in both keyboard schemes that is a clear step
    // away from the key fill, whereas the surfaceContainer ladder is tuned for the
    // suggestion shelf and reads as no change at all under a finger.
    val containerColor = if (isPressed) {
        MaterialTheme.colorScheme.surfaceVariant
    } else {
        MaterialTheme.colorScheme.surface
    }

    Box(
        modifier = modifier
            .size(FunctionKeySize)
            .clip(OutspokeShapes.key) // Gives the ripple a bound that matches the key shape
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(), // Shows the visual feedback
                onClick = {} // We handle logic in LaunchedEffect, but need this for accessibility
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(OutspokeShapes.key)
                .background(containerColor, OutspokeShapes.key)
                .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), OutspokeShapes.key),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF111111)
@Composable
private fun KeyboardActionButtonAllPreview() {
    OutspokeKeyboardTheme {
        Row(
            modifier = Modifier.padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KeyboardActionButton(
                icon = MyIcons.DeleteForever,
                contentDescription = "Delete all",
                onClick = {},
            )
            KeyboardActionButton(
                icon = MyIcons.BackspaceOutlined,
                contentDescription = "Delete word",
                onClick = {},
            )
            KeyboardActionButton(
                icon = MyIcons.Backspace,
                contentDescription = "Delete char",
                onClick = {},
            )
            KeyboardActionButton(
                icon = MyIcons.SubdirectoryArrowLeft,
                contentDescription = "Newline",
                onClick = {},
            )
            KeyboardActionButton(
                icon = MyIcons.Keyboard,
                contentDescription = "Switch keyboard",
                onClick = {},
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF111111)
@Composable
private fun KeyboardActionButtonDisabledPreview() {
    OutspokeKeyboardTheme {
        KeyboardActionButton(
            icon = MyIcons.Backspace,
            contentDescription = "Delete char (disabled)",
            onClick = {},
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
        )
    }
}

