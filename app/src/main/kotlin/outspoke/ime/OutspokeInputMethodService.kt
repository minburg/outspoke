package dev.brgr.outspoke.ime

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.Configuration
import android.content.res.Resources
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.ViewCompat
import androidx.lifecycle.*
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dev.brgr.outspoke.audio.AudioCaptureManager
import dev.brgr.outspoke.audio.PermissionHelper
import dev.brgr.outspoke.inference.EngineState
import dev.brgr.outspoke.inference.InferenceService
import dev.brgr.outspoke.inference.cleanTranscript
import dev.brgr.outspoke.settings.preferences.AppPreferences
import dev.brgr.outspoke.ui.keyboard.ImeComposeView
import dev.brgr.outspoke.ui.keyboard.KeyboardScreen
import dev.brgr.outspoke.ui.keyboard.KeyboardViewModel
import dev.brgr.outspoke.ui.keyboard.components.SUGGESTION_BAR_HEIGHT_DP
import dev.brgr.outspoke.ui.theme.OutspokeKeyboardTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private const val TAG = "OutspokeIME"

/**
 * The core IME service. Implements [LifecycleOwner], [ViewModelStoreOwner], and
 * [SavedStateRegistryOwner] so that [ImeComposeView] can wire them onto the view tree,
 * allowing Compose to function correctly inside a Service context.
 */
class OutspokeInputMethodService :
    InputMethodService(),
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private val store = ViewModelStore()
    override val viewModelStore: ViewModelStore get() = store

    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    private lateinit var keyboardViewModel: KeyboardViewModel

    /** On-device phonetic + n-gram word corrector; language-aware, no system dependencies. */
    private lateinit var wordSuggestionProvider: WordSuggestionProvider

    /**
     * Cached reference to the current input view so we can resize it when the suggestion
     * bar appears or disappears without recreating the whole Compose hierarchy.
     */
    private var imeComposeView: ImeComposeView? = null

    /** Non-null while the service is bound and the engine is available. */
    private var inferenceBinder: InferenceService.InferenceBinder? = null

    /** True whenever [bindService] has been called and [unbindService] has not yet matched it. */
    private var isBound = false

    /** Used to post and cancel bar-shrink runnables on the main thread. */
    private val handler = Handler(Looper.getMainLooper())

    /**
     * The active collector of [InferenceService.engineState], launched inside
     * [onServiceConnected]. Cancelled on every unbind / disconnect / destroy so that we
     * never have two collectors racing to push state from different binder instances
     * (e.g. after [onServiceDisconnected] rebinds and a second [onServiceConnected]
     * fires, or after a deliberate unbind leaves a dead binder's StateFlow dangling).
     */
    private var engineStateCollector: Job? = null

    /**
     * Cancels [engineStateCollector] and clears the binder-derived state on the VM.
     * Called on every path that detaches us from the current binder (deliberate unbind,
     * unexpected disconnect, destroy) so no stale collector keeps emitting from a dead
     * binder and re-arming a "Ready but no repo" UI state.
     */
    private fun detachFromBinder() {
        engineStateCollector?.cancel()
        engineStateCollector = null
        inferenceBinder = null
        keyboardViewModel.setInferenceRepository(null)
    }

    private val inferenceServiceConnection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val b = binder as InferenceService.InferenceBinder
            inferenceBinder = b

            // Replace any previous collector (e.g. left over from a rebind after
            // onServiceDisconnected) so only one collector ever drives the VM.
            engineStateCollector?.cancel()
            engineStateCollector = lifecycleScope.launch {
                b.getEngineState().collect { state ->
                    keyboardViewModel.setEngineState(state)
                    keyboardViewModel.setInferenceRepository(
                        if (state == EngineState.Ready) b.getRepository() else null
                    )
                }
            }

            // Sync the repo immediately so onRecordStart does not see a Ready state with
            // a null repo during the window before the first StateFlow emission.
            keyboardViewModel.setInferenceRepository(b.getRepository())

            Log.d(TAG, "InferenceService connected - engine state: ${b.getEngineState().value}")
        }

        override fun onServiceDisconnected(name: ComponentName) {
            Log.w(TAG, "InferenceService disconnected unexpectedly - attempting rebind")
            detachFromBinder()
            // Show "Loading…" rather than "Model not downloaded" - the model is still present;
            // the service was killed by the OS (e.g. OOM) and we are about to restart it.
            keyboardViewModel.setEngineState(EngineState.Loading)
            // isBound stays true - we already held the bind and are re-establishing it.
            // BIND_AUTO_CREATE will recreate the service process and reload the engine.
            bindService(inferenceServiceIntent(), inferenceServiceConnection, BIND_AUTO_CREATE)
        }
    }

    private fun inferenceServiceIntent() = Intent(this, InferenceService::class.java)

    override fun onCreate() {
        savedStateRegistryController.performAttach()
        savedStateRegistryController.performRestore(null)
        super.onCreate()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        window?.window?.decorView?.let { decorView ->
            decorView.setViewTreeLifecycleOwner(this)
            decorView.setViewTreeViewModelStoreOwner(this)
            decorView.setViewTreeSavedStateRegistryOwner(this)
        }

        wordSuggestionProvider = WordSuggestionProvider(this)
        // Wire the correction layer to the ASR model's acoustic alternatives: the lookup
        // reads the currently bound InferenceRepository's acoustic cache (populated at
        // decode time). Returns empty when no repository is bound (engine not ready) so
        // the corrector falls back to dictionary candidates.
        wordSuggestionProvider.acousticLookup = { word ->
            inferenceBinder?.getRepository()?.getAcousticAlternatives(word) ?: emptyList()
        }

        keyboardViewModel = ViewModelProvider(
            this,
            KeyboardViewModel.Factory(
                AudioCaptureManager(this),
                AppPreferences(this),
                wordSuggestionProvider,
            ),
        )[KeyboardViewModel::class.java]

        lifecycleScope.launch {
            keyboardViewModel.isRecording.collect { recording ->
                window?.window?.let { win ->
                    if (recording) win.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else win.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }

        // If the user presses record while EngineState is Ready but
        // no InferenceRepository is bound (a binder desync), let the VM nudge us so we can
        // request a reload of the (still-present) model instead of silently opening the
        // mic with no transcription.
        keyboardViewModel.onMissingRepo = {
            inferenceBinder?.reloadIfNeeded()
            if (!isBound) {
                bindService(inferenceServiceIntent(), inferenceServiceConnection, BIND_AUTO_CREATE)
                isBound = true
            }
        }

        bindService(inferenceServiceIntent(), inferenceServiceConnection, BIND_AUTO_CREATE)
        isBound = true
        Log.d(TAG, "InferenceService bind requested")

        // Measure once up front so the first window is sized from a cache rather than lazily
        // inside composition.
        windowGeometry = computeGeometry()
    }

    override fun onBindInput() {
        super.onBindInput()
        Log.d(TAG, "onBindInput")
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        wordSuggestionProvider.open()
    }

    override fun onWindowShown() {
        super.onWindowShown()
        // Mark the already-bound foreground service as started so it survives IME recreation.
        try {
            startService(inferenceServiceIntent())
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Unable to retain inference service; keeping the existing binding", e)
        }
        // The display may have changed while the window was hidden (rotation, split-screen
        // resize) - re-measure before deciding what height to restore.
        refreshGeometry()
        // Trust the view model, not the latch: see [suggestionBarShouldBeVisible].
        val barShowing = suggestionBarShouldBeVisible()
        barVisible = barShowing
        val targetHeight = keyboardHeightPx + if (barShowing) barSlotHeightPx else 0
        Log.d(
            TAG,
            "onWindowShown barVisible=$barVisible targetHeight=$targetHeight" +
                    " imeComposeView=${imeComposeView != null} isBound=$isBound"
        )
        // Rebind if a previous unbind (process-death recovery path or a future
        // explicit unload) left us disconnected. Without this the keyboard stayed
        // permanently detached from InferenceService after any unbind.
        if (!isBound) {
            bindService(inferenceServiceIntent(), inferenceServiceConnection, BIND_AUTO_CREATE)
            isBound = true
            Log.d(TAG, "onWindowShown - rebinding InferenceService")
        }
        // The model is kept warm across app switches because the
        // service stays bound. If Android reclaimed it under memory pressure (the service
        // closed the engine and went Unloaded), reload it now so it is ready by the time
        // the user presses the talk button.
        inferenceBinder?.reloadIfNeeded()
        // The keyboard window is now visible, so the app is while-in-use eligible for the
        // microphone FGS type. Upgrade from specialUse if the service fell back to it when
        // started in the background (issue #37). No-op when already microphone.
        inferenceBinder?.promoteToFgsMicrophone()
        applyWindowHeight(targetHeight)
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        Log.d(TAG, "onWindowHidden barVisible=$barVisible")
        // Cancel any pending bar-shrink. The bar's visible state must be preserved as-is
        // while the keyboard is hidden — it is restored from barVisible when onWindowShown
        // fires. Letting the shrink runnable fire while hidden would corrupt barVisible and
        // cause the window to come back at the wrong height.
        handler.removeCallbacks(shrinkWindowRunnable)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        // Intentionally do NOT unbind here. The inference service stays
        // bound for the lifetime of the IME process so the ~700 MB Parakeet model remains
        // warm in RAM across brief app switches and keyboard hide/show cycles, matching
        // the user expectation that switching back "soon" does not require a full reload.
        // Memory pressure is handled proactively by InferenceService's ComponentCallbacks2
        // (it closes the engine and reloads on the next onWindowShown via reloadIfNeeded).
    }

    override fun onUnbindInput() {
        super.onUnbindInput()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
    }

    override fun onDestroy() {
        handler.removeCallbacks(shrinkWindowRunnable)
        detachFromBinder()
        if (isBound) {
            unbindService(inferenceServiceConnection)
            isBound = false
        }
        wordSuggestionProvider.close()
        super.onDestroy()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
    }

    /**
     * IME window geometry for the current display: navigation bar height, keyboard window height
     * and the suggestion-bar slot, all in pixels.
     */
    private data class WindowGeometry(
        val navBarPx: Int,
        val keyboardPx: Int,
        val barSlotPx: Int,
    )

    /**
     * Cached [WindowGeometry]. [KeyboardScreen] reads the navigation-bar padding out of this same
     * Compose state, so refreshing it re-pads the strip without an explicit recomposition call.
     *
     * These three values used to be `by lazy` fields, frozen at the size the display had when the
     * service was first used. The service instance outlives rotation and split-screen resizing, so
     * a strip measured in portrait kept its portrait height afterwards — clipped keys in landscape,
     * a keyboard half the screen tall in split view. [refreshGeometry] re-measures on configuration
     * change and on every window show.
     */
    private var windowGeometry by mutableStateOf<WindowGeometry?>(null)

    private val geometry: WindowGeometry
        get() = windowGeometry ?: computeGeometry().also { windowGeometry = it }

    /** Height of the navigation bar in pixels, or 0 when gesture navigation is active. */
    private val navBarHeightPx: Int get() = geometry.navBarPx

    /**
     * Total height of the IME window for the keyboard panel (excludes the suggestion bar slot):
     * 20 % of the usable screen height (above the nav bar) PLUS the nav bar height.
     *
     * Why include [navBarHeightPx]:
     * The Android IME window is anchored to the very bottom of the screen — it is NOT
     * automatically placed above the navigation bar. The window therefore must be tall
     * enough to span the nav bar area so that its top edge reaches the desired keyboard
     * height above the nav bar. Content inside the Compose tree is then pushed above the
     * nav bar via explicit [navBarHeightPx] bottom padding (passed down to [KeyboardScreen]
     * and [KeyboardTutorialOverlay]), which is more reliable than [Modifier.navigationBarsPadding]
     * because inset dispatch inside IME windows is broken on some OEM ROMs.
     */
    private val keyboardHeightPx: Int get() = geometry.keyboardPx

    /** Pixel height of the suggestion bar slot (chips row + hairline divider). */
    private val barSlotHeightPx: Int get() = geometry.barSlotPx

    /**
     * Measures [WindowGeometry] against the display as it is right now.
     *
     * The navigation bar height consults two independent sources to guard against OEM ROMs
     * (e.g. Realme UI) that return 0 from [WindowManager.currentWindowMetrics] at the
     * service-context level even when button navigation is active:
     *
     *  1. [WindowManager.currentWindowMetrics] (public API, API 30+) — primary source,
     *     correct on stock AOSP and most devices.
     *
     *  2. The `navigation_bar_height` system dimension resource — a private-but-stable
     *     internal resource present on all known Android versions and OEM ROMs. Used only
     *     as a fallback when source 1 returns 0, because on gesture-nav devices the resource
     *     may still report the physical bar height even though no space is reserved.
     *
     * On gesture navigation both sources return 0, so the result is correctly 0.
     * On button navigation, if source 1 is broken by the OEM, source 2 saves us.
     */
    private fun computeGeometry(): WindowGeometry {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager

        // Source 1: public API
        val fromWindowMetrics = wm.currentWindowMetrics.windowInsets
            .getInsets(android.view.WindowInsets.Type.navigationBars()).bottom

        // Source 2: internal system resource — only consulted when source 1 returns 0.
        val navBar = if (fromWindowMetrics > 0) {
            fromWindowMetrics
        } else {
            try {
                val resId = Resources.getSystem()
                    .getIdentifier("navigation_bar_height", "dimen", "android")
                if (resId != 0) Resources.getSystem().getDimensionPixelSize(resId) else 0
            } catch (_: Exception) {
                0
            }
        }

        val usableHeight = wm.currentWindowMetrics.bounds.height() - navBar
        // SUGGESTION_BAR_HEIGHT_DP + 1dp divider
        val barSlot = ((SUGGESTION_BAR_HEIGHT_DP + 1) * resources.displayMetrics.density + 0.5f).toInt()

        return WindowGeometry(
            navBarPx = navBar,
            keyboardPx = (usableHeight * 0.20f).toInt() + navBar,
            barSlotPx = barSlot,
        )
    }

    /**
     * Re-measures the display into [windowGeometry]; `true` when the geometry actually changed.
     * Only ever called from the main thread, never during composition.
     */
    private fun refreshGeometry(): Boolean {
        val refreshed = computeGeometry()
        if (refreshed == windowGeometry) return false
        Log.d(TAG, "geometry refreshed: $windowGeometry -> $refreshed")
        windowGeometry = refreshed
        return true
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // A rotation or split-screen resize does not re-show the window, and the window keeps
        // whatever height was last applied - so push the new size now instead of waiting for the
        // next onWindowShown (which would leave the strip at the pre-rotation height until then).
        if (refreshGeometry() && window?.window != null) {
            applyWindowHeight(keyboardHeightPx + if (barVisible) barSlotHeightPx else 0)
        }
    }

    /**
     * Whether the suggestion bar is currently intended to be visible.
     * Drives [onWindowShown] restoring to the right size and the one-shot resize logic.
     */
    private var barVisible: Boolean = false

    /**
     * Whether the suggestion bar is showing *right now*, straight from the state the Compose
     * layer renders. [barVisible] is a latch the composition drives, so after a hide/show cycle it
     * can disagree with reality: a bar dismissed while the keyboard was closing had its shrink
     * runnable cancelled by [onWindowHidden] and left the latch set. Restoring the window height
     * from that latch made the window taller than its content - a blank band whose extra inset
     * also pushed bottom-anchored hosts (chat lists) off their scroll position.
     */
    private fun suggestionBarShouldBeVisible(): Boolean =
        keyboardViewModel.wordSuggestions.value.isNotEmpty() &&
                !keyboardViewModel.suggestionBarDismissed.value

    /**
     * Resize the IME window to accommodate the suggestion bar slot.
     *
     * [targetBarPx] is 0 (hide bar slot) or [barSlotHeightPx] (show bar slot). The sentinel
     * value -1 means "use [barSlotHeightPx]" (sent by the Compose layer which does not have
     * direct access to the resolved pixel value).
     *
     * **Show path:** window grows immediately, before the visual animation starts, so the
     * underlying app gets exactly one layout event up front and then sees the bar slide in
     * smoothly — no per-frame jank.
     *
     * **Hide path:** window shrinks after a brief delay that matches the visual animation
     * duration ([SUGGESTION_BAR_HIDE_DELAY_MS]). The app keeps its position while the bar
     * slides away, then snaps back down once — again a single layout event.
     */
    private fun updateImeHeight(targetBarPx: Int) {
        val resolvedBarPx = if (targetBarPx == -1) barSlotHeightPx else targetBarPx
        val newVisible = resolvedBarPx > 0
        Log.d(TAG, "updateImeHeight targetBarPx=$targetBarPx newVisible=$newVisible barVisible=$barVisible")
        handler.removeCallbacks(shrinkWindowRunnable)

        if (newVisible) {
            // Show: grow window immediately before animation starts.
            if (!barVisible) {
                barVisible = true
                applyWindowHeight(keyboardHeightPx + barSlotHeightPx)
            }
        } else {
            // Hide: schedule shrink to fire after the slide-out animation completes.
            if (barVisible) {
                handler.postDelayed(shrinkWindowRunnable, SUGGESTION_BAR_HIDE_DELAY_MS)
            }
        }
    }

    /**
     * How long to wait after the bar's hide signal before shrinking the window.
     * Should be >= the visual animation duration so the window never shrinks mid-animation.
     * A small additional buffer (50 ms) absorbs frame-timing variance.
     */
    // Must be >= SUGGESTION_BAR_ANIM_DURATION_MS (300 ms) or the window shrinks mid-slide and
    // cuts the bar's exit animation short; + 20 ms absorbs frame-timing variance. The bar
    // collapses its slot 50 ms before this fires, so the two never fight over the same pixels.
    private val SUGGESTION_BAR_HIDE_DELAY_MS = 320L

    /** Runnable that actually shrinks the window after the bar hide animation completes. */
    private val shrinkWindowRunnable = Runnable {
        barVisible = false
        applyWindowHeight(keyboardHeightPx)
    }

    /**
     * Apply [height] to both the compose view layout params and the IME window attributes.
     *
     * The write is skipped when the window attributes *and* the view's layout params already
     * carry [height]. The attributes are what the host app sees, so they are the value worth
     * comparing against: an unconditional write on every window show re-reported the IME inset
     * after the show animation had already started, which is what made a bottom-anchored chat
     * jump when the keyboard appeared. The view's layout params are checked as well because the
     * framework can resize the window itself while the keyboard is hidden, leaving a stale
     * cached value behind; either disagreement triggers the write.
     */
    private fun applyWindowHeight(height: Int) {
        val win = window?.window
        val view = imeComposeView
        val attrsHeight = win?.attributes?.height
        if (attrsHeight == height && (view == null || view.layoutParams?.height == height)) {
            Log.d(TAG, "applyWindowHeight($height) - window already at height, nothing to do")
            return
        }

        if (view == null) {
            // The framework sometimes reuses the decor view without calling onCreateInputView
            // again (mViewsCreated=true path). In that case imeComposeView is null but the
            // window still exists and must be resized directly.
            Log.d(TAG, "applyWindowHeight($height) — imeComposeView null, applying to window only")
            win?.also { it.attributes = it.attributes.apply { this.height = height } }
            return
        }

        Log.d(TAG, "applyWindowHeight($height) currentHeight=${view.layoutParams?.height} attrs=$attrsHeight")
        view.layoutParams = view.layoutParams.also { it.height = height }

        win?.also { it.attributes = it.attributes.apply { this.height = height } }

        view.requestLayout()
    }

    override fun onCreateInputView(): View {
        val currentlyVisible = suggestionBarShouldBeVisible()
        barVisible = currentlyVisible
        val initialHeight = keyboardHeightPx + (if (barVisible) barSlotHeightPx else 0)
        Log.d(TAG, "onCreateInputView barVisible=$barVisible initialHeight=$initialHeight")
        return ImeComposeView(
            context = this,
            lifecycleOwner = this,
            viewModelStoreOwner = this,
            savedStateRegistryOwner = this,
        ).also { view ->
            imeComposeView = view
            view.layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                initialHeight,
            )
            // Pin the window to the correct height immediately so the OS never briefly
            // shows a mis-sized IME frame before the first layout pass arrives.
            window?.window?.also { win ->
                val attrs = win.attributes
                attrs.height = initialHeight
                win.attributes = attrs
            }
            ViewCompat.requestApplyInsets(view)
            view.setKeyboardContent {
                OutspokeKeyboardTheme {
                    KeyboardScreen(
                        viewModel = keyboardViewModel,
                        onSwitchKeyboard = {
                            if (!switchToPreviousInputMethod()) {
                                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                                    .showInputMethodPicker()
                            }
                        },
                        onOpenCompanionApp = {
                            startActivity(
                                PermissionHelper.requestPermissionIntent()
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        },
                        onSuggestionBarHeightChanged = { barPx -> updateImeHeight(barPx) },
                        navBarHeightPx = navBarHeightPx,
                    )
                }
            }
        }
    }

    /**
     * Never enter fullscreen (extract-text) mode - the keyboard panel is always compact.
     */
    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onFinishInputView(finishingInput: Boolean) {
        Log.d(TAG, "onFinishInputView finishingInput=$finishingInput barVisible=$barVisible")
        super.onFinishInputView(finishingInput)
        // Deliberately keep [imeComposeView]: the framework reuses the same input view across
        // hide/show cycles without calling onCreateInputView again, and a null reference here
        // forced every later resize down the "window attributes only" path - the window then grew
        // or shrank while the retained view kept measuring at the old height, painting a blank
        // band and reporting an inset that no content filled.
    }

    /**
     * Report insets to the framework so the focused app scrolls its content above the
     * keyboard correctly, and so that the IME window properly intercepts touch events.
     *
     * The IME window height equals [keyboardHeightPx] + the current bar height, and grows
     * or shrinks in sync with the suggestion bar animation via [updateImeHeight]. Setting
     * [Insets.contentTopInsets] and [Insets.visibleTopInsets] to 0 tells the framework
     * that the IME content occupies the full window height, so the app above scrolls up
     * by the full window height — exactly matching GBoard's behaviour.
     *
     * [Insets.TOUCHABLE_INSETS_FRAME] ensures the full window frame consumes all touch
     * events — without this the default TOUCHABLE_INSETS_VISIBLE mode would compute a
     * zero-height touchable region and let taps fall through to the app underneath.
     */
    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        outInsets.contentTopInsets = 0
        outInsets.visibleTopInsets = 0
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_FRAME
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        Log.d(TAG, "onStartInput restarting=$restarting")
        // Open the spell-checker session here as a fallback — on some devices onBindInput
        // fires after onStartInput, or not at all. open() is idempotent.
        wordSuggestionProvider.open()
        val connection = currentInputConnection ?: return
        keyboardViewModel.setTextInjector(
            TextInjector(
                connection,
                attribute ?: EditorInfo(),
                // Display-clean with the user's current language so the field gets the same
                // language-aware cleaning the repository's partial/final cleaning uses
                // (filler removal, number normalisation, structural). Reading the
                // StateFlow snapshot here is safe — currentLanguage is collected eagerly and
                // always has a value.
                //
                // isSentenceStart tells the cleaner whether the chunk head begins a
                // sentence: without it every frozen chunk / composing span would be
                // capitalised mid-sentence (the cleaner capitalises its first word).
                //
                // skipSpuriousPeriods: the spurious-period filter already ran on the FULL
                // transcript in the repository (partial + final). Re-running it here on a
                // frozen substring would misfire on the substring's first segment (the tail
                // of a longer sentence) and delete a genuine sentence-final period, so it
                // is skipped — the authoritative full-text periods are preserved.
                displayCleanFn = { text, isSentenceStart ->
                    text.cleanTranscript(
                        isContinuation = !isSentenceStart,
                        language = keyboardViewModel.currentLanguage.value,
                        skipSpuriousPeriods = true,
                    )
                },
            )
        )
        Log.d(TAG, "onStartInput - engine: ${inferenceBinder?.getEngineState()?.value}")
    }

    /**
     * Detects when the focused text field is cleared externally - the primary signal for
     * "the user pressed Send and the app wiped the EditText".
     *
     * Most messaging and chat apps clear the field in-place without triggering
     * [onFinishInput] / [onStartInput], so this callback is the only reliable hook.
     *
     * Strategy: the update is only interesting when the cursor moves to (0, 0).  We then
     * do a cheap two-byte IPC read to confirm the field is genuinely empty (as opposed to
     * the user merely clicking at the very start of a non-empty field).  If it is empty we
     * forward to [KeyboardViewModel.onFieldCleared], which resets [TextInjector] alignment
     * state and, if recording is active, restarts the capture with a fresh audio window.
     */
    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int,
        newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(
            oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd
        )

        // Notify the suggestion bar about the word under the (possibly new) cursor position.
        keyboardViewModel.updateWordAtCursor()

        // Fast-path exit: the vast majority of selection updates have newSelStart > 0
        // (cursor after injected text, user tapped elsewhere, etc.).  Skip the IPC calls
        // unless the cursor landed at position 0.
        if (newSelStart != 0 || newSelEnd != 0) return

        // Also skip if the selection was already at (0,0) - no meaningful change occurred.
        if (oldSelStart == 0 && oldSelEnd == 0) return

        // Confirm the field is truly empty (cursor at 0 can also mean the user tapped at
        // the beginning of a non-empty field).
        val connection = currentInputConnection ?: return
        val before = connection.getTextBeforeCursor(1, 0)
        val after = connection.getTextAfterCursor(1, 0)
        if (before != null && before.isEmpty() && after != null && after.isEmpty()) {
            Log.d(TAG, "onUpdateSelection: field cleared externally → onFieldCleared")
            keyboardViewModel.onFieldCleared()
        }
    }

    override fun onFinishInput() {
        super.onFinishInput()
        keyboardViewModel.commitPartialAndStop()
        keyboardViewModel.setTextInjector(null)
    }
}



