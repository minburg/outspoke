package dev.brgr.outspoke.settings.model

import android.content.Context
import android.util.Log
import dev.brgr.outspoke.settings.model.ModelDownloadManager.Companion.INSTALLED_MARKER
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipInputStream

private const val TAG = "ModelDownloadManager"

/**
 * Downloads all files for a given [ModelInfo] and saves them to the directory managed by
 * [ModelStorageManager].
 *
 * Supports two download modes:
 *  - [DownloadSource.Files]      - individual files are streamed, SHA-256 verified, and
 *                                  atomically renamed on success.
 *  - [DownloadSource.ZipArchive] - a single ZIP archive is downloaded, optionally SHA-256
 *                                  verified, extracted, and a [INSTALLED_MARKER] file is
 *                                  written so [ModelStorageManager.isModelReady] can confirm
 *                                  the installation.
 *
 * Progress is reported as an aggregate [0.0, 1.0] fraction.
 * Network / IO failures are re-thrown so the calling ViewModel can surface a Snackbar.
 * The returned [Flow] must be collected on an IO-capable dispatcher.
 */
class ModelDownloadManager(
    private val client: OkHttpClient = OkHttpClient(),
) {

    fun download(context: Context, modelInfo: ModelInfo): Flow<ModelState> = flow {
        val modelDir = ModelStorageManager.getModelDir(context, modelInfo.id)
        modelDir.mkdirs()

        val success = when (val source = modelInfo.source) {
            is DownloadSource.Files -> downloadFiles(modelDir, source) { emit(it) }
            is DownloadSource.ZipArchive -> downloadZip(modelDir, source) { emit(it) }
        }

        if (!success) {
            emit(ModelState.Corrupted)
            return@flow
        }

        if (ModelStorageManager.isModelReady(context, modelInfo)) {
            Log.d(TAG, "All model files installed for ${modelInfo.id}")
            emit(ModelState.Ready)
        } else {
            Log.e(TAG, "Download finished but isModelReady=false for ${modelInfo.id}")
            emit(ModelState.Corrupted)
        }
    }

    /**
     * Downloads each file in [source] sequentially, reporting weighted aggregate progress
     * via [onProgress].
     *
     * @return `true` on full success; `false` if any SHA-256 digest mismatched (the caller
     *         will then emit [ModelState.Corrupted]).
     */
    private suspend fun downloadFiles(
        modelDir: File,
        source: DownloadSource.Files,
        onProgress: suspend (ModelState) -> Unit,
    ): Boolean {
        var completedWeight = 0f

        for (remoteFile in source.files) {
            val finalFile = File(modelDir, remoteFile.filename)
            val tempFile = File(modelDir, "${remoteFile.filename}.tmp")

            try {
                val request = Request.Builder().url(remoteFile.url(source.baseUrl)).build()
                val response = client.newCall(request).execute()

                if (!response.isSuccessful) {
                    throw RuntimeException(
                        "HTTP ${response.code} downloading ${remoteFile.filename}: ${response.message}"
                    )
                }

                val body = response.body
                    ?: throw RuntimeException("Empty response body for ${remoteFile.filename}")
                val totalBytes = body.contentLength()

                val digest = MessageDigest.getInstance("SHA-256")

                body.byteStream().use { input ->
                    tempFile.outputStream().use { output ->
                        val buffer = ByteArray(16 * 1024)
                        var bytesRead = 0L
                        var n: Int
                        while (input.read(buffer).also { n = it } != -1) {
                            output.write(buffer, 0, n)
                            digest.update(buffer, 0, n)
                            bytesRead += n
                            if (totalBytes > 0) {
                                val fileProgress = bytesRead.toFloat() / totalBytes
                                val overall = completedWeight + fileProgress * remoteFile.sizeFraction
                                onProgress(ModelState.Downloading(overall.coerceIn(0f, 1f)))
                            }
                        }
                    }
                }

                if (remoteFile.sha256 != null) {
                    val actual = digest.digest().toHexString()
                    if (!actual.equals(remoteFile.sha256, ignoreCase = true)) {
                        tempFile.delete()
                        Log.e(
                            TAG,
                            "SHA-256 mismatch for ${remoteFile.filename}: " +
                                    "expected=${remoteFile.sha256} actual=$actual",
                        )
                        return false
                    }
                    Log.d(TAG, "SHA-256 verified for ${remoteFile.filename}")
                }

                // Atomic rename - only replaces the final file after a fully verified download.
                tempFile.renameTo(finalFile)
                Log.d(TAG, "Saved ${remoteFile.filename} (${finalFile.length()} bytes)")

            } catch (e: Exception) {
                tempFile.delete()
                throw e
            }

            completedWeight += remoteFile.sizeFraction
        }

        return true
    }

    /**
     * Downloads the ZIP from [source], optionally verifies its SHA-256, extracts all
     * entries into [modelDir], and writes a [INSTALLED_MARKER] marker on success.
     *
     * Progress is reported in the range [0.0, 0.9] during the download phase;
     * the remaining 10 % covers extraction.
     *
     * @return `true` on full success; `false` if the archive hash mismatched.
     */
    private suspend fun downloadZip(
        modelDir: File,
        source: DownloadSource.ZipArchive,
        onProgress: suspend (ModelState) -> Unit,
    ): Boolean {
        val zipTemp = File(modelDir, "download.zip.tmp")

        try {
            val request = Request.Builder().url(source.url).build()
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                throw RuntimeException(
                    "HTTP ${response.code} downloading ZIP: ${response.message}"
                )
            }

            val body = response.body
                ?: throw RuntimeException("Empty response body for ZIP archive")
            val totalBytes = body.contentLength()

            val digest = if (source.sha256 != null) MessageDigest.getInstance("SHA-256") else null

            // Stream ZIP to temp file, reporting progress at 0–90 %.
            body.byteStream().use { input ->
                zipTemp.outputStream().use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var bytesRead = 0L
                    var n: Int
                    while (input.read(buffer).also { n = it } != -1) {
                        output.write(buffer, 0, n)
                        digest?.update(buffer, 0, n)
                        bytesRead += n
                        if (totalBytes > 0) {
                            onProgress(
                                ModelState.Downloading(
                                    (bytesRead.toFloat() / totalBytes * 0.9f).coerceIn(0f, 0.9f)
                                )
                            )
                        }
                    }
                }
            }

            // Verify archive integrity before extracting.
            if (source.sha256 != null && digest != null) {
                val actual = digest.digest().toHexString()
                if (!actual.equals(source.sha256, ignoreCase = true)) {
                    zipTemp.delete()
                    Log.e(TAG, "SHA-256 mismatch for ZIP archive")
                    return false
                }
                Log.d(TAG, "SHA-256 verified for ZIP archive")
            }

            onProgress(ModelState.Downloading(0.92f))
            extractZip(zipFile = zipTemp, destDir = modelDir)
            zipTemp.delete()

            // Write installation marker - checked by isModelReady() for ZIP-based models.
            File(modelDir, INSTALLED_MARKER).writeText("ok")
            Log.d(TAG, "ZIP extracted and installed to $modelDir")

        } catch (e: Exception) {
            zipTemp.delete()
            throw e
        }

        return true
    }

    /**
     * Extracts all entries from [zipFile] into [destDir].
     * Validates each resolved path to guard against zip-slip path-traversal attacks.
     */
    private fun extractZip(zipFile: File, destDir: File) {
        val canonicalDest = destDir.canonicalPath
        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                // Normalise separators and strip leading slashes.
                val name = entry.name.replace('\\', '/').trimStart('/')
                val outFile = File(destDir, name)

                // Prevent zip-slip: every resolved path must be under destDir.
                val outCanonical = outFile.canonicalPath
                require(
                    outCanonical == canonicalDest ||
                            outCanonical.startsWith(canonicalDest + File.separator)
                ) { "Zip entry escapes target directory: $name" }

                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { out -> zis.copyTo(out) }
                    Log.d(TAG, "Extracted: $name (${outFile.length()} bytes)")
                }

                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    companion object {
        /**
         * Marker file written by [downloadZip] after successful extraction.
         * [ModelStorageManager.isModelReady] checks for this file in ZIP-based models.
         */
        const val INSTALLED_MARKER = ".installed"
    }
}

/** Converts a [ByteArray] to a lowercase hex string. */
private fun ByteArray.toHexString(): String =
    joinToString("") { "%02x".format(it) }
