package dev.brgr.outspoke.settings.model

import android.content.Context
import java.io.File

/**
 * Manages on-disk locations and lifecycle for all on-device speech recognition models.
 *
 * Each model lives in its own sub-directory:
 *   `<filesDir>/models/<ModelId.storageDirName>/`
 *
 * All paths are within `context.filesDir` - no external-storage permission is required.
 */
object ModelStorageManager {

    private const val MODELS_ROOT = "models"

    /** Returns the root `models/` directory that contains all per-model sub-directories. */
    fun getModelsRoot(context: Context): File =
        File(context.filesDir, MODELS_ROOT)

    /** Returns the directory that holds all files for [modelId]. */
    fun getModelDir(context: Context, modelId: ModelId = ModelId.DEFAULT): File =
        File(getModelsRoot(context), modelId.storageDirName)

    /** Returns the [File] for a specific [filename] inside [modelId]'s directory. */
    fun getFilePath(context: Context, modelId: ModelId, filename: String): File =
        File(getModelDir(context, modelId), filename)

    /**
     * Returns `true` only when every file in [modelInfo]'s [ModelInfo.requiredFiles] list
     * exists and has a non-zero size.
     */
    fun isModelReady(context: Context, modelInfo: ModelInfo): Boolean =
        modelInfo.requiredFiles.all { filename ->
            val file = getFilePath(context, modelInfo.id, filename)
            file.exists() && file.length() > 0
        }

    /** Convenience overload - looks up [ModelInfo] from [ModelRegistry]. */
    fun isModelReady(context: Context, modelId: ModelId): Boolean =
        isModelReady(context, ModelRegistry[modelId])

    /**
     * Legacy no-arg overload that checks the default [ModelId.DEFAULT] model.
     * Kept for call-sites that have not yet been updated.
     */
    fun isModelReady(context: Context): Boolean =
        isModelReady(context, ModelId.DEFAULT)

    /** Deletes the entire model directory for [modelId] from internal storage. */
    fun deleteModel(context: Context, modelId: ModelId) {
        getModelDir(context, modelId).deleteRecursively()
    }

    /** Legacy no-arg overload - deletes the default model. */
    fun deleteModel(context: Context): Unit =
        deleteModel(context, ModelId.DEFAULT)
}
