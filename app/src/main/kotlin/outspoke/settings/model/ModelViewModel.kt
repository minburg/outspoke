package dev.brgr.outspoke.settings.model

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "ModelViewModel"

class ModelViewModel(application: Application) : AndroidViewModel(application) {

    private val downloadManager = ModelDownloadManager()

    private val _modelState = MutableStateFlow<ModelState>(
        // Immediately reflect the on-disk state so the UI is correct on first render.
        if (ModelStorageManager.isModelReady(application)) ModelState.Ready
        else ModelState.NotDownloaded,
    )
    val modelState: StateFlow<ModelState> = _modelState.asStateFlow()

    /** One-shot error messages intended to be shown in a Snackbar. */
    private val _errorMessage = MutableSharedFlow<String>(replay = 0)
    val errorMessage: SharedFlow<String> = _errorMessage.asSharedFlow()

    private var downloadJob: Job? = null

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    fun startDownload() {
        if (_modelState.value is ModelState.Downloading) return // Already running

        downloadJob?.cancel()
        downloadJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                downloadManager.download(getApplication()).collect { state ->
                    _modelState.value = state
                }
            } catch (e: Exception) {
                Log.e(TAG, "Model download failed", e)
                _modelState.value = ModelState.NotDownloaded
                _errorMessage.emit("Download failed: ${e.localizedMessage ?: "Unknown error"}")
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        _modelState.value = ModelState.NotDownloaded
    }

    fun deleteModel() {
        ModelStorageManager.deleteModel(getApplication())
        _modelState.value = ModelState.NotDownloaded
        Log.d(TAG, "Model deleted from internal storage")
    }
}

