package dev.brgr.outspoke.ui.keyboard.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.brgr.outspoke.ui.theme.OutspokeKeyboardTheme
import dev.brgr.outspoke.ui.theme.OutspokeShapes

/** All language options exposed in the keyboard. Order determines display order. */
val WHISPER_LANGUAGE_OPTIONS: List<Pair<String, String>> = listOf(
    "auto" to "Auto",
    "en" to "EN",
    "de" to "DE",
)

/**
 * A compact row of language pills displayed above the talk button when a Whisper model
 * is active.  Tapping a pill immediately updates [selectedLanguage] via [onLanguageSelected].
 *
 * The active option is the only filled one (`primaryContainer`); the inactive options are
 * transparent hairline pills. Filling them would put a second solid shape next to the accent
 * and make the row read as three competing buttons.
 *
 * @param selectedLanguage BCP-47 tag of the currently active option (e.g. `"auto"`, `"en"`).
 * @param onLanguageSelected Called with the new tag when the user taps a different pill.
 */
@Composable
fun LanguageSelector(
    selectedLanguage: String,
    onLanguageSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WHISPER_LANGUAGE_OPTIONS.forEach { (tag, label) ->
            val isSelected = tag == selectedLanguage
            val containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                Color.Transparent
            }
            val labelColor = if (isSelected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }

            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = labelColor,
                modifier = Modifier
                    .clip(OutspokeShapes.chip)
                    .background(containerColor, OutspokeShapes.chip)
                    .then(
                        if (isSelected) Modifier
                        else Modifier.border(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant,
                            OutspokeShapes.chip,
                        ),
                    )
                    .clickable(enabled = !isSelected) { onLanguageSelected(tag) }
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF111111)
@Composable
private fun LanguageSelectorAutoPreview() {
    OutspokeKeyboardTheme {
        LanguageSelector(selectedLanguage = "auto", onLanguageSelected = {})
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF111111)
@Composable
private fun LanguageSelectorDePreview() {
    OutspokeKeyboardTheme {
        LanguageSelector(selectedLanguage = "de", onLanguageSelected = {})
    }
}

