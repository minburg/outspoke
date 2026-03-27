package dev.brgr.outspoke.inference

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.util.Log
import dev.brgr.outspoke.audio.AudioChunk
import org.json.JSONObject
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.*

private const val TAG = "VoxtralEngine"

private const val SAMPLE_RATE   = 16_000
private const val N_FFT         = 400        // 25 ms analysis window @ 16 kHz
private const val HOP_LENGTH    = 160        // 10 ms hop → 100 frames / sec
private const val N_MELS        = 128        // mel frequency bins
private const val MAX_SAMPLES   = 480_000    // hard cap: 30 s @ 16 kHz
private const val TARGET_FRAMES = 3_000      // 30 s × 100 fps

private const val MAX_NEW_TOKENS = 448

/**
 * [SpeechEngine] implementation for the Voxtral Mini 4B Realtime ONNX model from
 * https://huggingface.co/onnx-community/Voxtral-Mini-4B-Realtime-2602-ONNX.
 *
 * Full inference pipeline:
 *  1. Normalise PCM samples to [-1, 1]
 *  2. Compute Whisper-compatible log-mel spectrogram  →  [1, 128, 3 000]
 *  3. encoder_model.onnx                              →  encoder hidden states [1, T_audio, D]
 *  4. Autoregressive Mistral decoder (decoder_model_merged.onnx) with KV-cache carry-over
 *  5. Detokenise using the BPE vocabulary from tokenizer.json
 *
 * Tensor names are logged at load time via [logSession] - cross-check against logcat
 * if the ONNX export revision changes the names.
 *
 * NOTE: Voxtral is a ~4 GB model - it requires a high-end device with substantial RAM.
 */
class VoxtralEngine : SpeechEngine {

    private companion object {
        // Q4-quantised model files from
        // https://huggingface.co/onnx-community/Voxtral-Mini-4B-Realtime-2602-ONNX/tree/main/onnx
        // ORT loads the companion .onnx_data sidecar(s) automatically when they reside
        // in the same directory as the .onnx header file.
        const val ENCODER_FILENAME     = "audio_encoder_q4.onnx"          // 416 kB + 661 MB data
        const val EMBED_TOKENS_FILENAME = "embed_tokens_q4.onnx"           // 857 B  + 258 MB data
        const val DECODER_FILENAME     = "decoder_model_merged_q4.onnx"    // 290 kB + ~2.3 GB data
        const val TOKENIZER_JSON       = "tokenizer.json"

        // Audio encoder tensor names (Whisper-compatible export)
        const val ENC_IN_FEATURES   = "input_features"       // FLOAT [1, 128, 3000]
        const val ENC_OUT_HIDDEN    = "last_hidden_state"     // FLOAT [1, T_audio, D]

        // Embed-tokens tensor names
        const val EMBED_IN_INPUT_IDS  = "input_ids"           // INT64 [1, seq]
        const val EMBED_OUT_EMBEDS    = "inputs_embeds"        // FLOAT [1, seq, hidden]

        // Decoder tensor names - decoder-only Mistral, no cross-attention.
        // Audio features are prepended to inputs_embeds on the prefill step.
        const val DEC_IN_INPUTS_EMBEDS = "inputs_embeds"      // FLOAT [1, seq, hidden]
        const val DEC_OUT_LOGITS       = "logits"             // FLOAT [1, seq, vocab]

        // Voxtral / Mistral special token IDs (standard sentencepiece BOS/EOS)
        const val TOKEN_BOS = 1
        const val TOKEN_EOS = 2

        // Default fallback IDs for Mistral instruction-format tokens.
        // The runtime looks these up dynamically from the loaded vocabulary and falls
        // back to these constants only when the token string is absent from the vocab.
        const val TOKEN_INST_DEFAULT = 3   // [INST]
        const val TOKEN_IEND_DEFAULT = 4   // [/INST]

        // Voxtral Realtime streaming architecture token.
        // The model emits [STREAMING_PAD] (ID 32) between real speech tokens to signal
        // "no new transcription yet - waiting for the next audio chunk".  In offline
        // (batch) mode all audio is available upfront, so these pads should be treated
        // as transparent filler: filtered from the hypothesis and used to detect
        // end-of-speech when too many appear consecutively.
        const val TOKEN_STREAMING_PAD      = 32
        const val MAX_CONSECUTIVE_PADS     = 30   // break after this many consecutive pads
        const val REPETITION_WINDOW_TOKENS = 12   // hypothesis tokens tracked for loop detection
    }

    private var env: OrtEnvironment? = null
    private var encoderSession: OrtSession? = null
    private var embedSession: OrtSession? = null
    private var decoderSession: OrtSession? = null

    /**
     * Serialises concurrent [transcribe] calls so that two 4-GB decoder sessions
     * never run simultaneously (which would OOM the device and make both ~10× slower).
     */
    private val inferenceLock = ReentrantLock()


    /**
     * `true` when the decoder's input schema contains `inputs_embeds`, meaning tokens
     * must be pre-embedded via [embedSession] before being fed to the decoder.
     * Determined once at [load] time by inspecting the decoder's inputNames.
     */
    private var decoderUsesEmbeds: Boolean = false

    /** Vocabulary: index == token ID, value == piece string. */
    private var vocabulary: Array<String> = emptyArray()

    /** Pre-built mel filterbank matrix: [N_MELS × (N_FFT/2+1)], row-major. */
    private var melFilters: FloatArray = FloatArray(0)

    /** Hann window of length N_FFT, computed once at init time. */
    private val hannWindow: FloatArray = FloatArray(N_FFT) { i ->
        (0.5 * (1.0 - cos(2.0 * PI * i / N_FFT))).toFloat()
    }

    @Volatile override var isLoaded: Boolean = false
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

        val encoderFile     = File(modelDir, ENCODER_FILENAME)
        val embedTokensFile = File(modelDir, EMBED_TOKENS_FILENAME)
        val decoderFile     = File(modelDir, DECODER_FILENAME)

        if (encoderFile.exists()) {
            encoderSession = e.createSession(encoderFile.absolutePath, opts)
            logSession("audio_encoder_q4", encoderSession!!)
        } else {
            Log.w(TAG, "$ENCODER_FILENAME not found in ${modelDir.path}")
        }

        if (embedTokensFile.exists()) {
            embedSession = e.createSession(embedTokensFile.absolutePath, opts)
            logSession("embed_tokens_q4", embedSession!!)
        } else {
            Log.w(TAG, "$EMBED_TOKENS_FILENAME not found - will try input_ids fallback on decoder")
        }

        if (decoderFile.exists()) {
            decoderSession = e.createSession(decoderFile.absolutePath, opts)
            logSession("decoder_model_merged_q4", decoderSession!!)
            // Detect at load time whether the decoder expects pre-embedded inputs
            decoderUsesEmbeds = DEC_IN_INPUTS_EMBEDS in decoderSession!!.inputNames
            Log.d(TAG, "Decoder decoderUsesEmbeds=$decoderUsesEmbeds")
        } else {
            Log.w(TAG, "$DECODER_FILENAME not found in ${modelDir.path}")
        }

        val tokenizerFile = File(modelDir, TOKENIZER_JSON)
        if (tokenizerFile.exists()) {
            vocabulary = loadVocabulary(tokenizerFile)
            Log.d(TAG, "Vocabulary loaded: ${vocabulary.size} tokens")
        } else {
            Log.w(TAG, "$TOKENIZER_JSON not found - detokenisation will be unavailable")
        }

        // Pre-build the mel filterbank once so transcribe() is allocation-light
        melFilters = buildMelFilterbank()

        opts.close()
        isLoaded = true
        Log.d(TAG, "VoxtralEngine ready (modelDir=${modelDir.path})")
    }

    override fun transcribe(chunk: AudioChunk): TranscriptResult {
        if (!isLoaded) return TranscriptResult.Failure(IllegalStateException("Engine not loaded"))

        // Skip stale chunks when inference is already running - avoids an unbounded queue
        // of old audio windows that would delay or permanently block the most-recent chunk.
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

                // 1. Normalise 16-bit PCM to float32 in [-1, 1]
                val samples = normalisePcm(chunk.samples)

                // 2. Whisper-compatible log-mel spectrogram: [N_MELS × TARGET_FRAMES]
                val melFeatures = computeLogMel(samples)

                // 3. Audio encoder: log-mel → audio embeddings [1, T_audio, D]
                val encoderHidden = encodeAudio(e, enc, melFeatures)

                // 4. Autoregressive Mistral decoder with instruction-format prefill
                val tokenIds = greedyDecode(e, dec, encoderHidden)
                encoderHidden.close()

                // 5. Detokenise BPE pieces → plain text
                val text = detokenize(tokenIds)
                if (text.isBlank()) TranscriptResult.Partial("") else TranscriptResult.Final(text)
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
        embedSession?.close();   embedSession   = null
        decoderSession?.close(); decoderSession = null
        env?.close();            env = null
        isLoaded = false
        Log.d(TAG, "VoxtralEngine closed")
    }

    /** Converts a 16-bit signed PCM [ShortArray] to float32 normalised to [-1.0, 1.0]. */
    private fun normalisePcm(pcm: ShortArray): FloatArray =
        FloatArray(pcm.size) { i -> pcm[i] / 32_768f }

    /**
     * Computes a Whisper-compatible log-mel spectrogram from float32 PCM samples.
     *
     * Returns a flat [FloatArray] representing the tensor of shape
     * [N_MELS, TARGET_FRAMES] in row-major order (mel × time).
     * The caller wraps this with shape [1, N_MELS, TARGET_FRAMES] for the encoder.
     *
     * Steps:
     *  1. Pad/trim audio to [MAX_SAMPLES] (30 s @ 16 kHz)
     *  2. Per-frame: apply Hann window, zero-pad to next power-of-2, FFT
     *  3. One-sided power spectrum dot-producted against the mel filterbank
     *  4. Natural-log compression and Whisper-style range normalisation
     */
    private fun computeLogMel(samples: FloatArray): FloatArray {
        // Pad or trim to exactly MAX_SAMPLES
        val audio = FloatArray(MAX_SAMPLES)
        System.arraycopy(samples, 0, audio, 0, minOf(samples.size, MAX_SAMPLES))

        val fftSize  = 512               // N_FFT=400 → next power of 2 is 512
        val freqBins = N_FFT / 2 + 1     // 201 one-sided DFT bins

        val mel     = FloatArray(N_MELS * TARGET_FRAMES)
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

            // In-place Cooley-Tukey radix-2 FFT
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

        // Whisper-style normalisation: clamp to (max − 8), then shift/scale
        var maxVal = mel[0]
        for (v in mel) if (v > maxVal) maxVal = v
        val floorVal = maxVal - 8f
        for (i in mel.indices) mel[i] = (maxOf(mel[i], floorVal) + 4f) / 4f

        return mel
    }

    /**
     * Builds the mel filterbank matrix of shape [N_MELS × (N_FFT/2+1)], row-major.
     *
     * Uses the Slaney-style HTK mel scale (identical to librosa / Whisper):
     *   Hz → mel : 2595 · log₁₀(1 + f/700)
     *   mel → Hz : 700 · (10^(m/2595) − 1)
     *
     * N_MELS + 2 evenly-spaced mel-scale centre frequencies are computed between
     * fMin and fMax; each filter is a triangular function of FFT frequency.
     */
    private fun buildMelFilterbank(): FloatArray {
        val freqBins = N_FFT / 2 + 1
        val fMin     = 0.0
        val fMax     = SAMPLE_RATE / 2.0   // 8 000 Hz

        fun hzToMel(hz: Double): Double = 2595.0 * log10(1.0 + hz / 700.0)
        fun melToHz(m: Double): Double   = 700.0 * (10.0.pow(m / 2595.0) - 1.0)

        val melMin   = hzToMel(fMin)
        val melMax   = hzToMel(fMax)
        val melStep  = (melMax - melMin) / (N_MELS + 1)

        // N_MELS + 2 equally-spaced points in mel domain → Hz
        val centre = DoubleArray(N_MELS + 2) { i -> melToHz(melMin + i * melStep) }
        // Frequency of each FFT bin
        val fftFreqs = DoubleArray(freqBins) { k -> k.toDouble() * SAMPLE_RATE / N_FFT }

        val filters = FloatArray(N_MELS * freqBins)
        for (m in 0 until N_MELS) {
            val lo  = centre[m]
            val mid = centre[m + 1]
            val hi  = centre[m + 2]
            for (k in 0 until freqBins) {
                val f = fftFreqs[k]
                val w = when {
                    f < lo  || f > hi -> 0.0
                    f <= mid          -> (f - lo)  / (mid - lo)
                    else              -> (hi - f)  / (hi - mid)
                }
                filters[m * freqBins + k] = w.toFloat()
            }
        }
        return filters
    }


    /**
     * In-place Cooley-Tukey radix-2 DIT FFT.
     * [re] and [im] must have the same length, which must be a power of 2.
     */
    private fun fftInPlace(re: DoubleArray, im: DoubleArray) {
        val n = re.size

        // Bit-reversal permutation
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                var tmp = re[i]; re[i] = re[j]; re[j] = tmp
                tmp = im[i]; im[i] = im[j]; im[j] = tmp
            }
        }

        // Butterfly stages
        var len = 2
        while (len <= n) {
            val halfLen   = len / 2
            val angleStep = -PI / halfLen   // = -2π / len
            for (i in 0 until n step len) {
                for (k in 0 until halfLen) {
                    val angle = angleStep * k
                    val wr = cos(angle); val wi = sin(angle)
                    val ur = re[i + k];  val ui = im[i + k]
                    val vr = wr * re[i + k + halfLen] - wi * im[i + k + halfLen]
                    val vi = wr * im[i + k + halfLen] + wi * re[i + k + halfLen]
                    re[i + k]           = ur + vr;  im[i + k]           = ui + vi
                    re[i + k + halfLen] = ur - vr;  im[i + k + halfLen] = ui - vi
                }
            }
            len = len shl 1
        }
    }

    /**
     * Wraps [melFeatures] (shape [N_MELS, TARGET_FRAMES]) into an ONNX tensor of
     * shape [1, N_MELS, TARGET_FRAMES], runs [session], and returns a cloned copy
     * of the encoder's `last_hidden_state` output [1, T_audio, D].
     *
     * The clone allows the [OrtSession.Result] to be closed immediately.
     *
     * Unlike a plain Whisper encoder, the Voxtral audio encoder is a full GQA-based
     * transformer and therefore requires [attention_mask], [position_ids], and
     * [past_key_values.*] in addition to [input_features].  All extra inputs are
     * discovered dynamically from [session.inputInfo] so the code is robust against
     * future export changes.
     */
    private fun encodeAudio(
        env: OrtEnvironment,
        session: OrtSession,
        melFeatures: FloatArray,
    ): OnnxTensor {
        val inputNames = session.inputNames.toSet()
        val inputs     = mutableMapOf<String, OnnxTensor>()

        try {
            // Primary: mel spectrogram [1, N_MELS, TARGET_FRAMES]
            inputs[ENC_IN_FEATURES] = OnnxTensor.createTensor(
                env,
                FloatBuffer.wrap(melFeatures),
                longArrayOf(1L, N_MELS.toLong(), TARGET_FRAMES.toLong()),
            )

            // The Voxtral audio encoder applies a stride-2 Conv1d front-end before the
            // transformer blocks, halving the time dimension: 3 000 → 1 500 frames.
            val encSeqLen = TARGET_FRAMES / 2L   // 1 500 encoder frames for 30 s audio

            // attention_mask: int64 [1, encSeqLen], all 1s - full window, no padding
            if ("attention_mask" in inputNames) {
                inputs["attention_mask"] = OnnxTensor.createTensor(
                    env,
                    LongBuffer.wrap(LongArray(encSeqLen.toInt()) { 1L }),
                    longArrayOf(1L, encSeqLen),
                )
            }

            // position_ids: int64 [1, encSeqLen], values [0 … encSeqLen-1]
            if ("position_ids" in inputNames) {
                inputs["position_ids"] = OnnxTensor.createTensor(
                    env,
                    LongBuffer.wrap(LongArray(encSeqLen.toInt()) { it.toLong() }),
                    longArrayOf(1L, encSeqLen),
                )
            }

            // Zero-initialise every remaining required input that we have not yet filled.
            //
            // The Voxtral audio encoder has several streaming-state tensors beyond the
            // standard past_key_values pattern (e.g. `past_padding_cache` for the Conv1d
            // front-end).  Rather than enumerate them by name, we discover them all from
            // inputInfo and apply a uniform shape-resolution rule:
            //
            //   • Rank-4 KV tensors  [batch, heads, past_seq, head_dim]:
            //       dim 2 (past_sequence_length) → 0  (no prior context)
            //       dim 0 (batch) and any other -1  → 1
            //   • All other tensors with dynamic dims:
            //       every -1 → 1  (smallest legal concrete size)
            //
            // Zero-length tensors (size == 0) are supported by ORT 1.21+ and correctly
            // signal "no past context" to the GQA and Conv cache nodes.
            for (name in inputNames) {
                if (name in inputs) continue                          // already provided
                val info  = session.inputInfo[name]?.info as? TensorInfo ?: continue
                val rank  = info.shape.size
                val shape = LongArray(rank) { i ->
                    val d = info.shape[i]
                    when {
                        d >= 0L             -> d   // static dim - keep as-is
                        rank == 4 && i == 2 -> 0L  // KV past sequence length → 0
                        else                -> 1L  // batch / other dynamic dim → 1
                    }
                }
                val size = shape.fold(1L) { acc, d -> acc * d }.toInt()
                Log.d(TAG, "encodeAudio: zero-init input '$name' shape=${shape.toList()} size=$size")
                inputs[name] = OnnxTensor.createTensor(env, FloatBuffer.wrap(FloatArray(size)), shape)
            }

            return session.run(inputs).use { result ->
                // Try the canonical name first, then fall back to the first output that is
                // not a streaming KV-cache state (present.*).  The Voxtral audio encoder
                // may use a different name depending on the export revision.
                val outputNames = session.outputNames.toList()
                val hiddenTensor = result.get(ENC_OUT_HIDDEN).orElse(null) as? OnnxTensor
                    ?: outputNames
                        .firstOrNull { !it.startsWith("present") }
                        ?.let { name ->
                            Log.d(TAG, "encodeAudio: '$ENC_OUT_HIDDEN' not found, using '$name' instead")
                            result.get(name).orElse(null) as? OnnxTensor
                        }
                    ?: throw RuntimeException(
                        "Encoder: no hidden-state output found. Available: $outputNames"
                    )
                cloneTensor(env, hiddenTensor)
            }
        } finally {
            // Release every input tensor regardless of success or failure
            inputs.values.forEach { it.close() }
        }
    }

    /**
     * Runs the decoder autoregressively in two phases:
     *
     * **Phase 1 - Instruction + audio prefill** (no logits):
     * Feeds `[BOS_emb, INST_emb, audio_embs…]` to populate the KV cache without
     * materialising the enormous `[1, T_prefill, vocab_size]` logit tensor on the
     * Android Java heap.  Falls back to a bare audio prefill if `[INST]` is absent
     * from the loaded vocabulary.
     *
     * **Phase 2 - Autoregressive generation** (starting from [/INST] seed token):
     * Each step embeds a single token → logits `[1, 1, vocab_size]` (~128 KB) - safe.
     *
     * **KV-cache ownership**: `present.*` tensors remain in ORT native (C++) memory via
     * the still-open `OrtSession.Result`.  The previous result is closed only *after*
     * the next step has consumed its tensors, keeping ~600 MB of KV data off the
     * constrained Android Java heap.
     */
    private fun greedyDecode(
        env: OrtEnvironment,
        session: OrtSession,
        audioEmbeds: OnnxTensor,   // encoder output: [1, T_audio, D]
    ): List<Int> {
        val inputNames       = session.inputNames.toSet()
        val outputNames      = session.outputNames.toList()
        val hasKvCache       = inputNames.any { it.startsWith("past_key_values") }
        val hasAttentionMask = "attention_mask" in inputNames
        val hasPositionIds   = "position_ids"   in inputNames
        Log.d(TAG, "greedyDecode: hasKvCache=$hasKvCache hasAttentionMask=$hasAttentionMask " +
                   "hasPositionIds=$hasPositionIds decoderUsesEmbeds=$decoderUsesEmbeds")

        val embed = embedSession
            ?: throw RuntimeException("embed_tokens session required - decoder uses inputs_embeds")

        // ── Extract audio embedding data and dimensions ───────────────────────
        val audioData  = FloatArray(audioEmbeds.floatBuffer.remaining())
        audioEmbeds.floatBuffer.rewind()
        audioEmbeds.floatBuffer.get(audioData)
        val audioShape = audioEmbeds.info.shape   // [1, T_audio, D]
        val tAudio     = audioShape[1].toInt()
        val hiddenDim  = audioShape[2].toInt()

        // ── Resolve special token IDs from the loaded vocabulary ──────────────
        // findTokenId() also verifies the piece is non-empty at that position.
        val tokenBos  = findTokenId("<s>",     TOKEN_BOS)
        val tokenInst = findTokenId("[INST]",  TOKEN_INST_DEFAULT)
        val tokenIEnd = findTokenId("[/INST]", TOKEN_IEND_DEFAULT)
        Log.d(TAG, "greedyDecode: tokenBos=$tokenBos tokenInst=$tokenInst tokenIEnd=$tokenIEnd")

        // Use Mistral instruction format only if [INST] is a real vocabulary entry.
        val useInstructionFormat = vocabulary.getOrNull(tokenInst)?.isNotEmpty() == true
        Log.d(TAG, "greedyDecode: useInstructionFormat=$useInstructionFormat")

        // ── Build the full prefill embedding sequence ─────────────────────────
        //
        // Instruction format : [BOS_emb, INST_emb, audio_embs…]  seed=[/INST]
        // Fallback format     : [audio_embs…]                     seed=BOS
        //
        // The prefill is run without logits to avoid materialising
        // [1, T_prefill, vocab_size] (~190 MB) on the Java heap.
        val prefixIds: LongArray
        val seedTokenId: Int
        if (useInstructionFormat) {
            prefixIds   = longArrayOf(tokenBos.toLong(), tokenInst.toLong())
            seedTokenId = tokenIEnd
        } else {
            prefixIds   = LongArray(0)
            seedTokenId = TOKEN_BOS
        }

        val fullPrefillData: FloatArray
        val fullPrefillLen: Int
        if (prefixIds.isNotEmpty()) {
            val prefixEmb  = embedTokens(env, embed, prefixIds)
            val prefixData = FloatArray(prefixEmb.floatBuffer.remaining())
            prefixEmb.floatBuffer.rewind()
            prefixEmb.floatBuffer.get(prefixData)
            prefixEmb.close()

            fullPrefillLen  = prefixIds.size + tAudio
            fullPrefillData = FloatArray(fullPrefillLen * hiddenDim)
            System.arraycopy(prefixData, 0, fullPrefillData, 0, prefixData.size)
            System.arraycopy(audioData,  0, fullPrefillData, prefixData.size, audioData.size)
        } else {
            fullPrefillLen  = tAudio
            fullPrefillData = audioData
        }

        Log.d(TAG, "greedyDecode: prefillLen=$fullPrefillLen " +
                   "(prefix=${prefixIds.size} + audio=$tAudio) seed=$seedTokenId")

        // Present-outputs-only set for the prefill run (logits deliberately omitted)
        val presentOutputNames: Set<String> = outputNames.filter { it.startsWith("present") }.toSet()

        // KV cache: input name → OnnxTensor that lives inside prevStepResult.
        // References only - no FloatArray copy - so ~600 MB stays in ORT native memory.
        val kvCacheTensors = mutableMapOf<String, OnnxTensor>()
        var prevStepResult: OrtSession.Result? = null
        val hypothesis     = mutableListOf<Int>()

        try {
            // ── Phase 1: Full-context prefill (no logits) ────────────────────
            val prefillInputs = mutableMapOf<String, OnnxTensor>()
            try {
                prefillInputs[DEC_IN_INPUTS_EMBEDS] = OnnxTensor.createTensor(
                    env,
                    FloatBuffer.wrap(fullPrefillData),
                    longArrayOf(1L, fullPrefillLen.toLong(), hiddenDim.toLong()),
                )
                if (hasAttentionMask) {
                    prefillInputs["attention_mask"] = OnnxTensor.createTensor(
                        env,
                        LongBuffer.wrap(LongArray(fullPrefillLen) { 1L }),
                        longArrayOf(1L, fullPrefillLen.toLong()),
                    )
                }
                if (hasPositionIds) {
                    prefillInputs["position_ids"] = OnnxTensor.createTensor(
                        env,
                        LongBuffer.wrap(LongArray(fullPrefillLen) { it.toLong() }),
                        longArrayOf(1L, fullPrefillLen.toLong()),
                    )
                }
                if (hasKvCache) {
                    addZeroKvInputs(env, session, inputNames, prefillInputs)
                }

                Log.d(TAG, "greedyDecode: running Phase 1 prefill (len=$fullPrefillLen)")
                prevStepResult = session.run(prefillInputs, presentOutputNames)
                collectKvTensors(prevStepResult!!, outputNames, kvCacheTensors)
                Log.d(TAG, "greedyDecode: Phase 1 complete - KV cache: ${kvCacheTensors.size} tensors")
            } finally {
                prefillInputs.values.forEach { it.close() }
            }

            var currentKvLen  = fullPrefillLen
            var nextToken     = seedTokenId  // [/INST] (instruction mode) or BOS (fallback)
            var consecutivePads = 0
            // Sliding window of the last REPETITION_WINDOW_TOKENS real (non-pad) hypothesis
            // tokens - used to detect when the model is cycling.
            val recentHypothesis = mutableListOf<Int>()

            // ── Phase 2: Autoregressive generation ───────────────────────────
            // Each step: embed one token → logits [1, 1, vocab_size] (~128 KB, safe).
            // Step 0 feeds the seed ([/INST] or BOS) and produces the first real token.
            for (step in 0 until MAX_NEW_TOKENS) {
                val genInputs = mutableMapOf<String, OnnxTensor>()
                try {
                    val tokenEmb  = embedTokens(env, embed, longArrayOf(nextToken.toLong()))
                    val tokenData = FloatArray(tokenEmb.floatBuffer.remaining())
                    tokenEmb.floatBuffer.rewind()
                    tokenEmb.floatBuffer.get(tokenData)
                    tokenEmb.close()

                    genInputs[DEC_IN_INPUTS_EMBEDS] = OnnxTensor.createTensor(
                        env,
                        FloatBuffer.wrap(tokenData),
                        longArrayOf(1L, 1L, hiddenDim.toLong()),
                    )
                    if (hasAttentionMask) {
                        val maskLen = currentKvLen + 1
                        genInputs["attention_mask"] = OnnxTensor.createTensor(
                            env,
                            LongBuffer.wrap(LongArray(maskLen) { 1L }),
                            longArrayOf(1L, maskLen.toLong()),
                        )
                    }
                    if (hasPositionIds) {
                        // Position of the new token = length of already-cached context
                        genInputs["position_ids"] = OnnxTensor.createTensor(
                            env,
                            LongBuffer.wrap(longArrayOf(currentKvLen.toLong())),
                            longArrayOf(1L, 1L),
                        )
                    }
                    // KV-cache tensors - owned by prevStepResult; do NOT close here
                    for ((name, tensor) in kvCacheTensors) {
                        genInputs[name] = tensor
                    }

                    val currentResult = session.run(genInputs)

                    // Safe to close the previous result now: its tensors were consumed
                    prevStepResult?.close()
                    prevStepResult = currentResult
                    kvCacheTensors.clear()
                    collectKvTensors(currentResult, outputNames, kvCacheTensors)

                    // Logits: [1, 1, vocab_size] - floatBuffer is ~128 KB, trivially safe
                    val logitsTensor = currentResult.get(DEC_OUT_LOGITS)
                        .orElseThrow { RuntimeException("Decoder output '$DEC_OUT_LOGITS' not found") }
                        as OnnxTensor
                    val vocabSize = logitsTensor.info.shape[2].toInt()
                    val logits    = FloatArray(vocabSize)
                    logitsTensor.floatBuffer.get(logits)  // buffer starts at 0; no rewind needed

                    val generated = logits.indices.maxByOrNull { logits[it] } ?: TOKEN_EOS
                    Log.d(TAG, "greedyDecode step=$step input=$nextToken " +
                               "generated=$generated (${vocabulary.getOrNull(generated)})")

                    nextToken    = generated
                    currentKvLen += 1

                    when (generated) {
                        TOKEN_EOS -> {
                            // Normal end of sequence
                            break
                        }
                        TOKEN_STREAMING_PAD -> {
                            // Voxtral Realtime streaming pad - "waiting for more audio".
                            // In batch/offline mode, too many consecutive pads means
                            // end-of-transcription (no more speech to decode).
                            consecutivePads++
                            if (consecutivePads > MAX_CONSECUTIVE_PADS) {
                                Log.d(TAG, "greedyDecode: $consecutivePads consecutive " +
                                           "[STREAMING_PAD] - end of transcription at step $step")
                                break
                            }
                            // Do NOT add to hypothesis; keep feeding pad back to maintain
                            // consistent KV-cache state (matches streaming training behaviour).
                        }
                        else -> {
                            consecutivePads = 0
                            hypothesis.add(generated)

                            // Repetition detection: if the last REPETITION_WINDOW_TOKENS
                            // real tokens form two identical halves, we're in a cycle.
                            recentHypothesis.add(generated)
                            if (recentHypothesis.size > REPETITION_WINDOW_TOKENS) {
                                recentHypothesis.removeAt(0)
                            }
                            if (recentHypothesis.size == REPETITION_WINDOW_TOKENS) {
                                val half = REPETITION_WINDOW_TOKENS / 2
                                if (recentHypothesis.subList(0, half) ==
                                    recentHypothesis.subList(half, REPETITION_WINDOW_TOKENS)) {
                                    Log.d(TAG, "greedyDecode: repeating cycle detected " +
                                               "at step $step - trimming tail and stopping")
                                    // Drop the duplicated second half from the hypothesis
                                    repeat(half) { hypothesis.removeLastOrNull() }
                                    break
                                }
                            }
                        }
                    }

                } finally {
                    // Close only tensors we created; KV-cache tensors are owned by prevStepResult
                    genInputs.forEach { (name, tensor) ->
                        if (!name.startsWith("past_key_values")) tensor.close()
                    }
                }
            }
        } finally {
            prevStepResult?.close()
        }

        Log.d(TAG, "greedyDecode: generated ${hypothesis.size} tokens")
        return hypothesis
    }

    /**
     * Collects `present.*` output tensors from [result] into [dest], renaming each
     * to its corresponding `past_key_values.*` input name.
     *
     * Tensors are **not copied** - they remain owned by [result].
     * [result] must stay open as long as any entry in [dest] is in use.
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
     * Finds the token ID for [token] by scanning the loaded vocabulary.
     * Returns [default] with a warning log when the token is not present.
     */
    private fun findTokenId(token: String, default: Int): Int {
        val id = vocabulary.indexOfFirst { it == token }
        return if (id >= 0) {
            Log.d(TAG, "findTokenId: '$token' → ID $id")
            id
        } else {
            Log.w(TAG, "findTokenId: '$token' not found in vocabulary - using default ID $default")
            default
        }
    }

    /**
     * Adds zero-initialised `past_key_values.*` input tensors to [inputs] for every
     * KV slot declared by [session].  Rank-4 tensors with a dynamic dim at index 2
     * (the past-sequence-length axis) receive a length of 0 to signal "no prior context".
     */
    private fun addZeroKvInputs(
        env: OrtEnvironment,
        session: OrtSession,
        inputNames: Set<String>,
        inputs: MutableMap<String, OnnxTensor>,
    ) {
        for (name in inputNames) {
            if (!name.startsWith("past_key_values")) continue
            val info  = session.inputInfo[name]?.info as? TensorInfo ?: continue
            val rank  = info.shape.size
            val shape = LongArray(rank) { i ->
                val d = info.shape[i]
                when {
                    d >= 0L              -> d    // static dim - keep as-is
                    rank == 4 && i == 2  -> 0L   // past sequence length → 0 (empty cache)
                    else                 -> 1L   // batch / other dynamic dim → 1
                }
            }
            val size = shape.fold(1L) { acc, d -> acc * d }.toInt()
            inputs[name] = OnnxTensor.createTensor(env, FloatBuffer.wrap(FloatArray(size)), shape)
        }
    }


    /**
     * Runs `embed_tokens_q4.onnx` to convert [ids] (INT64 `[1, seq]`) into dense
     * token embeddings (FLOAT `[1, seq, hidden_dim]`).
     *
     * Returns a cloned [OnnxTensor] that the caller owns and must close.
     * The session result is closed inside this function.
     */
    private fun embedTokens(env: OrtEnvironment, session: OrtSession, ids: LongArray): OnnxTensor {
        val seqLen    = ids.size.toLong()
        val idsTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(ids), longArrayOf(1L, seqLen))
        val inputs    = mapOf(EMBED_IN_INPUT_IDS to idsTensor)

        return session.run(inputs).use { result ->
            idsTensor.close()
            // The embed_tokens model output is named "inputs_embeds" by convention;
            // fall back to the first output if that name is not found.
            val outNames = session.outputNames.toList()
            val embeds = (result.get(EMBED_OUT_EMBEDS).orElse(null)
                ?: result.get(outNames[0]).orElse(null))
                as? OnnxTensor
                ?: throw RuntimeException("embed_tokens: no valid output tensor found")
            cloneTensor(env, embeds)
        }
    }

    /**
     * Loads the BPE vocabulary from a Hugging Face `tokenizer.json` file.
     *
     * Both sources are always merged so that special tokens such as `[INST]` and
     * `[/INST]` (stored in `added_tokens`) are available for instruction-format
     * construction even when a full `model.vocab` is also present.
     *
     *  1. `model.vocab` - maps BPE piece string → token ID.
     *  2. `added_tokens` - overlaid on top; overrides any conflicting positions and
     *     extends the array when IDs exceed the main vocab size.
     *
     * Returns an array indexed by token ID.
     */
    private fun loadVocabulary(file: File): Array<String> {
        val json    = JSONObject(file.readText())
        val entries = mutableMapOf<Int, String>()

        // Primary source: model.vocab (BPE piece → ID map)
        val vocab = json.optJSONObject("model")?.optJSONObject("vocab")
        vocab?.keys()?.forEach { token ->
            entries[vocab.getInt(token)] = token
        }

        // Overlay: added_tokens - contains [INST], [/INST], <s>, </s>, etc.
        // These may override positions in the main vocab or extend beyond it.
        val addedTokens = json.optJSONArray("added_tokens")
        if (addedTokens != null) {
            for (i in 0 until addedTokens.length()) {
                val obj = addedTokens.getJSONObject(i)
                entries[obj.getInt("id")] = obj.getString("content")
            }
        }

        if (entries.isEmpty()) {
            Log.w(TAG, "Could not parse vocabulary from $TOKENIZER_JSON - detokenisation will be empty")
            return emptyArray()
        }

        val maxId = entries.keys.maxOrNull() ?: 0
        val arr   = Array(maxId + 1) { i -> entries[i] ?: "" }
        Log.d(TAG, "loadVocabulary: ${arr.size} tokens (model.vocab + added_tokens merged)")
        return arr
    }

    /**
     * Converts a list of BPE token IDs to a plain-text string.
     *
     * Handles:
     *  - Byte-level GPT-2 / Mistral tokens: `Ġ` (U+0120) → space, `Ċ` (U+010A) → newline
     *  - SentencePiece word-boundary marker: `▁` (U+2581) → space
     *  - Special tokens enclosed in `<…>` - skipped
     */
    private fun detokenize(tokenIds: List<Int>): String {
        if (tokenIds.isEmpty()) return ""
        val sb = StringBuilder()
        for (id in tokenIds) {
            val piece = vocabulary.getOrNull(id)?.takeIf { it.isNotEmpty() } ?: continue
            if (piece.startsWith("<") && piece.endsWith(">")) continue   // skip special tokens
            sb.append(
                piece
                    .replace('\u0120', ' ')   // Ġ → space (byte-level BPE)
                    .replace('\u010A', '\n')  // Ċ → newline
                    .replace('▁', ' ')        // SentencePiece word boundary
            )
        }
        return sb.toString().trim()
    }

    /**
     * Deep-copies [source]'s float data into a new [OnnxTensor] with the same shape,
     * so the parent [OrtSession.Result] can be closed before the tensor data is read.
     */
    private fun cloneTensor(env: OrtEnvironment, source: OnnxTensor): OnnxTensor {
        val shape = source.info.shape
        val data  = FloatArray(source.floatBuffer.remaining())
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
}
