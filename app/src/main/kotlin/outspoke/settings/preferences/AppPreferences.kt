package dev.brgr.outspoke.settings.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
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

    private val keyTrailingSpace = booleanPreferencesKey("trailing_space")

    /** Add a space after the complete recording has been transcribed. */
    val trailingSpace: Flow<Boolean> = context.dataStore.data.map { it[keyTrailingSpace] ?: false }

    suspend fun setTrailingSpace(enabled: Boolean) {
        context.dataStore.edit { it[keyTrailingSpace] = enabled }
    }

    private val keyTriggerMode = stringPreferencesKey("trigger_mode")

    /** `"HOLD"` or `"TAP_TOGGLE"`. Defaults to `"HOLD"`. */
    val triggerMode: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[keyTriggerMode] ?: "HOLD"
    }

    suspend fun setTriggerMode(mode: String) {
        context.dataStore.edit { prefs -> prefs[keyTriggerMode] = mode }
    }

    private val keyDeleteButtonMode = stringPreferencesKey("delete_button_mode")

    /**
     * What the keyboard's delete (trash) button does:
     *   `"DELETE_LAST_SENTENCE"` (default) — removes only the last sentence before the
     *   cursor, so a long document is never wiped by a single tap.
     *   `"DELETE_ALL"` — clears the entire editor field.
     */
    val deleteButtonMode: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[keyDeleteButtonMode] ?: "DELETE_LAST_SENTENCE"
    }

    suspend fun setDeleteButtonMode(mode: String) {
        context.dataStore.edit { prefs -> prefs[keyDeleteButtonMode] = mode }
    }

    private val keyRawMicCapture = booleanPreferencesKey("raw_mic_capture")

    /**
     * When `true`, [dev.brgr.outspoke.audio.AudioCaptureManager] captures from
     * [android.media.MediaRecorder.AudioSource.UNPROCESSED] instead of
     * [android.media.MediaRecorder.AudioSource.DEFAULT], bypassing the platform's
     * voice processing (AEC / NS / AGC).
     *
     * Required for the speakerphone use case: with the default (voice-processed)
     * source, audio played from the device's own speaker is cancelled out of the
     * mic stream by AEC, so e.g. a voicemail playing on speaker transcribes as
     * silence. Off by default to keep the standard capture behaviour.
     */
    val rawMicCapture: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[keyRawMicCapture] ?: false
    }

    suspend fun setRawMicCapture(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[keyRawMicCapture] = enabled }
    }

    private val keyVadEnabled = booleanPreferencesKey("vad_enabled")

    /** Whether VAD (voice activity detection) is active. Defaults to `true`. */
    val vadEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[keyVadEnabled] ?: true
    }

    suspend fun setVadEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[keyVadEnabled] = enabled }
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

    private val keySuggestionBarEnabled = booleanPreferencesKey("suggestion_bar_enabled")

    /**
     * Master switch for the word-suggestion bar feature.
     *
     * When `false` (default) the suggestion bar is never shown, no dictionary is loaded,
     * and no background work is done.  When `true`, the bar appears after each dictation
     * commit and shows correction candidates from the languages selected in
     * [suggestionBarLanguages].
     */
    val suggestionBarEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[keySuggestionBarEnabled] ?: false
    }

    suspend fun setSuggestionBarEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[keySuggestionBarEnabled] = enabled }
    }

    private val keySuggestionBarLanguages = stringPreferencesKey("suggestion_bar_languages")

    /**
     * Comma-separated set of BCP-47 language tags the word corrector should search.
     * Only tags from [SuggestionLanguage.TAG_SET] are valid.
     *
     * Defaults to empty string → empty set → no language is active even if the feature
     * is enabled. Downloading a language pack adds its tag automatically (see
     * [addSuggestionBarLanguage]); the user can still uncheck a language at any time.
     */
    val suggestionBarLanguages: Flow<Set<String>> = context.dataStore.data.map { prefs ->
        val raw = prefs[keySuggestionBarLanguages] ?: ""
        if (raw.isBlank()) emptySet() else raw.split(",").toSet()
    }

    suspend fun setSuggestionBarLanguages(tags: Set<String>) {
        context.dataStore.edit { prefs ->
            prefs[keySuggestionBarLanguages] = tags.joinToString(",")
        }
    }

    /**
     * Atomically adds [tag] to [suggestionBarLanguages] if it is not already present.
     * Called when a language pack download completes so the language is active by
     * default — no extra user step needed.
     */
    suspend fun addSuggestionBarLanguage(tag: String) {
        context.dataStore.edit { prefs ->
            val raw = prefs[keySuggestionBarLanguages] ?: ""
            val current = if (raw.isBlank()) emptySet() else raw.split(",").toSet()
            if (tag !in current) {
                prefs[keySuggestionBarLanguages] = (current + tag).joinToString(",")
            }
        }
    }

    private val keyPreferredMicId = intPreferencesKey("preferred_mic_id")

    /**
     * The [android.media.AudioDeviceInfo.id] the microphone calibration selected as the
     * highest-fidelity input, or `0` when no calibration has been run (use the system
     * default). [dev.brgr.outspoke.audio.AudioCaptureManager] applies it via
     * `AudioRecord.setPreferredDevice`.
     */
    val preferredMicId: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[keyPreferredMicId] ?: 0
    }

    suspend fun setPreferredMicId(id: Int) {
        context.dataStore.edit { prefs -> prefs[keyPreferredMicId] = id }
    }

}
