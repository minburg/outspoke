package dev.brgr.outspoke.inference

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.FileObserver
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dev.brgr.outspoke.R
import dev.brgr.outspoke.settings.model.ModelStorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

private const val TAG = "InferenceService"
private const val CHANNEL_ID = "outspoke_inference"
private const val NOTIFICATION_ID = 1001

/**
 * A foreground [LifecycleService] that owns the [SpeechEngine] lifecycle.
 *
 * Responsibilities:
 *  - Show a persistent low-priority notification so Android keeps the process alive
 *    while the keyboard is active
 *  - Load the [SpeechEngine] on startup (background coroutine on [Dispatchers.Default])
 *  - Expose [InferenceRepository] to bound clients (the IME service) via [InferenceBinder]
 *  - Cleanly close the engine on [onDestroy]
 *
 * Lifecycle:
 *  `startForegroundService` → `onCreate` (load model) → `onBind` → …
 *  → `unbindService` → (OS decides to stop) → `onDestroy` (close engine)
 */
class InferenceService : LifecycleService() {

    // -------------------------------------------------------------------------
    // Engine state
    // -------------------------------------------------------------------------

    private val engine: SpeechEngine = ParakeetEngine()
    private val repository by lazy { InferenceRepository(engine) }

    /** Mutex preventing concurrent [loadEngine] calls from racing on the load/loaded check. */
    private val engineLoadMutex = Mutex()

    private val _engineState = MutableStateFlow<EngineState>(EngineState.Unloaded)

    /** Observable loading / runtime state. Observed by the IME (Step 29). */
    val engineState: StateFlow<EngineState> = _engineState.asStateFlow()

    // -------------------------------------------------------------------------
    // Binder
    // -------------------------------------------------------------------------

    inner class InferenceBinder : Binder() {
        fun getRepository(): InferenceRepository = repository
        fun getEngineState(): StateFlow<EngineState> = engineState
    }

    private val binder = InferenceBinder()

    override fun onBind(intent: android.content.Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification("Loading transcription engine…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )

        Log.d(TAG, "Service created — loading engine")
        lifecycleScope.launch(Dispatchers.Default) {
            loadEngine()
        }
        // Watch for model file changes so the engine auto-unloads on deletion
        // and auto-reloads when the companion app finishes a download.
        startModelWatcher()
    }

    override fun onDestroy() {
        modelFileObserver?.stopWatching()
        modelFileObserver = null
        currentWatchDir = null
        super.onDestroy()
        engine.close()
        Log.d(TAG, "Service destroyed — engine closed")
    }

    // -------------------------------------------------------------------------
    // Model directory watcher
    // -------------------------------------------------------------------------

    /**
     * Watches the closest existing ancestor of the model directory so we detect
     * both deletion (from the companion app) and installation (download complete).
     *
     * The observer is only recreated when the watched directory actually changes —
     * this prevents creating thousands of short-lived observer objects during a download
     * that produces many CREATE/CLOSE_WRITE events in quick succession.
     */
    private var modelFileObserver: FileObserver? = null
    private var currentWatchDir: File? = null

    private fun startModelWatcher() {
        val context = applicationContext
        val modelDir = ModelStorageManager.getModelDir(context)

        // Walk up to the first ancestor that exists.
        val watchDir = sequenceOf(modelDir, modelDir.parentFile, context.filesDir)
            .firstOrNull { it != null && it.exists() } ?: return

        // Skip recreating the observer if we are already watching the right directory.
        if (watchDir == currentWatchDir) return

        modelFileObserver?.stopWatching()
        currentWatchDir = watchDir

        modelFileObserver = object : FileObserver(watchDir, ALL_EVENTS) {
            override fun onEvent(event: Int, path: String?) {
                val mask = event and ALL_EVENTS
                // React on any delete, create, or completed-write event.
                if (mask and (DELETE or DELETE_SELF or MOVED_FROM or
                              CREATE or MOVED_TO or CLOSE_WRITE) != 0) {
                    onModelDirectoryChanged()
                }
            }
        }.also { it.startWatching() }

        Log.d(TAG, "FileObserver started on: $watchDir")
    }

    /**
     * Called (from a FileObserver background thread) whenever something changes
     * in the model directory hierarchy. Dispatches reactions on [Dispatchers.Default].
     */
    private fun onModelDirectoryChanged() {
        lifecycleScope.launch(Dispatchers.Default) {
            val context = applicationContext
            val isReady = ModelStorageManager.isModelReady(context)
            val currentState = _engineState.value

            when {
                !isReady && currentState == EngineState.Ready -> {
                    // Model was deleted while the engine was running.
                    Log.w(TAG, "Model files removed — transitioning to Unloaded")
                    engine.close()
                    _engineState.value = EngineState.Unloaded
                    updateNotification("Model not downloaded — open Outspoke to download")
                    // Re-watch so we can detect re-installation.
                    startModelWatcher()
                }
                isReady && currentState == EngineState.Unloaded -> {
                    // Model was installed (download completed) while the engine was idle.
                    Log.d(TAG, "Model files appeared — reloading engine")
                    loadEngine()
                    // Restart the watcher on the (now existing) model directory.
                    startModelWatcher()
                }
            }
        }
    }

    private suspend fun loadEngine() {
        engineLoadMutex.withLock {
            // Guard against a concurrent call that already completed loading.
            if (engine.isLoaded) return

            val context = applicationContext

            if (!ModelStorageManager.isModelReady(context)) {
                Log.w(TAG, "Model files not present — engine stays Unloaded")
                _engineState.value = EngineState.Unloaded
                updateNotification("Model not downloaded — open Outspoke to download")
                return
            }

            _engineState.value = EngineState.Loading
            updateNotification("Loading transcription engine…")

            try {
                val modelDir = ModelStorageManager.getModelDir(context)
                engine.load(modelDir)
                _engineState.value = EngineState.Ready
                updateNotification("Outspoke ready")
                Log.d(TAG, "Engine loaded successfully")
            } catch (e: Exception) {
                val msg = e.localizedMessage ?: "Unknown error"
                Log.e(TAG, "Engine load failed: $msg", e)
                _engineState.value = EngineState.Error(msg)
                updateNotification("Engine failed to load: $msg")
            }
        }
    }

    // -------------------------------------------------------------------------
    // Notification helpers
    // -------------------------------------------------------------------------

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Outspoke Voice Transcription",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shown while the voice transcription engine is active"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Outspoke")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }
}

