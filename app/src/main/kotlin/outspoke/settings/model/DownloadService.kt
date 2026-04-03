package dev.brgr.outspoke.settings.model

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dev.brgr.outspoke.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "DownloadService"

/**
 * Foreground service that owns the model download lifecycle.
 *
 * Downloads are launched on [lifecycleScope], which is tied to the service - not to any
 * Activity or ViewModel - so they survive screen navigation and the app being backgrounded.
 *
 * Callers interact via intents ([ACTION_START_DOWNLOAD] / [ACTION_CANCEL_DOWNLOAD]).
 * The service stops itself automatically once all active downloads finish.
 *
 * Progress is broadcast via the process-wide [downloadStates] [StateFlow] so [ModelViewModel]
 * can reflect live state in the UI without needing to bind to the service.
 */
class DownloadService : LifecycleService() {

    private val downloadManager = ModelDownloadManager()
    private val activeJobs = mutableMapOf<ModelId, Job>()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification("Preparing download…"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "startForeground rejected", e)
        }
        Log.d(TAG, "DownloadService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START_DOWNLOAD -> {
                val modelId = ModelId.fromName(intent.getStringExtra(EXTRA_MODEL_ID) ?: "")
                startDownload(modelId)
            }
            ACTION_CANCEL_DOWNLOAD -> {
                val modelId = ModelId.fromName(intent.getStringExtra(EXTRA_MODEL_ID) ?: "")
                cancelDownload(modelId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        activeJobs.values.forEach { it.cancel() }
        activeJobs.clear()
        Log.d(TAG, "DownloadService destroyed")
    }

    private fun startDownload(modelId: ModelId) {
        if (activeJobs[modelId]?.isActive == true) return

        activeJobs[modelId] = lifecycleScope.launch(Dispatchers.IO) {
            val modelInfo = ModelRegistry[modelId]
            try {
                downloadManager.download(applicationContext, modelInfo).collect { state ->
                    _downloadStates.update { it + (modelId to state) }
                    updateNotification()
                }
            } catch (e: CancellationException) {
                // State was already reset by cancelDownload(); rethrow to end the coroutine.
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Download failed for $modelId", e)
                _downloadStates.update { it + (modelId to ModelState.Corrupted) }
            } finally {
                activeJobs.remove(modelId)
                stopSelfIfIdle()
            }
        }
    }

    private fun cancelDownload(modelId: ModelId) {
        activeJobs[modelId]?.cancel()
        activeJobs.remove(modelId)
        _downloadStates.update { it + (modelId to ModelState.NotDownloaded) }
        stopSelfIfIdle()
        Log.d(TAG, "Download cancelled for $modelId")
    }

    private fun stopSelfIfIdle() {
        if (activeJobs.isEmpty()) {
            Log.d(TAG, "No active downloads - stopping service")
            stopSelf()
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Model Downloads",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shows progress while speech model files are downloading"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID, buildNotification(progressText()))
    }

    private fun progressText(): String {
        val active = _downloadStates.value.values.filterIsInstance<ModelState.Downloading>()
        return if (active.isEmpty()) "Download complete"
        else "Downloading… ${(active.map { it.progressFraction }.average() * 100).toInt()}%"
    }

    private fun buildNotification(text: String): Notification {
        val active = _downloadStates.value.values.filterIsInstance<ModelState.Downloading>()
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Outspoke - Downloading Model")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
        if (active.isNotEmpty()) {
            val pct = (active.map { it.progressFraction }.average() * 100).toInt()
            builder.setProgress(100, pct, false)
        }
        return builder.build()
    }

    companion object {
        const val ACTION_START_DOWNLOAD  = "dev.brgr.outspoke.action.START_DOWNLOAD"
        const val ACTION_CANCEL_DOWNLOAD = "dev.brgr.outspoke.action.CANCEL_DOWNLOAD"
        const val EXTRA_MODEL_ID         = "extra_model_id"

        private const val CHANNEL_ID      = "outspoke_download"
        private const val NOTIFICATION_ID = 2001

        private val _downloadStates = MutableStateFlow<Map<ModelId, ModelState>>(emptyMap())

        /**
         * Process-wide snapshot of all active / recently-completed downloads.
         * [ModelViewModel] merges this into its own state map so the UI stays in sync.
         */
        val downloadStates: StateFlow<Map<ModelId, ModelState>> = _downloadStates.asStateFlow()
    }
}

