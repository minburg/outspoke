package dev.brgr.outspoke.settings.model

import android.content.Context
import java.io.File

/**
 * Manages the location and lifecycle of the on-device Parakeet-V3 ONNX model files.
 *
 * The model is split into several files that all live under:
 *   `<filesDir>/models/parakeet-v3/`
 *
 * All paths are within `context.filesDir` — no external-storage permission is required.
 */
object ModelStorageManager {

    const val MODEL_DIR = "models/parakeet-v3"

    /** Every file that must be present and non-empty for the model to be considered ready. */
    val REQUIRED_FILES = listOf(
        "encoder-model.int8.onnx",
        "decoder_joint-model.int8.onnx",
        "nemo128.onnx",
        "config.json",
        "vocab.txt",
    )

    /** Returns the directory that holds all model files. */
    fun getModelDir(context: Context): File =
        File(context.filesDir, MODEL_DIR)

    /** Returns the [File] for a specific [filename] inside the model directory. */
    fun getFilePath(context: Context, filename: String): File =
        File(getModelDir(context), filename)

    /**
     * Returns `true` only when every file in [REQUIRED_FILES] exists and has a non-zero size.
     */
    fun isModelReady(context: Context): Boolean =
        REQUIRED_FILES.all { filename ->
            val file = getFilePath(context, filename)
            file.exists() && file.length() > 0
        }

    /** Deletes the entire model directory tree from internal storage. */
    fun deleteModel(context: Context) {
        getModelDir(context).deleteRecursively()
    }
}

