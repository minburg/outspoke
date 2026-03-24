package dev.brgr.outspoke.ui.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.brgr.outspoke.ui.keyboard.components.KeyboardActionButton
import dev.brgr.outspoke.ui.keyboard.components.StatusIndicator
import dev.brgr.outspoke.ui.keyboard.components.TalkButton
import dev.brgr.outspoke.ui.keyboard.components.WaveformBar
import dev.brgr.outspoke.ui.theme.OutspokeKeyboardTheme

/**
 * Root composable for the keyboard input view.
 *
 * Layout (top → bottom):
 *  1. [StatusIndicator] — crossfades between Idle / Listening / Processing / Error states.
 *  2. [WaveformBar]     — animates with real-time amplitude.
 *  3. Bottom row (left → right):
 *       [Delete All] · [Delete Word] · [TalkButton] · [Delete Char] · [Switch Keyboard]
 *     The outer pair is pinned to each edge; the TalkButton stays centred with equal
 *     weight on both sides.
 *
 * @param uiState                Current UI state collected from [KeyboardViewModel.uiState].
 * @param amplitude              Normalised RMS amplitude [0.0, 1.0].
 * @param isContinuous           `true` when continuous (locked) recording mode is active.
 * @param triggerMode            `"HOLD"` (default) or `"TAP_TOGGLE"`.
 * @param onRecordStart          Callback fired when the user presses the talk button.
 * @param onRecordStop           Callback fired when the user releases / stops recording.
 * @param onContinuousModeEnabled Callback fired when the drag-up lock threshold is crossed.
 * @param onDeleteChar           Delete the character immediately before the cursor.
 * @param onDeleteWord           Delete backward to the previous word boundary.
 * @param onDeleteAll            Delete all text in the current editor.
 * @param onSwitchKeyboard       Switches the active IME back to the previous keyboard.
 * @param onOpenCompanionApp     Opens the Outspoke companion app (e.g. to grant permission or download the model).
 */
@Composable
fun KeyboardScreen(
    uiState: KeyboardUiState,
    amplitude: Float,
    isContinuous: Boolean,
    triggerMode: String,
    onRecordStart: () -> Unit,
    onRecordStop: () -> Unit,
    onContinuousModeEnabled: () -> Unit,
    onDeleteChar: () -> Unit,
    onDeleteWord: () -> Unit,
    onDeleteAll: () -> Unit,
    onSwitchKeyboard: () -> Unit,
    onOpenCompanionApp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        // ── Top section: status + waveform ───────────────────────────────────
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatusIndicator(
                uiState = uiState,
                onOpenCompanionApp = onOpenCompanionApp,
                modifier = Modifier.wrapContentWidth(),
            )
            WaveformBar(
                amplitude = amplitude,
                modifier = Modifier.wrapContentWidth(),
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        // ── Bottom row: 5 buttons with TalkButton centred ────────────────────
        // Left and right groups each have weight(1f) so the centre button stays
        // exactly in the middle regardless of screen width.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Left group: [Delete All] ──────── [Delete Word]
            Box(modifier = Modifier.weight(1f)) {
                // Far-left: delete all text (mirror of the switch-keyboard button)
                KeyboardActionButton(
                    icon = Icons.Filled.DeleteForever,
                    contentDescription = "Delete all text",
                    onClick = onDeleteAll,
                    modifier = Modifier.align(Alignment.CenterStart),
                )
                // Adjacent-left: delete last word
                KeyboardActionButton(
                    icon = Icons.AutoMirrored.Outlined.Backspace,
                    contentDescription = "Delete last word",
                    onClick = onDeleteWord,
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Centre: talk button
            // isListening is true for both Listening AND Processing: audio capture runs
            // through the entire recording session; Processing just means inference has
            // already started emitting partial results while audio is still flowing.
            TalkButton(
                isListening = uiState is KeyboardUiState.Listening ||
                              uiState is KeyboardUiState.Processing,
                isContinuous = isContinuous,
                triggerMode = triggerMode,
                onRecordStart = onRecordStart,
                onRecordStop = onRecordStop,
                onContinuousModeEnabled = onContinuousModeEnabled,
                enabled = uiState !is KeyboardUiState.EngineLoading &&
                          uiState !is KeyboardUiState.Error,
            )

            Spacer(modifier = Modifier.width(8.dp))

            // Right group: [Delete Char] ──────── [Switch Keyboard]
            Box(modifier = Modifier.weight(1f)) {
                // Adjacent-right: delete single character
                KeyboardActionButton(
                    icon = Icons.AutoMirrored.Filled.Backspace,
                    contentDescription = "Delete character",
                    onClick = onDeleteChar,
                    modifier = Modifier.align(Alignment.CenterStart),
                )
                // Far-right: switch back to previous keyboard
                KeyboardActionButton(
                    icon = Icons.Filled.Keyboard,
                    contentDescription = "Switch keyboard",
                    onClick = onSwitchKeyboard,
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            }
        }
    }
}

/**
 * Convenience overload that reads directly from a [KeyboardViewModel]'s state flows.
 * Used by [dev.brgr.outspoke.ime.OutspokeInputMethodService] to set up the content.
 */
@Composable
fun KeyboardScreen(
    viewModel: KeyboardViewModel,
    onSwitchKeyboard: () -> Unit,
    onOpenCompanionApp: () -> Unit,
) {
    val uiState      by viewModel.uiState.collectAsState()
    val amplitude    by viewModel.amplitude.collectAsState()
    val isContinuous by viewModel.isContinuousMode.collectAsState()
    val triggerMode  by viewModel.triggerMode.collectAsState()

    KeyboardScreen(
        uiState               = uiState,
        amplitude             = amplitude,
        isContinuous          = isContinuous,
        triggerMode           = triggerMode,
        onRecordStart         = viewModel::onRecordStart,
        onRecordStop          = viewModel::onRecordStop,
        onContinuousModeEnabled = viewModel::onContinuousModeEnabled,
        onDeleteChar          = viewModel::deleteChar,
        onDeleteWord          = viewModel::deleteWord,
        onDeleteAll           = viewModel::deleteAll,
        onSwitchKeyboard      = onSwitchKeyboard,
        onOpenCompanionApp    = onOpenCompanionApp,
    )
}

// -------------------------------------------------------------------------------------------------
// Previews
// -------------------------------------------------------------------------------------------------

@Composable
private fun KeyboardScreenPreviewScaffold(
    uiState: KeyboardUiState,
    amplitude: Float = 0f,
    isContinuous: Boolean = false,
) {
    OutspokeKeyboardTheme {
        Box(modifier = Modifier.height(220.dp)) {
            KeyboardScreen(
                uiState               = uiState,
                amplitude             = amplitude,
                isContinuous          = isContinuous,
                triggerMode           = "HOLD",
                onRecordStart         = {},
                onRecordStop          = {},
                onContinuousModeEnabled = {},
                onDeleteChar          = {},
                onDeleteWord          = {},
                onDeleteAll           = {},
                onSwitchKeyboard      = {},
                onOpenCompanionApp    = {},
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF111111)
@Composable
private fun KeyboardScreenIdlePreview() {
    KeyboardScreenPreviewScaffold(uiState = KeyboardUiState.Idle)
}

@Preview(showBackground = true, backgroundColor = 0xFF111111)
@Composable
private fun KeyboardScreenListeningPreview() {
    KeyboardScreenPreviewScaffold(uiState = KeyboardUiState.Listening, amplitude = 0.6f)
}

@Preview(showBackground = true, backgroundColor = 0xFF111111)
@Composable
private fun KeyboardScreenContinuousPreview() {
    KeyboardScreenPreviewScaffold(
        uiState = KeyboardUiState.Listening,
        amplitude = 0.4f,
        isContinuous = true,
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF111111)
@Composable
private fun KeyboardScreenProcessingPreview() {
    KeyboardScreenPreviewScaffold(uiState = KeyboardUiState.Processing("Hello world…"))
}

@Preview(showBackground = true, backgroundColor = 0xFF111111)
@Composable
private fun KeyboardScreenErrorPreview() {
    KeyboardScreenPreviewScaffold(uiState = KeyboardUiState.Error("Microphone permission denied"))
}
