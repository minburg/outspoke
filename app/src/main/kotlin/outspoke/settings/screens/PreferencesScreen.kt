package dev.brgr.outspoke.settings.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.brgr.outspoke.settings.preferences.PreferencesViewModel

/**
 * User-configurable keyboard preferences backed by [PreferencesViewModel] / DataStore.
 *
 * Settings persist across process restarts.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreferencesScreen(
    viewModel: PreferencesViewModel = viewModel(),
) {
    val triggerMode by viewModel.triggerMode.collectAsState()
    val vadSensitivity by viewModel.vadSensitivity.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {

        // -----------------------------------------------------------------------
        // Trigger mode
        // -----------------------------------------------------------------------
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
                    onClick = { viewModel.setTriggerMode("HOLD") },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                ) {
                    Text("Hold to Talk")
                }
                SegmentedButton(
                    selected = triggerMode == "TAP_TOGGLE",
                    onClick = { viewModel.setTriggerMode("TAP_TOGGLE") },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                ) {
                    Text("Tap to Toggle")
                }
            }
        }

        HorizontalDivider()

        // -----------------------------------------------------------------------
        // VAD sensitivity
        // -----------------------------------------------------------------------
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
                onValueChange = viewModel::setVadSensitivity,
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

