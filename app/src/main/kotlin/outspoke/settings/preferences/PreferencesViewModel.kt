package dev.brgr.outspoke.settings.preferences

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PreferencesViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = AppPreferences(application)

    val triggerMode: StateFlow<String> = prefs.triggerMode.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = "HOLD",
    )

    fun setTriggerMode(mode: String) {
        viewModelScope.launch { prefs.setTriggerMode(mode) }
    }

    val vadSensitivity: StateFlow<Float> = prefs.vadSensitivity.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = 0.15f,
    )

    fun setVadSensitivity(value: Float) {
        viewModelScope.launch { prefs.setVadSensitivity(value) }
    }

    val postprocessingEnabled: StateFlow<Boolean> = prefs.postprocessingEnabled.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = true,
    )

    fun setPostprocessingEnabled(enabled: Boolean) {
        viewModelScope.launch { prefs.setPostprocessingEnabled(enabled) }
    }

    val showPipelineDiagnostics: StateFlow<Boolean> = prefs.showPipelineDiagnostics.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = false,
    )

    fun setShowPipelineDiagnostics(enabled: Boolean) {
        viewModelScope.launch { prefs.setShowPipelineDiagnostics(enabled) }
    }
}

