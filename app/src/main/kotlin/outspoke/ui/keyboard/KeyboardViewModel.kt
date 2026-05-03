package dev.brgr.outspoke.ui.keyboard

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.brgr.outspoke.audio.AudioCaptureManager
import dev.brgr.outspoke.ime.EnterAction
import dev.brgr.outspoke.ime.TextInjector
import dev.brgr.outspoke.inference.EngineState
import dev.brgr.outspoke.inference.InferenceRepository
import dev.brgr.outspoke.inference.PipelineDiagnostics
import dev.brgr.outspoke.inference.TranscriptResult
import dev.brgr.outspoke.settings.preferences.AppPreferences
import dev.brgr.outspoke.ui.keyboard.components.WHISPER_LANGUAGE_OPTIONS
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
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
        KeyboardUiState.EngineLoading(KeyboardUiState.LoadingReason.EngineStarting)
    )
    val uiState: StateFlow<KeyboardUiState> = _uiState.asStateFlow()

    /** Normalised RMS amplitude [0.0, 1.0] - updated by [AudioCaptureManager] per chunk. */
    val amplitude: StateFlow<Float> = audioCaptureManager.amplitude

    /**
     * `"HOLD"` (default) or `"TAP_TOGGLE"`.
     * Collected eagerly so the TalkButton always has the latest value without
     * needing a suspend context.
     */
    val triggerMode: StateFlow<String> = appPreferences.triggerMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, "HOLD")

    /**
     * VAD sensitivity in [0.0, 1.0]. Collected eagerly so the value is always available
     * as a snapshot when recording starts - no suspend context required.
     */
    val vadSensitivity: StateFlow<Float> = appPreferences.vadSensitivity
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0f)

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
     * `true` when the transcript post-processing pipeline (filler removal, stutter collapse,
     * repetition deduplication, capitalisation) is active.  Defaults to `true`.
     * Collected eagerly so the live value is always available when recording starts.
     */
    val postprocessingEnabled: StateFlow<Boolean> = appPreferences.postprocessingEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /**
     * Pipeline diagnostic counters for the current or most recent recording session.
     *
     * Updated live during recording and kept visible after the session ends so the user
     * can glance at the keyboard status bar to see if anything unusual happened (trims,
     * alignment recoveries, blanks discarded) without needing logcat.
     *
     * Reset at the start of each new recording in [onRecordStart].
     */
    private val _diagnostics = MutableStateFlow(PipelineDiagnostics())
    val diagnostics: StateFlow<PipelineDiagnostics> = _diagnostics.asStateFlow()

    /**
     * Whether the pipeline diagnostics badge is visible on the keyboard.
     * Collected eagerly so the UI always reflects the saved preference on first draw.
     */
    val showPipelineDiagnostics: StateFlow<Boolean> = appPreferences.showPipelineDiagnostics
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * **Debug builds only.** When `true`, the pipeline writes WAV files so the engineer
     * can listen to what the model hears. Collected eagerly so the value is always
     * available as a snapshot when recording starts.
     */
    val debugAudioDumpEnabled: StateFlow<Boolean> = appPreferences.debugAudioDumpEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * Persists [tag] to preferences and immediately forwards it to the loaded engine.
     * Safe to call at any time - the engine's [setLanguage] is thread-safe.
     */
    fun setWhisperLanguage(tag: String) {
        inferenceRepository?.setLanguage(tag)
        viewModelScope.launch { appPreferences.setWhisperLanguage(tag) }
    }

    /**
     * Whether the first-run keyboard tutorial should currently be visible.
     * Resolves to `true` on the very first keyboard opening and `false` permanently
     * after [dismissTutorial] is called.
     */
    val showTutorial: StateFlow<Boolean> = appPreferences.keyboardTutorialShown
        .map { shown -> !shown }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Persists the tutorial as seen so it is never shown again. */
    fun dismissTutorial() {
        viewModelScope.launch { appPreferences.setKeyboardTutorialShown(true) }
    }

    private val _engineState = MutableStateFlow<EngineState>(EngineState.Unloaded)

    /** Called by [OutspokeInputMethodService] whenever [InferenceService.engineState] changes. */
    fun setEngineState(state: EngineState) {
        _engineState.value = state
        when (state) {
            EngineState.Unloaded -> _uiState.value = KeyboardUiState.EngineLoading(
                KeyboardUiState.LoadingReason.ModelNotDownloaded
            )

            EngineState.Loading -> _uiState.value = KeyboardUiState.EngineLoading(
                KeyboardUiState.LoadingReason.EngineStarting
            )

            EngineState.Ready -> {
                // Clear any engine-driven blocking state so the user can start recording.
                if (_uiState.value is KeyboardUiState.EngineLoading) {
                    _uiState.value = KeyboardUiState.Idle
                }
            }

            is EngineState.Error -> _uiState.value = KeyboardUiState.Error(
                reason = KeyboardUiState.ErrorReason.EngineLoadFailed,
                detail = state.message,
            )
        }
    }

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

    private var textInjector: TextInjector? = null

    private val _enterAction = MutableStateFlow(EnterAction.DONE)

    /**
     * The context-aware action the Enter key should perform for the currently focused editor.
     * Updated each time [setTextInjector] is called (i.e. on every [onStartInput]).
     */
    val enterAction: StateFlow<EnterAction> = _enterAction.asStateFlow()

    fun setTextInjector(injector: TextInjector?) {
        textInjector = injector
        _enterAction.value = injector?.enterAction ?: EnterAction.DONE
    }

    private val _isContinuousMode = MutableStateFlow(false)

    /**
     * `true` while the keyboard is in locked continuous-recording mode (engaged by
     * dragging the talk button upward).  Resets to `false` whenever recording stops.
     */
    val isContinuousMode: StateFlow<Boolean> = _isContinuousMode.asStateFlow()

    /**
     * Called by the UI when the user drags the talk button past the lock threshold.
     * Recording continues uninterrupted - only the visual state changes.
     */
    fun onContinuousModeEnabled() {
        _isContinuousMode.value = true
    }

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

    /**
     * Perform the context-aware Enter action for the currently focused editor.
     * Inserts a newline for multi-line fields; otherwise triggers the editor's IME action
     * (search, send, go, done, next) via [InputConnection.performEditorAction].
     */
    fun performEnterAction() {
        textInjector?.performEnterAction()
    }

    private var captureJob: Job? = null

    /**
     * Start microphone capture and pipe audio through the inference engine.
     * Ignored if the engine is not yet [EngineState.Ready].
     */
    fun onRecordStart() {
        if (_engineState.value !is EngineState.Ready) {
            Log.w(TAG, "onRecordStart() ignored - engine not ready (${_engineState.value})")
            return
        }

        captureJob?.cancel()
        _uiState.value = KeyboardUiState.Listening
        _diagnostics.value = PipelineDiagnostics()

        captureJob = viewModelScope.launch {
            // Capture this coroutine's Job reference so the collect lambda can detect
            // whether it belongs to the currently active session.  If onRecordStart() is
            // called again (e.g. from onFieldCleared after a "Send"), captureJob is
            // replaced with the new job; any collect callbacks still queued from the OLD
            // job see captureJob != myJob and return immediately - preventing stale
            // partial results from bleeding into the fresh TextInjector state.
            val myJob = coroutineContext[Job]

            try {
                val repo = inferenceRepository
                if (repo == null) {
                    // Inference repo not yet bound - capture for amplitude feedback only.
                    Log.w(TAG, "No InferenceRepository - capturing audio without transcription")
                    audioCaptureManager.startCapture(
                        vadSensitivity = vadSensitivity.value * 0.4f,
                        debugAudioDumpEnabled = debugAudioDumpEnabled.value,
                    ).collect { /* amplitude updated internally */ }
                    // Flow completed naturally (stopCapture called); no inference to wait for.
                    _uiState.value = KeyboardUiState.Idle
                    return@launch
                }

                // Pipe audio through the inference engine on Dispatchers.Default.
                // TranscriptResult emissions drive both the UI and text injection.
                repo.transcribe(
                    audio = audioCaptureManager.startCapture(
                        vadSensitivity = vadSensitivity.value * 0.4f,
                        debugAudioDumpEnabled = debugAudioDumpEnabled.value,
                    ),
                    postprocessingEnabled = postprocessingEnabled.value,
                    debugAudioDumper = if (debugAudioDumpEnabled.value) audioCaptureManager.debugAudioDumper else null,
                ).collect { result ->
                        // Stale-session guard: if this job has been superseded (e.g. the field
                        // was cleared and a new session started), discard this result entirely
                        // so no old-session text is injected into the fresh TextInjector.
                        if (captureJob != null && captureJob != myJob) return@collect

                        when (result) {
                            is TranscriptResult.Partial -> {
                                _uiState.value = KeyboardUiState.Processing(result.text)
                                textInjector?.setPartial(result.text)
                                // Update alignment recovery counter from the injector.
                                val injector = textInjector
                                if (injector != null) {
                                    val d = _diagnostics.value
                                    if (injector.alignmentRecoveryCount > d.alignmentRecoveries) {
                                        _diagnostics.value = d.copy(alignmentRecoveries = injector.alignmentRecoveryCount)
                                    }
                                }
                            }

                            is TranscriptResult.Final -> {
                                Log.d(TAG, "Final transcript: \"${result.text}\"" +
                                        if (result.isUtteranceBoundary) " [utterance boundary]" else "")
                                textInjector?.commitFinal(result.text)
                                if (!result.isUtteranceBoundary) {
                                    _isContinuousMode.value = false
                                    _uiState.value = KeyboardUiState.Idle
                                    captureJob = null
                                }
                            }

                            is TranscriptResult.Failure -> {
                                Log.e(TAG, "Transcription failure", result.cause)
                                _isContinuousMode.value = false
                                _uiState.value = KeyboardUiState.Error(
                                    reason = KeyboardUiState.ErrorReason.TranscriptionFailed,
                                    detail = result.cause.message,
                                )
                            }

                            // The audio window was trimmed: shrink committedWords so the
                            // next partial can re-anchor without losing middle sentences.
                            is TranscriptResult.WindowTrimmed -> {
                                Log.d(TAG, "WindowTrimmed - resetting TextInjector alignment" +
                                        if (result.stableWords.isNotEmpty()) " (stableWords=${result.stableWords.size}w)" else "")
                                textInjector?.resetAfterTrim(result.stableWords)
                                _diagnostics.value = _diagnostics.value.copy(
                                    windowTrims = _diagnostics.value.windowTrims + 1
                                )
                            }
                        }
                    }

                // The flow completed normally. If still in Transcribing (or Listening), it
                // means VAD filtered out all audio (nothing was said) so InferenceRepository
                // emitted no Final result. Reset to Idle so the button becomes usable again.
                if (_uiState.value == KeyboardUiState.Transcribing ||
                    _uiState.value == KeyboardUiState.Listening
                ) {
                    Log.d(TAG, "Transcription flow ended with no Final result - resetting to Idle")
                    _isContinuousMode.value = false
                    _uiState.value = KeyboardUiState.Idle
                    captureJob = null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SecurityException) {
                Log.e(TAG, "Microphone permission denied", e)
                _isContinuousMode.value = false
                _uiState.value = KeyboardUiState.Error(
                    reason = KeyboardUiState.ErrorReason.MicPermissionDenied,
                )
            } catch (e: IllegalStateException) {
                Log.e(TAG, "AudioRecord failed to initialise", e)
                _isContinuousMode.value = false
                _uiState.value = KeyboardUiState.Error(
                    reason = KeyboardUiState.ErrorReason.MicInitFailed,
                    detail = e.message,
                )
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected audio capture error", e)
                _isContinuousMode.value = false
                _uiState.value = KeyboardUiState.Error(
                    reason = KeyboardUiState.ErrorReason.AudioCaptureFailed,
                    detail = e.message,
                )
            }
        }
    }

    fun onRecordStop() {
        // Always reset continuous mode when recording stops from any code path.
        _isContinuousMode.value = false

        // Stop the microphone - this terminates the upstream audio flow, which causes
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
            _uiState.value is KeyboardUiState.Processing
        ) {
            _uiState.value = KeyboardUiState.Transcribing
        }
    }

    /**
     * Called when the focused text field is cleared externally - typically because the user
     * pressed "Send" in a messaging app and the app cleared the [EditText] content.
     *
     * Resets [TextInjector] session state so the next recording does not try to align
     * against stale committed-word tracking that no longer matches the now-empty field.
     *
     * If a recording is actively in progress ([KeyboardUiState.Listening] /
     * [KeyboardUiState.Processing]), the capture job is cancelled and immediately restarted
     * with a fresh audio window - so the user continues dictating from a clean slate without
     * needing to toggle the talk button.  [isContinuousMode] is intentionally preserved so
     * the button stays active in continuous mode across the field-clear event.
     *
     * If the engine is still running its final inference pass ([KeyboardUiState.Transcribing]),
     * the job is cancelled (the result is no longer useful for the emptied field) and the
     * UI resets to [KeyboardUiState.Idle].
     */
    fun onFieldCleared() {
        Log.d(TAG, "onFieldCleared - resetting TextInjector and restarting audio if recording")

        // Always clear TextInjector state - stale committedWords would cause alignment
        // failures and potential duplication on the very next partial injection.
        textInjector?.clear()

        val isActivelyRecording = _uiState.value is KeyboardUiState.Listening ||
                _uiState.value is KeyboardUiState.Processing
        val hasInferenceRunning = _uiState.value is KeyboardUiState.Transcribing

        when {
            isActivelyRecording -> {
                // Explicitly stop the current audio capture so the old AudioRecord
                // stops feeding chunks into the channel buffer immediately - without
                // this, the old capture can keep producing audio for up to one read
                // cycle (~30 ms) after captureJob.cancel(), and those samples would
                // end up in the new session's rolling window via the Channel.UNLIMITED
                // buffer if the old flow hadn't been cancelled yet.
                audioCaptureManager.stopCapture()
                // onRecordStart() cancels the existing captureJob internally, which
                // discards the rolling audio window, and starts a fresh one.
                // _isContinuousMode is NOT touched so the button stays active.
                onRecordStart()
            }

            hasInferenceRunning -> {
                // Mic is already off but the final inference is still running.
                // The result is no longer needed (field was just cleared), so cancel it.
                captureJob?.cancel()
                captureJob = null
                _isContinuousMode.value = false
                _uiState.value = KeyboardUiState.Idle
            }
            // Idle / Error / Loading - TextInjector clear above is sufficient.
        }
    }

    /**
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
            // Transcribing (no partial yet) or any other state - remove any composing span
            // without committing. If the engine was still running its final pass it will be
            // cancelled; that partial audio is lost, which is acceptable on input-field change.
            textInjector?.clear()
        }
        // Cancel the capture coroutine immediately - don't wait for the audio loop to drain.
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

    class Factory(
        private val audioCaptureManager: AudioCaptureManager,
        private val appPreferences: AppPreferences,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            KeyboardViewModel(audioCaptureManager, appPreferences) as T
    }
}
