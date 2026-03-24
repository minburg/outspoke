package dev.brgr.outspoke.settings.model

/** All possible states of the local Parakeet-V3 ONNX model. */
sealed class ModelState {
    /** Model file is absent from internal storage. */
    object NotDownloaded : ModelState()

    /** Download is in progress. [progressFraction] is in [0.0, 1.0]. */
    data class Downloading(val progressFraction: Float) : ModelState()

    /** Model file is present, non-empty, and passed integrity verification. */
    object Ready : ModelState()

    /** Download completed but the SHA-256 hash did not match. */
    object Corrupted : ModelState()
}

