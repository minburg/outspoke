package dev.brgr.outspoke.devtools

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.brgr.outspoke.ui.keyboard.KeyboardScreen
import dev.brgr.outspoke.ui.keyboard.KeyboardUiState
import dev.brgr.outspoke.ui.keyboard.components.SUGGESTION_BAR_HEIGHT_DP
import dev.brgr.outspoke.ui.theme.OutspokeKeyboardTheme

/**
 * Debug-only host for the REAL [KeyboardScreen] composable.
 *
 * The keyboard is an IME window, so it cannot be screenshotted through an ordinary
 * activity. This activity draws the production composable with canned state over a mock
 * host app, which is the cheapest way to review the shipped keyboard surface on a device.
 * It renders the production composable unmodified - no mocks of the keyboard UI itself.
 *
 * Launch:
 * `adb shell am start -n dev.brgr.outspoke/dev.brgr.outspoke.devtools.KeyboardPreviewActivity
 * --es state listening`
 * with `state` in idle | listening | continuous | processing | no_speech | error | loading
 * | whisper | suggestions.
 */
class KeyboardPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val state = intent.getStringExtra("state") ?: "idle"
        // Optional override of the IME window height in dp, to verify the layout on short
        // strips (the real service uses 20 % of the usable screen height).
        val height = intent.getIntExtra("height", KEYBOARD_CONTENT_HEIGHT_DP)
        setContent { KeyboardPreviewRoot(state, height) }
    }
}

private const val KEYBOARD_CONTENT_HEIGHT_DP = 220

@Composable
private fun KeyboardPreviewRoot(state: String, contentHeightDp: Int = KEYBOARD_CONTENT_HEIGHT_DP) {
    val suggestionsVisible = state == "suggestions"
    Box(modifier = Modifier.fillMaxSize()) {
        HostAppMock()
        OutspokeKeyboardTheme {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height((contentHeightDp + if (suggestionsVisible) SUGGESTION_BAR_HEIGHT_DP else 0).dp),
            ) {
                val (uiState, amplitude, isContinuous, whisper, language) = stateSpec(state)
                // The emulator has no live microphone, so the meter is driven by the synthetic
                // one (real SpectrumAnalyzer, synthesised speech) whenever the canned state is
                // "recording". Other states leave it null: quiet fallback row.
                val syntheticSpectrum = rememberSyntheticSpectrum(active = amplitude > 0f)
                KeyboardScreen(
                    uiState = uiState,
                    amplitude = amplitude,
                    spectrum = syntheticSpectrum,
                    isContinuous = isContinuous,
                    triggerMode = "HOLD",
                    isWhisperEngine = whisper,
                    whisperLanguage = language,
                    onWhisperLanguageSelected = {},
                    onRecordStart = {},
                    onRecordStop = {},
                    onContinuousModeEnabled = {},
                    onDeleteChar = {},
                    onDeleteWord = {},
                    onDeleteAll = {},
                    onEnterAction = {},
                    onInsertSpace = {},
                    onSwitchKeyboard = {},
                    onOpenCompanionApp = {},
                    previewForceLockHint = state == "continuous",
                    wordSuggestions =
                        if (suggestionsVisible) {
                            listOf("the", "there", "they", "then", "thus")
                        } else {
                            emptyList()
                        },
                )
            }
        }
    }
}

/** Canned keyboard states for screenshotting; mirrors the previews in KeyboardScreen.kt. */
private fun stateSpec(state: String): KeyboardPreviewSpec = when (state) {
    "listening" -> KeyboardPreviewSpec(KeyboardUiState.Listening, 0.6f, false, false, "auto")
    "continuous" -> KeyboardPreviewSpec(KeyboardUiState.Listening, 0.35f, true, false, "auto")
    "processing" -> KeyboardPreviewSpec(KeyboardUiState.Processing("the quick brown"), 0f, false, false, "auto")
    "no_speech" -> KeyboardPreviewSpec(KeyboardUiState.NoSpeech, 0f, false, false, "auto")
    "error" -> KeyboardPreviewSpec(
        KeyboardUiState.Error(KeyboardUiState.ErrorReason.TranscriptionFailed), 0f, false, false, "auto",
    )
    "loading" -> KeyboardPreviewSpec(
        KeyboardUiState.EngineLoading(KeyboardUiState.LoadingReason.EngineStarting), 0f, false, false, "auto",
    )
    "whisper" -> KeyboardPreviewSpec(KeyboardUiState.Idle, 0f, false, true, "de")
    "suggestions" -> KeyboardPreviewSpec(KeyboardUiState.Idle, 0f, false, false, "auto")
    else -> KeyboardPreviewSpec(KeyboardUiState.Idle, 0f, false, false, "auto")
}

private data class KeyboardPreviewSpec(
    val uiState: KeyboardUiState,
    val amplitude: Float,
    val isContinuous: Boolean,
    val isWhisperEngine: Boolean,
    val whisperLanguage: String,
)

/** Stand-in for whatever app the keyboard is typing into, so contrast is judged in context. */
@Composable
private fun HostAppMock() {
    val dark = isSystemInDarkTheme()
    val background = if (dark) Color(0xFF1B1B1B) else Color(0xFFF7F7F7)
    val bubbleIn = if (dark) Color(0xFF2A2C2E) else Color(0xFFFFFFFF)
    val bubbleOut = if (dark) Color(0xFF2E4A42) else Color(0xFFDDF1EA)
    val textColor = if (dark) Color(0xFFE3E3E3) else Color(0xFF1A1C1B)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(background)
            .padding(horizontal = 16.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.Top),
    ) {
        MockBubble("How is the calibration going?", bubbleIn, textColor, start = true)
        MockBubble("Still tuning the mic meter", bubbleOut, textColor, start = false)
        MockBubble("Send me a screenshot", bubbleIn, textColor, start = true)
    }
}

@Composable
private fun MockBubble(text: String, color: Color, textColor: Color, start: Boolean) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (start) Alignment.CenterStart else Alignment.CenterEnd,
    ) {
        Box(
            modifier = Modifier
                .width(240.dp)
                .height(44.dp)
                .background(color, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text = text,
                style = TextStyle(fontSize = 14.sp),
                color = textColor,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
    }
}
