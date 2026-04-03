package dev.brgr.outspoke.settings.model

import android.content.Context
import android.util.Log
import dev.brgr.outspoke.settings.model.ModelDownloadManager.Companion.INSTALLED_MARKER
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
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
 * **Resumable downloads:** if a partial `.tmp` file exists from a previous attempt, an HTTP
 * `Range: bytes=<offset>-` request is issued.  A 206 response continues from the offset; a
 * 200 response means the server does not support range requests and the download restarts
 * from zero.  Partial files are kept on cancellation and network errors to allow resume;
 * they are only deleted on a SHA-256 mismatch.
 *
 * Progress is reported as an aggregate [0.0, 1.0] fraction.
 * All blocking I/O is dispatched internally on [kotlinx.coroutines.Dispatchers.IO].
 */
class ModelDownloadManager(
    private val client: OkHttpClient = OkHttpClient(),
) {

    fun download(context: Context, modelInfo: ModelInfo): Flow<ModelState> = channelFlow {
        val modelDir = ModelStorageManager.getModelDir(context, modelInfo.id)
        modelDir.mkdirs()

        val success = when (val source = modelInfo.source) {
            is DownloadSource.Files -> downloadFiles(modelDir, source) { send(it) }
            is DownloadSource.ZipArchive -> downloadZip(modelDir, source) { send(it) }
        }

        if (!success) {
            send(ModelState.Corrupted)
            return@channelFlow
        }

        if (ModelStorageManager.isModelReady(context, modelInfo)) {
            Log.d(TAG, "All model files installed for ${modelInfo.id}")
            send(ModelState.Ready)
        } else {
            Log.e(TAG, "Download finished but isModelReady=false for ${modelInfo.id}")
            send(ModelState.Corrupted)
        }
    }

    /**
     * Downloads each file in [source] sequentially, resuming via HTTP Range requests when a
     * partial `.tmp` file is found.  Files whose final destination already exists are skipped.
     *
     * @return `true` on full success; `false` if any SHA-256 digest mismatched.
     */
    private suspend fun downloadFiles(
        modelDir: File,
        source: DownloadSource.Files,
        onProgress: suspend (ModelState) -> Unit,
    ): Boolean {
        var completedWeight = 0f

        for (remoteFile in source.files) {
            val finalFile = File(modelDir, remoteFile.filename)
            val tempFile  = File(modelDir, "${remoteFile.filename}.tmp")

            // Skip files that were fully downloaded in a previous attempt.
            if (finalFile.exists() && finalFile.length() > 0) {
                Log.d(TAG, "Skipping ${remoteFile.filename} - already present")
                completedWeight += remoteFile.sizeFraction
                continue
            }

            val existingOffset = if (tempFile.exists()) tempFile.length() else 0L
            val requestBuilder = Request.Builder().url(remoteFile.url(source.baseUrl))
            if (existingOffset > 0) {
                requestBuilder.addHeader("Range", "bytes=$existingOffset-")
                Log.d(TAG, "Resuming ${remoteFile.filename} from byte $existingOffset")
            }

            val response = withContext(Dispatchers.IO) { client.newCall(requestBuilder.build()).execute() }

            // 206 = server honours the Range; 200 = server ignores it (restart from zero).
            val resuming: Boolean
            val offset: Long
            when (response.code) {
                206 -> {
                    resuming = true
                    offset   = existingOffset
                }
                200 -> {
                    resuming = false
                    offset   = 0L
                    if (existingOffset > 0) {
                        tempFile.delete()
                        Log.d(TAG, "Server returned 200 for Range request - restarting ${remoteFile.filename}")
                    }
                }
                else -> throw RuntimeException(
                    "HTTP ${response.code} downloading ${remoteFile.filename}: ${response.message}"
                )
            }

            try {
                val body           = response.body
                val responseLength = body.contentLength()
                // Total file size = bytes already on disk + bytes the server is sending now.
                val totalBytes = if (resuming && offset > 0 && responseLength > 0)
                    offset + responseLength else responseLength

                val digest = MessageDigest.getInstance("SHA-256")

                // Catch up the digest with bytes already written to the temp file.
                if (resuming && offset > 0 && remoteFile.sha256 != null) {
                    withContext(Dispatchers.IO) {
                        tempFile.inputStream().use { existing ->
                            val buf = ByteArray(16 * 1024)
                            var n: Int
                            while (existing.read(buf).also { n = it } != -1) digest.update(buf, 0, n)
                        }
                    }
                }

                withContext(Dispatchers.IO) {
                    FileOutputStream(tempFile, /* append = */ resuming).use { output ->
                        body.byteStream().use { input ->
                            val buffer = ByteArray(16 * 1024)
                            var newBytes = 0L
                            var n: Int
                            while (input.read(buffer).also { n = it } != -1) {
                                output.write(buffer, 0, n)
                                digest.update(buffer, 0, n)
                                newBytes += n
                                if (totalBytes > 0) {
                                    val fileProgress = (offset + newBytes).toFloat() / totalBytes
                                    val overall = completedWeight + fileProgress * remoteFile.sizeFraction
                                    onProgress(ModelState.Downloading(overall.coerceIn(0f, 1f)))
                                }
                            }
                        }
                    }
                }

                if (remoteFile.sha256 != null) {
                    val actual = digest.digest().toHexString()
                    if (!actual.equals(remoteFile.sha256, ignoreCase = true)) {
                        // Hash mismatch means corrupted data, delete so the next run starts fresh.
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

                // Atomic rename - the final file only appears after a fully verified write.
                tempFile.renameTo(finalFile)
                Log.d(TAG, "Saved ${remoteFile.filename} (${finalFile.length()} bytes)")

            } catch (e: Exception) {
                // Keep the temp file on any error (including cancellation) to allow resume.
                throw e
            }

            completedWeight += remoteFile.sizeFraction
        }

        return true
    }

    /**
     * Downloads the ZIP from [source], optionally verifies its SHA-256, extracts all
     * entries into [modelDir], and writes a [INSTALLED_MARKER] marker on success.
     * Supports resume via HTTP Range requests on the partial `.zip.tmp` file.
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
        val existingOffset = if (zipTemp.exists()) zipTemp.length() else 0L

        val requestBuilder = Request.Builder().url(source.url)
        if (existingOffset > 0) {
            requestBuilder.addHeader("Range", "bytes=$existingOffset-")
            Log.d(TAG, "Resuming ZIP download from byte $existingOffset")
        }

        val response = withContext(Dispatchers.IO) { client.newCall(requestBuilder.build()).execute() }

        val resuming: Boolean
        val offset: Long
        when (response.code) {
            206 -> {
                resuming = true
                offset   = existingOffset
            }
            200 -> {
                resuming = false
                offset   = 0L
                if (existingOffset > 0) {
                    zipTemp.delete()
                    Log.d(TAG, "Server returned 200 for ZIP Range request - restarting")
                }
            }
            else -> throw RuntimeException(
                "HTTP ${response.code} downloading ZIP: ${response.message}"
            )
        }

        try {
            val body           = response.body
            val responseLength = body.contentLength()
            val totalBytes = if (resuming && offset > 0 && responseLength > 0)
                offset + responseLength else responseLength

            val digest = if (source.sha256 != null) MessageDigest.getInstance("SHA-256") else null

            // Catch up the digest with bytes already written to the partial ZIP.
            if (resuming && offset > 0 && digest != null) {
                withContext(Dispatchers.IO) {
                    zipTemp.inputStream().use { existing ->
                        val buf = ByteArray(32 * 1024)
                        var n: Int
                        while (existing.read(buf).also { n = it } != -1) digest.update(buf, 0, n)
                    }
                }
            }

            withContext(Dispatchers.IO) {
                FileOutputStream(zipTemp, /* append = */ resuming).use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(32 * 1024)
                        var newBytes = 0L
                        var n: Int
                        while (input.read(buffer).also { n = it } != -1) {
                            output.write(buffer, 0, n)
                            digest?.update(buffer, 0, n)
                            newBytes += n
                            if (totalBytes > 0) {
                                val dl = (offset + newBytes).toFloat() / totalBytes
                                onProgress(ModelState.Downloading((dl * 0.9f).coerceIn(0f, 0.9f)))
                            }
                        }
                    }
                }
            }

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
            withContext(Dispatchers.IO) {
                extractZip(zipFile = zipTemp, destDir = modelDir)
                zipTemp.delete()
                // Write installation marker - checked by isModelReady() for ZIP-based models.
                File(modelDir, INSTALLED_MARKER).writeText("ok")
            }
            Log.d(TAG, "ZIP extracted and installed to $modelDir")

        } catch (e: Exception) {
            // Keep partial ZIP to allow resume on the next attempt.
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
                val name = entry.name.replace('\\', '/').trimStart('/')
                val outFile = File(destDir, name)

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
