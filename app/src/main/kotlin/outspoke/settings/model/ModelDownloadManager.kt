package dev.brgr.outspoke.settings.model

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

private const val TAG = "ModelDownloadManager"

private const val BASE_URL =
    "https://huggingface.co/istupakov/parakeet-tdt-0.6b-v3-onnx/resolve/main"

/**
 * Each entry describes one file that must be downloaded.
 * The INT8 quantized encoder + decoder/joint are used for on-device inference.
 *
 * [sha256] is the expected lowercase hex SHA-256 of the file contents.
 * Set to `null` to skip hash verification for that file (e.g. small JSON/text files
 * that may be updated independently of the model weights).
 */
private data class ModelFile(
    val filename: String,
    /** Rough fraction of total download size — used to weight aggregate progress. */
    val sizeFraction: Float,
    /** Expected SHA-256 hex digest, or `null` to skip verification. */
    val sha256: String? = null,
) {
    val url: String get() = "$BASE_URL/$filename"
}

private val MODEL_FILES = listOf(
    ModelFile(
        filename     = "encoder-model.int8.onnx",
        sizeFraction = 0.70f,
        // Obtain from: sha256sum encoder-model.int8.onnx  (or HuggingFace LFS metadata)
        sha256       = "6139d2fa7e1b086097b277c7149725edbab89cc7c7ae64b23c741be4055aff09",
    ),
    ModelFile(
        filename     = "decoder_joint-model.int8.onnx",
        sizeFraction = 0.19f,
        sha256       = "eea7483ee3d1a30375daedc8ed83e3960c91b098812127a0d99d1c8977667a70",
    ),
    ModelFile(
        filename     = "nemo128.onnx",
        sizeFraction = 0.06f,
        sha256       = "a9fde1486ebfcc08f328d75ad4610c67835fea58c73ba57e3209a6f6cf019e9f",
    ),
    ModelFile(
        filename     = "config.json",
        sizeFraction = 0.01f,
        sha256       = null,
    ),
    ModelFile(
        filename     = "vocab.txt",
        sizeFraction = 0.04f,
        sha256       = null,
    ),
)

/**
 * Downloads all Parakeet-V3 ONNX model files from Hugging Face and saves them to
 * the directory managed by [ModelStorageManager].
 *
 * Each file is first streamed to a `.tmp` file next to the final target, then the
 * SHA-256 hash is verified (when [ModelFile.sha256] is non-null), and finally the
 * file is atomically renamed on success so a partial download never leaves a corrupt file.
 *
 * Progress is reported as an aggregate [0.0, 1.0] fraction weighted by [ModelFile.sizeFraction].
 * The returned [Flow] must be collected on an IO-capable dispatcher.
 * Network / IO failures are re-thrown so the calling ViewModel can surface a Snackbar.
 */
class ModelDownloadManager(
    private val client: OkHttpClient = OkHttpClient(),
) {

    fun download(context: Context): Flow<ModelState> = flow {
        val modelDir = ModelStorageManager.getModelDir(context)
        modelDir.mkdirs()

        // Offset into the [0,1] progress range already completed by previous files.
        var completedWeight = 0f

        for (modelFile in MODEL_FILES) {
            val finalFile = File(modelDir, modelFile.filename)
            val tempFile  = File(modelDir, "${modelFile.filename}.tmp")

            try {
                val request = Request.Builder().url(modelFile.url).build()
                val response = client.newCall(request).execute()

                if (!response.isSuccessful) {
                    throw RuntimeException(
                        "HTTP ${response.code} downloading ${modelFile.filename}: ${response.message}"
                    )
                }

                val body = response.body
                    ?: throw RuntimeException("Empty response body for ${modelFile.filename}")
                val totalBytes = body.contentLength() // -1 if server omits Content-Length

                // Stream to temp file while computing SHA-256 in one pass.
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
                                val overall = completedWeight + fileProgress * modelFile.sizeFraction
                                emit(ModelState.Downloading(overall.coerceIn(0f, 1f)))
                            }
                        }
                    }
                }

                // Verify SHA-256 when an expected hash is provided.
                if (modelFile.sha256 != null) {
                    val actual = digest.digest().toHexString()
                    if (!actual.equals(modelFile.sha256, ignoreCase = true)) {
                        tempFile.delete()
                        Log.e(TAG, "SHA-256 mismatch for ${modelFile.filename}: expected=${modelFile.sha256} actual=$actual")
                        emit(ModelState.Corrupted)
                        return@flow
                    }
                    Log.d(TAG, "SHA-256 verified for ${modelFile.filename}")
                }

                // Atomic rename — only replaces the final file after a fully verified download.
                tempFile.renameTo(finalFile)
                Log.d(TAG, "Saved ${modelFile.filename} (${finalFile.length()} bytes)")

            } catch (e: Exception) {
                tempFile.delete() // Remove any partial temp file
                throw e
            }

            completedWeight += modelFile.sizeFraction
        }

        // Final check: all required files must now be present.
        if (ModelStorageManager.isModelReady(context)) {
            Log.d(TAG, "All model files installed successfully")
            emit(ModelState.Ready)
        } else {
            Log.e(TAG, "Download finished but isModelReady() returned false")
            emit(ModelState.Corrupted)
        }
    }
}

/** Converts a `ByteArray` to a lowercase hex string. */
private fun ByteArray.toHexString(): String =
    joinToString("") { "%02x".format(it) }

