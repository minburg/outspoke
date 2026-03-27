package dev.brgr.outspoke.ui.keyboard.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.SubdirectoryArrowLeft
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.brgr.outspoke.ui.theme.OutspokeKeyboardTheme

/**
 * A small icon button used for secondary keyboard actions (delete char, delete word,
 * delete all, switch keyboard).  Sized and tinted consistently with the rest of the
 * keyboard chrome.
 *
 * @param icon               Vector icon to display.
 * @param contentDescription Accessibility label.
 * @param onClick            Action triggered on tap.
 * @param tint               Icon colour - defaults to [MaterialTheme.colorScheme.onSurfaceVariant].
 */
@Composable
fun KeyboardActionButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(40.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(22.dp),
        )
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
                icon = Icons.Filled.DeleteForever,
                contentDescription = "Delete all",
                onClick = {},
            )
            KeyboardActionButton(
                icon = Icons.AutoMirrored.Outlined.Backspace,
                contentDescription = "Delete word",
                onClick = {},
            )
            KeyboardActionButton(
                icon = Icons.AutoMirrored.Filled.Backspace,
                contentDescription = "Delete char",
                onClick = {},
            )
            KeyboardActionButton(
                icon = Icons.Filled.SubdirectoryArrowLeft,
                contentDescription = "Newline",
                onClick = {},
            )
            KeyboardActionButton(
                icon = Icons.Filled.Keyboard,
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
            icon = Icons.AutoMirrored.Filled.Backspace,
            contentDescription = "Delete char (disabled)",
            onClick = {},
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
        )
    }
}

