package dev.brgr.outspoke.ime

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.inputmethodservice.InputMethodService
import android.os.IBinder
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.core.view.ViewCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dev.brgr.outspoke.audio.AudioCaptureManager
import dev.brgr.outspoke.audio.PermissionHelper
import dev.brgr.outspoke.inference.EngineState
import dev.brgr.outspoke.inference.InferenceService
import dev.brgr.outspoke.settings.preferences.AppPreferences
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import dev.brgr.outspoke.ui.keyboard.ImeComposeView
import dev.brgr.outspoke.ui.keyboard.KeyboardScreen
import dev.brgr.outspoke.ui.keyboard.KeyboardViewModel
import dev.brgr.outspoke.ui.theme.OutspokeKeyboardTheme

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

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    // -------------------------------------------------------------------------
    // ViewModel store
    // -------------------------------------------------------------------------

    private val store = ViewModelStore()
    override val viewModelStore: ViewModelStore get() = store

    // -------------------------------------------------------------------------
    // Saved state
    // -------------------------------------------------------------------------

    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    // -------------------------------------------------------------------------
    // ViewModel instances
    // -------------------------------------------------------------------------

    private lateinit var keyboardViewModel: KeyboardViewModel

    // -------------------------------------------------------------------------
    // InferenceService binding
    // -------------------------------------------------------------------------

    /** Non-null while the service is bound and the engine is available. */
    private var inferenceBinder: InferenceService.InferenceBinder? = null

    private val inferenceServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val b = binder as InferenceService.InferenceBinder
            inferenceBinder = b

            // Eagerly wire the repository — will be null if the engine is still loading,
            // but the collect block below re-fetches it once the engine becomes Ready.
            keyboardViewModel.setInferenceRepository(b.getRepository())

            // Forward every engine-state change to the ViewModel and keep the repository
            // reference in sync.  The repository is only non-null when the engine is Ready;
            // any other transition (Unloaded, Loading, Error) means inference is unavailable.
            lifecycleScope.launch {
                b.getEngineState().collect { state ->
                    keyboardViewModel.setEngineState(state)
                    // Re-fetch the repository on every state change so that a transition
                    // from Loading → Ready (which happens after the initial bind) correctly
                    // wires the newly created InferenceRepository into the ViewModel.
                    keyboardViewModel.setInferenceRepository(
                        if (state == EngineState.Ready) b.getRepository() else null
                    )
                }
            }

            Log.d(TAG, "InferenceService connected — engine state: ${b.getEngineState().value}")
        }

        override fun onServiceDisconnected(name: ComponentName) {
            Log.w(TAG, "InferenceService disconnected unexpectedly")
            inferenceBinder = null
            keyboardViewModel.setInferenceRepository(null)
            keyboardViewModel.setEngineState(EngineState.Unloaded)
        }
    }

    private fun inferenceServiceIntent() = Intent(this, InferenceService::class.java)

    // -------------------------------------------------------------------------
    // Service lifecycle → Compose lifecycle mapping
    // -------------------------------------------------------------------------

    override fun onCreate() {
        // SavedState must be attached and restored before super.onCreate() so the
        // registry is ready for any component that reads it during initialisation.
        savedStateRegistryController.performAttach()
        savedStateRegistryController.performRestore(null)
        super.onCreate()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        // Compose's WindowRecomposer looks for ViewTreeLifecycleOwner on the *root view*
        // of the IME window (the LinearLayout parent added by InputMethodService), not on
        // the ComposeView itself. We must tag the window's decorView so the walk-up search
        // succeeds when the keyboard is first shown.
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

        // Start the inference engine as a foreground service so it survives the
        // IME going to background, and bind so we can access InferenceRepository.
        startForegroundService(inferenceServiceIntent())
        bindService(inferenceServiceIntent(), inferenceServiceConnection, Context.BIND_AUTO_CREATE)
        Log.d(TAG, "InferenceService start + bind requested")
    }

    override fun onBindInput() {
        super.onBindInput()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
    }

    override fun onWindowShown() {
        super.onWindowShown()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
    }

    override fun onUnbindInput() {
        super.onUnbindInput()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
    }

    override fun onDestroy() {
        // Unbind but do NOT stop the service — let the OS manage it so the model
        // stays loaded across brief keyboard hide/show cycles.
        unbindService(inferenceServiceConnection)
        inferenceBinder = null
        super.onDestroy()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
    }

    // -------------------------------------------------------------------------
    // IME view
    // -------------------------------------------------------------------------

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
            // Pin the view to exactly the keyboard height so Compose doesn't try to
            // fill the entire IME window.
            view.layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                keyboardHeightPx,
            )
            // Request that window insets be re-dispatched once the view is attached to the
            // window. This ensures Compose's LocalWindowInsets is populated so that
            // navigationBarsPadding() inside KeyboardScreen works correctly.
            ViewCompat.requestApplyInsets(view)
            view.setKeyboardContent {
                OutspokeKeyboardTheme {
                    KeyboardScreen(
                        viewModel = keyboardViewModel,
                        onSwitchKeyboard = {
                            // switchToPreviousInputMethod() returns false if there is no
                            // previous IME recorded (e.g. first launch). Fall back to the
                            // system IME picker dialog so the user is never left stranded.
                            if (!switchToPreviousInputMethod()) {
                                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                                    .showInputMethodPicker()
                            }
                        },
                        onOpenCompanionApp = {
                            // IME is a Service — must add NEW_TASK so Android allows
                            // launching an Activity from a non-Activity context.
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
     * Never enter fullscreen (extract-text) mode — the keyboard panel is always compact.
     */
    override fun onEvaluateFullscreenMode(): Boolean = false

    /**
     * Report insets to the framework so the focused app scrolls its content above the
     * keyboard correctly, and so that the IME window properly intercepts touch events.
     *
     * The IME window is sized to exactly [keyboardHeightPx] and sits at the bottom of
     * the screen, so our content starts at Y=0 within the window.
     * [Insets.TOUCHABLE_INSETS_FRAME] ensures the full window frame consumes all touch
     * events — without this the default TOUCHABLE_INSETS_VISIBLE mode would compute a
     * zero-height touchable region (visibleTopInsets == windowHeight) and let every tap
     * fall through to the app underneath.
     */
    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        // Content and visible regions start at the very top of our keyboard window (Y = 0).
        // This tells the framework to push the focused app up by the full keyboardHeightPx,
        // so the app's bottom edge sits exactly at the top of the keyboard panel.
        // keyboardHeightPx already accounts for the nav-bar area, so the app is never
        // partially covered regardless of whether 3-button or gesture navigation is active.
        outInsets.contentTopInsets = 0
        outInsets.visibleTopInsets = 0
        // Make the entire window frame touchable — prevents tap-through to the app below.
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_FRAME
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        // Build a fresh TextInjector for this input session.
        // currentInputConnection is guaranteed non-null here by the IME framework.
        val connection = currentInputConnection ?: return
        keyboardViewModel.setTextInjector(
            TextInjector(connection, attribute ?: EditorInfo())
        )
        Log.d(TAG, "onStartInput — engine: ${inferenceBinder?.getEngineState()?.value}")
    }

    override fun onFinishInput() {
        super.onFinishInput()
        // Step 30: commit any in-progress partial composing text as final before the
        // InputConnection is torn down, then cancel the capture job. This ensures no
        // underlined composing text is left behind when the user switches apps.
        keyboardViewModel.commitPartialAndStop()
        // Safe to null the injector now — all writes to the InputConnection are done.
        keyboardViewModel.setTextInjector(null)
    }
}



