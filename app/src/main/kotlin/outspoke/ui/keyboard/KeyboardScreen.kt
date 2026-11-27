package dev.brgr.outspoke.ui.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
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
import dev.brgr.outspoke.ui.keyboard.components.*
import dev.brgr.outspoke.ui.theme.MyIcons
import dev.brgr.outspoke.audio.SpectrumFrame
import dev.brgr.outspoke.ui.theme.OutspokeKeyboardTheme

/** Vertical rhythm between the status row and the waveform inside the top section. */
private val TopSectionGap = 4.dp

/**
 * Root composable for the keyboard input view.
 *
 * Layout (top → bottom):
 *  1. Hairline separating the strip from the host app.
 *  2. Suggestion-bar slot (sibling of the keyboard, never an overlay).
 *  3. [StatusIndicator] - crossfades between Idle / Listening / Processing / Error states,
 *     with [Insert Space] and [Switch Keyboard] at the right of the same row.
 *  4. [WaveformBar]     - level meter: mirrored frequency bands while capturing.
 *  5. Bottom row (left → right):
 *       [Delete All] · [Delete Word] · [TalkButton] · [Delete Char] · [Enter / context action]
 *     The outer pair is pinned to each edge; the TalkButton stays centred with equal
 *     weight on both sides.
 *
 * @param uiState                Current UI state collected from [KeyboardViewModel.uiState].
 * @param amplitude              Normalised RMS amplitude [0.0, 1.0] (fallback meter).
 * @param spectrum               Band energies for the mirrored level meter; `null` falls back to
 *                               the RMS row while no analysed chunk has arrived or on engines
 *                               whose capture path has no spectrum.
 * @param isContinuous           `true` when continuous (locked) recording mode is active.
 * @param triggerMode            `"HOLD"` (default) or `"TAP_TOGGLE"`.
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
 * @param wordSuggestions        Alternative word candidates for the word under the cursor.
 * @param suggestionBarDismissed `true` after the user dismissed the bar; suppresses it.
 * @param onSuggestionTapped     Called when the user taps a word suggestion chip.
 * @param onDismissSuggestionBar Called when the user taps the × button in the suggestion bar.
 */
@Composable
fun KeyboardScreen(
    uiState: KeyboardUiState,
    amplitude: Float,
    spectrum: SpectrumFrame? = null,
    isContinuous: Boolean,
    triggerMode: String,
    onRecordStart: () -> Unit,
    onRecordStop: () -> Unit,
    onContinuousModeEnabled: () -> Unit,
    onRetry: (() -> Unit)? = null,
    onDeleteChar: () -> Unit,
    onDeleteWord: () -> Unit,
    onDeleteAll: () -> Unit,
    onEnterAction: () -> Unit,
    onInsertSpace: () -> Unit = {},
    enterAction: EnterAction = EnterAction.DONE,
    onSwitchKeyboard: () -> Unit,
    onOpenCompanionApp: () -> Unit,
    modifier: Modifier = Modifier,
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
    // Explicit nav bar bottom padding — authoritative service-level value, same pattern
    // as KeyboardTutorialOverlay. 0 on gesture nav, real height on button-nav devices.
    val navBarPaddingDp = with(density) { navBarHeightPx.toDp() }

    // The IME window is a fixed size (keyboard + suggestion bar slot). The bar animates by
    // translating its own content, and the keyboard below it takes the remaining height, so
    // neither is displaced by bar animation or window resize events.
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // Read the incoming constraints here: the Compose compiler plugin will not let a
        // BoxWithConstraintsScope receiver be captured by a nested composable lambda.
        val fillsHostHeight = constraints.hasBoundedHeight
        // The suggestion-bar slot and the keyboard divide the window height as siblings.
        // They must never overlap: the keyboard used to be a fixed-height box pinned to the
        // bottom of the window with the bar *overlaid* on top of it, so any mismatch between
        // `keyboardContentHeightPx` and the real window height pushed the status row under the
        // bar shelf - visible as a dark band with the status pill cut in half.
        Column(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
            // Hairline at the window's top edge, so the strip reads as a separate surface rather
            // than a continuation of the page above it: with the keyboard background close to a
            // messaging app's own background the boundary otherwise disappears. Same token and
            // weight as the suggestion bar's own divider, so the two lines never disagree.
            HorizontalDivider(
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
            )

            // Suggestion bar — reserves its height while visible, collapses (after its slide-out
            // animation) once hidden, so the keyboard below always gets the rest of the window.
            SuggestionBar(
                suggestions = wordSuggestions,
                dismissed = suggestionBarDismissed,
                onSuggestionTapped = onSuggestionTapped,
                onDismiss = onDismissSuggestionBar,
                dismissContentDescription = dismissSuggestionBarContentDescription,
                onWindowSizeTarget = onSuggestionBarHeightChanged,
                modifier = Modifier.fillMaxWidth(),
            )

            // Main keyboard content — takes whatever height is left below the bar slot.
            // Inside, the button row is anchored to the bottom edge and the top section
            // (status + waveform) is centred in the remaining space, which prevents the dead
            // whitespace a SpaceBetween Column leaves on tall keyboard windows (tablets).
            Box(
                modifier = (
                    if (fillsHostHeight) Modifier.weight(1f)
                    else Modifier.wrapContentHeight()   // previews / unbounded host
                    )
                    .fillMaxWidth()
                    // Gesture-navigation devices report a 0 px navigation bar, but the system gesture
                    // pill still floats over the bottom ~14 dp of the window - hence the floor, which
                    // also has to absorb the talk button's 1.12x "listening" growth.
                    .padding(
                        start = 16.dp,
                        end = 16.dp,
                        top = 4.dp,
                        bottom = maxOf(navBarPaddingDp + 8.dp, 20.dp),
                    ),
            ) {
                // Top section: one status line (pill, whisper chips, trailing keys) plus the
                // waveform, centred in the space above the button row. The waveform is the only
                // decorative row, so it is the one that yields when the window is too short for
                // everything - the window is 20 % of the screen height, and on a small phone the
                // alternative is the status pill being clipped by the window's own top edge.
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.Center)
                        // Keep it above the button row so they never overlap: the talk button's own
                        // diameter is the whole clearance - the fixed-height window has no more slack.
                        .padding(bottom = TalkButtonSize),
                ) {
                    val waveformFits =
                        maxHeight >= FunctionKeySize + TopSectionGap + WaveformBarHeight
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(TopSectionGap),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Status pill (plus Whisper chips) share one weighted cell in front of the two
                            // fixed keys. Weighted, because an unweighted status block is measured at its
                            // full desired width *before* the keys are placed: a long error string such as
                            // "Microphone in use - close the other recording app" then slid both trailing
                            // keys off the right edge of the window. Errors stay centred - which is what the
                            // dummy spacer pair this replaces achieved - every other state stays
                            // left-aligned, exactly as before.
                            Box(modifier = Modifier.weight(1f)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement =
                                        if (uiState is KeyboardUiState.Error) {
                                            Arrangement.Center
                                        } else {
                                            Arrangement.Start
                                        },
                                ) {
                                    StatusIndicator(
                                        uiState = uiState,
                                        onOpenCompanionApp = onOpenCompanionApp,
                                        onRetry = onRetry ?: onRecordStart,
                                        modifier = Modifier.weight(1f, fill = false),
                                    )
                                }
                            }
                            // Both trailing keys of the status row are chrome-matched: one bare icon
                            // next to a bordered key read as a disabled control.
                            Box(modifier = Modifier.width(FunctionKeySize + 4.dp)) {
                                KeyboardActionButton(
                                    icon = MyIcons.SpaceBar,
                                    contentDescription = stringResource(R.string.cd_insert_space),
                                    onClick = onInsertSpace,
                                    repeatEnabled = false,
                                    modifier = Modifier
                                        .align(Alignment.CenterEnd)
                                        .onGloballyPositioned { lc ->
                                            tutorialPositions?.record(TutorialButtonId.SPACE, lc)
                                        },
                                )
                            }

                            Box(modifier = Modifier.width(FunctionKeySize + 4.dp)) {
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

                        if (waveformFits) {
                            WaveformBar(
                                amplitude = amplitude,
                                spectrum = spectrum,
                                modifier = Modifier.wrapContentWidth(),
                            )
                        }
                    }
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

                    // Right group: [Delete Char]  [Enter / context action]
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
        } // end bar + content column
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
    navBarHeightPx: Int = 0,
) {
    val uiState by viewModel.uiState.collectAsState()
    val amplitude by viewModel.amplitude.collectAsState()
    val spectrum by viewModel.spectrum.collectAsState()
    val isContinuous by viewModel.isContinuousMode.collectAsState()
    val triggerMode by viewModel.triggerMode.collectAsState()
    val enterAction by viewModel.enterAction.collectAsState()
    val showTutorial by viewModel.showTutorial.collectAsState()
    val wordSuggestions by viewModel.wordSuggestions.collectAsState()
    val suggestionBarDismissed by viewModel.suggestionBarDismissed.collectAsState()

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
            spectrum = spectrum,
            isContinuous = isContinuous,
            triggerMode = triggerMode,
            onRecordStart = viewModel::onRecordStart,
            onRecordStop = viewModel::onRecordStop,
            onContinuousModeEnabled = viewModel::onContinuousModeEnabled,
            onRetry = viewModel::onRetry,
            onDeleteChar = viewModel::deleteChar,
            onDeleteWord = viewModel::deleteWord,
            onDeleteAll = viewModel::deleteAll,
            onEnterAction = viewModel::performEnterAction,
            onInsertSpace = viewModel::insertSpace,
            enterAction = enterAction,
            onSwitchKeyboard = onSwitchKeyboard,
            onOpenCompanionApp = onOpenCompanionApp,
            wordSuggestions = wordSuggestions,
            suggestionBarDismissed = suggestionBarDismissed,
            onSuggestionTapped = viewModel::replaceWordAtCursor,
            onDismissSuggestionBar = viewModel::dismissSuggestionBar,
            dismissSuggestionBarContentDescription = stringResource(R.string.cd_dismiss_suggestion_bar),
            onSuggestionBarHeightChanged = onSuggestionBarHeightChanged,
            tutorialPositions = tutorialPositions,
            navBarHeightPx = navBarHeightPx,
        )

        // Tutorial overlay covers the keyboard on first launch.
        // Held back while the engine is loading or missing: step one spotlights the talk button
        // and says "hold to record", which is a lie while that button is disabled - and on the
        // literal first open (no model downloaded yet) that is exactly the state the user meets.
        // The flag is only cleared on dismiss, so the walk-through appears as soon as the model
        // is ready instead of teaching a dead control.
        if (showTutorial && uiState !is KeyboardUiState.EngineLoading) {
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

