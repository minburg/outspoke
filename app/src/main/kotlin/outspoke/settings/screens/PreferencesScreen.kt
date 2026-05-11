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
import dev.brgr.outspoke.R
import dev.brgr.outspoke.ime.correction.SuggestionDownloadState
import dev.brgr.outspoke.ime.correction.SuggestionLanguage
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
    val suggestionBarEnabled by viewModel.suggestionBarEnabled.collectAsState()
    val suggestionBarLanguages by viewModel.suggestionBarLanguages.collectAsState()
    val downloadStates by viewModel.downloadStates.collectAsState()

    PreferencesContent(
        triggerMode = triggerMode,
        vadSensitivity = vadSensitivity,
        postprocessingEnabled = postprocessingEnabled,
        showPipelineDiagnostics = showPipelineDiagnostics,
        suggestionBarEnabled = suggestionBarEnabled,
        suggestionBarLanguages = suggestionBarLanguages,
        downloadStates = downloadStates,
        onTriggerModeChange = viewModel::setTriggerMode,
        onVadSensitivityChange = viewModel::setVadSensitivity,
        onPostprocessingChange = viewModel::setPostprocessingEnabled,
        onShowPipelineDiagnosticsChange = viewModel::setShowPipelineDiagnostics,
        onSuggestionBarEnabledChange = viewModel::setSuggestionBarEnabled,
        onSuggestionBarLanguagesChange = viewModel::setSuggestionBarLanguages,
        onDownloadLanguage = viewModel::downloadLanguage,
        onCancelDownload = viewModel::cancelDownload,
        onDeleteLanguage = viewModel::deleteLanguage,
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
    suggestionBarEnabled: Boolean = false,
    suggestionBarLanguages: Set<String> = emptySet(),
    downloadStates: Map<String, SuggestionDownloadState> = emptyMap(),
    onTriggerModeChange: (String) -> Unit,
    onVadSensitivityChange: (Float) -> Unit,
    onPostprocessingChange: (Boolean) -> Unit,
    onShowPipelineDiagnosticsChange: (Boolean) -> Unit,
    onSuggestionBarEnabledChange: (Boolean) -> Unit = {},
    onSuggestionBarLanguagesChange: (Set<String>) -> Unit = {},
    onDownloadLanguage: (String) -> Unit = {},
    onCancelDownload: (String) -> Unit = {},
    onDeleteLanguage: (String) -> Unit = {},
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


        // Word Suggestion Bar — master toggle + per-language download controls.
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

        HorizontalDivider()

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

        HorizontalDivider()

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

@Preview(showBackground = true, name = "Preferences · Suggestion Bar Enabled")
@Composable
private fun PreferencesSuggestionBarPreview() {
    OutspokeTheme {
        PreferencesContent(
            triggerMode = "HOLD",
            vadSensitivity = 0f,
            postprocessingEnabled = true,
            showPipelineDiagnostics = false,
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
            onTriggerModeChange = {},
            onVadSensitivityChange = {},
            onPostprocessingChange = {},
            onShowPipelineDiagnosticsChange = {},
        )
    }
}
