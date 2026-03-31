package dev.brgr.outspoke.inference

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Debug
import android.os.FileObserver
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dev.brgr.outspoke.R
import dev.brgr.outspoke.settings.model.ModelId
import dev.brgr.outspoke.settings.model.ModelRegistry
import dev.brgr.outspoke.settings.model.ModelStorageManager
import dev.brgr.outspoke.settings.preferences.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
 *  - Observe [AppPreferences.selectedModelId] and load the appropriate [SpeechEngine]
 *    via [SpeechEngineFactory] whenever the selection changes
 *  - Watch the `models/` directory for file-system changes so the engine auto-reloads
 *    after a download completes and auto-unloads when model files are deleted
 *  - Expose [InferenceRepository] to bound clients (the IME) via [InferenceBinder]
 *  - Cleanly close the engine on [onDestroy]
 */
class InferenceService : LifecycleService() {

    /** The currently loaded engine, or `null` while loading / unloaded. */
    @Volatile
    private var currentEngine: SpeechEngine? = null

    /** Repository wrapping [currentEngine]. Rebuilt each time the engine changes. */
    @Volatile
    private var currentRepository: InferenceRepository? = null

    /** Mutex preventing concurrent [reloadForModel] calls from racing. */
    private val engineLoadMutex = Mutex()

    private val _engineState = MutableStateFlow<EngineState>(EngineState.Loading)

    /** Observable loading / runtime state observed by the IME. */
    val engineState: StateFlow<EngineState> = _engineState.asStateFlow()

    inner class InferenceBinder : Binder() {
        /** Returns the active [InferenceRepository], or `null` while the engine is loading. */
        fun getRepository(): InferenceRepository? = currentRepository
        fun getEngineState(): StateFlow<EngineState> = engineState
    }

    private val binder = InferenceBinder()

    override fun onBind(intent: android.content.Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification("Loading transcription engine…"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } catch (e: SecurityException) {
            // startForeground() is rejected when the service is started via the IME binding
            // (no Activity in the foreground).  We continue running as a plain bound service -
            // no persistent notification, but the engine loads and the keyboard stays functional.
            // The notification will appear the next time the user opens the companion Activity.
            Log.w(TAG, "startForeground rejected - running as bound service without notification", e)
        }

        // Log device and memory info at service startup
        val am = getSystemService(ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        Log.i(TAG, "Service created on device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        Log.i(TAG, "Total RAM: ${memInfo.totalMem / (1024 * 1024)} MB, Avail: ${memInfo.availMem / (1024 * 1024)} MB, LowMem: ${memInfo.lowMemory}")

        Log.d(TAG, "Service created - observing selected model preference")

        // Observe the selected model preference and reload the engine whenever it changes.
        lifecycleScope.launch(Dispatchers.Default) {
            AppPreferences(applicationContext).selectedModelId.collect { modelId ->
                reloadForModel(modelId)
            }
        }

        // Watch the models/root directory to detect external changes (e.g. download complete).
        startModelWatcher()
    }

    override fun onDestroy() {
        modelFileObserver?.stopWatching()
        modelFileObserver = null
        currentWatchDir = null
        super.onDestroy()
        currentEngine?.close()
        currentEngine = null
        currentRepository = null
        logMemoryUsage()
        Log.d(TAG, "Service destroyed - engine closed")
    }

    /**
     * Closes any existing engine and loads a fresh one for [modelId].
     * Always called inside [engineLoadMutex] to serialise concurrent invocations.
     */
    private suspend fun reloadForModel(modelId: ModelId) {
        engineLoadMutex.withLock {
            // Close the current engine before replacing it.
            currentEngine?.let { engine ->
                engine.close()
                Log.d(TAG, "Previous engine closed before reload")
            }
            currentEngine = null
            currentRepository = null

            if (!ModelStorageManager.isModelReady(applicationContext, modelId)) {
                Log.w(TAG, "Model $modelId files not present - engine stays Unloaded")
                _engineState.value = EngineState.Unloaded
                updateNotification("Model not downloaded - open Outspoke to download")
                return
            }

            // Log model file size and available memory before loading
            val modelDir = ModelStorageManager.getModelDir(applicationContext, modelId)
            val modelSizeMB = modelDir.walkTopDown().filter { it.isFile }.sumOf { it.length() } / (1024 * 1024)
            val am = getSystemService(ACTIVITY_SERVICE) as? ActivityManager
            val memInfo = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
            Log.i(TAG, "Preparing to load model $modelId, modelDir=${modelDir.path}, size=${modelSizeMB}MB")
            Log.i(TAG, "Available RAM: ${memInfo.availMem / (1024 * 1024)} MB, LowMem: ${memInfo.lowMemory}")
            if (modelSizeMB > 500 && memInfo.availMem < modelSizeMB * 2 * 1024 * 1024) {
                Log.w(TAG, "Device RAM may be insufficient for large model $modelId (model: ${modelSizeMB}MB, avail: ${memInfo.availMem / (1024 * 1024)}MB)")
            }

            _engineState.value = EngineState.Loading
            updateNotification("Loading transcription engine…")

            val startTime = System.currentTimeMillis()
            try {
                val engine = SpeechEngineFactory.create(modelId)
                engine.load(modelDir)
                currentEngine = engine
                currentRepository = InferenceRepository(engine)
                _engineState.value = EngineState.Ready
                updateNotification("Outspoke ready (${ModelRegistry[modelId].displayName})")
                val elapsed = System.currentTimeMillis() - startTime
                Log.i(TAG, "Engine loaded successfully for $modelId in ${elapsed}ms")
                logMemoryUsage()
            } catch (e: Exception) {
                val msg = e.localizedMessage ?: "Unknown error"
                Log.e(TAG, "Engine load failed for $modelId: $msg", e)
                _engineState.value = EngineState.Error(msg)
                updateNotification("Engine failed to load: $msg")
                logMemoryUsage()
            }
        }
    }

    /**
     * Watches the `models/` root directory so we detect both deletion and installation of
     * model files for any model - triggering an engine reload for the selected model.
     *
     * The observer is only recreated when the watched directory actually changes.
     */
    private var modelFileObserver: FileObserver? = null
    private var currentWatchDir: File? = null

    private fun startModelWatcher() {
        val context = applicationContext
        // Watch the models/ root; fall back to filesDir if models/ doesn't exist yet.
        val modelsRoot = ModelStorageManager.getModelsRoot(context).also { it.mkdirs() }
        val watchDir = if (modelsRoot.exists()) modelsRoot else context.filesDir

        if (watchDir == currentWatchDir) return

        modelFileObserver?.stopWatching()
        currentWatchDir = watchDir

        modelFileObserver = object : FileObserver(watchDir, ALL_EVENTS) {
            override fun onEvent(event: Int, path: String?) {
                val mask = event and ALL_EVENTS
                if (mask and (DELETE or DELETE_SELF or MOVED_FROM or
                            CREATE or MOVED_TO or CLOSE_WRITE) != 0
                ) {
                    onModelDirectoryChanged()
                }
            }
        }.also { it.startWatching() }

        Log.d(TAG, "FileObserver started on: $watchDir")
    }

    /**
     * Called (on a FileObserver background thread) whenever anything changes under the
     * `models/` directory. Checks whether the selected model's readiness changed and
     * reloads or unloads the engine accordingly.
     */
    private fun onModelDirectoryChanged() {
        lifecycleScope.launch(Dispatchers.Default) {
            val context = applicationContext
            val selectedModelId = AppPreferences(context).selectedModelId.first()
            val isReady = ModelStorageManager.isModelReady(context, selectedModelId)
            val currentState = _engineState.value

            when {
                !isReady && currentState == EngineState.Ready -> {
                    Log.w(TAG, "Selected model files removed - unloading engine")
                    reloadForModel(selectedModelId) // will detect !isReady and set Unloaded
                    startModelWatcher()
                }

                isReady && currentState == EngineState.Unloaded -> {
                    Log.d(TAG, "Selected model files appeared - loading engine")
                    reloadForModel(selectedModelId)
                    startModelWatcher()
                }
            }
        }
    }

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

    private fun logMemoryUsage() {
        val runtime = Runtime.getRuntime()
        val usedMemMB = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        val maxMemMB = runtime.maxMemory() / (1024 * 1024)
        Log.i(TAG, "App memory usage: used=${usedMemMB}MB, max=${maxMemMB}MB")
        val debugMem = Debug.MemoryInfo()
        Debug.getMemoryInfo(debugMem)
        Log.i(TAG, "Debug memory: dalvik=${debugMem.dalvikPrivateDirty}KB, native=${debugMem.nativePrivateDirty}KB, totalPss=${debugMem.totalPss}KB")
    }
}
