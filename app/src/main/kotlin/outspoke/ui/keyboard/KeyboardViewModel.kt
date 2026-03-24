package dev.brgr.outspoke.ui.keyboard

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.brgr.outspoke.audio.AudioCaptureManager
import dev.brgr.outspoke.ime.TextInjector
import dev.brgr.outspoke.inference.EngineState
import dev.brgr.outspoke.inference.InferenceRepository
import dev.brgr.outspoke.inference.TranscriptResult
import dev.brgr.outspoke.settings.preferences.AppPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val TAG = "KeyboardViewModel"

/**
 * Bridges the IME lifecycle, audio capture, and inference pipeline into a stream of
 * [KeyboardUiState] values consumed by [KeyboardScreen].
 */
class KeyboardViewModel(
    private val audioCaptureManager: AudioCaptureManager,
    private val appPreferences: AppPreferences,
) : ViewModel() {

    private val _uiState = MutableStateFlow<KeyboardUiState>(
        KeyboardUiState.EngineLoading("Loading transcription engine…")
    )
    val uiState: StateFlow<KeyboardUiState> = _uiState.asStateFlow()

    /** Normalised RMS amplitude [0.0, 1.0] — updated by [AudioCaptureManager] per chunk. */
    val amplitude: StateFlow<Float> = audioCaptureManager.amplitude

    // -------------------------------------------------------------------------
    // Trigger mode (Step 31)
    // -------------------------------------------------------------------------

    /**
     * `"HOLD"` (default) or `"TAP_TOGGLE"`.
     * Collected eagerly so the TalkButton always has the latest value without
     * needing a suspend context.
     */
    val triggerMode: StateFlow<String> = appPreferences.triggerMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, "HOLD")

    // -------------------------------------------------------------------------
    // Engine state (Step 29)
    // -------------------------------------------------------------------------

    private val _engineState = MutableStateFlow<EngineState>(EngineState.Unloaded)

    /** Called by [OutspokeInputMethodService] whenever [InferenceService.engineState] changes. */
    fun setEngineState(state: EngineState) {
        _engineState.value = state
        when (state) {
            EngineState.Unloaded -> _uiState.value = KeyboardUiState.EngineLoading(
                "Model not downloaded — open Outspoke to download"
            )
            EngineState.Loading -> _uiState.value = KeyboardUiState.EngineLoading(
                "Loading transcription engine…"
            )
            EngineState.Ready -> {
                // Clear any engine-driven blocking state so the user can start recording.
                if (_uiState.value is KeyboardUiState.EngineLoading) {
                    _uiState.value = KeyboardUiState.Idle
                }
            }
            is EngineState.Error -> _uiState.value = KeyboardUiState.Error(
                "Engine failed to load: ${state.message}"
            )
        }
    }

    // -------------------------------------------------------------------------
    // Inference repository (Step 28)
    // -------------------------------------------------------------------------

    private var inferenceRepository: InferenceRepository? = null

    /** Set by [OutspokeInputMethodService] when the service binding is established. */
    fun setInferenceRepository(repo: InferenceRepository?) {
        inferenceRepository = repo
    }

    // -------------------------------------------------------------------------
    // Text injection
    // -------------------------------------------------------------------------

    private var textInjector: TextInjector? = null

    fun setTextInjector(injector: TextInjector?) {
        textInjector = injector
    }

    // -------------------------------------------------------------------------
    // Continuous-mode flag
    // -------------------------------------------------------------------------

    private val _isContinuousMode = MutableStateFlow(false)

    /**
     * `true` while the keyboard is in locked continuous-recording mode (engaged by
     * dragging the talk button upward).  Resets to `false` whenever recording stops.
     */
    val isContinuousMode: StateFlow<Boolean> = _isContinuousMode.asStateFlow()

    /**
     * Called by the UI when the user drags the talk button past the lock threshold.
     * Recording continues uninterrupted — only the visual state changes.
     */
    fun onContinuousModeEnabled() {
        _isContinuousMode.value = true
    }

    // -------------------------------------------------------------------------
    // Delete helpers — delegate to TextInjector
    // -------------------------------------------------------------------------

    /** Delete the character immediately before the cursor. */
    fun deleteChar() {
        textInjector?.deleteChar()
    }

    /** Delete backward to the previous word boundary. */
    fun deleteWord() {
        textInjector?.deleteWord()
    }

    /** Delete all text in the current editor field. */
    fun deleteAll() {
        textInjector?.deleteAll()
    }

    // -------------------------------------------------------------------------
    // Audio capture → inference (Step 28)
    // -------------------------------------------------------------------------

    private var captureJob: Job? = null

    /**
     * Start microphone capture and pipe audio through the inference engine.
     * Ignored if the engine is not yet [EngineState.Ready].
     */
    fun onRecordStart() {
        if (_engineState.value !is EngineState.Ready) {
            Log.w(TAG, "onRecordStart() ignored — engine not ready (${_engineState.value})")
            return
        }

        captureJob?.cancel()
        _uiState.value = KeyboardUiState.Listening

        captureJob = viewModelScope.launch {
            try {
                val repo = inferenceRepository
                if (repo == null) {
                    // Inference repo not yet bound — capture for amplitude feedback only.
                    Log.w(TAG, "No InferenceRepository — capturing audio without transcription")
                    audioCaptureManager.startCapture().collect { /* amplitude updated internally */ }
                    // Flow completed naturally (stopCapture called); no inference to wait for.
                    _uiState.value = KeyboardUiState.Idle
                    return@launch
                }

                // Pipe audio through the inference engine on Dispatchers.Default.
                // TranscriptResult emissions drive both the UI and text injection.
                repo.transcribe(audioCaptureManager.startCapture())
                    .collect { result ->
                        when (result) {
                            is TranscriptResult.Partial -> {
                                _uiState.value = KeyboardUiState.Processing(result.text)
                                textInjector?.setPartial(result.text)
                            }
                            is TranscriptResult.Final -> {
                                Log.d(TAG, "Final transcript: \"${result.text}\"")
                                textInjector?.commitFinal(result.text)
                                _isContinuousMode.value = false
                                _uiState.value = KeyboardUiState.Idle
                                captureJob = null
                            }
                            is TranscriptResult.Failure -> {
                                Log.e(TAG, "Transcription failure", result.cause)
                                _isContinuousMode.value = false
                                _uiState.value = KeyboardUiState.Error(
                                    "Transcription failed: ${result.cause.message}"
                                )
                            }
                        }
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SecurityException) {
                Log.e(TAG, "Microphone permission denied", e)
                _isContinuousMode.value = false
                _uiState.value = KeyboardUiState.Error(
                    "Microphone permission denied — open Outspoke to grant it"
                )
            } catch (e: IllegalStateException) {
                Log.e(TAG, "AudioRecord failed to initialise", e)
                _isContinuousMode.value = false
                _uiState.value = KeyboardUiState.Error(
                    "Could not open the microphone: ${e.message}"
                )
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected audio capture error", e)
                _isContinuousMode.value = false
                _uiState.value = KeyboardUiState.Error("Audio capture failed: ${e.message}")
            }
        }
    }

    fun onRecordStop() {
        // Always reset continuous mode when recording stops from any code path.
        _isContinuousMode.value = false
        // Signal natural flow completion — AudioCaptureManager's read loop exits on the next
        // 40 ms cycle, InferenceRepository runs a final inference on the accumulated buffer,
        // and emits TranscriptResult.Final which transitions the UI to Idle and commits text.
        audioCaptureManager.stopCapture()

        // If there is no active capture (engine not ready, double-tap, etc.) go Idle now.
        if (captureJob?.isActive != true) {
            _uiState.value = KeyboardUiState.Idle
        }
    }

    /**
     * Step 30 — called by [dev.brgr.outspoke.ime.OutspokeInputMethodService.onFinishInput].
     *
     * Commits any in-progress composing (partial) text as final, then cancels the capture job
     * immediately. Must be called **before** [setTextInjector] is set to null so that the
     * [android.view.inputmethod.InputConnection] is still valid when we write the final text.
     */
    fun commitPartialAndStop() {
        val currentState = _uiState.value
        if (currentState is KeyboardUiState.Processing && currentState.partial.isNotEmpty()) {
            // Commit the last partial transcript so no text is lost on app-switch.
            textInjector?.commitFinal(currentState.partial)
        } else {
            // Remove any composing span left in the editor without committing.
            textInjector?.clear()
        }
        // Cancel the capture coroutine immediately — don't wait for the audio loop to drain.
        captureJob?.cancel()
        captureJob = null
        _isContinuousMode.value = false
        audioCaptureManager.stopCapture()
        _uiState.value = KeyboardUiState.Idle
    }

    override fun onCleared() {
        super.onCleared()
        captureJob?.cancel()
        _isContinuousMode.value = false
        textInjector = null
        inferenceRepository = null
    }

    // -------------------------------------------------------------------------
    // Factory
    // -------------------------------------------------------------------------

    class Factory(
        private val audioCaptureManager: AudioCaptureManager,
        private val appPreferences: AppPreferences,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            KeyboardViewModel(audioCaptureManager, appPreferences) as T
    }
}
