package dev.brgr.outspoke.settings.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.brgr.outspoke.settings.preferences.PreferencesViewModel
import dev.brgr.outspoke.ui.theme.OutspokeTheme

/**
 * User-configurable keyboard preferences backed by [PreferencesViewModel] / DataStore.
 *
 * Settings persist across process restarts.
 */
@Composable
fun PreferencesScreen(
    viewModel: PreferencesViewModel = viewModel(),
) {
    val triggerMode    by viewModel.triggerMode.collectAsState()
    val vadSensitivity by viewModel.vadSensitivity.collectAsState()

    PreferencesContent(
        triggerMode           = triggerMode,
        vadSensitivity        = vadSensitivity,
        onTriggerModeChange   = viewModel::setTriggerMode,
        onVadSensitivityChange = viewModel::setVadSensitivity,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PreferencesContent(
    triggerMode: String,
    vadSensitivity: Float,
    onTriggerModeChange: (String) -> Unit,
    onVadSensitivityChange: (Float) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Trigger Mode",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "How to start and stop voice recording.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = triggerMode == "HOLD",
                    onClick = { onTriggerModeChange("HOLD") },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                ) {
                    Text("Hold to Talk")
                }
                SegmentedButton(
                    selected = triggerMode == "TAP_TOGGLE",
                    onClick = { onTriggerModeChange("TAP_TOGGLE") },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                ) {
                    Text("Tap to Toggle")
                }
            }
        }

        HorizontalDivider()

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("VAD Sensitivity", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = if (vadSensitivity == 0f) "Off"
                           else "${(vadSensitivity * 100).toInt()}%",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "Filters background noise and silence before transcription. " +
                       "Set to Off to disable filtering and pass all audio through.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = vadSensitivity,
                onValueChange = onVadSensitivityChange,
                valueRange = 0f..1f,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Off", style = MaterialTheme.typography.labelSmall)
                Text("Aggressive", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}


@Preview(showBackground = true, name = "Preferences · Hold / VAD Off")
@Composable
private fun PreferencesHoldVadOffPreview() {
    OutspokeTheme {
        PreferencesContent(
            triggerMode            = "HOLD",
            vadSensitivity         = 0f,
            onTriggerModeChange    = {},
            onVadSensitivityChange = {},
        )
    }
}

@Preview(showBackground = true, name = "Preferences · Tap Toggle / VAD 60%")
@Composable
private fun PreferencesTapToggleHighVadPreview() {
    OutspokeTheme {
        PreferencesContent(
            triggerMode            = "TAP_TOGGLE",
            vadSensitivity         = 0.6f,
            onTriggerModeChange    = {},
            onVadSensitivityChange = {},
        )
    }
}

