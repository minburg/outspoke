package dev.brgr.outspoke.inference

import ai.onnxruntime.*
import android.util.Log
import dev.brgr.outspoke.audio.AudioChunk
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.*

private const val TAG = "WhisperEngine"

private const val SAMPLE_RATE = 16_000
private const val N_FFT = 400        // 25 ms window @ 16 kHz
private const val HOP_LENGTH = 160        // 10 ms hop → 100 frames / sec
private const val N_MELS = 128        // Whisper large-v3/turbo uses 128 mel bins (v1/v2/small/medium use 80)
private const val MAX_SAMPLES = 480_000    // 30 s @ 16 kHz
private const val TARGET_FRAMES = 3_000      // 30 s × 100 fps

private const val MAX_NEW_TOKENS = 448

/**
 * [SpeechEngine] implementation targeting the `onnx-community/whisper-large-v3-turbo`
 * INT8 ONNX export, but compatible with any HuggingFace optimum Whisper export that
 * follows the standard merged-decoder format.
 *
 * Expected files in `modelDir`:
 *  - `encoder_model_int8.onnx`          - plain transformer encoder, no KV cache (~615 MB)
 *  - `decoder_model_merged_int8.onnx`   - merged decoder with `use_cache_branch` control (~420 MB)
 *  - `tokenizer.json`                   - HuggingFace tokenizer vocabulary
 *
 * Inference pipeline:
 *  1. Normalise 16-bit PCM to float32 [-1, 1]
 *  2. Compute Whisper log-mel spectrogram → [1, 128, 3 000]
 *  3. Encoder: mel → hidden states [1, 1 500, D]  (D = 1 280 for large models)
 *  4. Decoder phase 1 (use_cache_branch=0): feed SOT prefix, yield first token
 *  5. Decoder phase 2 (use_cache_branch=1): greedy autoregressive loop
 *  6. Detokenise BPE token IDs → plain text
 */
class WhisperEngine : SpeechEngine {

    private companion object {
        const val ENCODER_FILENAME = "encoder_model_int8.onnx"
        const val DECODER_FILENAME = "decoder_model_merged_int8.onnx"
        const val TOKENIZER_JSON = "tokenizer.json"

        // Encoder tensor names
        const val ENC_IN_FEATURES = "input_features"    // FLOAT [1, 128, 3000]
        const val ENC_OUT_HIDDEN = "last_hidden_state" // FLOAT [1, 1500, D]

        // Decoder tensor names
        const val DEC_IN_INPUT_IDS = "input_ids"
        const val DEC_IN_ENCODER_HIDDEN = "encoder_hidden_states"
        const val DEC_IN_USE_CACHE_BRANCH = "use_cache_branch"
        const val DEC_OUT_LOGITS = "logits"

        // Whisper special token IDs - identical across all model sizes (vocab size = 51 865)
        const val TOKEN_SOT = 50258  // <|startoftranscript|>
        const val TOKEN_EOT = 50256  // <|endoftext|>
        const val TOKEN_ENGLISH = 50259  // <|en|>
        const val TOKEN_TRANSCRIBE = 50359  // <|transcribe|>
        const val TOKEN_NO_TIMESTAMPS = 50363  // <|notimestamps|>
        // Any token ID >= TOKEN_EOT is a Whisper control / special token - kept for reference
        // but resolved dynamically via findTokenId() at load time.
    }

    private var env: OrtEnvironment? = null
    private var encoderSession: OrtSession? = null
    private var decoderSession: OrtSession? = null

    /** Serialises concurrent [transcribe] calls to avoid double-loading the decoder. */
    private val inferenceLock = ReentrantLock()

    /** Vocabulary indexed by token ID. */
    private var vocabulary: Array<String> = emptyArray()

    // Special token IDs resolved dynamically from the loaded vocabulary.
    // Hardcoded values are only fallbacks for models where the token string is absent.
    private var tokenSot = TOKEN_SOT
    private var tokenEnglish = TOKEN_ENGLISH
    private var tokenTranscribe = TOKEN_TRANSCRIBE
    private var tokenNoTimestamps = TOKEN_NO_TIMESTAMPS
    private var tokenEot = TOKEN_EOT

    /** Mel filterbank matrix [N_MELS × (N_FFT/2+1)], built once at load time. */
    private var melFilters: FloatArray = FloatArray(0)

    /**
     * BCP-47 language tag controlling decoder behaviour.
     *
     * `"auto"` → run [detectLanguage] on every inference call (unreliable for very short clips).
     * Any other value (e.g. `"en"`, `"de"`, `"es"`) → look up `<|tag|>` in the vocabulary and
     * use it as the fixed language token, skipping language detection entirely.
     *
     * Updated by [setLanguage]; thread-safe via [Volatile].
     */
    @Volatile
    private var languageTag: String = "auto"

    override fun setLanguage(tag: String) {
        languageTag = tag
        Log.d(TAG, "setLanguage: $tag")
    }

    /**
     * BCP-47 tags to restrict auto-detection to (e.g. `["en", "de", "es"]`).
     * Empty list = unconstrained - searches all ~100 language tokens, which is unreliable
     * for short clips because adjacent tokens like EN (50259) and ES (50262) can have nearly
     * identical logits.  Set via [setLanguageConstraints].
     */
    @Volatile
    private var constrainedTags: List<String> = emptyList()

    override fun setLanguageConstraints(tags: List<String>) {
        constrainedTags = tags
        Log.d(TAG, "setLanguageConstraints: $tags")
    }

    /** Hann window of length N_FFT, computed at construction time. */
    private val hannWindow: FloatArray = FloatArray(N_FFT) { i ->
        (0.5 * (1.0 - cos(2.0 * PI * i / N_FFT))).toFloat()
    }

    @Volatile
    override var isLoaded: Boolean = false
        private set

    override fun load(modelDir: File) {
        check(!isLoaded) { "Already loaded; call close() before reloading" }

        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(2)
            setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
        }

        env = OrtEnvironment.getEnvironment()
        val e = env!!

        val encoderFile = File(modelDir, ENCODER_FILENAME)
        val decoderFile = File(modelDir, DECODER_FILENAME)

        if (encoderFile.exists()) {
            encoderSession = e.createSession(encoderFile.absolutePath, opts)
            logSession("encoder_int8", encoderSession!!)
        } else {
            Log.w(TAG, "$ENCODER_FILENAME not found in ${modelDir.path}")
        }

        if (decoderFile.exists()) {
            decoderSession = e.createSession(decoderFile.absolutePath, opts)
            logSession("decoder_merged_int8", decoderSession!!)
        } else {
            Log.w(TAG, "$DECODER_FILENAME not found in ${modelDir.path}")
        }

        val tokenizerFile = File(modelDir, TOKENIZER_JSON)
        if (tokenizerFile.exists()) {
            vocabulary = loadVocabulary(tokenizerFile)
            Log.d(TAG, "Vocabulary loaded: ${vocabulary.size} tokens")

            // Resolve special token IDs from the actual vocabulary - they shift between
            // model sizes (large-v3 added more languages, pushing task tokens up by one).
            tokenSot = findTokenId("<|startoftranscript|>", TOKEN_SOT)
            tokenEnglish = findTokenId("<|en|>", TOKEN_ENGLISH)
            tokenTranscribe = findTokenId("<|transcribe|>", TOKEN_TRANSCRIBE)
            tokenNoTimestamps = findTokenId("<|notimestamps|>", TOKEN_NO_TIMESTAMPS)
            tokenEot = findTokenId("<|endoftext|>", TOKEN_EOT)
            Log.d(
                TAG, "Token IDs: SOT=$tokenSot EN=$tokenEnglish " +
                        "TRANSCRIBE=$tokenTranscribe NO_TS=$tokenNoTimestamps EOT=$tokenEot"
            )
        } else {
            Log.w(TAG, "$TOKENIZER_JSON not found - detokenisation will be unavailable")
        }

        // Pre-build the filterbank so transcribe() is allocation-light
        melFilters = buildMelFilterbank()

        opts.close()
        isLoaded = true
        Log.d(TAG, "WhisperEngine ready (modelDir=${modelDir.path})")
    }

    override fun transcribe(chunk: AudioChunk): TranscriptResult {
        if (!isLoaded) return TranscriptResult.Failure(IllegalStateException("Engine not loaded"))

        // Skip stale chunks when inference is already running
        if (!inferenceLock.tryLock()) {
            Log.d(TAG, "transcribe: inference already in progress - skipping chunk")
            return TranscriptResult.Partial("")
        }
        try {
            val enc = encoderSession ?: return TranscriptResult.Failure(
                IllegalStateException("Encoder session not available")
            )
            val dec = decoderSession ?: return TranscriptResult.Failure(
                IllegalStateException("Decoder session not available")
            )

            return try {
                val e = env!!

                // Whisper requires at least 2 s of audio for reliable output.
                // A 1-second clip produces hallucinated single-word results that immediately
                // hit EOT - return Partial so the caller doesn't commit garbage as Final.
                if (chunk.samples.size < SAMPLE_RATE * 2) {
                    Log.d(TAG, "transcribe: clip too short (${chunk.samples.size} samples) - returning Partial")
                    return TranscriptResult.Partial("")
                }

                // 1. Normalise 16-bit PCM to float32 in [-1, 1]
                val samples = normalisePcm(chunk.samples)

                // 2. Whisper log-mel spectrogram: [N_MELS, TARGET_FRAMES]
                val melFeatures = computeLogMel(samples)

                // 3. Encoder: mel → hidden states [1, 1500, D]
                val encoderHidden = encodeAudio(e, enc, melFeatures)

                // 4. Autoregressive decoder
                val tokenIds = greedyDecode(e, dec, encoderHidden, chunk.samples.size)
                encoderHidden.close()

                // 5. Detokenise
                val text = detokenize(tokenIds)
                // A single-token output that hits EOT immediately is almost always a
                // hallucination from insufficient audio - treat it as tentative.
                when {
                    text.isBlank() -> TranscriptResult.Partial("")
                    tokenIds.size <= 1 -> TranscriptResult.Partial(text)
                    else -> TranscriptResult.Final(text)
                }
            } catch (ex: Exception) {
                Log.e(TAG, "transcribe() failed", ex)
                TranscriptResult.Failure(ex)
            }
        } finally {
            inferenceLock.unlock()
        }
    }

    override fun close() {
        encoderSession?.close(); encoderSession = null
        decoderSession?.close(); decoderSession = null
        env?.close(); env = null
        isLoaded = false
        Log.d(TAG, "WhisperEngine closed")
    }

    /** Converts a 16-bit signed PCM [ShortArray] to float32 normalised to [-1.0, 1.0]. */
    private fun normalisePcm(pcm: ShortArray): FloatArray =
        FloatArray(pcm.size) { i -> pcm[i] / 32_768f }

    /**
     * Computes a Whisper-compatible log-mel spectrogram from float32 PCM samples.
     *
     * Returns a flat [FloatArray] of shape [N_MELS × TARGET_FRAMES] (row-major).
     * The caller wraps this with batch dim [1, N_MELS, TARGET_FRAMES] for the encoder.
     */
    private fun computeLogMel(samples: FloatArray): FloatArray {
        // Pad or trim to exactly MAX_SAMPLES
        val audio = FloatArray(MAX_SAMPLES)
        System.arraycopy(samples, 0, audio, 0, minOf(samples.size, MAX_SAMPLES))

        val fftSize = 512               // N_FFT=400 → next power of 2 is 512
        val freqBins = N_FFT / 2 + 1     // 201 one-sided DFT bins

        val mel = FloatArray(N_MELS * TARGET_FRAMES)
        val fftReal = DoubleArray(fftSize)
        val fftImag = DoubleArray(fftSize)

        for (t in 0 until TARGET_FRAMES) {
            val start = t * HOP_LENGTH

            // Fill real part with windowed samples; zero-pad the rest
            for (i in 0 until fftSize) {
                fftReal[i] = if (i < N_FFT) {
                    val s = start + i
                    (if (s < audio.size) audio[s] * hannWindow[i] else 0f).toDouble()
                } else 0.0
                fftImag[i] = 0.0
            }

            fftInPlace(fftReal, fftImag)

            // Project one-sided power spectrum onto mel filterbank rows
            for (m in 0 until N_MELS) {
                var energy = 0.0
                val rowOffset = m * freqBins
                for (k in 0 until freqBins) {
                    val power = fftReal[k] * fftReal[k] + fftImag[k] * fftImag[k]
                    energy += melFilters[rowOffset + k] * power
                }
                mel[m * TARGET_FRAMES + t] = ln(maxOf(energy, 1e-10)).toFloat()
            }
        }

        // Whisper-style normalisation: clamp to (max − 8), then shift/scale to ~[-1, 1]
        var maxVal = mel[0]
        for (v in mel) if (v > maxVal) maxVal = v
        val floorVal = maxVal - 8f
        for (i in mel.indices) mel[i] = (maxOf(mel[i], floorVal) + 4f) / 4f

        return mel
    }

    /**
     * Builds the mel filterbank matrix of shape [N_MELS × (N_FFT/2+1)], row-major.
     *
     * Uses Slaney-style HTK mel scale (identical to librosa / Whisper):
     *   Hz → mel : 2595 · log₁₀(1 + f/700)
     *   mel → Hz : 700 · (10^(m/2595) − 1)
     */
    private fun buildMelFilterbank(): FloatArray {
        val freqBins = N_FFT / 2 + 1
        val fMin = 0.0
        val fMax = SAMPLE_RATE / 2.0  // 8 000 Hz

        fun hzToMel(hz: Double): Double = 2595.0 * log10(1.0 + hz / 700.0)
        fun melToHz(m: Double): Double = 700.0 * (10.0.pow(m / 2595.0) - 1.0)

        val melMin = hzToMel(fMin)
        val melMax = hzToMel(fMax)
        val melStep = (melMax - melMin) / (N_MELS + 1)

        val centre = DoubleArray(N_MELS + 2) { i -> melToHz(melMin + i * melStep) }
        val fftFreqs = DoubleArray(freqBins) { k -> k.toDouble() * SAMPLE_RATE / N_FFT }

        val filters = FloatArray(N_MELS * freqBins)
        for (m in 0 until N_MELS) {
            val lo = centre[m]
            val mid = centre[m + 1]
            val hi = centre[m + 2]
            for (k in 0 until freqBins) {
                val f = fftFreqs[k]
                val w = when {
                    f !in lo..hi -> 0.0
                    f <= mid -> (f - lo) / (mid - lo)
                    else -> (hi - f) / (hi - mid)
                }
                filters[m * freqBins + k] = w.toFloat()
            }
        }
        return filters
    }

    /**
     * In-place Cooley-Tukey radix-2 DIT FFT.
     * [re] and [im] must have equal length that is a power of 2.
     */
    private fun fftInPlace(re: DoubleArray, im: DoubleArray) {
        val n = re.size

        // Bit-reversal permutation
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit; bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                var tmp = re[i]; re[i] = re[j]; re[j] = tmp
                tmp = im[i]; im[i] = im[j]; im[j] = tmp
            }
        }

        // Butterfly stages
        var len = 2
        while (len <= n) {
            val halfLen = len / 2
            val angleStep = -PI / halfLen
            for (i in 0 until n step len) {
                for (k in 0 until halfLen) {
                    val angle = angleStep * k
                    val wr = cos(angle)
                    val wi = sin(angle)
                    val ur = re[i + k]
                    val ui = im[i + k]
                    val vr = wr * re[i + k + halfLen] - wi * im[i + k + halfLen]
                    val vi = wr * im[i + k + halfLen] + wi * re[i + k + halfLen]
                    re[i + k] = ur + vr; im[i + k] = ui + vi
                    re[i + k + halfLen] = ur - vr; im[i + k + halfLen] = ui - vi
                }
            }
            len = len shl 1
        }
    }

    /**
     * Wraps [melFeatures] (shape [N_MELS, TARGET_FRAMES]) into an ONNX tensor of
     * shape [1, N_MELS, TARGET_FRAMES], runs the encoder session once, and returns
     * a cloned copy of `last_hidden_state` [1, 1500, D].
     *
     * Whisper's encoder is a plain transformer - no KV cache, no past inputs.
     */
    private fun encodeAudio(
        env: OrtEnvironment,
        session: OrtSession,
        melFeatures: FloatArray,
    ): OnnxTensor {
        val input = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(melFeatures),
            longArrayOf(1L, N_MELS.toLong(), TARGET_FRAMES.toLong()),
        )
        return session.run(mapOf(ENC_IN_FEATURES to input)).use { result ->
            input.close()
            val hidden = result.get(ENC_OUT_HIDDEN).orElse(null) as? OnnxTensor
                ?: throw RuntimeException(
                    "Encoder output '$ENC_OUT_HIDDEN' not found. Available: ${session.outputNames}"
                )
            cloneTensor(env, hidden)
        }
    }

    /**
     * Runs a single decoder forward pass with `[SOT]` and empty KV cache, then
     * argmaxes over the language token range to detect the spoken language.
     *
     * Language tokens occupy the contiguous range `[tokenEnglish, tokenTranscribe - 2]`
     * (50259–50357 for large-v3 / large-v3-turbo; 99 languages total).
     *
     * @param candidateTokenIds When non-empty, the argmax is restricted to only these token
     *                          IDs.  Passing a small set (e.g. EN + DE + ES) makes detection
     *                          far more reliable than searching across all ~100 languages,
     *                          because adjacent tokens like ES (50262) can no longer steal the
     *                          win from EN (50259) on a tiny logit margin.
     *                          Pass an empty array to search the full language range (unreliable
     *                          on short clips).
     * @return The detected language token ID (e.g. 50259 for English, 50261 for German).
     */
    private fun detectLanguage(
        env: OrtEnvironment,
        session: OrtSession,
        encHiddenShape: LongArray,
        encHiddenData: FloatArray,
        candidateTokenIds: IntArray = intArrayOf(),
    ): Int {
        val inputs = mutableMapOf<String, OnnxTensor>()
        try {
            inputs[DEC_IN_INPUT_IDS] = OnnxTensor.createTensor(
                env,
                LongBuffer.wrap(longArrayOf(tokenSot.toLong())),
                longArrayOf(1L, 1L),
            )
            inputs[DEC_IN_ENCODER_HIDDEN] = OnnxTensor.createTensor(
                env,
                FloatBuffer.wrap(encHiddenData),
                encHiddenShape,
            )
            inputs[DEC_IN_USE_CACHE_BRANCH] = createBoolTensor(env, false)
            addZeroKvInputs(env, session, session.inputNames.toSet(), inputs)

            val result = session.run(inputs)
            try {
                val logitsTensor = result.get(DEC_OUT_LOGITS)
                    .orElseThrow { RuntimeException("No logits during language detection") }
                        as OnnxTensor
                val vocabSize = logitsTensor.info.shape[2].toInt()
                val logits = FloatArray(vocabSize)
                logitsTensor.floatBuffer.rewind()
                logitsTensor.floatBuffer.get(logits)

                // Build the search set: explicit candidates or the full language token range.
                val candidates: IntArray = if (candidateTokenIds.isNotEmpty()) {
                    candidateTokenIds.filter { it in 0 until vocabSize }.toIntArray()
                } else {
                    val first = tokenEnglish
                    val last = minOf(tokenTranscribe - 2, vocabSize - 1)
                    if (last >= first) IntArray(last - first + 1) { first + it } else intArrayOf()
                }

                if (candidates.isEmpty()) {
                    Log.w(TAG, "detectLanguage: no valid candidates - falling back to English")
                    return tokenEnglish
                }

                var bestLang = candidates[0]
                var bestLogit = logits[bestLang]
                for (id in candidates.drop(1)) {
                    val v = logits[id]
                    if (v > bestLogit) {
                        bestLogit = v; bestLang = id
                    }
                }

                // Log the confidence gap (top minus second-best).
                // A gap < 1.0 signals an uncertain detection; > 3.0 is confident.
                val secondBest = candidates.filter { it != bestLang }
                    .maxOfOrNull { logits[it] } ?: Float.NEGATIVE_INFINITY
                val gap = bestLogit - secondBest
                Log.d(
                    TAG, "detectLanguage: token=$bestLang " +
                            "(${vocabulary.getOrNull(bestLang)}) gap=${"%.2f".format(gap)}"
                )
                return bestLang
            } finally {
                result.close()
            }
        } finally {
            inputs.values.forEach { it.close() }
        }
    }

    /**
     * Runs the `decoder_model_merged` autoregressively using its two-branch design:
     *
     * **Language detection** (pre-pass):
     * Feeds `[SOT]` with an empty KV cache and argmaxes over the language token range
     * (50259–50357) to auto-detect the spoken language.  The KV cache from this pass
     * is discarded immediately - it is only ~1 token, so the cost is negligible compared
     * to the encoder.
     *
     * **Phase 1** (`use_cache_branch = 0`):
     * Feeds the 4-token Whisper prefix `[SOT, LANG, TRANSCRIBE, NO_TIMESTAMPS]`.
     * Returns `logits [1, 4, vocab]` - the last position yields the first real token.
     * Both decoder and encoder cross-attention KV caches are populated.
     *
     * **Phase 2** (`use_cache_branch = 1`):
     * One token per step.  Decoder KV advances every step; encoder cross-attention KV
     * is present in every result output (passed through unchanged) and reused as-is.
     *
     * KV-cache ownership: tensors live inside an open [OrtSession.Result] in ORT
     * native memory.  The previous result is closed only after the next step has
     * produced a new result, keeping large KV buffers off the Android Java heap.
     */
    private fun greedyDecode(
        env: OrtEnvironment,
        session: OrtSession,
        encoderHidden: OnnxTensor,
        sampleCount: Int,
    ): List<Int> {
        val outputNames = session.outputNames.toList()

        // Copy encoder hidden state once - reused as input every decoder step
        val encHiddenShape = encoderHidden.info.shape
        val encHiddenData = FloatArray(encoderHidden.floatBuffer.remaining())
        encoderHidden.floatBuffer.rewind()
        encoderHidden.floatBuffer.get(encHiddenData)

        // Resolve the language token.
        //
        // Forced tag (e.g. "en", "de"): vocabulary lookup, no decoder pre-pass needed.
        //
        // "auto" with constraints (the normal case when the LanguageSelector is visible):
        //   - Resolve each constrained tag to a token ID once.
        //   - If the clip is < 2 s there is not enough phoneme signal for reliable detection;
        //     fall back to the first constrained option to avoid picking an adjacent token
        //     (e.g. ES=50262 beats EN=50259 by 0.1 logit on a short clip → hallucination).
        //   - Otherwise run detectLanguage() restricted to those candidate token IDs.
        //
        // "auto" unconstrained: full ~100-language range - still supported but not recommended.
        val detectedLang = if (languageTag != "auto") {
            findTokenId("<|$languageTag|>", tokenEnglish).also {
                Log.d(TAG, "greedyDecode: forced language=$languageTag → token=$it")
            }
        } else {
            val candidates = if (constrainedTags.isNotEmpty()) {
                constrainedTags.mapNotNull { tag ->
                    val id = findTokenId("<|$tag|>", -1)
                    if (id < 0) {
                        Log.w(TAG, "greedyDecode: unknown constraint '$tag'"); null
                    } else id
                }.toIntArray()
            } else {
                intArrayOf()  // empty = full language range inside detectLanguage()
            }

            val minSamplesForDetect = SAMPLE_RATE * 2  // 2 s of speech minimum
            if (sampleCount < minSamplesForDetect && candidates.isNotEmpty()) {
                Log.d(
                    TAG, "greedyDecode: clip too short ($sampleCount samples) - " +
                            "skipping detection, using ${constrainedTags.first()}"
                )
                candidates[0]
            } else {
                detectLanguage(env, session, encHiddenShape, encHiddenData, candidates)
            }
        }

        // Build the SOT prefix from dynamically resolved token IDs
        val prefixIds = longArrayOf(
            tokenSot.toLong(), detectedLang.toLong(),
            tokenTranscribe.toLong(), tokenNoTimestamps.toLong(),
        )
        val prefixLen = prefixIds.size

        val hypothesis = mutableListOf<Int>()
        // KV cache: input name (past_key_values.*) → tensor owned by prevResult
        val kvCache = mutableMapOf<String, OnnxTensor>()
        var prevResult: OrtSession.Result? = null

        try {
            // -- Phase 1: seed call - use_cache_branch=0 --
            val firstInputs = mutableMapOf<String, OnnxTensor>()
            try {
                firstInputs[DEC_IN_INPUT_IDS] = OnnxTensor.createTensor(
                    env,
                    LongBuffer.wrap(prefixIds),
                    longArrayOf(1L, prefixLen.toLong()),
                )
                firstInputs[DEC_IN_ENCODER_HIDDEN] = OnnxTensor.createTensor(
                    env,
                    FloatBuffer.wrap(encHiddenData),
                    encHiddenShape,
                )
                // use_cache_branch is tensor(bool) in this export
                firstInputs[DEC_IN_USE_CACHE_BRANCH] = createBoolTensor(env, false)
                // Empty KV cache: past_seq=0 for every rank-4 KV slot
                addZeroKvInputs(env, session, session.inputNames.toSet(), firstInputs)

                Log.d(TAG, "greedyDecode: Phase 1 (use_cache_branch=0, prefixLen=$prefixLen)")
                prevResult = session.run(firstInputs)

                // logits [1, prefixLen, vocab] - take last position
                val logitsTensor = prevResult.get(DEC_OUT_LOGITS)
                    .orElseThrow { RuntimeException("Decoder output '$DEC_OUT_LOGITS' not found") }
                        as OnnxTensor
                val vocabSize = logitsTensor.info.shape[2].toInt()
                val allLogits = FloatArray(prefixLen * vocabSize)
                logitsTensor.floatBuffer.rewind()
                logitsTensor.floatBuffer.get(allLogits)

                val lastOffset = (prefixLen - 1) * vocabSize
                var firstToken = 0
                var maxLogit = allLogits[lastOffset]
                for (i in 1 until vocabSize) {
                    val v = allLogits[lastOffset + i]
                    if (v > maxLogit) {
                        maxLogit = v; firstToken = i
                    }
                }
                Log.d(
                    TAG, "greedyDecode Phase 1: firstToken=$firstToken " +
                            "(${vocabulary.getOrNull(firstToken)})"
                )

                // Collect all present.* → past_key_values.*
                collectKvTensors(prevResult, outputNames, kvCache)

                if (firstToken >= tokenEot) {
                    Log.d(TAG, "greedyDecode: first generated token is special ($firstToken) - empty result")
                    return emptyList()
                }
                hypothesis.add(firstToken)

                // -- Phase 2: autoregressive loop - use_cache_branch=1 --
                var currentToken = firstToken
                for (step in 0 until MAX_NEW_TOKENS - 1) {
                    // Guard against the ONNX KV-cache reshape crash that occurs when the
                    // accumulated sequence length exceeds the model's internal tiling boundary
                    // (~448 tokens). Stop well below that limit.
                    if (hypothesis.size >= 400) {
                        Log.w(TAG, "greedyDecode: hit token safety cap - stopping")
                        break
                    }

                    val genInputs = mutableMapOf<String, OnnxTensor>()
                    try {
                        genInputs[DEC_IN_INPUT_IDS] = OnnxTensor.createTensor(
                            env,
                            LongBuffer.wrap(longArrayOf(currentToken.toLong())),
                            longArrayOf(1L, 1L),
                        )
                        genInputs[DEC_IN_ENCODER_HIDDEN] = OnnxTensor.createTensor(
                            env,
                            FloatBuffer.wrap(encHiddenData),
                            encHiddenShape,
                        )
                        genInputs[DEC_IN_USE_CACHE_BRANCH] = createBoolTensor(env, true)
                        // KV-cache tensors are owned by prevResult; do NOT close them here
                        for ((name, tensor) in kvCache) genInputs[name] = tensor

                        val currentResult = session.run(genInputs)

                        // Safe to close the previous result now - its KV tensors were consumed
                        prevResult?.close()
                        prevResult = currentResult
                        kvCache.clear()
                        collectKvTensors(currentResult, outputNames, kvCache)

                        // logits [1, 1, vocab] - position 0
                        val stepLogits = currentResult.get(DEC_OUT_LOGITS)
                            .orElseThrow { RuntimeException("No '$DEC_OUT_LOGITS' at step $step") }
                                as OnnxTensor
                        val sv = stepLogits.info.shape[2].toInt()
                        val logits = FloatArray(sv)
                        stepLogits.floatBuffer.rewind()
                        stepLogits.floatBuffer.get(logits)

                        val generated = logits.indices.maxByOrNull { logits[it] } ?: TOKEN_EOT
                        Log.d(
                            TAG, "greedyDecode step=$step input=$currentToken " +
                                    "generated=$generated (${vocabulary.getOrNull(generated)})"
                        )

                        // Stop on EOT or any other Whisper control token
                        if (generated >= tokenEot) {
                            Log.d(TAG, "greedyDecode: stop token $generated at step $step")
                            break
                        }

                        hypothesis.add(generated)
                        // Abort immediately if a bigram/trigram cycle has locked in - this
                        // prevents multi-second CPU burns and the downstream ORT reshape crash.
                        if (hasRepetitionLoop(hypothesis)) {
                            Log.w(TAG, "greedyDecode: repetition loop detected at step $step - aborting")
                            return emptyList()
                        }
                        currentToken = generated

                    } finally {
                        // Close only tensors we created; KV tensors are owned by prevResult
                        genInputs.forEach { (name, tensor) ->
                            if (!name.startsWith("past_key_values")) tensor.close()
                        }
                    }
                }
            } finally {
                firstInputs.values.forEach { it.close() }
            }
        } finally {
            prevResult?.close()
        }

        Log.d(TAG, "greedyDecode: generated ${hypothesis.size} tokens")
        return hypothesis
    }

    /**
     * Collects `present.*` output tensors from [result] into [dest], renaming each to
     * its corresponding `past_key_values.*` input name.
     *
     * Covers both decoder self-attention (`present.*.decoder.*`) and encoder
     * cross-attention (`present.*.encoder.*`) KV slots.
     * Tensors are **not copied** - they remain owned by [result].
     */
    private fun collectKvTensors(
        result: OrtSession.Result,
        outputNames: List<String>,
        dest: MutableMap<String, OnnxTensor>,
    ) {
        for (outName in outputNames) {
            if (!outName.startsWith("present")) continue
            val tensor = result.get(outName).orElse(null) as? OnnxTensor ?: continue
            dest[outName.replaceFirst("present", "past_key_values")] = tensor
        }
    }

    /**
     * Adds zero-initialised `past_key_values.*` input tensors for every KV slot
     * declared by [session].  Rank-4 tensors receive `past_seq_len = 0` at dim[2]
     * to signal "no prior context"; all other dynamic dims default to 1.
     */
    private fun addZeroKvInputs(
        env: OrtEnvironment,
        session: OrtSession,
        inputNames: Set<String>,
        inputs: MutableMap<String, OnnxTensor>,
    ) {
        for (name in inputNames) {
            if (!name.startsWith("past_key_values")) continue
            val info = session.inputInfo[name]?.info as? TensorInfo ?: continue
            val rank = info.shape.size
            val shape = LongArray(rank) { i ->
                val d = info.shape[i]
                when {
                    d >= 0L -> d    // static dim - keep as-is
                    rank == 4 && i == 2 -> 0L   // past_sequence_length → 0 (empty cache)
                    else -> 1L   // batch / other dynamic dim → 1
                }
            }
            val size = shape.fold(1L) { acc, d -> acc * d }.toInt()
            inputs[name] = OnnxTensor.createTensor(env, FloatBuffer.wrap(FloatArray(size)), shape)
        }
    }

    /**
     * Loads the BPE vocabulary from a HuggingFace `tokenizer.json` file.
     *
     * Merges two sources so that Whisper control tokens (`<|...|>`) are always present:
     *  1. `model.vocab` - BPE piece string → token ID map
     *  2. `added_tokens` - overlaid on top (adds / overrides special token entries)
     *
     * Returns an array indexed by token ID.
     */
    private fun loadVocabulary(file: File): Array<String> {
        val json = JSONObject(file.readText())
        val entries = mutableMapOf<Int, String>()

        val vocab = json.optJSONObject("model")?.optJSONObject("vocab")
        vocab?.keys()?.forEach { token -> entries[vocab.getInt(token)] = token }

        val addedTokens = json.optJSONArray("added_tokens")
        if (addedTokens != null) {
            for (i in 0 until addedTokens.length()) {
                val obj = addedTokens.getJSONObject(i)
                entries[obj.getInt("id")] = obj.getString("content")
            }
        }

        if (entries.isEmpty()) {
            Log.w(TAG, "Could not parse vocabulary from $TOKENIZER_JSON")
            return emptyArray()
        }

        val maxId = entries.keys.maxOrNull() ?: 0
        val arr = Array(maxId + 1) { i -> entries[i] ?: "" }
        Log.d(TAG, "loadVocabulary: ${arr.size} tokens (model.vocab + added_tokens merged)")
        return arr
    }

    /**
     * Converts a list of BPE token IDs to a plain-text string.
     *
     * - `Ġ` (U+0120) → space  (byte-level GPT-2 / Whisper BPE word-start marker)
     * - `Ċ` (U+010A) → newline
     * - `▁` (U+2581) → space  (SentencePiece boundary marker, present in some exports)
     * - Whisper control tokens `<|...|>` are skipped
     */
    private fun detokenize(tokenIds: List<Int>): String {
        if (tokenIds.isEmpty()) return ""
        val sb = StringBuilder()
        for (id in tokenIds) {
            val piece = vocabulary.getOrNull(id)?.takeIf { it.isNotEmpty() } ?: continue
            // Skip Whisper control tokens: <|startoftranscript|>, <|en|>, <pad>, etc.
            if (piece.startsWith("<") && piece.endsWith(">")) continue
            sb.append(
                piece
                    .replace('\u0120', ' ')   // Ġ → space
                    .replace('\u010A', '\n')  // Ċ → newline
                    .replace('▁', ' ')        // SentencePiece word boundary
            )
        }
        return sb.toString().trim()
    }

    /**
     * Returns true when the tail of [tokens] shows a repeating bigram cycle.
     *
     * A sliding window of [windowSize] tokens is inspected.  If the last 4 tokens
     * form a 2-token pattern (A B A B …) that has repeated at least [maxRepeats] times
     * consecutively, the decoder has fallen into an infinite loop and should be stopped.
     *
     * This is a well-known Whisper greedy-decoder failure mode on ambiguous / silent audio.
     */
    private fun hasRepetitionLoop(
        tokens: List<Int>,
        windowSize: Int = 30,
        maxRepeats: Int = 5,
    ): Boolean {
        if (tokens.size < windowSize) return false
        val window = tokens.takeLast(windowSize)
        if (window.size < 4) return false

        val a = window[window.size - 1]
        val b = window[window.size - 2]
        val c = window[window.size - 3]
        val d = window[window.size - 4]

        // Detected a 2-token cycle candidate (a == c, b == d → pattern: …b a b a)
        if (a == c && b == d) {
            var repeats = 0
            var i = window.size - 1
            while (i >= 1) {
                if (window[i] == a && window[i - 1] == b) {
                    repeats++
                    i -= 2
                } else {
                    break
                }
            }
            if (repeats >= maxRepeats) return true
        }
        return false
    }

    /**
     * Creates a 1-element `tensor(bool)`.
     *
     * ORT exposes a public `createTensor(env, ByteBuffer, long[], OnnxJavaType)` overload.
     * Passing `OnnxJavaType.BOOL` with a single-byte ByteBuffer (0 = false, 1 = true)
     * produces the correct `tensor(bool)` type without reflection.
     */
    private fun createBoolTensor(env: OrtEnvironment, value: Boolean): OnnxTensor {
        val buf = ByteBuffer.wrap(byteArrayOf(if (value) 1 else 0))
        return OnnxTensor.createTensor(env, buf, longArrayOf(1L), OnnxJavaType.BOOL)
    }

    /**
     * Deep-copies [source]'s float data into a new [OnnxTensor] with the same shape,
     * allowing the parent [OrtSession.Result] to be closed before the data is read.
     */
    private fun cloneTensor(env: OrtEnvironment, source: OnnxTensor): OnnxTensor {
        val shape = source.info.shape
        val data = FloatArray(source.floatBuffer.remaining())
        source.floatBuffer.rewind()
        source.floatBuffer.get(data)
        return OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape)
    }

    /** Logs all input and output tensor names / shapes for a loaded session. */
    private fun logSession(label: String, session: OrtSession) {
        Log.d(TAG, "=== $label inputs ===")
        session.inputNames.forEach { n -> Log.d(TAG, "  [$n] ${session.inputInfo[n]}") }
        Log.d(TAG, "=== $label outputs ===")
        session.outputNames.forEach { n -> Log.d(TAG, "  [$n] ${session.outputInfo[n]}") }
    }

    /**
     * Finds the token ID for [token] by scanning the loaded vocabulary.
     * Returns [default] with a warning log when the token is not present.
     */
    private fun findTokenId(token: String, default: Int): Int {
        val id = vocabulary.indexOfFirst { it == token }
        return if (id >= 0) {
            Log.d(TAG, "findTokenId: '$token' → $id")
            id
        } else {
            Log.w(TAG, "findTokenId: '$token' not found - using default $default")
            default
        }
    }
}
