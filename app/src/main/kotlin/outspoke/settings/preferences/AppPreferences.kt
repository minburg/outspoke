package dev.brgr.outspoke.settings.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.brgr.outspoke.settings.model.ModelId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// Single DataStore instance per process - the delegate ensures this.
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "outspoke_prefs")

/**
 * Thin wrapper around [DataStore<Preferences>] that exposes typed [Flow]s and suspend setters
 * for every user-configurable preference.
 *
 * Instantiate with application context to avoid memory leaks.
 */
class AppPreferences(private val context: Context) {

    private val keyTriggerMode = stringPreferencesKey("trigger_mode")

    /** `"HOLD"` or `"TAP_TOGGLE"`. Defaults to `"HOLD"`. */
    val triggerMode: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[keyTriggerMode] ?: "HOLD"
    }

    suspend fun setTriggerMode(mode: String) {
        context.dataStore.edit { prefs -> prefs[keyTriggerMode] = mode }
    }

    private val keyVadSensitivity = floatPreferencesKey("vad_sensitivity")

    /** VAD aggressiveness in [0.0, 1.0]. Defaults to 0.0 (disabled). */
    val vadSensitivity: Flow<Float> = context.dataStore.data.map { prefs ->
        prefs[keyVadSensitivity] ?: 0f
    }

    suspend fun setVadSensitivity(value: Float) {
        context.dataStore.edit { prefs -> prefs[keyVadSensitivity] = value }
    }

    private val keySelectedModelId = stringPreferencesKey("selected_model_id")

    /**
     * The [ModelId] of the model that [InferenceService] should load.
     * Defaults to [ModelId.DEFAULT] when no preference has been saved.
     */
    val selectedModelId: Flow<ModelId> = context.dataStore.data.map { prefs ->
        val stored = prefs[keySelectedModelId]
        if (stored != null) runCatching { ModelId.valueOf(stored) }.getOrDefault(ModelId.DEFAULT)
        else ModelId.DEFAULT
    }

    suspend fun setSelectedModelId(modelId: ModelId) {
        context.dataStore.edit { prefs -> prefs[keySelectedModelId] = modelId.name }
    }

    private val keyWhisperLanguage = stringPreferencesKey("whisper_language")

    /**
     * BCP-47 language tag for Whisper decoding, or `"auto"` for automatic detection.
     * Supported values: `"auto"`, `"en"`, `"de"`, `"nl"`.
     * Defaults to `"auto"`.
     */
    val whisperLanguage: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[keyWhisperLanguage] ?: "auto"
    }

    suspend fun setWhisperLanguage(tag: String) {
        context.dataStore.edit { prefs -> prefs[keyWhisperLanguage] = tag }
    }

    private val keyPostprocessingEnabled = booleanPreferencesKey("postprocessing_enabled")

    /**
     * When `true` (default) the transcript post-processing pipeline is active:
     * filler words are removed, stutters collapsed, repeated phrases deduplicated,
     * and sentence capitalisation enforced.
     *
     * Set to `false` to receive the raw model output unchanged - useful for debugging
     * whether cleaning is responsible for dropped or altered words.
     */
    val postprocessingEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[keyPostprocessingEnabled] ?: true
    }

    suspend fun setPostprocessingEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[keyPostprocessingEnabled] = enabled }
    }

    private val keyShowPipelineDiagnostics = booleanPreferencesKey("show_pipeline_diagnostics")

    /**
     * When `true`, the keyboard UI displays a live [PipelineDiagnostics] summary badge.
     * Defaults to `false` - hidden by default to keep the UI clean.
     */
    val showPipelineDiagnostics: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[keyShowPipelineDiagnostics] ?: false
    }

    suspend fun setShowPipelineDiagnostics(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[keyShowPipelineDiagnostics] = enabled }
    }

    private val keyKeyboardTutorialShown = booleanPreferencesKey("keyboard_tutorial_shown")

    /**
     * `true` once the first-run keyboard tutorial has been dismissed by the user.
     * Defaults to `false` so the tutorial is shown on the very first keyboard opening.
     */
    val keyboardTutorialShown: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[keyKeyboardTutorialShown] ?: false
    }

    suspend fun setKeyboardTutorialShown(shown: Boolean) {
        context.dataStore.edit { prefs -> prefs[keyKeyboardTutorialShown] = shown }
    }
}
