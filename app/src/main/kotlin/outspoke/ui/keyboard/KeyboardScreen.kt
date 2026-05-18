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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
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
 * @param wordSuggestions        Alternative word candidates for the word under the cursor.
 * @param suggestionBarDismissed `true` after the user dismissed the bar; suppresses it.
 * @param onSuggestionTapped     Called when the user taps a word suggestion chip.
 * @param onDismissSuggestionBar Called when the user taps the × button in the suggestion bar.
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
    onRetry: (() -> Unit)? = null,
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
    wordSuggestions: List<String> = emptyList(),
    suggestionBarDismissed: Boolean = false,
    onSuggestionTapped: (String) -> Unit = {},
    onDismissSuggestionBar: () -> Unit = {},
    dismissSuggestionBarContentDescription: String = "",
    /**
     * Called immediately when the suggestion bar's visibility target changes, with the
     * desired window add-on height in pixels. -1 means "use the full bar slot height"
     * (resolved by the service using its density). 0 means bar is hidden.
     * The service uses this for a single up-front window resize instead of per-frame.
     */
    onSuggestionBarHeightChanged: (Int) -> Unit = {},
    /**
     * Fixed height in pixels for the main keyboard content area (buttons, waveform, status).
     * When non-zero this is used directly so the content is completely decoupled from the
     * animated window height — the buttons never move during the suggestion bar animation.
     * Defaults to 0 for previews, which fall back to [Modifier.weight].
     */
    keyboardContentHeightPx: Int = 0,
    tutorialPositions: TutorialPositions? = null,
    /**
     * Navigation bar height in pixels from [WindowManager.currentWindowMetrics] at the
     * service level. Applied as explicit bottom padding on the keyboard content column so
     * that buttons are never drawn behind the system navigation bar.
     *
     * This mirrors the same pattern used by [KeyboardTutorialOverlay]: we do NOT use
     * [Modifier.navigationBarsPadding] here because inset dispatch inside an IME window
     * is unreliable on some OEM ROMs — if insets are never delivered the modifier is a
     * silent no-op and the buttons draw behind the nav bar. The service-level value is
     * authoritative and always correct.
     *
     * 0 on gesture-navigation devices (no bar to avoid), real height on button-nav devices.
     */
    navBarHeightPx: Int = 0,
) {
    val density = LocalDensity.current
    // Convert the service-provided pixel heights to Dp once; stay constant per session.
    val mainContentHeight = if (keyboardContentHeightPx > 0) {
        with(density) { keyboardContentHeightPx.toDp() }
    } else null
    // Explicit nav bar bottom padding — authoritative service-level value, same pattern
    // as KeyboardTutorialOverlay. 0 on gesture nav, real height on button-nav devices.
    val navBarPaddingDp = with(density) { navBarHeightPx.toDp() }

    // The IME window is a fixed size (keyboard + suggestion bar slot). The bar clips its
    // own content internally as it animates. The keyboard Column is pinned to the bottom
    // with a fixed height and is completely unaffected by bar animation or window events.
    Box(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // Suggestion bar — clips its content to the animated height. When hidden, the
        // bar slot at the top of the window shows the background colour; the app above
        // is scrolled to fill that area via onComputeInsets, so no gap is visible.
        SuggestionBar(
            suggestions = wordSuggestions,
            dismissed = suggestionBarDismissed,
            onSuggestionTapped = onSuggestionTapped,
            onDismiss = onDismissSuggestionBar,
            dismissContentDescription = dismissSuggestionBarContentDescription,
            onWindowSizeTarget = onSuggestionBarHeightChanged,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .zIndex(1f),
        )

        // Main keyboard content — always pinned to the bottom with a fixed height.
        // Uses a Box so the button row is anchored to the bottom edge and the top section
        // (status + waveform) is vertically centred in the remaining space above. This
        // prevents the large empty gap that appears on tablets/high-res screens when a
        // Column with SpaceBetween distributes all leftover space as dead whitespace.
        Box(
            modifier = if (mainContentHeight != null) {
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(mainContentHeight)
            } else {
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .wrapContentHeight()
            }
                .padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = navBarPaddingDp + 8.dp),
        ) {
            // Top section: status row + (optional) language selector + waveform.
            // Centred vertically in the space above the button row so that on tall
            // keyboard windows (tablets) the waveform sits in the middle rather than
            // being pushed hard against the top edge.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.Center)
                    // Keep it above the button row so they never overlap.
                    // The TalkButton is 72 dp tall; 80 dp gives a safe margin.
                    .padding(bottom = 80.dp),
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
                        onRetry = onRetry ?: onRecordStart,
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
                                    tutorialPositions?.record(TutorialButtonId.SWITCH_KEYBOARD, lc)
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

            //  Bottom row: 5 buttons with TalkButton centred
            // Left and right groups each have weight(1f) so the centre button stays
            // exactly in the middle regardless of screen width.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter),
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
                                tutorialPositions?.record(TutorialButtonId.DELETE_ALL, lc)
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
                                tutorialPositions?.record(TutorialButtonId.DELETE_WORD, lc)
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
                        tutorialPositions?.record(TutorialButtonId.TALK, lc)
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
                                tutorialPositions?.record(TutorialButtonId.DELETE_CHAR, lc)
                            },
                    )
                    // Centre-right: context-aware Enter action
                    val (enterIcon, enterDescription) = when (enterAction) {
                        EnterAction.SEARCH -> MyIcons.Search to stringResource(R.string.cd_action_search)
                        EnterAction.GO -> MyIcons.ArrowForward to stringResource(R.string.cd_action_go)
                        EnterAction.NEXT -> MyIcons.ArrowForward to stringResource(R.string.cd_action_next)
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
                                tutorialPositions?.record(TutorialButtonId.ENTER, lc)
                            },
                    )

                }
            }
        } // end main content Box
    } // end outer Box
}

/**
 * Convenience overload that reads directly from a [KeyboardViewModel]'s state flows.
 * Used by [dev.brgr.outspoke.ime.OutspokeInputMethodService] to set up the content.
 *
 * Shows the [KeyboardTutorialOverlay] on first launch until the user dismisses it.
 *
 * @param navBarHeightPx Navigation bar height in pixels from [WindowManager.currentWindowMetrics].
 *                       Passed directly to [KeyboardTutorialOverlay] so its Skip/Next buttons
 *                       are always positioned above the system navigation bar, regardless of
 *                       whether insets are correctly dispatched into the IME window's Compose
 *                       tree on the current device.
 */
@Composable
fun KeyboardScreen(
    viewModel: KeyboardViewModel,
    onSwitchKeyboard: () -> Unit,
    onOpenCompanionApp: () -> Unit,
    onSuggestionBarHeightChanged: (Int) -> Unit = {},
    keyboardContentHeightPx: Int = 0,
    navBarHeightPx: Int = 0,
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
    val wordSuggestions by viewModel.wordSuggestions.collectAsState()
    val suggestionBarDismissed by viewModel.suggestionBarDismissed.collectAsState()

    // Only surface real diagnostics counters when the user has enabled the badge in settings.
    val diagnostics = if (showPipelineDiagnostics) rawDiagnostics else PipelineDiagnostics()

    // Shared state that records each button's LayoutCoordinates for the tutorial spotlight.
    // Created once and kept alive so positions are ready the moment the overlay appears.
    val tutorialPositions = remember { TutorialPositions() }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
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
            onRetry = viewModel::onRetry,
            onDeleteChar = viewModel::deleteChar,
            onDeleteWord = viewModel::deleteWord,
            onDeleteAll = viewModel::deleteAll,
            onEnterAction = viewModel::performEnterAction,
            enterAction = enterAction,
            onSwitchKeyboard = onSwitchKeyboard,
            onOpenCompanionApp = onOpenCompanionApp,
            diagnostics = diagnostics,
            wordSuggestions = wordSuggestions,
            suggestionBarDismissed = suggestionBarDismissed,
            onSuggestionTapped = viewModel::replaceWordAtCursor,
            onDismissSuggestionBar = viewModel::dismissSuggestionBar,
            dismissSuggestionBarContentDescription = stringResource(R.string.cd_dismiss_suggestion_bar),
            onSuggestionBarHeightChanged = onSuggestionBarHeightChanged,
            keyboardContentHeightPx = keyboardContentHeightPx,
            tutorialPositions = tutorialPositions,
            navBarHeightPx = navBarHeightPx,
        )

        // Tutorial overlay covers the keyboard on first launch.
        if (showTutorial) {
            KeyboardTutorialOverlay(
                positions = tutorialPositions,
                onDismiss = viewModel::dismissTutorial,
                modifier = Modifier.matchParentSize(),
                navBarHeightPx = navBarHeightPx,
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
    wordSuggestions: List<String> = emptyList(),
    suggestionBarDismissed: Boolean = false,
) {
    // Add SUGGESTION_BAR_HEIGHT_DP when the bar will be visible so the preview
    // is tall enough to show both the bar and the keyboard buttons without clipping.
    val barVisible = wordSuggestions.isNotEmpty() && !suggestionBarDismissed
    val previewHeight =
        if (barVisible) (220 + SUGGESTION_BAR_HEIGHT_DP) else 220
    OutspokeKeyboardTheme {
        Box(modifier = Modifier.height(previewHeight.dp)) {
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
                wordSuggestions = wordSuggestions,
                suggestionBarDismissed = suggestionBarDismissed,
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
    KeyboardScreenPreviewScaffold(
        uiState = KeyboardUiState.Error(KeyboardUiState.ErrorReason.MicPermissionDenied),
        showLockHint = false
    )
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

@Preview(showBackground = true, backgroundColor = 0xFF111111)
@Composable
private fun KeyboardScreenSuggestionsPreview() {
    KeyboardScreenPreviewScaffold(
        uiState = KeyboardUiState.Idle,
        wordSuggestions = listOf("hello", "hallo", "hollow", "hell"),
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF111111)
@Composable
private fun KeyboardScreenSuggestionsDismissedPreview() {
    KeyboardScreenPreviewScaffold(
        uiState = KeyboardUiState.Idle,
        wordSuggestions = listOf("hello", "hallo", "hollow"),
        suggestionBarDismissed = true,
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF111111)
@Composable
private fun KeyboardScreenTransientErrorPreview() {
    KeyboardScreenPreviewScaffold(
        uiState = KeyboardUiState.Error(KeyboardUiState.ErrorReason.TranscriptionFailed, detail = "ONNX error")
    )
}

