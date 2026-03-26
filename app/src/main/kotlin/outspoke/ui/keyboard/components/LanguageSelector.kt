package dev.brgr.outspoke.ui.keyboard.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.brgr.outspoke.ui.theme.OutspokeKeyboardTheme

/** All language options exposed in the keyboard. Order determines display order. */
val WHISPER_LANGUAGE_OPTIONS: List<Pair<String, String>> = listOf(
    "auto" to "Auto",
    "en"   to "EN",
    "de"   to "DE",
    "es"   to "ES",
)

/**
 * A compact row of language pills displayed above the talk button when a Whisper model
 * is active.  Tapping a pill immediately updates [selectedLanguage] via [onLanguageSelected].
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
    val pillShape = RoundedCornerShape(50)

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WHISPER_LANGUAGE_OPTIONS.forEach { (tag, label) ->
            val isSelected = tag == selectedLanguage
            val bgColor    = if (isSelected) MaterialTheme.colorScheme.primary
                             else MaterialTheme.colorScheme.surface
            val textColor  = if (isSelected) MaterialTheme.colorScheme.onPrimary
                             else MaterialTheme.colorScheme.onSurfaceVariant
            val borderColor = if (isSelected) MaterialTheme.colorScheme.primary
                              else MaterialTheme.colorScheme.outlineVariant

            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = textColor,
                modifier = Modifier
                    .clip(pillShape)
                    .background(bgColor, pillShape)
                    .border(1.dp, borderColor, pillShape)
                    .clickable(enabled = !isSelected) { onLanguageSelected(tag) }
                    .padding(horizontal = 10.dp, vertical = 3.dp),
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

