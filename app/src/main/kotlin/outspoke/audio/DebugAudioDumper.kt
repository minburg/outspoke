package dev.brgr.outspoke.audio

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "DebugAudioDumper"

/**
 * Debug-only audio tap that writes pipeline audio to WAV files on disk so the engineer
 * can listen to exactly what the model hears.
 *
 * Two taps are available:
 *  - **VAD output tap** (Tap 1): A single continuous WAV file (`debug_vad_output.wav`)
 *    containing every chunk that survived VAD gating. Useful for tuning Silero VAD
 *    threshold and hangover settings. Call [startSession] before capture and
 *    [appendVadChunk] for each emitted chunk.
 *  - **Stride window tap** (Tap 2): One numbered WAV file per stride window
 *    (`debug_stride_NNN.wav`) that captures the exact audio fed to `engine.transcribe()`.
 *    Useful for hearing artefacts introduced by the sliding-window trim logic.
 *    Call [dumpStrideWindow] at each stride boundary.
 *
 * Files are written to the app's external files directory
 * (`/sdcard/Android/data/<package>/files/debug_audio/`) where they can be pulled
 * with `adb pull` without requiring MANAGE_EXTERNAL_STORAGE.
 *
 * This class is intentionally allocation-light on the hot path: the only allocations
 * inside [appendVadChunk] and [dumpStrideWindow] are the PCM → byte conversion arrays,
 * which cannot be avoided for WAV serialisation.
 *
 * @param context Application context used to resolve the output directory.
 */
class DebugAudioDumper(context: Context) {

    private val outputDir: File = File(
        context.getExternalFilesDir(null) ?: context.filesDir,
        "debug_audio"
    )

    private var vadWriter: WavFileWriter? = null
    private val strideCounter = AtomicInteger(0)

    /**
     * Starts a new recording session.
     * - Creates [outputDir] if needed.
     * - Deletes any stride WAV files left over from a previous session.
     * - Opens a fresh `debug_vad_output.wav` file for the VAD tap.
     *
     * Must be called before [appendVadChunk] or [dumpStrideWindow].
     */
    fun startSession(sampleRate: Int = 16_000) {
        outputDir.mkdirs()

        // Remove old stride files so the numbered sequence always starts at 000.
        outputDir.listFiles { f -> f.name.startsWith("debug_stride_") }
            ?.forEach { it.delete() }

        strideCounter.set(0)

        val vadFile = File(outputDir, "debug_vad_output.wav")
        vadWriter?.runCatching { close() }  // close any orphaned writer from a previous session
        vadWriter = try {
            WavFileWriter(vadFile, sampleRate)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open VAD dump file: ${vadFile.absolutePath}", e)
            null
        }

        Log.d(TAG, "Debug audio session started → $outputDir")
    }

    /**
     * Appends [samples] to the continuous VAD-output WAV file.
     * No-op if [startSession] has not been called or the writer failed to open.
     */
    fun appendVadChunk(samples: ShortArray) {
        vadWriter?.runCatching { append(samples) }
    }

    /**
     * Writes [samples] (the entire stride window fed to the model) as a new numbered
     * WAV file (`debug_stride_NNN.wav`).
     */
    fun dumpStrideWindow(samples: ShortArray, sampleRate: Int = 16_000) {
        val n = strideCounter.getAndIncrement()
        val strideFile = File(outputDir, "debug_stride_%03d.wav".format(n))
        try {
            WavFileWriter(strideFile, sampleRate).use { it.append(samples) }
            Log.d(TAG, "[DUMP] stride $n → ${strideFile.name} (${samples.size} samples)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write stride dump $n", e)
        }
    }

    /**
     * Closes the VAD-output WAV file and finalizes its header.
     * Call this at the end of each recording session (e.g. in `AudioCaptureManager`'s
     * `finally` block).
     */
    fun endSession() {
        vadWriter?.runCatching { close() }
        vadWriter = null
        Log.d(TAG, "Debug audio session ended → files in $outputDir")
    }
}

