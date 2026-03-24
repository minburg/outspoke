package dev.brgr.outspoke.audio

/**
 * A single chunk of raw PCM audio captured from the microphone.
 *
 * @param samples      16-bit signed PCM samples (mono, [sampleRate] Hz).
 * @param sampleRate   Capture sample rate; always 16 000 Hz for Parakeet.
 * @param timestampMs  Wall-clock time (ms) when this chunk was read from the hardware buffer.
 */
data class AudioChunk(
    val samples: ShortArray,
    val sampleRate: Int = 16_000,
    val timestampMs: Long = System.currentTimeMillis(),
) {
    // ShortArray does not implement equals/hashCode structurally in Kotlin, so we override
    // them here to ensure two chunks with the same PCM content are considered equal.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioChunk) return false
        return sampleRate == other.sampleRate &&
            timestampMs == other.timestampMs &&
            samples.contentEquals(other.samples)
    }

    override fun hashCode(): Int {
        var result = samples.contentHashCode()
        result = 31 * result + sampleRate
        result = 31 * result + timestampMs.hashCode()
        return result
    }
}

