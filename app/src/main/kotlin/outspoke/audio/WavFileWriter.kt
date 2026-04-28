package dev.brgr.outspoke.audio

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

/**
 * Writes raw PCM 16-bit mono audio to a valid WAV file.
 *
 * Usage:
 * ```kotlin
 * val writer = WavFileWriter(file, sampleRate = 16_000)
 * writer.append(shortArrayOfSamples)
 * writer.close() // finalizes the WAV header with correct byte counts
 * ```
 *
 * The WAV header is written as a placeholder on construction and finalized with
 * the correct [dataBytes] count on [close]. The file must not be opened by
 * another process until after [close] is called.
 */
class WavFileWriter(file: File, private val sampleRate: Int, private val channels: Int = 1) : Closeable {

    private val raf = RandomAccessFile(file, "rw").apply {
        setLength(0)
        // Reserve 44 bytes for the WAV header; will be overwritten on close().
        write(ByteArray(WAV_HEADER_SIZE))
    }

    private var dataBytes = 0

    /** Appends [samples] (16-bit PCM, little-endian) to the WAV data section. */
    fun append(samples: ShortArray) {
        if (samples.isEmpty()) return
        val bytes = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val s = samples[i].toInt()
            bytes[i * 2]     = (s and 0xFF).toByte()
            bytes[i * 2 + 1] = (s shr 8 and 0xFF).toByte()
        }
        raf.write(bytes)
        dataBytes += bytes.size
    }

    /**
     * Finalizes the WAV header and closes the underlying file.
     * Must be called exactly once; subsequent calls have no effect.
     */
    override fun close() {
        try {
            raf.seek(0)
            writeWavHeader()
        } finally {
            raf.close()
        }
    }

    private fun writeWavHeader() {
        val byteRate = sampleRate * channels * BITS_PER_SAMPLE / 8
        val blockAlign = channels * BITS_PER_SAMPLE / 8

        // RIFF chunk descriptor
        raf.write("RIFF".toByteArray(Charsets.US_ASCII))
        raf.writeIntLE(WAV_HEADER_SIZE - 8 + dataBytes)  // file size - 8
        raf.write("WAVE".toByteArray(Charsets.US_ASCII))

        // fmt sub-chunk
        raf.write("fmt ".toByteArray(Charsets.US_ASCII))
        raf.writeIntLE(16)           // PCM sub-chunk size
        raf.writeShortLE(1)          // AudioFormat = 1 (PCM)
        raf.writeShortLE(channels)
        raf.writeIntLE(sampleRate)
        raf.writeIntLE(byteRate)
        raf.writeShortLE(blockAlign)
        raf.writeShortLE(BITS_PER_SAMPLE)

        // data sub-chunk
        raf.write("data".toByteArray(Charsets.US_ASCII))
        raf.writeIntLE(dataBytes)
    }


    private fun RandomAccessFile.writeIntLE(value: Int) {
        write(value and 0xFF)
        write(value shr 8 and 0xFF)
        write(value shr 16 and 0xFF)
        write(value shr 24 and 0xFF)
    }

    private fun RandomAccessFile.writeShortLE(value: Int) {
        write(value and 0xFF)
        write(value shr 8 and 0xFF)
    }

    private companion object {
        const val WAV_HEADER_SIZE = 44
        const val BITS_PER_SAMPLE = 16
    }
}

