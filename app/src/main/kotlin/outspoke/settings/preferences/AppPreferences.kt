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

    private val keyTriggerMode = stringPreferencesKey("trigger_mode")

    /** `"HOLD"` or `"TAP_TOGGLE"`. Defaults to `"HOLD"`. */
    val triggerMode: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[keyTriggerMode] ?: "HOLD"
    }

    suspend fun setTriggerMode(mode: String) {
        context.dataStore.edit { prefs -> prefs[keyTriggerMode] = mode }
    }

    private val keyVadEnabled = booleanPreferencesKey("vad_enabled")

    /** Whether VAD (voice activity detection) is active. Defaults to `true`. */
    val vadSensitivity: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[keyVadEnabled] ?: true
    }

    suspend fun setVadSensitivity(enabled: Boolean) {
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

    private val keyForcedLanguage = stringPreferencesKey("forced_language")

    /**
     * BCP-47 language tag to force on the active speech engine, or `null` for automatic detection.
     *
     * Supported values for Parakeet TDT 0.6B-v3:
     *   `null` (auto), `"en"`, `"de"`, `"fr"`, `"es"`, `"it"`, `"pt"`, `"nl"`, `"pl"`,
     *   `"zh"`, `"ja"`, `"ko"`.
     *
     * When non-null, `InferenceService` passes this tag to `SpeechEngine.setLanguage()` after
     * loading the engine.  The language is also used by the post-processing pipeline
     * (filler-word removal, number normalisation) to select the correct locale rules.
     *
     * Defaults to `null` (auto-detect) so existing installs are unaffected.
     */
    val forcedLanguage: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[keyForcedLanguage]   // null when key absent → auto-detect
    }

    suspend fun setForcedLanguage(tag: String?) {
        context.dataStore.edit { prefs ->
            if (tag == null) prefs.remove(keyForcedLanguage)
            else prefs[keyForcedLanguage] = tag
        }
    }

    private val keyFormatNumbersAsDigits = booleanPreferencesKey("format_numbers_as_digits")

    /**
     * When `true` (default), the post-processing pipeline converts number-word sequences
     * such as "twelve", "two thousand and twenty five", or "zwölf" into their digit
     * equivalents ("12", "2025", "12").
     *
     * Set to `false` to keep number words as spoken — useful when the user dictates
     * text where the written form of numbers is preferred (e.g. legal or literary writing).
     */
    val formatNumbersAsDigits: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[keyFormatNumbersAsDigits] ?: true
    }

    suspend fun setFormatNumbersAsDigits(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[keyFormatNumbersAsDigits] = enabled }
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
     * is enabled (so users must explicitly choose at least one language on first use).
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

    private val keySuggestionBarDismissed = booleanPreferencesKey("suggestion_bar_dismissed")

    /**
     * `true` once the user has tapped the dismiss (×) button in the suggestion bar.
     * The bar will not appear again until a new recording session starts.
     *
     * Reset to `false` automatically at the start of each new recording session so the
     * bar can appear again if the user taps on a word in the new transcription.
     *
     * Defaults to `false` so the bar is shown on first use.
     */
    val suggestionBarDismissed: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[keySuggestionBarDismissed] ?: false
    }

    suspend fun setSuggestionBarDismissed(dismissed: Boolean) {
        context.dataStore.edit { prefs -> prefs[keySuggestionBarDismissed] = dismissed }
    }
}
