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
import dev.brgr.outspoke.settings.model.ModelId
import dev.brgr.outspoke.settings.preferences.AppPreferences
import dev.brgr.outspoke.ui.keyboard.components.WHISPER_LANGUAGE_OPTIONS
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
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

    /**
     * VAD sensitivity in [0.0, 1.0]. Collected eagerly so the value is always available
     * as a snapshot when recording starts — no suspend context required.
     */
    val vadSensitivity: StateFlow<Float> = appPreferences.vadSensitivity
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0f)

    // -------------------------------------------------------------------------
    // Whisper language selection
    // -------------------------------------------------------------------------

    /**
     * `true` when the currently selected model is a Whisper variant.
     * Used to conditionally show the language selector in the keyboard UI.
     */
    val isWhisperEngine: StateFlow<Boolean> = appPreferences.selectedModelId
        .map { it.name.startsWith("WHISPER") }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * Currently selected Whisper language tag: `"auto"`, `"en"`, `"de"`, or `"nl"`.
     * Collected eagerly so the selector always reflects the saved value on first draw.
     */
    val whisperLanguage: StateFlow<String> = appPreferences.whisperLanguage
        .stateIn(viewModelScope, SharingStarted.Eagerly, "auto")

    /**
     * Persists [tag] to preferences and immediately forwards it to the loaded engine.
     * Safe to call at any time — the engine's [setLanguage] is thread-safe.
     */
    fun setWhisperLanguage(tag: String) {
        inferenceRepository?.setLanguage(tag)
        viewModelScope.launch { appPreferences.setWhisperLanguage(tag) }
    }

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
        // Apply the persisted language tag immediately.
        repo?.setLanguage(whisperLanguage.value)
        // Constrain auto-detection to only the languages shown in the selector.
        // "Auto" then means "detect from EN/DE/ES" instead of "any of ~100 languages",
        // which eliminates false picks like ES (50262) beating EN (50259) by a tiny margin.
        repo?.setLanguageConstraints(
            WHISPER_LANGUAGE_OPTIONS.filter { (tag, _) -> tag != "auto" }.map { (tag, _) -> tag }
        )
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

    /** Insert a newline at the current cursor position, replacing any active selection. */
    fun newline() {
        textInjector?.sendNewline()
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
                    audioCaptureManager.startCapture(vadSensitivity = vadSensitivity.value * 0.4f)
                        .collect { /* amplitude updated internally */ }
                    // Flow completed naturally (stopCapture called); no inference to wait for.
                    _uiState.value = KeyboardUiState.Idle
                    return@launch
                }

                // Pipe audio through the inference engine on Dispatchers.Default.
                // TranscriptResult emissions drive both the UI and text injection.
                repo.transcribe(audioCaptureManager.startCapture(vadSensitivity = vadSensitivity.value * 0.4f))
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

        // Stop the microphone — this terminates the upstream audio flow, which causes
        // InferenceRepository to finish collecting audio and run its definitive final
        // inference pass.  We intentionally do NOT cancel captureJob here: slow engines
        // like Whisper can take 10-20 s to produce the Final result after audio ends,
        // and cancelling the job before it is collected means commitFinal() is never
        // called and no text is injected.  The job self-terminates once it collects
        // TranscriptResult.Final (or Failure).
        audioCaptureManager.stopCapture()

        // Switch to Transcribing so the UI clearly signals "mic off, engine still working".
        // The collect loop in onRecordStart() will transition back to Idle (and commit
        // the text) once Final arrives.
        if (_uiState.value is KeyboardUiState.Listening ||
            _uiState.value is KeyboardUiState.Processing) {
            _uiState.value = KeyboardUiState.Transcribing
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
            // Transcribing (no partial yet) or any other state — remove any composing span
            // without committing. If the engine was still running its final pass it will be
            // cancelled; that partial audio is lost, which is acceptable on input-field change.
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
