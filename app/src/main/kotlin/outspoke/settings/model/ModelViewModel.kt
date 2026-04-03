package dev.brgr.outspoke.settings.model

import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.brgr.outspoke.inference.InferenceService
import dev.brgr.outspoke.settings.preferences.AppPreferences
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

private const val TAG = "ModelViewModel"

/**
 * Manages the download state for every model in [ModelRegistry] and the currently
 * selected active model.
 *
 * Downloads are delegated to [DownloadService], which runs them in a foreground service so
 * they survive screen navigation and app backgrounding.  [DownloadService.downloadStates]
 * is merged into [modelStates] so the UI always reflects the latest progress.
 *
 * [selectedModelId] reflects the model whose engine will be loaded by [InferenceService].
 * Calling [selectModel] persists the choice to [AppPreferences] so it survives restarts.
 */
class ModelViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = AppPreferences(application)

    private val _modelStates = MutableStateFlow(
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

    init {
        // Hydrate the selected model from DataStore on first launch.
        viewModelScope.launch {
            _selectedModelId.value = prefs.selectedModelId.first()
        }

        // Merge live download progress from DownloadService into our state map.
        // Service states take precedence over the static disk-check snapshot for models
        // the service is actively tracking.
        viewModelScope.launch {
            var previousStates = emptyMap<ModelId, ModelState>()
            DownloadService.downloadStates.collect { serviceStates ->
                // Surface an error message on the first Corrupted transition.
                for ((id, state) in serviceStates) {
                    if (state is ModelState.Corrupted && previousStates[id] !is ModelState.Corrupted) {
                        _errorMessage.emit("Download failed for ${ModelRegistry[id].displayName}")
                    }
                }
                previousStates = serviceStates
                _modelStates.update { current -> current + serviceStates }
            }
        }
    }

    /** Starts downloading [modelId] via [DownloadService]. */
    fun startDownload(modelId: ModelId) {
        val context = getApplication<Application>()
        context.startForegroundService(
            Intent(context, DownloadService::class.java).apply {
                action = DownloadService.ACTION_START_DOWNLOAD
                putExtra(DownloadService.EXTRA_MODEL_ID, modelId.name)
            }
        )
        // Optimistically set Downloading(0) so the button changes before the first
        // progress emission arrives from the service.
        _modelStates.update { it + (modelId to ModelState.Downloading(0f)) }
        Log.d(TAG, "Download requested for $modelId")
    }

    /** Cancels an in-progress download for [modelId]. */
    fun cancelDownload(modelId: ModelId) {
        val context = getApplication<Application>()
        context.startService(
            Intent(context, DownloadService::class.java).apply {
                action = DownloadService.ACTION_CANCEL_DOWNLOAD
                putExtra(DownloadService.EXTRA_MODEL_ID, modelId.name)
            }
        )
        _modelStates.update { it + (modelId to ModelState.NotDownloaded) }
        Log.d(TAG, "Download cancelled for $modelId")
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
