package dev.brgr.outspoke.ime

import android.content.ComponentName
import android.content.Context
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
import dev.brgr.outspoke.settings.preferences.AppPreferences
import dev.brgr.outspoke.ui.keyboard.ImeComposeView
import dev.brgr.outspoke.ui.keyboard.KeyboardScreen
import dev.brgr.outspoke.ui.keyboard.KeyboardViewModel
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

        keyboardViewModel = ViewModelProvider(
            this,
            KeyboardViewModel.Factory(
                AudioCaptureManager(this),
                AppPreferences(this),
            ),
        )[KeyboardViewModel::class.java]

        bindService(inferenceServiceIntent(), inferenceServiceConnection, BIND_AUTO_CREATE)
        isBound = true
        Log.d(TAG, "InferenceService bind requested")
    }

    override fun onBindInput() {
        super.onBindInput()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
    }

    override fun onWindowShown() {
        super.onWindowShown()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        // Cancel a pending idle-unload - the user is back before the timeout fired.
        handler.removeCallbacks(idleUnloadRunnable)
        // If the timeout already fired and we unloaded, rebind now so the engine reloads.
        if (!isBound) {
            Log.d(TAG, "Keyboard shown after idle unload - rebinding InferenceService")
            bindService(inferenceServiceIntent(), inferenceServiceConnection, BIND_AUTO_CREATE)
            isBound = true
        }
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        // Schedule an engine unload. Cancelled immediately if the keyboard reappears soon.
        handler.postDelayed(idleUnloadRunnable, IDLE_UNLOAD_DELAY_MS)
    }

    override fun onUnbindInput() {
        super.onUnbindInput()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
    }

    override fun onDestroy() {
        handler.removeCallbacks(idleUnloadRunnable)
        if (isBound) {
            unbindService(inferenceServiceConnection)
            isBound = false
        }
        inferenceBinder = null
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
     * Total height of the keyboard panel in pixels.
     * = 25 % of the usable screen height (above the navigation bar) + the navigation bar itself,
     * so the visible content area is always 25 % of the space the user can actually see.
     */
    private val keyboardHeightPx: Int by lazy {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val screenHeight = wm.currentWindowMetrics.bounds.height()
        val usableHeight = screenHeight - navBarHeightPx
        (usableHeight * 0.25f).toInt() + navBarHeightPx
    }

    override fun onCreateInputView(): View {
        return ImeComposeView(
            context = this,
            lifecycleOwner = this,
            viewModelStoreOwner = this,
            savedStateRegistryOwner = this,
        ).also { view ->
            view.layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                keyboardHeightPx,
            )
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
                    )
                }
            }
        }
    }

    /**
     * Never enter fullscreen (extract-text) mode - the keyboard panel is always compact.
     */
    override fun onEvaluateFullscreenMode(): Boolean = false

    /**
     * Report insets to the framework so the focused app scrolls its content above the
     * keyboard correctly, and so that the IME window properly intercepts touch events.
     *
     * The IME window is sized to exactly [keyboardHeightPx] and sits at the bottom of
     * the screen, so our content starts at Y=0 within the window.
     * [Insets.TOUCHABLE_INSETS_FRAME] ensures the full window frame consumes all touch
     * events - without this the default TOUCHABLE_INSETS_VISIBLE mode would compute a
     * zero-height touchable region (visibleTopInsets == windowHeight) and let every tap
     * fall through to the app underneath.
     */
    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        outInsets.contentTopInsets = 0
        outInsets.visibleTopInsets = 0
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_FRAME
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        val connection = currentInputConnection ?: return
        keyboardViewModel.setTextInjector(
            TextInjector(connection, attribute ?: EditorInfo())
        )
        Log.d(TAG, "onStartInput - engine: ${inferenceBinder?.getEngineState()?.value}")
    }

    override fun onFinishInput() {
        super.onFinishInput()
        keyboardViewModel.commitPartialAndStop()
        keyboardViewModel.setTextInjector(null)
    }
}



