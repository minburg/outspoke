package dev.brgr.outspoke.inference

import dev.brgr.outspoke.audio.AudioChunk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class EngineLifetimeTest {
    private class Engine : SpeechEngine {
        var closed = false
        override val isLoaded get() = !closed
        override fun load(modelDir: File) = Unit
        override fun close() { closed = true }
        override fun transcribe(chunk: AudioChunk): TranscriptResult {
            check(!closed) { "Model closed during recording" }
            return TranscriptResult.Final("still recording", confidence = 0.99f)
        }
    }

    @Test
    fun `a decoder failure during the final flush is reported`() = runBlocking {
        val fake = FakeStreamingParakeet(emptyList())
        val failure = IllegalStateException("Encoder unavailable")
        val engine = object : SpeechEngine by fake, ChunkStreamingEngine by fake {
            override fun encodeBuffer(samples: FloatArray): Pair<ai.onnxruntime.OnnxTensor, Int> {
                throw failure
            }
        }
        val results = InferenceRepository(engine).transcribe(
            flowOf(AudioChunk(ShortArray(8_000) { 1000 })),
        ).toList()
        assertSame(failure, (results.single() as TranscriptResult.Failure).cause)
    }

    @Test
    fun `memory pressure cannot close an active recording and final text survives`() = runBlocking {
        withTimeout(5_000) {
            val engine = Engine()
            val repo = InferenceRepository(engine)
            val capturing = CompletableDeferred<Unit>()
            val stop = CompletableDeferred<Unit>()
            val result = async {
                repo.transcribe(flow {
                    emit(AudioChunk(ShortArray(32_000) { 1000 }))
                    capturing.complete(Unit)
                    stop.await()
                    emit(AudioChunk(ShortArray(8_000) { 1000 }))
                }, postprocessingEnabled = false).toList()
            }
            capturing.await()
            assertFalse(repo.closeEngine(onlyIfIdle = true))
            assertFalse(engine.closed)
            stop.complete(Unit)
            assertTrue(result.await().any { it is TranscriptResult.Final && it.text == "still recording" })
            assertTrue(repo.closeEngine(onlyIfIdle = true))
            assertTrue(engine.closed)
        }
    }

    @Test
    fun `a stale repository cannot open the microphone after model teardown`() = runBlocking {
        val repo = InferenceRepository(Engine())
        repo.closeEngine()
        var captureStarted = false
        try {
            repo.transcribe(flow<AudioChunk> { captureStarted = true }).toList()
            fail("Closed model should reject recording")
        } catch (_: IllegalStateException) {
            assertFalse(captureStarted)
        }
    }
}
