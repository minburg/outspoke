package dev.brgr.outspoke.settings.preferences

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.brgr.outspoke.ime.correction.SuggestionDownloadState
import dev.brgr.outspoke.ime.correction.SuggestionFileDownloader
import dev.brgr.outspoke.ime.correction.SuggestionFileManager
import dev.brgr.outspoke.ime.correction.SuggestionLanguage
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class PreferencesViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = AppPreferences(application)
    private val downloader = SuggestionFileDownloader()

    val triggerMode: StateFlow<String> = prefs.triggerMode.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = "HOLD",
    )

    fun setTriggerMode(mode: String) {
        viewModelScope.launch { prefs.setTriggerMode(mode) }
    }

    val vadSensitivity: StateFlow<Boolean> = prefs.vadSensitivity.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = true,
    )

    fun setVadSensitivity(enabled: Boolean) {
        viewModelScope.launch { prefs.setVadSensitivity(enabled) }
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

    /** Resets the tutorial-shown flag so it plays again the next time the keyboard opens. */
    fun resetTutorial() {
        viewModelScope.launch { prefs.setKeyboardTutorialShown(false) }
    }

    val suggestionBarEnabled: StateFlow<Boolean> = prefs.suggestionBarEnabled.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = false,
    )

    fun setSuggestionBarEnabled(enabled: Boolean) {
        viewModelScope.launch { prefs.setSuggestionBarEnabled(enabled) }
    }

    val suggestionBarLanguages: StateFlow<Set<String>> = prefs.suggestionBarLanguages.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptySet(),
    )

    fun setSuggestionBarLanguages(tags: Set<String>) {
        viewModelScope.launch { prefs.setSuggestionBarLanguages(tags) }
    }

    // ── Per-language download state ───────────────────────────────────────────

    /**
     * Download state for each [SuggestionLanguage], keyed by [SuggestionLanguage.tag].
     * Initialised by checking which files already exist on disk.
     */
    private val _downloadStates = MutableStateFlow(
        SuggestionLanguage.entries.associate { lang ->
            lang.tag to if (SuggestionFileManager.isLanguageReady(application, lang.tag))
                SuggestionDownloadState.Ready
            else
                SuggestionDownloadState.NotDownloaded
        }
    )
    val downloadStates: StateFlow<Map<String, SuggestionDownloadState>> =
        _downloadStates.asStateFlow()

    /** Active download jobs, one per language tag. */
    private val downloadJobs = HashMap<String, Job>()

    /** Starts (or resumes) downloading the correction files for [tag]. */
    fun downloadLanguage(tag: String) {
        if (downloadJobs[tag]?.isActive == true) return  // already running
        downloadJobs[tag] = viewModelScope.launch {
            downloader.download(getApplication(), tag).collect { state ->
                _downloadStates.value = _downloadStates.value + (tag to state)
            }
        }
    }

    /** Cancels an in-progress download for [tag]. */
    fun cancelDownload(tag: String) {
        downloadJobs[tag]?.cancel()
        downloadJobs.remove(tag)
        // Restore to previous state (ready if files exist, else not-downloaded).
        val ctx = getApplication<Application>()
        val state = if (SuggestionFileManager.isLanguageReady(ctx, tag))
            SuggestionDownloadState.Ready else SuggestionDownloadState.NotDownloaded
        _downloadStates.value = _downloadStates.value + (tag to state)
    }

    /** Deletes downloaded files for [tag] and resets its state. */
    fun deleteLanguage(tag: String) {
        cancelDownload(tag)
        SuggestionFileManager.deleteLanguage(getApplication(), tag)
        _downloadStates.value = _downloadStates.value + (tag to SuggestionDownloadState.NotDownloaded)
        // Also remove from active languages preference.
        val current = suggestionBarLanguages.value
        if (tag in current) {
            viewModelScope.launch { prefs.setSuggestionBarLanguages(current - tag) }
        }
    }
}
