package dev.brgr.outspoke.settings.model

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.brgr.outspoke.settings.preferences.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

private const val TAG = "ModelViewModel"

/**
 * Manages the download state for every model in [ModelRegistry] and the currently
 * selected active model.
 *
 * [modelStates] emits a snapshot map (ModelId → ModelState) that is kept up to date as
 * downloads progress and as models are installed or deleted.
 *
 * [selectedModelId] reflects the model whose engine will be loaded by [InferenceService].
 * Calling [selectModel] persists the choice to [AppPreferences] so it survives restarts.
 */
class ModelViewModel(application: Application) : AndroidViewModel(application) {

    private val downloadManager = ModelDownloadManager()
    private val prefs = AppPreferences(application)

    private val _modelStates = MutableStateFlow<Map<ModelId, ModelState>>(
        // Eagerly check on-disk state so the UI is correct on first render.
        ModelRegistry.all.associate { info ->
            info.id to if (ModelStorageManager.isModelReady(application, info)) {
                ModelState.Ready
            } else {
                ModelState.NotDownloaded
            }
        }
    )
    val modelStates: StateFlow<Map<ModelId, ModelState>> = _modelStates.asStateFlow()

    private val _selectedModelId = MutableStateFlow(ModelId.DEFAULT)
    val selectedModelId: StateFlow<ModelId> = _selectedModelId.asStateFlow()

    private val _errorMessage = MutableSharedFlow<String>(replay = 0)
    val errorMessage: SharedFlow<String> = _errorMessage.asSharedFlow()

    private val downloadJobs = mutableMapOf<ModelId, Job>()

    init {
        // Hydrate the selected model from DataStore on first launch.
        viewModelScope.launch {
            _selectedModelId.value = prefs.selectedModelId.first()
        }
    }

    /** Starts downloading [modelId] if it is not already being downloaded. */
    fun startDownload(modelId: ModelId) {
        if (_modelStates.value[modelId] is ModelState.Downloading) return

        downloadJobs[modelId]?.cancel()
        downloadJobs[modelId] = viewModelScope.launch(Dispatchers.IO) {
            val modelInfo = ModelRegistry[modelId]
            try {
                downloadManager.download(getApplication(), modelInfo).collect { state ->
                    _modelStates.update { it + (modelId to state) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Model download failed for $modelId", e)
                _modelStates.update { it + (modelId to ModelState.NotDownloaded) }
                _errorMessage.emit("Download failed: ${e.localizedMessage ?: "Unknown error"}")
            }
        }
    }

    /** Cancels an in-progress download for [modelId] and resets its state. */
    fun cancelDownload(modelId: ModelId) {
        downloadJobs[modelId]?.cancel()
        downloadJobs.remove(modelId)
        _modelStates.update { it + (modelId to ModelState.NotDownloaded) }
    }

    /** Deletes all files for [modelId] from internal storage. */
    fun deleteModel(modelId: ModelId) {
        ModelStorageManager.deleteModel(getApplication(), modelId)
        _modelStates.update { it + (modelId to ModelState.NotDownloaded) }
        // Fall back to the default model if the active model was just deleted.
        if (_selectedModelId.value == modelId && modelId != ModelId.DEFAULT) {
            selectModel(ModelId.DEFAULT)
        }
        Log.d(TAG, "Model $modelId deleted from internal storage")
    }

    /**
     * Marks [modelId] as the active speech recognition model and persists the choice.
     * [InferenceService] observes the persisted preference and reloads its engine accordingly.
     */
    fun selectModel(modelId: ModelId) {
        _selectedModelId.value = modelId
        viewModelScope.launch { prefs.setSelectedModelId(modelId) }
        Log.d(TAG, "Selected model changed to: $modelId")
    }
}
