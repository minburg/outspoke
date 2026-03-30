package dev.brgr.outspoke.inference

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import dev.brgr.outspoke.audio.AudioChunk
import org.json.JSONObject
import java.io.File
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.LongBuffer

private const val TAG = "ParakeetEngine"
private const val FALLBACK_BLANK_ID = 1024

private object Names {
    // nemo128.onnx  ← verified: expected [waveforms, waveforms_lens]
    const val PREP_IN_AUDIO = "waveforms"
    const val PREP_IN_LENGTH = "waveforms_lens"
    // Outputs accessed by index: [0] = features, [1] = feature lengths

    // encoder-model.int8.onnx  ← verified
    const val ENC_IN_SIGNAL = "audio_signal"   // FLOAT [-1, 128, -1]
    const val ENC_IN_LENGTH = "length"          // INT64 [-1]
    const val ENC_OUT_SIGNAL = "outputs"          // FLOAT [-1, 1024, -1]  (B, D, T)
    const val ENC_OUT_LEN = "encoded_lengths"  // INT64 [-1]

    // decoder_joint-model.int8.onnx  ← verified
    const val DEC_IN_ENC_OUT = "encoder_outputs"  // FLOAT [-1, 1024, -1]
    const val DEC_IN_TARGETS = "targets"           // INT32 [-1, -1]
    const val DEC_IN_TARGET_LEN = "target_length"     // INT32 [-1]
    const val DEC_IN_STATES_1 = "input_states_1"    // FLOAT [2, -1, 640]
    const val DEC_IN_STATES_2 = "input_states_2"    // FLOAT [2, -1, 640]
    // Outputs accessed by index:
    //   0 → "outputs"         FLOAT [-1,-1,-1, 8198]  (B, T_enc, T_tgt, vocab+dur)
    //   1 → "prednet_lengths" INT32 [-1]
    //   2 → "output_states_1" FLOAT [2,-1, 640]
    //   3 → "output_states_2" FLOAT [2,-1, 640]
}

/**
 * Wraps the three Parakeet-V3 ONNX sessions.
 *
 * Pipeline (all tensor names verified from device logcat):
 *  1. Normalise PCM  →  float32 in [-1, 1]
 *  2. nemo128.onnx   →  log-mel features  [1, 128, T′]
 *  3. encoder        →  encoded features  [1, 1024, T_enc]   ← (B, D, T) format!
 *  4. greedy TDT     →  token IDs via decoder_joint with LSTM state carry-over
 *  5. detokenise     →  string via vocab.txt
 */
class ParakeetEngine : SpeechEngine {

    private var env: OrtEnvironment? = null
    private var prepSession: OrtSession? = null
    private var encSession: OrtSession? = null
    private var decSession: OrtSession? = null

    private var vocabulary: Array<String> = emptyArray()
    private var blankId: Int = FALLBACK_BLANK_ID
    private var numDurations: Int = 0   // derived at load: outputDim - (blankId + 1)


    @Volatile
    override var isLoaded: Boolean = false
        private set


    /**
     * Creates all ONNX sessions and loads the vocabulary.
     * **Must be called on a background thread** - loading takes 1-3 s on first run.
     *
     * @throws IllegalStateException if called while already loaded.
     * @throws Exception (OrtException / IOException) if any model file is corrupt.
     */
    override fun load(modelDir: File) {
        check(!isLoaded) { "Already loaded; call close() before reloading" }

        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(2)
            setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
        }

        env = OrtEnvironment.getEnvironment()
        val e = env!!

        val prepFile = File(modelDir, "nemo128.onnx")
        if (prepFile.exists()) {
            prepSession = e.createSession(prepFile.absolutePath, opts)
            logSession("nemo128 (preprocessor)", prepSession!!)
        } else {
            Log.w(TAG, "nemo128.onnx absent - will forward raw audio to encoder (shapes will mismatch)")
        }

        encSession = e.createSession(File(modelDir, "encoder-model.int8.onnx").absolutePath, opts)
        logSession("encoder", encSession!!)

        decSession = e.createSession(
            File(modelDir, "decoder_joint-model.int8.onnx").absolutePath, opts
        )
        logSession("decoder_joint", decSession!!)

        // vocab.txt format: "<token_text> <id>" (e.g. "▁like 2656").
        // Lines are in ID order so line N = token ID N - we just need the first field.
        vocabulary = File(modelDir, "vocab.txt").readLines()
            .map { line -> line.trim().split(Regex("\\s+")).firstOrNull().orEmpty() }
            .toTypedArray()
        Log.d(TAG, "Vocabulary: ${vocabulary.size} tokens")

        // Resolution order:
        //  1. Scan vocab.txt for a known blank label - most reliable for NeMo TDT exports
        //  2. config.json - may be stale or wrong in third-party ONNX conversions
        //  3. vocabulary.size - 1 - safe heuristic (blank is always the last NeMo token)
        blankId = scanVocabForBlankId()
            ?: parseBlankId(File(modelDir, "config.json"))
                    ?: run {
                val fallback = (vocabulary.size - 1).coerceAtLeast(0)
                Log.w(TAG, "blank_id not found in vocab or config.json - using vocabulary.size-1=$fallback")
                fallback
            }
        val blankLabel = vocabulary.getOrNull(blankId) ?: "<out-of-range>"
        Log.d(TAG, "Blank id: $blankId  ('$blankLabel')")

        // Derive numDurations from the decoder's first output dimension
        val decOutNames = decSession!!.outputNames.toList()
        val jointOutInfo = decSession!!.outputInfo[decOutNames[0]]
        val jointDim = (jointOutInfo?.info as? ai.onnxruntime.TensorInfo)?.shape?.last()?.toInt() ?: 0
        numDurations = if (jointDim > blankId + 1) jointDim - (blankId + 1) else 0
        Log.d(TAG, "Joint output dim: $jointDim  numDurations: $numDurations")

        opts.close()
        isLoaded = true
        Log.d(TAG, "ParakeetEngine ready (modelDir=${modelDir.path})")
    }


    /**
     * Transcribes [chunk] synchronously and returns a [TranscriptResult].
     *
     * - Short chunks (< ~200 ms) will typically return [TranscriptResult.Partial] with an
     *   empty string because the encoder does not have enough context. Accumulate chunks in
     *   [InferenceRepository] before calling this for production use.
     * - This is a **blocking** call - always dispatch to [kotlinx.coroutines.Dispatchers.Default].
     */
    override fun transcribe(chunk: AudioChunk): TranscriptResult {
        if (!isLoaded) return TranscriptResult.Failure(IllegalStateException("Engine not loaded"))
        return try {
            val e = env!!
            val enc = encSession!!
            val dec = decSession!!

            // 1. Normalise
            val samples = normalizePcm(chunk.samples)

            // 2. Preprocess → mel features
            val (feats, featLen) = preprocess(e, samples)

            // 3. Encode
            val (encOut, encLen) = encode(e, enc, feats, featLen)
            feats.close()

            // 4. Greedy TDT decode
            val text = greedyDecode(e, dec, encOut, encLen)
            encOut.close()

            if (text.isBlank()) TranscriptResult.Partial("") else TranscriptResult.Final(text)
        } catch (ex: Exception) {
            Log.e(TAG, "transcribe() failed", ex)
            TranscriptResult.Failure(ex)
        }
    }

    /** Releases all native ONNX Runtime resources. Safe to call more than once. */
    override fun close() {
        prepSession?.close(); prepSession = null
        encSession?.close(); encSession = null
        decSession?.close(); decSession = null
        env?.close(); env = null
        isLoaded = false
        Log.d(TAG, "ParakeetEngine closed")
    }

    /**
     * Converts [pcm] PCM samples to float32 in [-1.0, 1.0].
     *
     * Intentionally allocates a fresh array per call so that concurrent partial-inference
     * coroutines each operate on independent data. The allocation cost (~64 KB per 1-s
     * chunk) is negligible compared to the ONNX inference itself.
     */
    private fun normalizePcm(pcm: ShortArray): FloatArray {
        val out = FloatArray(pcm.size)
        for (i in pcm.indices) out[i] = pcm[i] / 32_768f
        return out
    }

    /**
     * Runs nemo128.onnx to convert raw audio to log-mel features.
     * Input names verified: `waveforms` (FLOAT) + `waveforms_lens` (INT64).
     * Outputs accessed by index: [0] = feature tensor, [1] = lengths.
     * The feature length is read from the tensor's time dimension to avoid
     * INT32/INT64 ambiguity on the length output.
     */
    private fun preprocess(env: OrtEnvironment, samples: FloatArray): Pair<OnnxTensor, Long> {
        val audioLen = samples.size.toLong()
        val prep = prepSession ?: run {
            Log.w(TAG, "No preprocessor - forwarding raw audio (shapes will mismatch)")
            return OnnxTensor.createTensor(
                env, FloatBuffer.wrap(samples), longArrayOf(1L, audioLen)
            ) to audioLen
        }

        val prepOutputNames = prep.outputNames.toList()
        val audioTensor = OnnxTensor.createTensor(
            env, FloatBuffer.wrap(samples), longArrayOf(1L, audioLen)
        )
        val lenTensor = OnnxTensor.createTensor(
            env, LongBuffer.wrap(longArrayOf(audioLen)), longArrayOf(1L)
        )
        val inputs = mapOf(Names.PREP_IN_AUDIO to audioTensor, Names.PREP_IN_LENGTH to lenTensor)

        return prep.run(inputs).use { result ->
            audioTensor.close(); lenTensor.close()
            val featTensor = result.get(prepOutputNames[0]).get() as OnnxTensor
            // Read time dimension directly from shape - avoids INT32/INT64 ambiguity on length output
            val featLen = featTensor.info.shape[2]   // shape: [batch, 128, T′]
            cloneTensor(env, featTensor) to featLen
        }
    }

    private fun encode(
        env: OrtEnvironment,
        session: OrtSession,
        features: OnnxTensor,
        featLen: Long,
    ): Pair<OnnxTensor, Int> {
        val lenTensor = OnnxTensor.createTensor(
            env, LongBuffer.wrap(longArrayOf(featLen)), longArrayOf(1L)
        )
        val inputs = mapOf(Names.ENC_IN_SIGNAL to features, Names.ENC_IN_LENGTH to lenTensor)

        return session.run(inputs).use { result ->
            lenTensor.close()
            val outTensor = result.get(Names.ENC_OUT_SIGNAL)
                .orElseThrow { RuntimeException("Encoder output '${Names.ENC_OUT_SIGNAL}' not found") }
                    as OnnxTensor
            val lenOut = result.get(Names.ENC_OUT_LEN)
                .orElseThrow { RuntimeException("Encoder output '${Names.ENC_OUT_LEN}' not found") }
                    as OnnxTensor
            val encLen = lenOut.longBuffer[0].toInt()
            cloneTensor(env, outTensor) to encLen
        }
    }

    /**
     * Greedy TDT decoder - all details verified from logcat on 2026-03-23:
     *
     * Encoder output layout: [batch, enc_dim=1024, enc_time]  ← (B, D, T) NOT (B, T, D)
     *
     * decoder_joint inputs:
     *   encoder_outputs  FLOAT [1, 1024, 1]    one frame at a time
     *   targets          INT32 [1, 1]           previous token
     *   target_length    INT32 [1]              always 1
     *   input_states_1   FLOAT [2, 1, 640]      LSTM hidden
     *   input_states_2   FLOAT [2, 1, 640]      LSTM cell
     *
     * decoder_joint outputs  (by index):
     *   0  outputs          FLOAT [1, 1, 1, 8198]  joint logits
     *   1  prednet_lengths  INT32 [1]              ignored
     *   2  output_states_1  FLOAT [2, 1, 640]      updated LSTM hidden
     *   3  output_states_2  FLOAT [2, 1, 640]      updated LSTM cell
     *
     * Logit layout: [0..blankId] = token logits (blank at blankId=8193),
     *               [blankId+1 .. blankId+numDurations] = TDT duration logits
     *
     * TDT advance rule:
     *   blank:     t += max(1, predictedDuration)
     *   non-blank: emit token; t += predictedDuration (0 = stay at same frame)
     */
    private fun greedyDecode(
        env: OrtEnvironment,
        session: OrtSession,
        encoderOut: OnnxTensor,
        encodedLength: Int,
    ): String {
        // Encoder layout: [1, D, T]
        val encShape = encoderOut.info.shape
        val encDim = encShape[1].toInt()   // D = 1024
        val T = encodedLength

        val encData = FloatArray(encoderOut.floatBuffer.remaining())
        encoderOut.floatBuffer.rewind()
        encoderOut.floatBuffer.get(encData)

        // LSTM state buffers: [2, 1, 640] = 1280 floats, initialised to zero
        val stateShape = longArrayOf(2L, 1L, 640L)
        var lstmState1 = FloatArray(2 * 1 * 640)
        var lstmState2 = FloatArray(2 * 1 * 640)

        val decOutputNames = session.outputNames.toList()

        val hypothesis = mutableListOf<Int>()
        // Predictor embedding range is [0, blankId), so initialise with 0 (SOS).
        // blankId is a *joint output label* only - never fed into the embedding layer.
        var prevToken = 0
        var t = 0
        var maxIter = T * 20 + 50   // global safety cap
        var tokensAtFrame = 0             // per-frame guard against duration=0 loops
        val MAX_TOKENS_PER_FRAME = 30
        val MAX_HYPOTHESIS = 2000     // ~20-30 s of speech at typical token rate

        while (t < T && maxIter-- > 0 && hypothesis.size < MAX_HYPOTHESIS) {
            // Extract one encoder frame: encoder[0, :, t] → frame shape [1, D, 1]
            val frameData = FloatArray(encDim) { d -> encData[d * T + t] }

            val frameTensor = OnnxTensor.createTensor(
                env, FloatBuffer.wrap(frameData), longArrayOf(1L, encDim.toLong(), 1L)
            )
            // targets and target_length are INT32 (verified from logcat)
            val targetTensor = OnnxTensor.createTensor(
                env, IntBuffer.wrap(intArrayOf(prevToken)), longArrayOf(1L, 1L)
            )
            val targetLenTensor = OnnxTensor.createTensor(
                env, IntBuffer.wrap(intArrayOf(1)), longArrayOf(1L)
            )
            val statesTensor1 = OnnxTensor.createTensor(
                env, FloatBuffer.wrap(lstmState1), stateShape
            )
            val statesTensor2 = OnnxTensor.createTensor(
                env, FloatBuffer.wrap(lstmState2), stateShape
            )

            val inputs = mapOf(
                Names.DEC_IN_ENC_OUT to frameTensor,
                Names.DEC_IN_TARGETS to targetTensor,
                Names.DEC_IN_TARGET_LEN to targetLenTensor,
                Names.DEC_IN_STATES_1 to statesTensor1,
                Names.DEC_IN_STATES_2 to statesTensor2,
            )

            try {
                session.run(inputs).use { result ->
                    // Joint logits: [1, 1, 1, 8198] → flat array of 8198 floats
                    val logitsTensor = result.get(decOutputNames[0]).get() as OnnxTensor
                    val logits = FloatArray(logitsTensor.floatBuffer.remaining())
                    logitsTensor.floatBuffer.get(logits)

                    // Token: argmax over [0..blankId] (inclusive)
                    val predictedToken = (0..blankId).maxByOrNull { logits[it] } ?: blankId

                    // Duration: argmax over the last numDurations logits.
                    // Snapshot to a local val so the compiler's flow analysis can prove
                    // the range is non-empty inside the if-branch (mutable var fields are
                    // not tracked across the guard check).
                    val nDur = numDurations
                    val predictedDur = if (nDur > 0) {
                        val durBase = blankId + 1
                        // coerceAtLeast(1) is a no-op here (nDur > 0 is already guaranteed),
                        // but it makes the bound provably ≥ 1 to the IDE's range-empty inspection,
                        // which doesn't track numeric constraints through if-conditions.
                        (0 until nDur.coerceAtLeast(1)).maxByOrNull { logits[durBase + it] } ?: 0
                    } else 0

                    // Update LSTM states on every step
                    if (decOutputNames.size > 2) {
                        val s1 = result.get(decOutputNames[2]).get() as OnnxTensor
                        lstmState1 = FloatArray(s1.floatBuffer.remaining()).also { s1.floatBuffer.get(it) }
                    }
                    if (decOutputNames.size > 3) {
                        val s2 = result.get(decOutputNames[3]).get() as OnnxTensor
                        lstmState2 = FloatArray(s2.floatBuffer.remaining()).also { s2.floatBuffer.get(it) }
                    }

                    // TDT advance rule
                    if (predictedToken == blankId) {
                        t += maxOf(1, predictedDur)
                        tokensAtFrame = 0
                    } else {
                        hypothesis.add(predictedToken)
                        prevToken = predictedToken
                        tokensAtFrame++
                        if (predictedDur > 0) {
                            t += predictedDur
                            tokensAtFrame = 0
                        } else if (tokensAtFrame >= MAX_TOKENS_PER_FRAME) {
                            // Safety: force advance when duration=0 emissions pile up on one frame
                            Log.w(TAG, "greedyDecode: stuck at frame $t - forcing advance")
                            t++
                            tokensAtFrame = 0
                        }
                        // else: predictedDur == 0 and within per-frame cap → stay on frame
                    }
                }
            } finally {
                frameTensor.close()
                targetTensor.close()
                targetLenTensor.close()
                statesTensor1.close()
                statesTensor2.close()
            }
        }

        return detokenize(hypothesis)
    }

    /**
     * Converts token IDs to a string using [vocabulary].
     * Handles SentencePiece word-boundary markers (U+2581 `▁`).
     *
     * Filtered entries:
     *  - [blankId] - the blank/CTC token; should never reach here but guard anyway
     *  - Purely numeric strings - NeMo SentencePiece exports label unused/special token
     *    slots with their index (e.g. "8192", "1076", "▁7877"). Both bare and
     *    `▁`-prefixed forms are filtered so that word-boundary variants such as
     *    "▁8192" are caught before the space-insertion logic runs.
     */
    /**
     * Converts token IDs to a string using [vocabulary].
     * Handles SentencePiece word-boundary markers (U+2581 `▁`).
     *
     * NeMo SentencePiece exports fill unused vocabulary slots with their own index as
     * the token string (e.g. `"7883"`, `"▁1980ess"`, `"▁7880over"`). We handle these
     * by stripping any leading digit run from the bare token and using only the
     * alphabetic/punctuation suffix:
     *   - `"7865"`    → effective = ""     → skip entirely
     *   - `"▁3402a"`  → effective = "a"    → emit with word-boundary space
     *   - `"▁7880over"` → effective = "over" → emit with word-boundary space
     *   - `"1980ess"` → effective = "ess"  → append directly (no space; continues prev word)
     *
     * A post-processing pass collapses any double-spaces that arise from removed
     * digit-only tokens and moves spaces that ended up before punctuation.
     */
    private fun detokenize(tokenIds: List<Int>): String {
        if (tokenIds.isEmpty()) return ""
        val raw = buildString {
            for (id in tokenIds) {
                if (id < 0 || id >= vocabulary.size || id == blankId) continue
                val token = vocabulary[id]
                // Remove the SentencePiece word-boundary marker before processing.
                val bare = token.removePrefix("▁")
                // Strip any leading digit run - NeMo fills unused slots with their index
                // (e.g. "▁1980ess" → bare = "1980ess" → effective = "ess").
                // If nothing meaningful remains after stripping, skip the token entirely.
                val effective = bare.dropWhile { it.isDigit() }
                if (effective.isBlank()) continue
                when {
                    token.startsWith("▁") -> {
                        if (isNotEmpty()) append(' ')
                        append(effective)
                    }

                    else -> append(effective)
                }
            }
        }
        // Post-process: remove spaces that ended up before punctuation (artifact of
        // digit-only tokens being dropped mid-sequence) and collapse any double-spaces.
        return raw
            .replace(Regex(" ([.,!?;:])"), "$1")
            .replace(Regex(" {2,}"), " ")
            .trim()
    }

    /**
     * Copies [source]'s float data into a new [OnnxTensor] so that the parent
     * `OrtSession.Result` can be safely closed before the tensor is consumed.
     */
    private fun cloneTensor(env: OrtEnvironment, source: OnnxTensor): OnnxTensor {
        val shape = source.info.shape
        val data = FloatArray(source.floatBuffer.remaining())
        source.floatBuffer.rewind()
        source.floatBuffer.get(data)
        return OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape)
    }

    /** Prints all input/output tensor names and shapes for a loaded session. */
    private fun logSession(label: String, session: OrtSession) {
        Log.d(TAG, "=== $label inputs ===")
        session.inputNames.forEach { n -> Log.d(TAG, "  [$n] ${session.inputInfo[n]}") }
        Log.d(TAG, "=== $label outputs ===")
        session.outputNames.forEach { n -> Log.d(TAG, "  [$n] ${session.outputInfo[n]}") }
    }

    /**
     * Scans [vocabulary] for a known blank-token label and returns its index.
     * NeMo TDT ONNX exports use `<blk>` by convention; guarding a few variants
     * makes this robust across different export tools.
     * Returns `null` if no recognisable blank entry is found.
     */
    private fun scanVocabForBlankId(): Int? {
        val blankLabels = setOf("<blk>", "<blank>", "[blank]", "<eps>")
        vocabulary.forEachIndexed { idx, token ->
            if (token in blankLabels) {
                Log.d(TAG, "Detected blank token '$token' at index $idx from vocabulary scan")
                return idx
            }
        }
        return null
    }

    /**
     * Attempts to read `blank_id` from `config.json`.
     * Checks (in order): top-level, `model_defaults`, `decoder`, `tokenizer` sections.
     * Returns `null` if the file is absent or no key is found - caller falls back to vocabulary.size-1.
     */
    private fun parseBlankId(configFile: File): Int? {
        if (!configFile.exists()) return null
        return runCatching {
            val json = JSONObject(configFile.readText())
            // Top-level key (common in ONNX-converted NeMo exports)
            if (json.has("blank_id")) return@runCatching json.getInt("blank_id")
            // NeMo model_defaults section
            json.optJSONObject("model_defaults")?.let {
                if (it.has("blank_id")) return@runCatching it.getInt("blank_id")
            }
            // NeMo decoder section
            json.optJSONObject("decoder")?.let {
                if (it.has("blank_id")) return@runCatching it.getInt("blank_id")
            }
            // NeMo tokenizer section
            json.optJSONObject("tokenizer")?.let {
                if (it.has("blank_id")) return@runCatching it.getInt("blank_id")
            }
            null
        }.getOrNull()
    }
}

