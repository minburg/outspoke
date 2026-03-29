package dev.brgr.outspoke.audio

/**
 * Common interface for Voice Activity Detection filters.
 */
interface IVadFilter {
    /** `true` while outputting speech frames (including during the hangover window). */
    val isSpeechActive: Boolean

    /**
     * Processes a single chunk of audio and returns a list of chunks to forward to inference.
     * Returns an empty list during silence, and buffers leading audio to emit on speech onset.
     */
    fun process(chunk: AudioChunk, rms: Float): List<AudioChunk>

    /**
     * Resets internal state. Should be called when a recording session ends.
     * Returns `true` if the session ended while speech was active (meaning trailing audio
     * might have been cut off).
     */
    fun flush(): Boolean

    /** Releases any resources held by the filter (like native ONNX sessions). */
    fun close() {}
}
