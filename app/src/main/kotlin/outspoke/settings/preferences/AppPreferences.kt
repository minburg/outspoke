package dev.brgr.outspoke.settings.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.brgr.outspoke.settings.model.ModelId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// Single DataStore instance per process — the delegate ensures this.
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "outspoke_prefs")

/**
 * Thin wrapper around [DataStore<Preferences>] that exposes typed [Flow]s and suspend setters
 * for every user-configurable preference.
 *
 * Instantiate with application context to avoid memory leaks.
 */
class AppPreferences(private val context: Context) {

    // -------------------------------------------------------------------------
    // Trigger mode
    // -------------------------------------------------------------------------

    private val keyTriggerMode = stringPreferencesKey("trigger_mode")

    /** `"HOLD"` or `"TAP_TOGGLE"`. Defaults to `"HOLD"`. */
    val triggerMode: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[keyTriggerMode] ?: "HOLD"
    }

    suspend fun setTriggerMode(mode: String) {
        context.dataStore.edit { prefs -> prefs[keyTriggerMode] = mode }
    }

    // -------------------------------------------------------------------------
    // VAD sensitivity
    // -------------------------------------------------------------------------

    private val keyVadSensitivity = floatPreferencesKey("vad_sensitivity")

    /** VAD aggressiveness in [0.0, 1.0]. Defaults to 0.0 (disabled). */
    val vadSensitivity: Flow<Float> = context.dataStore.data.map { prefs ->
        prefs[keyVadSensitivity] ?: 0f
    }

    suspend fun setVadSensitivity(value: Float) {
        context.dataStore.edit { prefs -> prefs[keyVadSensitivity] = value }
    }

    // -------------------------------------------------------------------------
    // Selected model
    // -------------------------------------------------------------------------

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
}
