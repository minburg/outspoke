package dev.brgr.outspoke.settings.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
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
    val triggerMode by viewModel.triggerMode.collectAsState()
    val vadSensitivity by viewModel.vadSensitivity.collectAsState()
    val postprocessingEnabled by viewModel.postprocessingEnabled.collectAsState()
    val showPipelineDiagnostics by viewModel.showPipelineDiagnostics.collectAsState()

    PreferencesContent(
        triggerMode = triggerMode,
        vadSensitivity = vadSensitivity,
        postprocessingEnabled = postprocessingEnabled,
        showPipelineDiagnostics = showPipelineDiagnostics,
        onTriggerModeChange = viewModel::setTriggerMode,
        onVadSensitivityChange = viewModel::setVadSensitivity,
        onPostprocessingChange = viewModel::setPostprocessingEnabled,
        onShowPipelineDiagnosticsChange = viewModel::setShowPipelineDiagnostics,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PreferencesContent(
    triggerMode: String,
    vadSensitivity: Float,
    postprocessingEnabled: Boolean,
    showPipelineDiagnostics: Boolean,
    onTriggerModeChange: (String) -> Unit,
    onVadSensitivityChange: (Float) -> Unit,
    onPostprocessingChange: (Boolean) -> Unit,
    onShowPipelineDiagnosticsChange: (Boolean) -> Unit,
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

        HorizontalDivider()

        // Post-processing toggle - lets users bypass filler/repetition cleaning for debugging.
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Transcript Post-Processing",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "Removes filler words (uh, um, hmm), collapses stutters, and cleans up " +
                        "repeated phrases the model sometimes hallucinates. " +
                        "Disable to receive the exact raw model output - useful for diagnosing " +
                        "whether cleaning is responsible for dropped or altered words.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = if (postprocessingEnabled) "Enabled" else "Disabled (raw output)",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Switch(
                    checked = postprocessingEnabled,
                    onCheckedChange = onPostprocessingChange,
                )
            }
        }

        HorizontalDivider()

        // Pipeline diagnostics toggle - shows a live summary badge on the keyboard.
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Pipeline Diagnostics",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "Shows a live badge on the keyboard with counters for window trims, " +
                        "alignment recoveries, and discarded blank strides. " +
                        "Useful for diagnosing pipeline issues; off by default.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = if (showPipelineDiagnostics) "Visible" else "Hidden",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Switch(
                    checked = showPipelineDiagnostics,
                    onCheckedChange = onShowPipelineDiagnosticsChange,
                )
            }
        }
    }
}


@Preview(showBackground = true, name = "Preferences · Hold / VAD Off")
@Composable
private fun PreferencesHoldVadOffPreview() {
    OutspokeTheme {
        PreferencesContent(
            triggerMode = "HOLD",
            vadSensitivity = 0f,
            postprocessingEnabled = true,
            showPipelineDiagnostics = false,
            onTriggerModeChange = {},
            onVadSensitivityChange = {},
            onPostprocessingChange = {},
            onShowPipelineDiagnosticsChange = {},
        )
    }
}

@Preview(showBackground = true, name = "Preferences · Tap Toggle / VAD 60%")
@Composable
private fun PreferencesTapToggleHighVadPreview() {
    OutspokeTheme {
        PreferencesContent(
            triggerMode = "TAP_TOGGLE",
            vadSensitivity = 0.6f,
            postprocessingEnabled = false,
            showPipelineDiagnostics = true,
            onTriggerModeChange = {},
            onVadSensitivityChange = {},
            onPostprocessingChange = {},
            onShowPipelineDiagnosticsChange = {},
        )
    }
}

