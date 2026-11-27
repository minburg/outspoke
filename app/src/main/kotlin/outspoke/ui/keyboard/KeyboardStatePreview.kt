package dev.brgr.outspoke.ui.keyboard

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import dev.brgr.outspoke.ime.EnterAction
import dev.brgr.outspoke.ui.keyboard.components.SUGGESTION_BAR_HEIGHT_DP
import dev.brgr.outspoke.ui.theme.OutspokeKeyboardTheme

/**
 * State matrix for the keyboard strip: renders the real [KeyboardScreen] for every
 * [KeyboardUiState] the ViewModel can emit, in light and dark, so the whole matrix can be
 * eyeballed in Android Studio without a device, a granted microphone or a downloaded model.
 *
 * The per-component previews next to [KeyboardScreen] cover one widget each; this file exists
 * to catch the layout interactions between them (a long error string pushing the trailing keys
 * off the window, the suggestion bar slot fighting the keyboard for height, a status pill that
 * stops fitting once a long message joins its row).
 *
 * Deliberately *not* multiplied across the matrix: `navBarHeightPx` (a pixel value resolved by the
 * service from `WindowManager`, so a fixed preview value would assert nothing), continuous mode and
 * the lock hint (both already previewed in [KeyboardScreen]). `enterAction` is the one axis worth a
 * second look, so it gets its own single preview function over Idle.
 */

/** Phone-class IME window width; the real strip is `fillMaxWidth`, so previews pin one. */
private const val PreviewStripWidthDp = 411

/** Strip height used by the other keyboard previews (suggestion-bar slot excluded). */
private const val PreviewStripHeightDp = 220

/** Detail strings long enough to prove the status pill ellipsises instead of shoving keys off-screen. */
private const val LongPermissionDetail =
    "SecurityException: NetworkLocationProvider requires android.permission.RECORD_AUDIO - grant it in System Settings > Apps > Outspoke > Permissions"
private const val LongMicBusyDetail =
    "AudioRecord.startRecording() returned ERROR_INVALID_OPERATION - another app (Voice Recorder) currently owns the microphone session"
private const val LongMicInitDetail =
    "AudioRecord(state=INVALID, reason=BAD_VALUE) for 16000 Hz mono PCM_16BIT - the audio HAL rejected the requested sample rate"
private const val LongTranscribeDetail =
    "OnnxruntimeException: got \"Invalid dimension\" while running the encoder session silero_vad.onnx on audio frame 412 of 1180"
private const val LongAudioDetail =
    "AudioRecord.read() returned -1 (ERROR_DEAD_OBJECT) 380 ms after startRecording; the capture session was invalidated by the platform"
private const val LongEngineDetail =
    "IOException: failed to open assets/models/sensevoice-small.int8.onnx - read 0 of 23841234 expected bytes, the download was interrupted"

/** One cell of the matrix: a UI state plus the few non-state inputs that change its rendering. */
internal data class KeyboardPreviewCase(
    val name: String,
    val uiState: KeyboardUiState,
    /** Normalised RMS meter level; non-zero only where a live waveform is meaningful. */
    val amplitude: Float = 0f,
    val enterAction: EnterAction = EnterAction.DONE,
    val wordSuggestions: List<String> = emptyList(),
    val suggestionBarDismissed: Boolean = false,
) {
    /** The preview pane labels each rendered cell with this string. */
    override fun toString(): String = name
}

/** Every [KeyboardUiState] variant, including all six [KeyboardUiState.ErrorReason]s. */
internal class KeyboardStatePreviewProvider : PreviewParameterProvider<KeyboardPreviewCase> {
    override val values: Sequence<KeyboardPreviewCase> = sequenceOf(
        KeyboardPreviewCase("Idle", KeyboardUiState.Idle),
        KeyboardPreviewCase("Listening", KeyboardUiState.Listening, amplitude = 0.6f),
        KeyboardPreviewCase("Processing", KeyboardUiState.Processing("some partial text"), amplitude = 0.3f),
        KeyboardPreviewCase("Transcribing", KeyboardUiState.Transcribing),
        KeyboardPreviewCase("NoSpeech", KeyboardUiState.NoSpeech),
        KeyboardPreviewCase(
            "EngineLoading(ModelNotDownloaded)",
            KeyboardUiState.EngineLoading(KeyboardUiState.LoadingReason.ModelNotDownloaded),
        ),
        KeyboardPreviewCase(
            "EngineLoading(EngineStarting)",
            KeyboardUiState.EngineLoading(KeyboardUiState.LoadingReason.EngineStarting),
        ),
        errorCase("Error(MicPermissionDenied)", KeyboardUiState.ErrorReason.MicPermissionDenied, LongPermissionDetail),
        errorCase("Error(MicBusy)", KeyboardUiState.ErrorReason.MicBusy, LongMicBusyDetail),
        errorCase("Error(MicInitFailed)", KeyboardUiState.ErrorReason.MicInitFailed, LongMicInitDetail),
        errorCase(
            "Error(TranscriptionFailed)",
            KeyboardUiState.ErrorReason.TranscriptionFailed,
            LongTranscribeDetail,
        ),
        errorCase("Error(AudioCaptureFailed)", KeyboardUiState.ErrorReason.AudioCaptureFailed, LongAudioDetail),
        errorCase("Error(EngineLoadFailed)", KeyboardUiState.ErrorReason.EngineLoadFailed, LongEngineDetail),
    )

    private fun errorCase(
        name: String,
        reason: KeyboardUiState.ErrorReason,
        detail: String,
    ) = KeyboardPreviewCase(name, KeyboardUiState.Error(reason, detail))
}

/**
 * Suggestion-bar combinations. `wordSuggestions` is a separate [KeyboardScreen] parameter (it
 * comes from `viewModel.wordSuggestions`, not from [KeyboardUiState]), so the bar cannot be part
 * of the state provider above and gets its own preview function instead.
 */
internal class KeyboardSuggestionBarPreviewProvider : PreviewParameterProvider<KeyboardPreviewCase> {
    override val values: Sequence<KeyboardPreviewCase> = sequenceOf(
        KeyboardPreviewCase(
            "Idle · suggestions visible",
            KeyboardUiState.Idle,
            wordSuggestions = listOf("hello", "hallo", "hollow", "hell oh", "hellos"),
        ),
        KeyboardPreviewCase(
            "Idle · suggestions dismissed",
            KeyboardUiState.Idle,
            wordSuggestions = listOf("hello", "hallo", "hollow"),
            suggestionBarDismissed = true,
        ),
        KeyboardPreviewCase(
            "Processing · suggestions visible",
            KeyboardUiState.Processing("some partial text"),
            amplitude = 0.3f,
            wordSuggestions = listOf("some", "sum", "somm", "som"),
        ),
    )
}

/** The three Enter actions that change the centre button's glyph; GO/NEXT/DONE render like DONE. */
internal class KeyboardEnterActionPreviewProvider : PreviewParameterProvider<KeyboardPreviewCase> {
    override val values: Sequence<KeyboardPreviewCase> =
        listOf(EnterAction.SEND, EnterAction.SEARCH, EnterAction.NEWLINE)
            .asSequence()
            .map { KeyboardPreviewCase("Idle · enterAction=$it", KeyboardUiState.Idle, enterAction = it) }
}

@Composable
private fun KeyboardStatePreviewScaffold(case: KeyboardPreviewCase) {
    // The bar reserves its own slot above the keyboard, so the preview window grows by exactly
    // the amount the IME window would; otherwise the bar clips the status row.
    val barVisible = case.wordSuggestions.isNotEmpty() && !case.suggestionBarDismissed
    val stripHeightDp = PreviewStripHeightDp + if (barVisible) SUGGESTION_BAR_HEIGHT_DP else 0
    OutspokeKeyboardTheme {
        Box(modifier = Modifier.width(PreviewStripWidthDp.dp).height(stripHeightDp.dp)) {
            KeyboardScreen(
                uiState = case.uiState,
                amplitude = case.amplitude,
                isContinuous = false,
                triggerMode = "HOLD",
                onRecordStart = {},
                onRecordStop = {},
                onContinuousModeEnabled = {},
                // The service always passes the ViewModel's retry; StatusIndicator still shows
                // the action only for the transient reasons, which is what we want to see here.
                onRetry = {},
                onDeleteChar = {},
                onDeleteWord = {},
                onDeleteAll = {},
                onEnterAction = {},
                enterAction = case.enterAction,
                onSwitchKeyboard = {},
                onOpenCompanionApp = {},
                previewForceLockHint = false,
                wordSuggestions = case.wordSuggestions,
                suggestionBarDismissed = case.suggestionBarDismissed,
            )
        }
    }
}

@Preview(
    name = "Keyboard state · light",
    showBackground = true,
    backgroundColor = 0xFFF3F6F5,
    widthDp = PreviewStripWidthDp,
    heightDp = PreviewStripHeightDp,
)
@Preview(
    name = "Keyboard state · dark",
    showBackground = true,
    backgroundColor = 0xFF111111,
    uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_NIGHT_UNDEFINED,
    widthDp = PreviewStripWidthDp,
    heightDp = PreviewStripHeightDp,
)
@Composable
private fun KeyboardStateMatrixPreview(@PreviewParameter(KeyboardStatePreviewProvider::class) case: KeyboardPreviewCase) {
    KeyboardStatePreviewScaffold(case)
}

@Preview(
    name = "Keyboard state + suggestion bar · light",
    showBackground = true,
    backgroundColor = 0xFFF3F6F5,
    widthDp = PreviewStripWidthDp,
)
@Preview(
    name = "Keyboard state + suggestion bar · dark",
    showBackground = true,
    backgroundColor = 0xFF111111,
    uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_NIGHT_UNDEFINED,
    widthDp = PreviewStripWidthDp,
)
@Composable
private fun KeyboardStateSuggestionBarPreview(
    @PreviewParameter(KeyboardSuggestionBarPreviewProvider::class) case: KeyboardPreviewCase,
) {
    KeyboardStatePreviewScaffold(case)
}

@Preview(
    name = "Keyboard state · enter action light",
    showBackground = true,
    backgroundColor = 0xFFF3F6F5,
    widthDp = PreviewStripWidthDp,
    heightDp = PreviewStripHeightDp,
)
@Preview(
    name = "Keyboard state · enter action dark",
    showBackground = true,
    backgroundColor = 0xFF111111,
    uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_NIGHT_UNDEFINED,
    widthDp = PreviewStripWidthDp,
    heightDp = PreviewStripHeightDp,
)
@Composable
private fun KeyboardStateEnterActionPreview(
    @PreviewParameter(KeyboardEnterActionPreviewProvider::class) case: KeyboardPreviewCase,
) {
    KeyboardStatePreviewScaffold(case)
}
