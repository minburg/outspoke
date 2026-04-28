package dev.brgr.outspoke.settings.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.brgr.outspoke.BuildConfig
import dev.brgr.outspoke.R
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
    val debugAudioDumpEnabled by viewModel.debugAudioDumpEnabled.collectAsState()

    PreferencesContent(
        triggerMode = triggerMode,
        vadSensitivity = vadSensitivity,
        postprocessingEnabled = postprocessingEnabled,
        showPipelineDiagnostics = showPipelineDiagnostics,
        debugAudioDumpEnabled = debugAudioDumpEnabled,
        onTriggerModeChange = viewModel::setTriggerMode,
        onVadSensitivityChange = viewModel::setVadSensitivity,
        onPostprocessingChange = viewModel::setPostprocessingEnabled,
        onShowPipelineDiagnosticsChange = viewModel::setShowPipelineDiagnostics,
        onDebugAudioDumpChange = viewModel::setDebugAudioDumpEnabled,
        onResetTutorial = viewModel::resetTutorial,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PreferencesContent(
    triggerMode: String,
    vadSensitivity: Float,
    postprocessingEnabled: Boolean,
    showPipelineDiagnostics: Boolean,
    debugAudioDumpEnabled: Boolean = false,
    onTriggerModeChange: (String) -> Unit,
    onVadSensitivityChange: (Float) -> Unit,
    onPostprocessingChange: (Boolean) -> Unit,
    onShowPipelineDiagnosticsChange: (Boolean) -> Unit,
    onDebugAudioDumpChange: (Boolean) -> Unit = {},
    onResetTutorial: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.pref_trigger_mode_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.pref_trigger_mode_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = triggerMode == "HOLD",
                    onClick = { onTriggerModeChange("HOLD") },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                ) {
                    Text(stringResource(R.string.pref_trigger_hold))
                }
                SegmentedButton(
                    selected = triggerMode == "TAP_TOGGLE",
                    onClick = { onTriggerModeChange("TAP_TOGGLE") },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                ) {
                    Text(stringResource(R.string.pref_trigger_tap_toggle))
                }
            }
        }

        HorizontalDivider()

        // Voice Activity Detection toggle.
        // Uses SileroVAD to filter out non-speech audio before transcription.
        // The underlying preference is stored as a float (0 = off, >0 = on) for
        // backwards compatibility; the toggle writes 0f (off) or 1f (on).
        val vadEnabled = vadSensitivity > 0f
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.pref_vad_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.pref_vad_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = if (vadEnabled) stringResource(R.string.state_enabled)
                    else stringResource(R.string.state_disabled),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Switch(
                    checked = vadEnabled,
                    onCheckedChange = { enabled -> onVadSensitivityChange(if (enabled) 1f else 0f) },
                )
            }
        }

        HorizontalDivider()

        // Post-processing toggle - lets users bypass filler/repetition cleaning for debugging.
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.pref_postprocessing_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.pref_postprocessing_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = if (postprocessingEnabled) stringResource(R.string.state_enabled)
                    else stringResource(R.string.pref_postprocessing_disabled_raw),
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
                text = stringResource(R.string.pref_diagnostics_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.pref_diagnostics_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = if (showPipelineDiagnostics) stringResource(R.string.pref_diagnostics_visible)
                    else stringResource(R.string.pref_diagnostics_hidden),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Switch(
                    checked = showPipelineDiagnostics,
                    onCheckedChange = onShowPipelineDiagnosticsChange,
                )
            }
        }

        HorizontalDivider()

        // Tutorial reset - lets the user (or developer) replay the keyboard walk-through.
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.pref_tutorial_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.pref_tutorial_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = onResetTutorial,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.pref_tutorial_reset))
            }
        }

        // Debug: Audio Tap - only visible in debug builds so it never ships to end users.
        if (BuildConfig.DEBUG) {
            HorizontalDivider()

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.pref_debug_audio_dump_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = stringResource(R.string.pref_debug_audio_dump_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = if (debugAudioDumpEnabled) stringResource(R.string.pref_debug_audio_dump_enabled)
                        else stringResource(R.string.pref_debug_audio_dump_disabled),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Switch(
                        checked = debugAudioDumpEnabled,
                        onCheckedChange = onDebugAudioDumpChange,
                    )
                }
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

@Preview(showBackground = true, name = "Preferences · Tap Toggle / VAD On")
@Composable
private fun PreferencesTapToggleVadOnPreview() {
    OutspokeTheme {
        PreferencesContent(
            triggerMode = "TAP_TOGGLE",
            vadSensitivity = 1f,
            postprocessingEnabled = false,
            showPipelineDiagnostics = true,
            onTriggerModeChange = {},
            onVadSensitivityChange = {},
            onPostprocessingChange = {},
            onShowPipelineDiagnosticsChange = {},
        )
    }
}
