package dev.brgr.outspoke.ui.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.brgr.outspoke.R
import dev.brgr.outspoke.ime.EnterAction
import dev.brgr.outspoke.inference.PipelineDiagnostics
import dev.brgr.outspoke.ui.keyboard.components.*
import dev.brgr.outspoke.ui.theme.MyIcons
import dev.brgr.outspoke.ui.theme.OutspokeKeyboardTheme

/**
 * Root composable for the keyboard input view.
 *
 * Layout (top → bottom):
 *  1. [StatusIndicator] - crossfades between Idle / Listening / Processing / Error states.
 *  2. [WaveformBar]     - animates with real-time amplitude.
 *  3. Bottom row (left → right):
 *       [Delete All] · [Delete Word] · [TalkButton] · [Delete Char] · [Switch Keyboard]
 *     The outer pair is pinned to each edge; the TalkButton stays centred with equal
 *     weight on both sides.
 *
 * @param uiState                Current UI state collected from [KeyboardViewModel.uiState].
 * @param amplitude              Normalised RMS amplitude [0.0, 1.0].
 * @param isContinuous           `true` when continuous (locked) recording mode is active.
 * @param triggerMode            `"HOLD"` (default) or `"TAP_TOGGLE"`.
 * @param isWhisperEngine        `true` when the active engine is a Whisper variant - controls
 *                               visibility of the language selector row.
 * @param whisperLanguage        Currently selected Whisper language tag (`"auto"`, `"en"`, …).
 * @param onWhisperLanguageSelected Called when the user taps a language pill.
 * @param onRecordStart          Callback fired when the user presses the talk button.
 * @param onRecordStop           Callback fired when the user releases / stops recording.
 * @param onContinuousModeEnabled Callback fired when the drag-up lock threshold is crossed.
 * @param onDeleteChar           Delete the character immediately before the cursor.
 * @param onDeleteWord           Delete backward to the previous word boundary.
 * @param onDeleteAll            Delete all text in the current editor.
 * @param onEnterAction          Perform the context-aware Enter action (newline or IME action).
 * @param enterAction            The semantic action for the Enter key in the current editor.
 * @param onSwitchKeyboard       Switches the active IME back to the previous keyboard.
 * @param onOpenCompanionApp     Opens the Outspoke companion app (e.g. to grant permission or download the model).
 * @param diagnostics            Pipeline counters from the most recent recording session.
 */
@Composable
fun KeyboardScreen(
    uiState: KeyboardUiState,
    amplitude: Float,
    isContinuous: Boolean,
    triggerMode: String,
    isWhisperEngine: Boolean,
    whisperLanguage: String,
    onWhisperLanguageSelected: (String) -> Unit,
    onRecordStart: () -> Unit,
    onRecordStop: () -> Unit,
    onContinuousModeEnabled: () -> Unit,
    onDeleteChar: () -> Unit,
    onDeleteWord: () -> Unit,
    onDeleteAll: () -> Unit,
    onEnterAction: () -> Unit,
    enterAction: EnterAction = EnterAction.DONE,
    onSwitchKeyboard: () -> Unit,
    onOpenCompanionApp: () -> Unit,
    modifier: Modifier = Modifier,
    diagnostics: PipelineDiagnostics = PipelineDiagnostics(),
    previewForceLockHint: Boolean = false,
    /** When non-null each button records its [LayoutCoordinates] here for the tutorial. */
    tutorialPositions: dev.brgr.outspoke.ui.keyboard.components.TutorialPositions? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth()
            // Wrap to content height so the keyboard panel does not expand beyond
            // its natural size. navigationBarsPadding() adds the nav-bar height at
            // the bottom, which (a) pushes buttons above the nav bar and (b) extends
            // the background colour to cover the nav-bar area.
            .wrapContentHeight().background(MaterialTheme.colorScheme.background)
            // Push content above the navigation bar so buttons are never hidden behind it.
            .navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        //  Top section: status + (optional) language selector + waveform 
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (uiState is KeyboardUiState.Error) {
                    Box(modifier = Modifier.weight(1f)) {}
                    Spacer(modifier = Modifier.width(8.dp))
                }

                StatusIndicator(
                    uiState = uiState,
                    diagnostics = diagnostics,
                    onOpenCompanionApp = onOpenCompanionApp,
                )
                Spacer(modifier = Modifier.width(8.dp))

                Box(modifier = Modifier.weight(1f)) {
                    KeyboardActionButton(
                        icon = MyIcons.Keyboard,
                        contentDescription = stringResource(R.string.cd_switch_keyboard),
                        onClick = onSwitchKeyboard,
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .onGloballyPositioned { lc ->
                                tutorialPositions?.record(dev.brgr.outspoke.ui.keyboard.components.TutorialButtonId.SWITCH_KEYBOARD, lc)
                            },
                    )
                }
            }

            // Language selector - only shown for Whisper models and when the engine
            // is actually ready (hide during loading / error states).
            if (isWhisperEngine && uiState !is KeyboardUiState.EngineLoading && uiState !is KeyboardUiState.Error) {
                LanguageSelector(
                    selectedLanguage = whisperLanguage,
                    onLanguageSelected = onWhisperLanguageSelected,
                )
            }
            WaveformBar(
                amplitude = amplitude,
                modifier = Modifier.wrapContentWidth(),
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        //  Bottom row: 5 buttons with TalkButton centred 
        // Left and right groups each have weight(1f) so the centre button stays
        // exactly in the middle regardless of screen width.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Left group: [Delete All]  [Delete Word]
            Box(modifier = Modifier.weight(1f)) {
                // Far-left: delete all text (mirror of the switch-keyboard button)
                KeyboardActionButton(
                    icon = MyIcons.DeleteForever,
                    contentDescription = stringResource(R.string.cd_delete_all),
                    onClick = onDeleteAll,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .onGloballyPositioned { lc ->
                            tutorialPositions?.record(dev.brgr.outspoke.ui.keyboard.components.TutorialButtonId.DELETE_ALL, lc)
                        },
                )
                // Adjacent-left: delete last word
                KeyboardActionButton(
                    icon = MyIcons.BackspaceOutlined,
                    contentDescription = stringResource(R.string.cd_delete_word),
                    onClick = onDeleteWord,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .onGloballyPositioned { lc ->
                            tutorialPositions?.record(dev.brgr.outspoke.ui.keyboard.components.TutorialButtonId.DELETE_WORD, lc)
                        },
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Centre: talk button
            // isListening is true for Listening and Processing (mic active, audio flowing).
            // Transcribing means audio has stopped but the engine is still working - mic off,
            // button disabled until the Final result arrives and the state returns to Idle.
            TalkButton(
                isListening = uiState is KeyboardUiState.Listening || uiState is KeyboardUiState.Processing,
                isContinuous = isContinuous,
                triggerMode = triggerMode,
                onRecordStart = onRecordStart,
                onRecordStop = onRecordStop,
                onContinuousModeEnabled = onContinuousModeEnabled,
                enabled = uiState !is KeyboardUiState.EngineLoading && uiState !is KeyboardUiState.Error && uiState !is KeyboardUiState.Transcribing,
                previewForceLockHint = previewForceLockHint,
                modifier = Modifier.onGloballyPositioned { lc ->
                    tutorialPositions?.record(dev.brgr.outspoke.ui.keyboard.components.TutorialButtonId.TALK, lc)
                },
            )

            Spacer(modifier = Modifier.width(8.dp))

            // Right group: [Delete Char]  [Switch Keyboard]
            Box(modifier = Modifier.weight(1f)) {
                // Adjacent-right: delete single character
                KeyboardActionButton(
                    icon = MyIcons.Backspace,
                    contentDescription = stringResource(R.string.cd_delete_char),
                    onClick = onDeleteChar,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .onGloballyPositioned { lc ->
                            tutorialPositions?.record(dev.brgr.outspoke.ui.keyboard.components.TutorialButtonId.DELETE_CHAR, lc)
                        },
                )
                // Centre-right: context-aware Enter action
                val (enterIcon, enterDescription) = when (enterAction) {
                    EnterAction.SEARCH  -> MyIcons.Search         to stringResource(R.string.cd_action_search)
                    EnterAction.GO      -> MyIcons.ArrowForward   to stringResource(R.string.cd_action_go)
                    EnterAction.NEXT    -> MyIcons.ArrowForward   to stringResource(R.string.cd_action_next)
                    EnterAction.SEND,
                    EnterAction.DONE,
                    EnterAction.NEWLINE -> MyIcons.SubdirectoryArrowLeft to stringResource(R.string.cd_action_enter)
                }
                KeyboardActionButton(
                    icon = enterIcon,
                    contentDescription = enterDescription,
                    onClick = onEnterAction,
                    // Disable auto-repeat for action buttons - search/send/go should only fire once.
                    repeatEnabled = enterAction == EnterAction.NEWLINE,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .onGloballyPositioned { lc ->
                            tutorialPositions?.record(dev.brgr.outspoke.ui.keyboard.components.TutorialButtonId.ENTER, lc)
                        },
                )

            }
        }
    }
}

/**
 * Convenience overload that reads directly from a [KeyboardViewModel]'s state flows.
 * Used by [dev.brgr.outspoke.ime.OutspokeInputMethodService] to set up the content.
 *
 * Shows the [KeyboardTutorialOverlay] on first launch until the user dismisses it.
 */
@Composable
fun KeyboardScreen(
    viewModel: KeyboardViewModel,
    onSwitchKeyboard: () -> Unit,
    onOpenCompanionApp: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val amplitude by viewModel.amplitude.collectAsState()
    val isContinuous by viewModel.isContinuousMode.collectAsState()
    val triggerMode by viewModel.triggerMode.collectAsState()
    val isWhisperEngine by viewModel.isWhisperEngine.collectAsState()
    val whisperLanguage by viewModel.whisperLanguage.collectAsState()
    val rawDiagnostics by viewModel.diagnostics.collectAsState()
    val showPipelineDiagnostics by viewModel.showPipelineDiagnostics.collectAsState()
    val enterAction by viewModel.enterAction.collectAsState()
    val showTutorial by viewModel.showTutorial.collectAsState()

    // Only surface real diagnostics counters when the user has enabled the badge in settings.
    val diagnostics = if (showPipelineDiagnostics) rawDiagnostics else PipelineDiagnostics()

    // Shared state that records each button's LayoutCoordinates for the tutorial spotlight.
    // Created once and kept alive so positions are ready the moment the overlay appears.
    val tutorialPositions = remember { dev.brgr.outspoke.ui.keyboard.components.TutorialPositions() }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight(),
    ) {
        KeyboardScreen(
            uiState = uiState,
            amplitude = amplitude,
            isContinuous = isContinuous,
            triggerMode = triggerMode,
            isWhisperEngine = isWhisperEngine,
            whisperLanguage = whisperLanguage,
            onWhisperLanguageSelected = viewModel::setWhisperLanguage,
            onRecordStart = viewModel::onRecordStart,
            onRecordStop = viewModel::onRecordStop,
            onContinuousModeEnabled = viewModel::onContinuousModeEnabled,
            onDeleteChar = viewModel::deleteChar,
            onDeleteWord = viewModel::deleteWord,
            onDeleteAll = viewModel::deleteAll,
            onEnterAction = viewModel::performEnterAction,
            enterAction = enterAction,
            onSwitchKeyboard = onSwitchKeyboard,
            onOpenCompanionApp = onOpenCompanionApp,
            diagnostics = diagnostics,
            tutorialPositions = tutorialPositions,
        )

        // Tutorial overlay covers the keyboard on first launch.
        if (showTutorial) {
            dev.brgr.outspoke.ui.keyboard.components.KeyboardTutorialOverlay(
                positions = tutorialPositions,
                onDismiss = viewModel::dismissTutorial,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

@Composable
private fun KeyboardScreenPreviewScaffold(
    uiState: KeyboardUiState,
    amplitude: Float = 0f,
    isContinuous: Boolean = false,
    isWhisperEngine: Boolean = false,
    whisperLanguage: String = "auto",
    showLockHint: Boolean = false,
    enterAction: EnterAction = EnterAction.DONE,
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
                onEnterAction = {},
                enterAction = enterAction,
                onSwitchKeyboard = {},
                onOpenCompanionApp = {},
                previewForceLockHint = showLockHint,
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF111111)
@Composable
private fun KeyboardScreenIdlePreview() {
    KeyboardScreenPreviewScaffold(uiState = KeyboardUiState.Idle, showLockHint = false)
}

@Preview(showBackground = true, backgroundColor = 0xFF111111)
@Composable
private fun KeyboardScreenWhisperIdlePreview() {
    KeyboardScreenPreviewScaffold(
        uiState = KeyboardUiState.Idle,
        isWhisperEngine = true,
        whisperLanguage = "de",
        showLockHint = true,
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF111111)
@Composable
private fun KeyboardScreenListeningPreview() {
    KeyboardScreenPreviewScaffold(uiState = KeyboardUiState.Listening, amplitude = 0.6f, showLockHint = true)
}

@Preview(showBackground = true, backgroundColor = 0xFF111111)
@Composable
private fun KeyboardScreenContinuousPreview() {
    KeyboardScreenPreviewScaffold(
        uiState = KeyboardUiState.Listening,
        amplitude = 0.4f,
        isContinuous = true,
        showLockHint = true,
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF111111)
@Composable
private fun KeyboardScreenProcessingPreview() {
    KeyboardScreenPreviewScaffold(uiState = KeyboardUiState.Processing("Hello world…"), showLockHint = true)
}

@Preview(showBackground = true, backgroundColor = 0xFF111111)
@Composable
private fun KeyboardScreenErrorPreview() {
    KeyboardScreenPreviewScaffold(uiState = KeyboardUiState.Error(KeyboardUiState.ErrorReason.MicPermissionDenied), showLockHint = false)
}

@Preview(showBackground = true, backgroundColor = 0xFF111111)
@Composable
private fun KeyboardScreenSearchActionPreview() {
    KeyboardScreenPreviewScaffold(uiState = KeyboardUiState.Idle, enterAction = EnterAction.SEARCH)
}

@Preview(showBackground = true, backgroundColor = 0xFF111111)
@Composable
private fun KeyboardScreenSendActionPreview() {
    KeyboardScreenPreviewScaffold(uiState = KeyboardUiState.Idle, enterAction = EnterAction.SEND)
}

@Preview(showBackground = true, backgroundColor = 0xFF111111)
@Composable
private fun KeyboardScreenNewlineActionPreview() {
    KeyboardScreenPreviewScaffold(uiState = KeyboardUiState.Idle, enterAction = EnterAction.NEWLINE)
}

