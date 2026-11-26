package dev.brgr.outspoke.audio

import ai.onnxruntime.OrtSession
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SileroVadFailureTest {
    @Test
    fun `speech keeps flowing after the neural VAD session fails`() {
        val model = File("src/main/res/raw/silero_vad_v4.onnx")
        val filter = SileroVadFilter(model.readBytes())
        // Force the same run() exception as a failed native session during recording.
        val field = SileroVadFilter::class.java.getDeclaredField("session")
        field.isAccessible = true
        val session = field.get(filter) as OrtSession
        session.close()
        try {
            val speech = AudioChunk(ShortArray(480) { 4000 })
            val output = (1..30).flatMap { filter.process(speech, 0.12f) }
            assertTrue("VAD failure must not silently discard ongoing speech", output.any { it.samples.isNotEmpty() })
        } finally {
            field.set(filter, null)
            filter.close()
        }
    }
}
