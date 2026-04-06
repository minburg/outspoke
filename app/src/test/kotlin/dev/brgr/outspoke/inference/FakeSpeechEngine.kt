package dev.brgr.outspoke.inference

import dev.brgr.outspoke.audio.AudioChunk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File

/**
 * A scripted [SpeechEngine] that returns pre-defined [TranscriptResult]s for each successive
 * [transcribe] call.  Once the list is exhausted the last entry is repeated, so tests do not
 * need to size the response list to match the exact number of engine calls.
 */
class FakeSpeechEngine(
    private val responses: List<TranscriptResult>,
) : SpeechEngine {

    private var callIndex = 0

    override val isLoaded = true
    override fun load(modelDir: File) = Unit
    override fun close() = Unit

    override fun transcribe(chunk: AudioChunk): TranscriptResult {
        val result = responses.getOrElse(callIndex) { responses.last() }
        callIndex++
        return result
    }
}

/**
 * Produces [chunks] silent 1-second audio blobs (16 000 samples each at 16 kHz).
 * The content is all-zero PCM (silence); the [InferenceRepository] only cares about
 * sample counts, not actual audio data, during unit tests.
 */
fun silentAudioFlow(chunks: Int): Flow<AudioChunk> = flow {
    repeat(chunks) { emit(AudioChunk(ShortArray(16_000))) }
}

