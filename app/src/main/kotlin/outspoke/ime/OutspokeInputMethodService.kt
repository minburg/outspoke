package dev.brgr.outspoke.ime

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
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
import kotlinx.coroutines.launch

private const val TAG = "OutspokeIME"

/**
 * How long (ms) the keyboard must stay hidden before the inference engine is unloaded
 * from RAM. Cancelled immediately if the keyboard reappears within this window.
 * 30 s is long enough to cover brief app-switches but short enough to reclaim model
 * memory (500 MB+) before the OS has to do it forcefully via an OOM kill.
 */
private const val IDLE_UNLOAD_DELAY_MS = 30_000L

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

    /** Used to post and cancel the idle-unload runnable on the main thread. */
    private val handler = Handler(Looper.getMainLooper())

    /**
     * Posted by [onWindowHidden] and cancelled by [onWindowShown].
     * When it fires the service is unbound so Android can destroy it and free the
     * model sessions from RAM. The engine reloads transparently on next [onWindowShown].
     */
    private val idleUnloadRunnable = Runnable {
        if (isBound) {
            Log.d(
                TAG,
                "Keyboard idle for ${IDLE_UNLOAD_DELAY_MS / 1000}s - unbinding InferenceService to free model RAM"
            )
            unbindService(inferenceServiceConnection)
            isBound = false
            inferenceBinder = null
            keyboardViewModel.setInferenceRepository(null)
            keyboardViewModel.setEngineState(EngineState.Loading)
        }
    }

    private val inferenceServiceConnection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val b = binder as InferenceService.InferenceBinder
            inferenceBinder = b

            keyboardViewModel.setInferenceRepository(b.getRepository())

            lifecycleScope.launch {
                b.getEngineState().collect { state ->
                    keyboardViewModel.setEngineState(state)
                    keyboardViewModel.setInferenceRepository(
                        if (state == EngineState.Ready) b.getRepository() else null
                    )
                }
            }

            Log.d(TAG, "InferenceService connected - engine state: ${b.getEngineState().value}")
        }

        override fun onServiceDisconnected(name: ComponentName) {
            Log.w(TAG, "InferenceService disconnected unexpectedly - attempting rebind")
            inferenceBinder = null
            keyboardViewModel.setInferenceRepository(null)
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

        keyboardViewModel = ViewModelProvider(
            this,
            KeyboardViewModel.Factory(
                AudioCaptureManager(this),
                AppPreferences(this),
                wordSuggestionProvider,
            ),
        )[KeyboardViewModel::class.java]

        // When the keyboard is reshown after being hidden, sync the window height to the
        // current bar state. The per-frame animation callback handles all transitions while
        // the keyboard is visible; this is only needed for the initial show after a hide.
        lifecycleScope.launch {
            keyboardViewModel.wordSuggestions.collect { }
        }

        bindService(inferenceServiceIntent(), inferenceServiceConnection, BIND_AUTO_CREATE)
        isBound = true
        Log.d(TAG, "InferenceService bind requested")
    }

    override fun onBindInput() {
        super.onBindInput()
        Log.d(TAG, "onBindInput")
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        wordSuggestionProvider.open()
    }

    override fun onWindowShown() {
        super.onWindowShown()
        handler.removeCallbacks(idleUnloadRunnable)
        val targetHeight = keyboardHeightPx + (if (barVisible) barSlotHeightPx else 0)
        Log.d(
            TAG,
            "onWindowShown barVisible=$barVisible targetHeight=$targetHeight imeComposeView=${imeComposeView != null}"
        )
        applyWindowHeight(targetHeight, force = true)
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
        handler.postDelayed(idleUnloadRunnable, IDLE_UNLOAD_DELAY_MS)
    }

    override fun onUnbindInput() {
        super.onUnbindInput()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
    }

    override fun onDestroy() {
        handler.removeCallbacks(idleUnloadRunnable)
        handler.removeCallbacks(shrinkWindowRunnable)
        if (isBound) {
            unbindService(inferenceServiceConnection)
            isBound = false
        }
        inferenceBinder = null
        wordSuggestionProvider.close()
        super.onDestroy()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
    }

    /**
     * Height of the navigation bar in pixels, or 0 when gesture navigation is active.
     * Computed lazily using [WindowManager.currentWindowMetrics] (API 30+).
     */
    private val navBarHeightPx: Int by lazy {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        wm.currentWindowMetrics.windowInsets
            .getInsets(android.view.WindowInsets.Type.navigationBars()).bottom
    }

    /**
     * Total height of the keyboard panel in pixels (visible content only — excludes
     * the suggestion bar slot). = 20% of the usable screen height (above nav bar) + nav bar.
     */
    private val keyboardHeightPx: Int by lazy {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val screenHeight = wm.currentWindowMetrics.bounds.height()
        val usableHeight = screenHeight - navBarHeightPx
        (usableHeight * 0.20f).toInt() + navBarHeightPx
    }

    /**
     * Pixel height of the suggestion bar slot (chips row + hairline divider).
     * Pre-computed from the dp constant so we never run density resolution on main thread
     * from a background context.
     */
    private val barSlotHeightPx: Int by lazy {
        // SUGGESTION_BAR_HEIGHT_DP + 1dp divider = SUGGESTION_BAR_HEIGHT_DP + 1
        val density = resources.displayMetrics.density
        ((SUGGESTION_BAR_HEIGHT_DP + 1) * density + 0.5f).toInt()
    }

    /**
     * Whether the suggestion bar is currently intended to be visible.
     * Drives [onWindowShown] restoring to the right size and the one-shot resize logic.
     */
    private var barVisible: Boolean = false

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
    private val SUGGESTION_BAR_HIDE_DELAY_MS = 270L  // 220 ms anim + 50 ms buffer

    /** Runnable that actually shrinks the window after the bar hide animation completes. */
    private val shrinkWindowRunnable = Runnable {
        barVisible = false
        applyWindowHeight(keyboardHeightPx)
    }

    /**
     * Apply [height] to both the compose view layout params and the IME window attributes.
     *
     * The [force] flag bypasses the equality guard on the view's layout params — necessary
     * after the keyboard window is hidden and reshown, because the system may have silently
     * overridden the actual window height while our [imeComposeView] still reports the old
     * cached value in its [android.view.ViewGroup.LayoutParams].
     */
    private fun applyWindowHeight(height: Int, force: Boolean = false) {
        val view = imeComposeView
        if (view == null) {
            // The framework sometimes reuses the decor view without calling onCreateInputView
            // again (mViewsCreated=true path). In that case imeComposeView is null but the
            // window still exists and must be resized directly.
            Log.d(TAG, "applyWindowHeight($height) — imeComposeView null, applying to window only")
            window?.window?.also { win ->
                val attrs = win.attributes
                if (!force && attrs.height == height) return
                attrs.height = height
                win.attributes = attrs
            }
            return
        }

        val currentHeight = view.layoutParams?.height ?: return
        Log.d(TAG, "applyWindowHeight($height) force=$force currentHeight=$currentHeight")
        if (!force && currentHeight == height) return

        view.layoutParams = view.layoutParams.also { it.height = height }

        window?.window?.also { win ->
            val attrs = win.attributes
            attrs.height = height
            win.attributes = attrs
        }

        view.requestLayout()
    }

    override fun onCreateInputView(): View {
        val currentlyVisible = keyboardViewModel.wordSuggestions.value.isNotEmpty() &&
                !keyboardViewModel.suggestionBarDismissed.value
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
                        keyboardContentHeightPx = keyboardHeightPx,
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
        imeComposeView = null
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
                displayCleanFn = { text -> text.cleanTranscript() },
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



