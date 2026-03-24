package dev.brgr.outspoke.ui.keyboard.components

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * A small icon button used for secondary keyboard actions (delete char, delete word,
 * delete all, switch keyboard).  Sized and tinted consistently with the rest of the
 * keyboard chrome.
 *
 * @param icon               Vector icon to display.
 * @param contentDescription Accessibility label.
 * @param onClick            Action triggered on tap.
 * @param tint               Icon colour — defaults to [MaterialTheme.colorScheme.onSurfaceVariant].
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

