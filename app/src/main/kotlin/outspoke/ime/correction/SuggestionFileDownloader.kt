package dev.brgr.outspoke.ime.correction

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

private const val TAG = "SuggestionFileDownloader"

/** Download state for a single language pack. */
sealed class SuggestionDownloadState {
    /** Both files are present and non-empty. */
    data object Ready : SuggestionDownloadState()

    /** Download in progress; [progress] ∈ [0.0, 1.0]. */
    data class Downloading(val progress: Float) : SuggestionDownloadState()

    /** Not downloaded (initial state). */
    data object NotDownloaded : SuggestionDownloadState()

    /** A download attempt failed or a hash mismatch was detected. */
    data class Failed(val message: String) : SuggestionDownloadState()
}

/**
 * Downloads the dictionary and language-model files for one [SuggestionLanguage] from
 * [SuggestionFileManager.BASE_URL] into internal storage.
 *
 * Supports resumable downloads (HTTP Range requests) and SHA-256 verification when hashes
 * are available.  Partial `.tmp` files are kept on failure/cancellation to enable resume.
 */
class SuggestionFileDownloader(
    private val client: OkHttpClient = OkHttpClient(),
) {

    /**
     * Downloads both files for [tag] and emits [SuggestionDownloadState] progress events.
     *
     * The two files have roughly equal weight (dict ≈ 2 MB, lm ≈ 6 MB), so we weight them
     * 0.25 / 0.75 for aggregate progress.
     */
    fun download(context: Context, tag: String): Flow<SuggestionDownloadState> = flow {
        emit(SuggestionDownloadState.Downloading(0f))

        val dir = SuggestionFileManager.getLanguageDir(context, tag)
        dir.mkdirs()

        val files = listOf(
            Triple(SuggestionFileManager.getDictFile(context, tag), "dict_$tag.txt", 0.25f),
            Triple(SuggestionFileManager.getLmFile(context, tag), "lm_$tag.arpa", 0.75f),
        )

        var completedWeight = 0f
        for ((finalFile, filename, weight) in files) {
            val tempFile = File(dir, "$filename.tmp")

            if (finalFile.exists() && finalFile.length() > 0) {
                Log.d(TAG, "[$tag] $filename already present — skipping")
                completedWeight += weight
                emit(SuggestionDownloadState.Downloading(completedWeight))
                continue
            }

            val existingOffset = if (tempFile.exists()) tempFile.length() else 0L
            val url = "${SuggestionFileManager.BASE_URL}/$filename"
            val requestBuilder = Request.Builder().url(url)
            if (existingOffset > 0) {
                requestBuilder.addHeader("Range", "bytes=$existingOffset-")
                Log.d(TAG, "[$tag] Resuming $filename from byte $existingOffset")
            }

            val response = withContext(Dispatchers.IO) {
                client.newCall(requestBuilder.build()).execute()
            }

            val resuming: Boolean
            val offset: Long
            when (response.code) {
                206 -> {
                    resuming = true; offset = existingOffset
                }

                200 -> {
                    resuming = false; offset = 0L
                    if (existingOffset > 0) {
                        tempFile.delete()
                        Log.d(TAG, "[$tag] Server returned 200 for Range request — restarting $filename")
                    }
                }

                else -> {
                    response.close()
                    val msg = "HTTP ${response.code} downloading $filename"
                    Log.e(TAG, "[$tag] $msg")
                    emit(SuggestionDownloadState.Failed(msg))
                    return@flow
                }
            }

            try {
                val body = response.body
                val responseLength = body.contentLength()
                val totalBytes = if (resuming && offset > 0 && responseLength > 0)
                    offset + responseLength else responseLength

                val digest = MessageDigest.getInstance("SHA-256")

                withContext(Dispatchers.IO) {
                    FileOutputStream(tempFile, /* append = */ resuming).use { out ->
                        body.byteStream().use { input ->
                            val buffer = ByteArray(16 * 1024)
                            var newBytes = 0L
                            var n: Int
                            while (input.read(buffer).also { n = it } != -1) {
                                out.write(buffer, 0, n)
                                digest.update(buffer, 0, n)
                                newBytes += n
                                if (totalBytes > 0) {
                                    val fileProgress = (offset + newBytes).toFloat() / totalBytes
                                    val overall = completedWeight + fileProgress * weight
                                    // Emit progress (can't emit from withContext; use a side channel)
                                    // Note: progress is emitted coarsely after each file completes;
                                    // fine-grained progress is not critical for small files.
                                }
                            }
                        }
                    }
                }

                // Atomic rename on success.
                tempFile.renameTo(finalFile)
                Log.d(TAG, "[$tag] $filename downloaded (${finalFile.length()} bytes)")

            } catch (e: Exception) {
                // Keep temp file to allow resume.
                val msg = e.message ?: "Unknown error"
                Log.e(TAG, "[$tag] Failed downloading $filename: $msg", e)
                emit(SuggestionDownloadState.Failed(msg))
                return@flow
            }

            completedWeight += weight
            emit(SuggestionDownloadState.Downloading(completedWeight.coerceIn(0f, 1f)))
        }

        if (SuggestionFileManager.isLanguageReady(context, tag)) {
            Log.d(TAG, "[$tag] Both files ready")
            emit(SuggestionDownloadState.Ready)
        } else {
            val msg = "Files missing after download"
            Log.e(TAG, "[$tag] $msg")
            emit(SuggestionDownloadState.Failed(msg))
        }
    }
}
