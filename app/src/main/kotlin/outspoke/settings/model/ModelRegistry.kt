package dev.brgr.outspoke.settings.model

private const val PARAKEET_BASE =
    "https://huggingface.co/istupakov/parakeet-tdt-0.6b-v3-onnx/resolve/main"

// Moondream's post-trained parakeet-tdt-0.6b-v3 (same architecture/tokenizer, better WER),
// exported to ONNX by Olicorne. int8 encoder/decoder live under /int8/; preprocessor,
// vocab and config sit at the repo root (nemo128.onnx and vocab.txt are byte-identical to
// the istupakov v3 export above).
private const val PARAKEET_ULTRA_BASE =
    "https://huggingface.co/Olicorne/parakeet-tdt-0.6b-v3-ultra-onnx/resolve/main/int8"
private const val PARAKEET_ULTRA_ROOT =
    "https://huggingface.co/Olicorne/parakeet-tdt-0.6b-v3-ultra-onnx/resolve/main"

// Orukeet (oruk's fine-tune of parakeet-tdt-0.6b-v3), INT8 export with a combined
// decoder+joiner graph. Flat repo layout; nemo128.onnx and vocab.txt are byte-identical
// to the istupakov v3 files. Weights CC BY-SA 4.0, converter MIT - see NOTICE in README.
private const val ORUKEET_BASE =
    "https://huggingface.co/ysdede/orukeet-0.6b-onnx/resolve/main"

// ONNX model files are under /onnx/; tokenizer + config files are at the repo root.
private const val VOXTRAL_BASE =
    "https://huggingface.co/onnx-community/Voxtral-Mini-4B-Realtime-2602-ONNX/resolve/main/onnx"
private const val VOXTRAL_ROOT =
    "https://huggingface.co/onnx-community/Voxtral-Mini-4B-Realtime-2602-ONNX/resolve/main"

// Whisper Large v3 Turbo INT8 - same onnx-community export format as Voxtral.
// ONNX files live under /onnx/; tokenizer.json is at the repo root.
private const val WHISPER_TURBO_BASE =
    "https://huggingface.co/onnx-community/whisper-large-v3-turbo/resolve/main/onnx"
private const val WHISPER_TURBO_ROOT =
    "https://huggingface.co/onnx-community/whisper-large-v3-turbo/resolve/main"

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
    /**
     * When set, this exact URL is used instead of `"$baseUrl/$filename"`.
     * Use this for files that live at a different path than the model's [DownloadSource.Files.baseUrl]
     * (e.g. tokenizer files at the HuggingFace repo root when ONNX files are under `/onnx/`).
     */
    val urlOverride: String? = null,
) {
    /** Returns [urlOverride] if set, otherwise constructs `"$baseUrl/$filename"`. */
    fun url(baseUrl: String): String = urlOverride ?: "$baseUrl/$filename"
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

/**
 * Central registry of all supported speech recognition models.
 *
 * Add a new [ModelInfo] entry here and a corresponding [ModelId] value to expose a new
 * model in the model-management UI without touching any other code.
 */
object ModelRegistry {

    //    val all: List<ModelInfo> = listOf(parakeetV3, voxtralMini, whisperLargeV3Turbo)
    val all: List<ModelInfo> =
        listOf(parakeetV3, parakeetUltra, orukeet) // for now removed voxtralMini and whisperLargeV3Turbo as I could not get it to run on-device within reasonable resource limits

    private val byId: Map<ModelId, ModelInfo> = all.associateBy { it.id }

    /** Returns the [ModelInfo] for [id], throwing if the ID is not registered. */
    operator fun get(id: ModelId): ModelInfo =
        byId[id] ?: error("ModelRegistry: unknown ModelId $id")
}

private val parakeetV3 = ModelInfo(
    id = ModelId.PARAKEET_V3,
    displayName = "Parakeet-V3 (Default)",
    description = "NeMo TDT model for on-device ASR optimised for English and 24 other european languages like DE, FR, ES, IT, RU etc. " +
            "Very fast and compact - the recommended choice for most devices.",
    approximateSizeMb = 700,
    source = DownloadSource.Files(
        baseUrl = PARAKEET_BASE,
        files = listOf(
            RemoteFile(
                filename = "encoder-model.int8.onnx",
                sizeFraction = 0.70f,
                sha256 = "6139d2fa7e1b086097b277c7149725edbab89cc7c7ae64b23c741be4055aff09",
            ),
            RemoteFile(
                filename = "decoder_joint-model.int8.onnx",
                sizeFraction = 0.19f,
                sha256 = "eea7483ee3d1a30375daedc8ed83e3960c91b098812127a0d99d1c8977667a70",
            ),
            RemoteFile(
                filename = "nemo128.onnx",
                sizeFraction = 0.06f,
                sha256 = "a9fde1486ebfcc08f328d75ad4610c67835fea58c73ba57e3209a6f6cf019e9f",
            ),
            RemoteFile(filename = "config.json", sizeFraction = 0.01f, sha256 = null),
            RemoteFile(filename = "vocab.txt", sizeFraction = 0.04f, sha256 = null),
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

// Parakeet Ultra: moondream's post-trained parakeet-tdt-0.6b-v3 (same 0.6B architecture
// and tokenizer, same 25 languages) - clearly better WER on FLEURS, background noise and
// long-form audio. File sizes verified via HuggingFace API:
//   int8/encoder-model.int8.onnx         649 431 593 B ≈ 619 MB
//   int8/decoder_joint-model.int8.onnx    18 203 490 B ≈  17 MB
//   nemo128.onnx + vocab.txt + config.json ≈ 0.2 MB
// Total ≈ 668 MB. Engine: ParakeetEngine (tensor names and quantisation-agnostic -
// verified byte-identical vocab/preprocessor and identical graph I/O vs the v3 export).
private val parakeetUltra = ModelInfo(
    id = ModelId.PARAKEET_ULTRA,
    displayName = "Parakeet Ultra",
    description = "Moondream's post-trained Parakeet-TDT v3: same size and speed, but more accurate " +
            "for English and the same 24 other european languages (DE, FR, ES, IT, RU etc.) - notably " +
            "better in background noise and on long dictations.",
    approximateSizeMb = 668,
    source = DownloadSource.Files(
        baseUrl = PARAKEET_ULTRA_BASE,
        files = listOf(
            RemoteFile(
                filename = "encoder-model.int8.onnx",
                sizeFraction = 0.97f,
                sha256 = "714047d70be4b612dce02e0ab2f9e0d6552f78adc421f408d154070796bc2dcf",
            ),
            RemoteFile(
                filename = "decoder_joint-model.int8.onnx",
                sizeFraction = 0.027f,
                sha256 = "667ffde2242a560923cba6bc7f69ef03d736f8a36ca9588f2e6f9fa818cf2fc1",
            ),
            RemoteFile(
                filename = "nemo128.onnx",
                sizeFraction = 0.001f,
                sha256 = "a9fde1486ebfcc08f328d75ad4610c67835fea58c73ba57e3209a6f6cf019e9f",
                urlOverride = "$PARAKEET_ULTRA_ROOT/nemo128.onnx",
            ),
            RemoteFile(
                filename = "vocab.txt",
                sizeFraction = 0.001f,
                sha256 = "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d",
                urlOverride = "$PARAKEET_ULTRA_ROOT/vocab.txt",
            ),
            RemoteFile(
                filename = "config.json",
                sizeFraction = 0.0f,
                sha256 = null,
                urlOverride = "$PARAKEET_ULTRA_ROOT/config.json",
            ),
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

// Orukeet: community fine-tune of parakeet-tdt-0.6b-v3 (same architecture/tokenizer/
// languages; ~1.5x faster CPU decode, WER on par or better - mixed per language).
// File sizes verified via HuggingFace API (cross-checked against the repo's manifest.json):
//   encoder-model.int8.onnx         653 182 378 B ≈ 623 MB
//   decoder_joint-model.int8.onnx    18 202 844 B ≈  17 MB
//   nemo128.onnx + vocab.txt + config.json ≈ 0.2 MB
// Total ≈ 672 MB. Its decoder lists prednet_lengths FIRST (logits last) - the one real
// export with reordered outputs; load() resolves output names accordingly (issue #39).
private val orukeet = ModelInfo(
    id = ModelId.ORUKEET,
    displayName = "Orukeet (v3 fine-tune)",
    description = "Community fine-tune of Parakeet-TDT v3 for English and 24 other european languages. " +
            "Roughly 30 % faster CPU decoding than Parakeet-V3 with similar accuracy.",
    approximateSizeMb = 672,
    source = DownloadSource.Files(
        baseUrl = ORUKEET_BASE,
        files = listOf(
            RemoteFile(
                filename = "encoder-model.int8.onnx",
                sizeFraction = 0.97f,
                sha256 = "7b55f2a504a20a8e462899f5befd45f4a1784948d76ed0127902d9cf39405487",
            ),
            RemoteFile(
                filename = "decoder_joint-model.int8.onnx",
                sizeFraction = 0.027f,
                sha256 = "95d3b1f53f9aadc5ef58e63664a3681a2184ee228b5010e1ef975a1c4ea8318a",
            ),
            RemoteFile(
                filename = "nemo128.onnx",
                sizeFraction = 0.001f,
                sha256 = "a9fde1486ebfcc08f328d75ad4610c67835fea58c73ba57e3209a6f6cf019e9f",
            ),
            RemoteFile(
                filename = "vocab.txt",
                sizeFraction = 0.001f,
                sha256 = "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d",
            ),
            RemoteFile(filename = "config.json", sizeFraction = 0.0f, sha256 = null),
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

// Q4-quantised variant: audio_encoder_q4 (661 MB) + embed_tokens_q4 (258 MB)
//                       + decoder_model_merged_q4 (~2.3 GB) + tokenizer.json
// Total ≈ 3 300 MB - suitable only for high-end devices with ≥ 6 GB RAM.
@Suppress("unused")
private val voxtralMini = ModelInfo(
    id = ModelId.VOXTRAL_MINI,
    displayName = "Voxtral Mini 4B (Q4)",
    description = "Mistral-based multilingual speech model from onnx-community. " +
            "Q4-quantised (~3.3 GB) - requires a high-end device with ample storage.",
    approximateSizeMb = 3_300,
    source = DownloadSource.Files(
        baseUrl = VOXTRAL_BASE,
        files = listOf(
            // Audio encoder: .onnx header + single .onnx_data sidecar
            RemoteFile(filename = "audio_encoder_q4.onnx", sizeFraction = 0.01f, sha256 = null),
            RemoteFile(filename = "audio_encoder_q4.onnx_data", sizeFraction = 0.20f, sha256 = null),
            // Token embedding table
            RemoteFile(filename = "embed_tokens_q4.onnx", sizeFraction = 0.01f, sha256 = null),
            RemoteFile(filename = "embed_tokens_q4.onnx_data", sizeFraction = 0.08f, sha256 = null),
            // Merged decoder: header + two data shards
            RemoteFile(filename = "decoder_model_merged_q4.onnx", sizeFraction = 0.01f, sha256 = null),
            RemoteFile(filename = "decoder_model_merged_q4.onnx_data", sizeFraction = 0.61f, sha256 = null),
            RemoteFile(filename = "decoder_model_merged_q4.onnx_data_1", sizeFraction = 0.08f, sha256 = null),
            // Tokenizer + config - these live at the repo ROOT, not under /onnx/
            RemoteFile(
                filename = "tokenizer.json", sizeFraction = 0.00f, sha256 = null,
                urlOverride = "$VOXTRAL_ROOT/tokenizer.json"
            ),
            RemoteFile(
                filename = "tokenizer_config.json", sizeFraction = 0.00f, sha256 = null,
                urlOverride = "$VOXTRAL_ROOT/tokenizer_config.json"
            ),
            RemoteFile(
                filename = "generation_config.json", sizeFraction = 0.00f, sha256 = null,
                urlOverride = "$VOXTRAL_ROOT/generation_config.json"
            ),
        ),
    ),
    requiredFiles = listOf(
        "audio_encoder_q4.onnx",
        "audio_encoder_q4.onnx_data",
        "embed_tokens_q4.onnx",
        "embed_tokens_q4.onnx_data",
        "decoder_model_merged_q4.onnx",
        "decoder_model_merged_q4.onnx_data",
        "tokenizer.json",
    ),
)

// Whisper Large v3 Turbo INT8 - real file sizes (verified via HuggingFace API):
//   encoder_model_int8.onnx          644 822 094 B ≈ 615 MB
//   decoder_model_merged_int8.onnx   439 936 716 B ≈ 420 MB  (merged branches + full vocab table)
//   tokenizer.json                     2 480 617 B ≈   2 MB
//   Total                                          ≈ 1 037 MB ≈ 1.1 GB
// Same onnx-community optimum export format → tensor names match WhisperEngine directly.
private val whisperLargeV3Turbo = ModelInfo(
    id = ModelId.WHISPER_SMALL,   // reuses existing ModelId slot
    displayName = "Whisper Large-v3 Turbo (INT8)",
    description = "OpenAI Whisper Large-v3 encoder with a 2-layer turbo decoder. " +
            "Near-Large-v3 accuracy, multilingual, INT8-quantised (~1.1 GB).",
    approximateSizeMb = 1_037,
    source = DownloadSource.Files(
        baseUrl = WHISPER_TURBO_BASE,
        files = listOf(
            RemoteFile(
                filename = "encoder_model_int8.onnx",
                sizeFraction = 0.593f,
                sha256 = null,
            ),
            RemoteFile(
                filename = "decoder_model_merged_int8.onnx",
                sizeFraction = 0.405f,
                sha256 = null,
            ),
            RemoteFile(
                filename = "tokenizer.json",
                sizeFraction = 0.002f,
                sha256 = null,
                urlOverride = "$WHISPER_TURBO_ROOT/tokenizer.json",
            ),
        ),
    ),
    requiredFiles = listOf(
        "encoder_model_int8.onnx",
        "decoder_model_merged_int8.onnx",
        "tokenizer.json",
    ),
)

