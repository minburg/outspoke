package dev.brgr.outspoke.settings.model

// ---------------------------------------------------------------------------
// Base URLs
// ---------------------------------------------------------------------------

private const val PARAKEET_BASE =
    "https://huggingface.co/istupakov/parakeet-tdt-0.6b-v3-onnx/resolve/main"

// TODO: Verify the exact file names by browsing
//       https://huggingface.co/onnx-community/Voxtral-Mini-4B-Realtime-2602-ONNX/tree/main/onnx
//       and update the RemoteFile list + requiredFiles below accordingly.
private const val VOXTRAL_BASE =
    "https://huggingface.co/onnx-community/Voxtral-Mini-4B-Realtime-2602-ONNX/resolve/main/onnx"

// Whisper Small INT8 is a single ZIP archive — no baseUrl needed.
private const val WHISPER_ZIP_URL =
    "https://huggingface.co/DocWolle/whisperOnnx/resolve/main/whisper_small_int8.zip"

// ---------------------------------------------------------------------------
// Data types
// ---------------------------------------------------------------------------

/**
 * Describes one file to download as part of a [DownloadSource.Files] source.
 *
 * @param filename     Filename on the remote server and on disk after download.
 * @param sizeFraction Approximate fraction of the total model download size, used to
 *                     weight aggregate progress reporting (must sum to ≤ 1.0 across
 *                     all files in a model).
 * @param sha256       Expected lowercase-hex SHA-256 digest, or `null` to skip verification.
 */
data class RemoteFile(
    val filename: String,
    val sizeFraction: Float,
    val sha256: String? = null,
) {
    /** Constructs the full download URL from the given [baseUrl]. */
    fun url(baseUrl: String): String = "$baseUrl/$filename"
}

/** Describes how a model's files are obtained from a remote server. */
sealed class DownloadSource {

    /**
     * A list of individual files downloaded one by one.
     *
     * @param baseUrl Common URL prefix; each file's full URL is `"$baseUrl/$filename"`.
     * @param files   Ordered list of files to download.
     */
    data class Files(
        val baseUrl: String,
        val files: List<RemoteFile>,
    ) : DownloadSource()

    /**
     * A single ZIP archive that is downloaded, integrity-checked, and extracted into the
     * model directory.  After extraction, [ModelDownloadManager] writes a `.installed`
     * marker file so [ModelStorageManager.isModelReady] can confirm the installation.
     *
     * @param url    Direct URL to the ZIP archive (`resolve/main/…` HuggingFace URL).
     * @param sha256 Expected SHA-256 of the ZIP file itself, or `null` to skip.
     */
    data class ZipArchive(
        val url: String,
        val sha256: String? = null,
    ) : DownloadSource()
}

/**
 * All metadata needed to display, download, store, and identify a speech model.
 *
 * @param requiredFiles Files that must be present and non-empty for
 *                      [ModelStorageManager.isModelReady] to return `true`.
 *                      For [DownloadSource.ZipArchive] models, use `listOf(".installed")`.
 */
data class ModelInfo(
    val id: ModelId,
    val displayName: String,
    val description: String,
    /** Approximate total download size displayed in the UI. */
    val approximateSizeMb: Int,
    val source: DownloadSource,
    val requiredFiles: List<String>,
)

// ---------------------------------------------------------------------------
// Registry
// ---------------------------------------------------------------------------

/**
 * Central registry of all supported speech recognition models.
 *
 * Add a new [ModelInfo] entry here and a corresponding [ModelId] value to expose a new
 * model in the model-management UI without touching any other code.
 */
object ModelRegistry {

    val all: List<ModelInfo> = listOf(parakeetV3, voxtralMini, whisperSmall)

    private val byId: Map<ModelId, ModelInfo> = all.associateBy { it.id }

    /** Returns the [ModelInfo] for [id], throwing if the ID is not registered. */
    operator fun get(id: ModelId): ModelInfo =
        byId[id] ?: error("ModelRegistry: unknown ModelId $id")
}

// ---------------------------------------------------------------------------
// Model definitions
// ---------------------------------------------------------------------------

private val parakeetV3 = ModelInfo(
    id = ModelId.PARAKEET_V3,
    displayName = "Parakeet-V3 (Default)",
    description = "NeMo TDT model optimised for English on-device ASR. " +
                  "Fast and compact — the recommended choice for most devices.",
    approximateSizeMb = 300,
    source = DownloadSource.Files(
        baseUrl = PARAKEET_BASE,
        files = listOf(
            RemoteFile(
                filename     = "encoder-model.int8.onnx",
                sizeFraction = 0.70f,
                sha256       = "6139d2fa7e1b086097b277c7149725edbab89cc7c7ae64b23c741be4055aff09",
            ),
            RemoteFile(
                filename     = "decoder_joint-model.int8.onnx",
                sizeFraction = 0.19f,
                sha256       = "eea7483ee3d1a30375daedc8ed83e3960c91b098812127a0d99d1c8977667a70",
            ),
            RemoteFile(
                filename     = "nemo128.onnx",
                sizeFraction = 0.06f,
                sha256       = "a9fde1486ebfcc08f328d75ad4610c67835fea58c73ba57e3209a6f6cf019e9f",
            ),
            RemoteFile(filename = "config.json", sizeFraction = 0.01f, sha256 = null),
            RemoteFile(filename = "vocab.txt",   sizeFraction = 0.04f, sha256 = null),
        ),
    ),
    requiredFiles = listOf(
        "encoder-model.int8.onnx",
        "decoder_joint-model.int8.onnx",
        "nemo128.onnx",
        "config.json",
        "vocab.txt",
    ),
)

// NOTE: File names below are based on the standard onnx-community export convention.
// TODO: Confirm exact names from the repo and add SHA-256 hashes before production use.
//       This is a 4 GB+ model — suitable only for high-end devices with ample storage.
private val voxtralMini = ModelInfo(
    id = ModelId.VOXTRAL_MINI,
    displayName = "Voxtral Mini 4B",
    description = "Mistral-based multilingual speech model from onnx-community. " +
                  "Very large (~4 GB) — requires a high-end device with ample storage.",
    approximateSizeMb = 4_000,
    source = DownloadSource.Files(
        baseUrl = VOXTRAL_BASE,
        files = listOf(
            RemoteFile(filename = "encoder_model.onnx",          sizeFraction = 0.28f, sha256 = null),
            RemoteFile(filename = "decoder_model_merged.onnx",   sizeFraction = 0.58f, sha256 = null),
            RemoteFile(filename = "config.json",                 sizeFraction = 0.01f, sha256 = null),
            RemoteFile(filename = "generation_config.json",      sizeFraction = 0.01f, sha256 = null),
            RemoteFile(filename = "tokenizer.json",              sizeFraction = 0.04f, sha256 = null),
            RemoteFile(filename = "tokenizer_config.json",       sizeFraction = 0.01f, sha256 = null),
            RemoteFile(filename = "vocab.json",                  sizeFraction = 0.04f, sha256 = null),
            RemoteFile(filename = "merges.txt",                  sizeFraction = 0.03f, sha256 = null),
        ),
    ),
    requiredFiles = listOf(
        "encoder_model.onnx",
        "decoder_model_merged.onnx",
        "tokenizer.json",
    ),
)

// TODO: Verify the exact file names inside whisper_small_int8.zip and update
//       WhisperEngine.kt accordingly. Add the ZIP's SHA-256 hash once confirmed.
private val whisperSmall = ModelInfo(
    id = ModelId.WHISPER_SMALL,
    displayName = "Whisper Small INT8",
    description = "OpenAI Whisper Small, INT8-quantised. " +
                  "Good multilingual support (~250 MB). Distributed as a ZIP archive.",
    approximateSizeMb = 250,
    source = DownloadSource.ZipArchive(
        url    = WHISPER_ZIP_URL,
        sha256 = null, // TODO: fill in once the ZIP's SHA-256 is verified
    ),
    // The .installed marker is written by ModelDownloadManager after successful ZIP extraction.
    requiredFiles = listOf(".installed"),
)

