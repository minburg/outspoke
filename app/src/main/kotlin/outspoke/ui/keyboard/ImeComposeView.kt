package dev.brgr.outspoke.ui.keyboard

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.doOnAttach
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistryOwner

// Fixed Imports (Kotlin Extension Functions)
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dev.brgr.outspoke.ui.theme.OutspokeKeyboardTheme

/**
 * An [AbstractComposeView] that installs the lifecycle, ViewModel store, and saved-state
 * tree owners that Compose requires but an [InputMethodService] does not provide by default.
 *
 * The service implements all three owner interfaces and passes itself in; this view wires
 * them onto the view tree so that Compose internals (recomposer, viewModel(), etc.) can
 * locate them via [ViewTreeLifecycleOwner] / [ViewTreeViewModelStoreOwner] /
 * [ViewTreeSavedStateRegistryOwner].
 */
class ImeComposeView(
    context: Context,
    lifecycleOwner: LifecycleOwner,
    viewModelStoreOwner: ViewModelStoreOwner,
    savedStateRegistryOwner: SavedStateRegistryOwner,
) : AbstractComposeView(context) {

    // Backed by mutableStateOf so that a content swap (unlikely but possible)
    // triggers recomposition without needing to detach/reattach the view.
    private var content: (@Composable () -> Unit) by mutableStateOf({})

    init {
        // Updated to use the Kotlin extension functions
        setViewTreeLifecycleOwner(lifecycleOwner)
        setViewTreeViewModelStoreOwner(viewModelStoreOwner)
        setViewTreeSavedStateRegistryOwner(savedStateRegistryOwner)
        // Once the view is attached to the IME window, request a fresh inset dispatch so
        // that Compose's LocalWindowInsets (and hence navigationBarsPadding()) is populated.
        doOnAttach { ViewCompat.requestApplyInsets(this) }
    }

    /** Replace the hosted composable content. Safe to call before or after attachment. */
    fun setKeyboardContent(newContent: @Composable () -> Unit) {
        content = newContent
    }

    @Composable
    override fun Content() {
        content()
    }
}

// -------------------------------------------------------------------------------------------------
// Previews — render KeyboardScreen directly (ImeComposeView.Content() is a passthrough lambda)
// -------------------------------------------------------------------------------------------------

@Composable
private fun ImePreviewScaffold(
    uiState: KeyboardUiState,
    amplitude: Float = 0f,
    isContinuous: Boolean = false,
    isWhisperEngine: Boolean = false,
    whisperLanguage: String = "auto",
) {
    OutspokeKeyboardTheme {
        Box(modifier = Modifier.height(220.dp)) {
            KeyboardScreen(
                uiState = uiState,
                amplitude = amplitude,
                isContinuous = isContinuous,
                triggerMode = "HOLD",
                isWhisperEngine = isWhisperEngine,
                whisperLanguage = whisperLanguage,
                onWhisperLanguageSelected = {},
                onRecordStart = {},
                onRecordStop = {},
                onContinuousModeEnabled = {},
                onDeleteChar = {},
                onDeleteWord = {},
                onDeleteAll = {},
                onNewline = {},
                onSwitchKeyboard = {},
                onOpenCompanionApp = {},
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF111111, name = "IME · Idle")
@Composable
private fun ImeIdlePreview() {
    ImePreviewScaffold(uiState = KeyboardUiState.Idle)
}

@Preview(showBackground = true, backgroundColor = 0xFF111111, name = "IME · Listening")
@Composable
private fun ImeListeningPreview() {
    ImePreviewScaffold(uiState = KeyboardUiState.Listening, amplitude = 0.65f)
}

@Preview(showBackground = true, backgroundColor = 0xFF111111, name = "IME · Continuous")
@Composable
private fun ImeContinuousPreview() {
    ImePreviewScaffold(uiState = KeyboardUiState.Listening, amplitude = 0.4f, isContinuous = true)
}

@Preview(showBackground = true, backgroundColor = 0xFF111111, name = "IME · Processing")
@Composable
private fun ImeProcessingPreview() {
    ImePreviewScaffold(uiState = KeyboardUiState.Processing("Hello world…"))
}

@Preview(showBackground = true, backgroundColor = 0xFF111111, name = "IME · Transcribing")
@Composable
private fun ImeTranscribingPreview() {
    ImePreviewScaffold(uiState = KeyboardUiState.Transcribing)
}

@Preview(showBackground = true, backgroundColor = 0xFF111111, name = "IME · Whisper + Language Bar")
@Composable
private fun ImeWhisperPreview() {
    ImePreviewScaffold(
        uiState = KeyboardUiState.Idle,
        isWhisperEngine = true,
        whisperLanguage = "de",
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF111111, name = "IME · Engine Loading")
@Composable
private fun ImeEngineLoadingPreview() {
    ImePreviewScaffold(uiState = KeyboardUiState.EngineLoading("Loading transcription engine…"))
}

@Preview(showBackground = true, backgroundColor = 0xFF111111, name = "IME · Error")
@Composable
private fun ImeErrorPreview() {
    ImePreviewScaffold(uiState = KeyboardUiState.Error("Microphone permission denied"))
}

