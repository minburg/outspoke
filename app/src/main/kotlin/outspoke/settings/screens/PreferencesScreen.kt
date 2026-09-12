package dev.brgr.outspoke.settings.screens

import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
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
import dev.brgr.outspoke.R
import dev.brgr.outspoke.ime.correction.SuggestionDownloadState
import dev.brgr.outspoke.ime.correction.SuggestionLanguage
import dev.brgr.outspoke.settings.preferences.PreferencesViewModel
import dev.brgr.outspoke.ui.theme.OutspokeTheme

/**
 * Category 1 — Microphone, Trigger & Delete Button.
 *
 * Microphone calibration entry point, the recording trigger mode, and the
 * behaviour of the keyboard's delete (trash) button.
 * Backed by [PreferencesViewModel] / DataStore; settings persist across
 * process restarts.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InputPreferencesScreen(
    viewModel: PreferencesViewModel = viewModel(),
    onNavigateToCalibration: () -> Unit = {},
) {
    val trailingSpace by viewModel.trailingSpace.collectAsState()
    val triggerMode by viewModel.triggerMode.collectAsState()
    val deleteButtonMode by viewModel.deleteButtonMode.collectAsState()
    val rawMicCapture by viewModel.rawMicCapture.collectAsState()

    PreferencesColumn {
        MicSection(
            onNavigateToCalibration = onNavigateToCalibration,
            rawMicCapture = rawMicCapture,
            onRawMicCaptureChange = viewModel::setRawMicCapture,
        )
        HorizontalDivider()
        TriggerModeSection(
            triggerMode = triggerMode,
            onTriggerModeChange = viewModel::setTriggerMode,
        )
        HorizontalDivider()
        Row(
            modifier = Modifier.fillMaxWidth().toggleable(
                value = trailingSpace, role = Role.Switch, onValueChange = viewModel::setTrailingSpace,
            ).padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.pref_trailing_space_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.pref_trailing_space_summary), style = MaterialTheme.typography.bodyMedium)
            }
            Switch(checked = trailingSpace, onCheckedChange = null)
        }
        HorizontalDivider()
        DeleteButtonSection(
            deleteButtonMode = deleteButtonMode,
            onDeleteButtonModeChange = viewModel::setDeleteButtonMode,
        )
    }
}

/**
 * Category 2 — Speech Processing.
 *
 * Voice activity detection, transcript post-processing, and the word
 * suggestion bar. Backed by [PreferencesViewModel] / DataStore.
 */
@Composable
fun SpeechPreferencesScreen(
    viewModel: PreferencesViewModel = viewModel(),
) {
    val vadSensitivity by viewModel.vadSensitivity.collectAsState()
    val postprocessingEnabled by viewModel.postprocessingEnabled.collectAsState()
    val suggestionBarEnabled by viewModel.suggestionBarEnabled.collectAsState()
    val suggestionBarLanguages by viewModel.suggestionBarLanguages.collectAsState()
    val downloadStates by viewModel.downloadStates.collectAsState()

    PreferencesColumn {
        VadSection(
            vadSensitivity = vadSensitivity,
            onVadSensitivityChange = viewModel::setVadSensitivity,
        )
        HorizontalDivider()
        PostprocessingSection(
            postprocessingEnabled = postprocessingEnabled,
            onPostprocessingChange = viewModel::setPostprocessingEnabled,
        )
        HorizontalDivider()
        SuggestionBarSection(
            suggestionBarEnabled = suggestionBarEnabled,
            suggestionBarLanguages = suggestionBarLanguages,
            downloadStates = downloadStates,
            onSuggestionBarEnabledChange = viewModel::setSuggestionBarEnabled,
            onSuggestionBarLanguagesChange = viewModel::setSuggestionBarLanguages,
            onDownloadLanguage = viewModel::downloadLanguage,
            onCancelDownload = viewModel::cancelDownload,
            onDeleteLanguage = viewModel::deleteLanguage,
        )
    }
}

/**
 * Category 3 — Tools.
 *
 * Keyboard tutorial replay and the pipeline diagnostics toggle.
 * Backed by [PreferencesViewModel] / DataStore.
 */
@Composable
fun ToolsPreferencesScreen(
    viewModel: PreferencesViewModel = viewModel(),
) {
    val showPipelineDiagnostics by viewModel.showPipelineDiagnostics.collectAsState()

    PreferencesColumn {
        TutorialSection(onResetTutorial = viewModel::resetTutorial)
        HorizontalDivider()
        DiagnosticsSection(
            showPipelineDiagnostics = showPipelineDiagnostics,
            onShowPipelineDiagnosticsChange = viewModel::setShowPipelineDiagnostics,
        )
    }
}

/** Shared scroll container for the category preference screens. */
@Composable
private fun PreferencesColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        content()
    }
}

@Composable
private fun MicSection(
    onNavigateToCalibration: () -> Unit,
    rawMicCapture: Boolean,
    onRawMicCaptureChange: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.pref_mic_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.pref_mic_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = onNavigateToCalibration,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.pref_mic_calibrate))
        }

        Text(
            text = stringResource(R.string.pref_raw_mic_title),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = stringResource(R.string.pref_raw_mic_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = if (rawMicCapture) stringResource(R.string.state_enabled)
                else stringResource(R.string.state_disabled),
                style = MaterialTheme.typography.bodyMedium,
            )
            Switch(
                checked = rawMicCapture,
                onCheckedChange = onRawMicCaptureChange,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TriggerModeSection(
    triggerMode: String,
    onTriggerModeChange: (String) -> Unit,
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeleteButtonSection(
    deleteButtonMode: String,
    onDeleteButtonModeChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.pref_delete_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.pref_delete_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = deleteButtonMode == "DELETE_ALL",
                onClick = { onDeleteButtonModeChange("DELETE_ALL") },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            ) {
                Text(stringResource(R.string.pref_delete_all))
            }
            SegmentedButton(
                selected = deleteButtonMode == "DELETE_LAST_SENTENCE",
                onClick = { onDeleteButtonModeChange("DELETE_LAST_SENTENCE") },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            ) {
                Text(stringResource(R.string.pref_delete_last_sentence))
            }
        }
    }
}

@Composable
private fun VadSection(
    vadSensitivity: Boolean,
    onVadSensitivityChange: (Boolean) -> Unit,
) {
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
                text = if (vadSensitivity) stringResource(R.string.state_enabled)
                else stringResource(R.string.state_disabled),
                style = MaterialTheme.typography.bodyMedium,
            )
            Switch(
                checked = vadSensitivity,
                onCheckedChange = onVadSensitivityChange,
            )
        }
    }
}

@Composable
private fun PostprocessingSection(
    postprocessingEnabled: Boolean,
    onPostprocessingChange: (Boolean) -> Unit,
) {
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
}

/**
 * Word Suggestion Bar — master toggle + per-language download controls.
 */
@Composable
private fun SuggestionBarSection(
    suggestionBarEnabled: Boolean,
    suggestionBarLanguages: Set<String>,
    downloadStates: Map<String, SuggestionDownloadState>,
    onSuggestionBarEnabledChange: (Boolean) -> Unit,
    onSuggestionBarLanguagesChange: (Set<String>) -> Unit,
    onDownloadLanguage: (String) -> Unit,
    onCancelDownload: (String) -> Unit,
    onDeleteLanguage: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.pref_suggestion_bar_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.pref_suggestion_bar_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = if (suggestionBarEnabled) stringResource(R.string.pref_suggestion_bar_enabled)
                else stringResource(R.string.pref_suggestion_bar_disabled),
                style = MaterialTheme.typography.bodyMedium,
            )
            Switch(
                checked = suggestionBarEnabled,
                onCheckedChange = onSuggestionBarEnabledChange,
            )
        }

        if (suggestionBarEnabled) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.pref_suggestion_bar_languages_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = stringResource(R.string.pref_suggestion_bar_languages_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))

            // One row per supported language with download state + enable toggle.
            SuggestionLanguage.entries.forEach { lang ->
                val dlState = downloadStates[lang.tag] ?: SuggestionDownloadState.NotDownloaded
                val isReady = dlState is SuggestionDownloadState.Ready
                val isSelected = lang.tag in suggestionBarLanguages

                SuggestionLanguageRow(
                    language = lang,
                    downloadState = dlState,
                    isSelected = isSelected,
                    onToggleSelected = { checked ->
                        if (isReady) {
                            val updated = if (checked) suggestionBarLanguages + lang.tag
                            else suggestionBarLanguages - lang.tag
                            onSuggestionBarLanguagesChange(updated)
                        }
                    },
                    onDownload = { onDownloadLanguage(lang.tag) },
                    onCancelDownload = { onCancelDownload(lang.tag) },
                    onDelete = { onDeleteLanguage(lang.tag) },
                )
            }

            val anyReady = SuggestionLanguage.entries.any {
                downloadStates[it.tag] is SuggestionDownloadState.Ready && it.tag in suggestionBarLanguages
            }
            if (!anyReady) {
                Text(
                    text = stringResource(R.string.pref_suggestion_bar_no_language),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun TutorialSection(
    onResetTutorial: () -> Unit,
) {
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
}

@Composable
private fun DiagnosticsSection(
    showPipelineDiagnostics: Boolean,
    onShowPipelineDiagnosticsChange: (Boolean) -> Unit,
) {
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
}

/**
 * Single row for a suggestion language: shows the language name, its download state,
 * and appropriate action controls.
 *
 * States:
 * - [SuggestionDownloadState.NotDownloaded] → Download button
 * - [SuggestionDownloadState.Downloading]   → Progress bar + Cancel button
 * - [SuggestionDownloadState.Ready]         → Checkmark icon + enable Checkbox + Delete button
 * - [SuggestionDownloadState.Failed]        → Error text + Retry button
 */
@Composable
private fun SuggestionLanguageRow(
    language: SuggestionLanguage,
    downloadState: SuggestionDownloadState,
    isSelected: Boolean,
    onToggleSelected: (Boolean) -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = language.displayName,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )

            when (downloadState) {
                is SuggestionDownloadState.NotDownloaded -> {
                    TextButton(onClick = onDownload) {
                        Text(stringResource(R.string.suggestion_lang_download))
                    }
                }

                is SuggestionDownloadState.Downloading -> {
                    TextButton(onClick = onCancelDownload) {
                        Text(stringResource(R.string.suggestion_lang_cancel))
                    }
                }

                is SuggestionDownloadState.Ready -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Checkbox(
                            checked = isSelected,
                            onCheckedChange = onToggleSelected,
                        )
                        TextButton(onClick = onDelete) {
                            Text(
                                text = stringResource(R.string.suggestion_lang_delete),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }

                is SuggestionDownloadState.Failed -> {
                    TextButton(onClick = onDownload) {
                        Text(
                            text = stringResource(R.string.suggestion_lang_retry),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }

        // Progress bar shown while downloading.
        if (downloadState is SuggestionDownloadState.Downloading) {
            LinearProgressIndicator(
                progress = { downloadState.progress },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // Error message when download failed.
        if (downloadState is SuggestionDownloadState.Failed) {
            Text(
                text = downloadState.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Preview(showBackground = true, name = "Prefs · Microphone")
@Composable
private fun MicSectionPreview() {
    OutspokeTheme {
        PreferencesColumn {
            MicSection(onNavigateToCalibration = {}, rawMicCapture = false, onRawMicCaptureChange = {})
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true, name = "Prefs · Trigger Mode (Hold)")
@Composable
private fun TriggerModeSectionHoldPreview() {
    OutspokeTheme {
        PreferencesColumn {
            TriggerModeSection(triggerMode = "HOLD", onTriggerModeChange = {})
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true, name = "Prefs · Trigger Mode (Tap Toggle)")
@Composable
private fun TriggerModeSectionTapTogglePreview() {
    OutspokeTheme {
        PreferencesColumn {
            TriggerModeSection(triggerMode = "TAP_TOGGLE", onTriggerModeChange = {})
        }
    }
}

@Preview(showBackground = true, name = "Prefs · VAD Enabled")
@Composable
private fun VadSectionPreview() {
    OutspokeTheme {
        PreferencesColumn {
            VadSection(vadSensitivity = true, onVadSensitivityChange = {})
        }
    }
}

@Preview(showBackground = true, name = "Prefs · Post-Processing Disabled")
@Composable
private fun PostprocessingSectionPreview() {
    OutspokeTheme {
        PreferencesColumn {
            PostprocessingSection(postprocessingEnabled = false, onPostprocessingChange = {})
        }
    }
}

@Preview(showBackground = true, name = "Prefs · Suggestion Bar Enabled")
@Composable
private fun SuggestionBarSectionPreview() {
    OutspokeTheme {
        PreferencesColumn {
            SuggestionBarSection(
                suggestionBarEnabled = true,
                suggestionBarLanguages = setOf("en"),
                downloadStates = mapOf(
                    "nl" to SuggestionDownloadState.NotDownloaded,
                    "en" to SuggestionDownloadState.Ready,
                    "fr" to SuggestionDownloadState.Downloading(0.45f),
                    "de" to SuggestionDownloadState.Failed("Network error"),
                    "it" to SuggestionDownloadState.NotDownloaded,
                    "pl" to SuggestionDownloadState.NotDownloaded,
                    "es" to SuggestionDownloadState.NotDownloaded,
                ),
                onSuggestionBarEnabledChange = {},
                onSuggestionBarLanguagesChange = {},
                onDownloadLanguage = {},
                onCancelDownload = {},
                onDeleteLanguage = {},
            )
        }
    }
}

@Preview(showBackground = true, name = "Prefs · Tutorial")
@Composable
private fun TutorialSectionPreview() {
    OutspokeTheme {
        PreferencesColumn {
            TutorialSection(onResetTutorial = {})
        }
    }
}

@Preview(showBackground = true, name = "Prefs · Diagnostics Visible")
@Composable
private fun DiagnosticsSectionPreview() {
    OutspokeTheme {
        PreferencesColumn {
            DiagnosticsSection(showPipelineDiagnostics = true, onShowPipelineDiagnosticsChange = {})
        }
    }
}
