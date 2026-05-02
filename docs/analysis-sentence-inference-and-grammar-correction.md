# Analysis: Sentence-Level Re-Inference, Confidence Scoring, and On-Device Grammar Correction

> **Purpose**: This document analyses two potential improvements to the Outspoke transcription pipeline:
> 1. Re-running inference on the full audio of a completed sentence and comparing the result against the incremental streaming output, including the feasibility of deriving a confidence score.
> 2. The state-of-the-art in ultra-small, on-device grammar correction models that could post-process the final committed text.
>
> No code is written here. The goal is a thorough technical foundation for future implementation decisions.

---

## Table of Contents

1. [Current Pipeline Architecture (Context)](#1-current-pipeline-architecture-context)
2. [Sentence-Level Re-Inference](#2-sentence-level-re-inference)
   - [What Constitutes a "Complete Detected Commit"?](#21-what-constitutes-a-complete-detected-commit)
   - [Why Re-Inference Could Help](#22-why-re-inference-could-help)
   - [Why Re-Inference Is Non-Trivial](#23-why-re-inference-is-non-trivial)
   - [Confidence Scoring from Model Internals](#24-confidence-scoring-from-model-internals)
   - [Reconciling Streaming and Re-Inference Results](#25-reconciling-streaming-and-re-inference-results)
   - [Practical Verdict](#26-practical-verdict)
3. [On-Device Grammar Correction](#3-on-device-grammar-correction)
   - [Why Grammar Correction Matters Here](#31-why-grammar-correction-matters-here)
   - [Model Taxonomy](#32-model-taxonomy)
   - [Candidate Models in Detail](#33-candidate-models-in-detail)
   - [Memory Pressure Reality Check](#34-memory-pressure-reality-check)
   - [Recommended Short-list](#35-recommended-short-list)
4. [Cross-Cutting Considerations](#4-cross-cutting-considerations)
5. [Summary and Recommended Next Steps](#5-summary-and-recommended-next-steps)

---

## 1. Current Pipeline Architecture (Context)

Understanding what already exists is essential before proposing changes.

### Audio capture
`AudioCaptureManager` reads microphone audio in **30 ms / 480-sample chunks** at 16 kHz mono (PCM-16). Each chunk is passed through the Silero VAD v4 (ONNX) which classifies frames as speech or silence with a 60 ms onset gate, 450 ms lead-in, and 450 ms hangover, smoothing over transient noise.

### Rolling-window streaming inference
`InferenceRepository` maintains a **rolling audio window** fed by the VAD-filtered chunk stream. The key parameters are:

| Constant | Value | Purpose |
|---|---|---|
| `MIN_SAMPLES` | 2 s | Minimum window before the first partial fires |
| `STRIDE_SAMPLES` | 1 s | New inference every second of new audio |
| `MAX_WINDOW_SAMPLES` | 30 s | Hard ceiling on window size |
| `STABLE_STRIDES` | 3 | Consecutive strides that must agree before audio trim |
| `TRIGGER_WINDOW_SAMPLES` | 6 s | Trim logic activates above this size |
| `MIN_CONTEXT_SAMPLES` | 4 s | Tail context kept after a stable-chunk trim |
| `FORCE_TRIM_WINDOW_SAMPLES` | 12 s | Aggressive trim when strides diverge |
| `SILENCE_TRIM_STRIDES` | 2 | Consecutive blank strides before proactive trim |

Every second a **partial inference** is run on the full accumulated window. Word lists from the last 3 partials are compared; when a stable leading prefix is detected, the corresponding audio is trimmed from the window front, and a `TranscriptResult.WindowTrimmed` event carries the stable word list to `TextInjector`.

### Final inference pass
When the user releases the record key and `AudioCaptureManager` completes its trailing drain, the `channelFlow` in `InferenceRepository` exits its `collect` block with any remaining audio still in the window. A **single final inference** is run on this residual audio and emitted as `TranscriptResult.Final`. This is the closest thing the current system has to a "complete sentence" pass — but it only covers the audio that was *not yet stable-trimmed*, not the full sentence.

### Current model
**Parakeet-TDT 0.6B-v3** (ONNX INT8, ~700 MB) is the only active model. Its decoder-joint network outputs a 4-D tensor `[B, T_enc, T_tgt, vocab+duration]` (8198 dimensions) whose softmax values encode the probability of each token (or blank) at each encoder frame × decoder position. Currently only the greedy argmax is consumed; the full distribution is discarded immediately.

---

## 2. Sentence-Level Re-Inference

### 2.1 What Constitutes a "Complete Detected Commit"?

The pipeline has no explicit sentence boundary event. Boundaries can be inferred from multiple signals:

1. **VAD silence** — after the Silero VAD transitions to `State.SILENCE` and the hangover drain completes, the user has paused. This is the strongest boundary signal. The existing `flush()` call in `AudioCaptureManager.startCapture()` triggers exactly at this point.
2. **Punctuation tokens** — Parakeet TDT emits sentence-ending punctuation (`.`, `!`, `?`) as vocabulary tokens. Detecting one in the partial stream is a soft sentence boundary.
3. **Recording session end** — when the user releases the record key, the entire accumulated audio for that session is implicitly a "complete utterance".

The **most natural and already-instrumented boundary** is the recording session end (signal 3). Signal 1 could also work for long continuous dictation sessions. Signal 2 is unreliable because punctuation is sometimes deferred by multiple strides.

### 2.2 Why Re-Inference Could Help

#### Context advantage
In the streaming pipeline, the **first 2–4 seconds** of any sentence are decoded with a very short audio context. Parakeet TDT is a transducer — it processes the encoder output sequentially — so early tokens that were emitted with sparse context can be miscorrected once more audio arrives. In practice this surfaces as the "mid-sentence word swap" artefact (e.g. `"Mitpush"` → `"Push to talk"` over several strides). A single pass over the *complete* sentence audio lets the encoder see all frames at once, which typically resolves such ambiguities.

#### Hallucination and repetition stability
The sliding window trimming is designed precisely to fight hallucination loops. A full-sentence pass avoids the alignment instability that triggers these loops — the model sees a natural acoustic boundary at both ends.

#### No TextInjector interaction
During a re-inference pass the model runs independently. There is no suffix-overlap alignment, no `WindowTrimmed` event, and no committed-word anchor to maintain. The output is a clean, unbroken transcript of the full sentence.

### 2.3 Why Re-Inference Is Non-Trivial

#### Double compute cost
Parakeet TDT on a mid-range phone (e.g. Pixel 6a, Snapdragon 778G) takes **300–700 ms** for a 5-second audio clip. Re-inference on a full 10-second sentence would cost a similar additional 600–1000 ms. On a fast device with DSP acceleration this might be acceptable if the re-inference runs asynchronously; on a low-end device it risks noticeable jank.

#### Audio buffering requirement
The stable-chunk trimming system **discards audio** from the window front once words are confirmed stable. To re-run inference on the entire sentence, a **separate sentence audio buffer** must be maintained alongside the rolling window, one that is never trimmed. This is a non-trivial architectural addition — the sentence buffer must:
- Start recording at the VAD onset
- Be keyed to the recording session (not the rolling window)
- Be bounded (e.g. max 30 s) to avoid unbounded memory

#### Result reconciliation complexity
The streaming partial and the re-inference result will often differ in minor ways (punctuation, one swapped word). Deciding which version to commit, and how to replace already-injected text in the active input field, is the hardest part. `TextInjector` uses suffix-overlap alignment to avoid duplicating text — it would need to support a "replace committed words" operation that is currently not implemented.

#### User-visible flicker
If the final re-inference result contradicts what the user saw during streaming, the text in the input field will visibly change after they have already moved on. This is jarring. Strategies to mitigate this include:
- Only replacing text when confidence is meaningfully higher
- Restricting replacements to short utterances (< 5 s)
- Showing an "improved" indicator rather than silently swapping

### 2.4 Confidence Scoring from Model Internals

#### From Parakeet TDT logits

The decoder-joint ONNX session outputs tensor `[B, T_enc, T_tgt, 8198]` at every decoding step. The first `1025` entries are token probabilities (vocab 1024 + blank), the remainder encode duration predictions.

A **per-token confidence score** can be derived as:

```
softmax(logit[blank_id])   → P(blank | frame, context)  ← "the model is uncertain here"
1 - P(blank)               → P(some token emitted)       ← rough token confidence
argmax logit probability   → P(chosen token)              ← greedy confidence
```

For a full utterance, the most interpretable aggregate is the **geometric mean of per-token probabilities** (i.e. the mean of log-probabilities), which is equivalent to the per-token log-likelihood normalised by length. This is exactly what NVIDIA's NeMo confidence module computes for CTC/RNN-T models:

```
confidence = exp( (1/N) × Σ log P(yᵢ | x) )
```

Where `yᵢ` are the emitted (non-blank) tokens and `x` is the acoustic input.

**Values near 1.0** indicate the model was highly certain at each emission step. **Values below ~0.6** suggest one or more tokens were ambiguous and re-inference may help.

Crucially, *the logit tensor is already computed during every existing inference call*. The only change required to extract confidence is to read the softmax values at the chosen token positions before discarding the output tensor.

#### From Whisper logits (if WhisperEngine is used)

The Whisper merged decoder already outputs `logits [1, 1, vocab_size]` at each autoregressive step. Log-softmax at the greedy token gives `log P(yᵢ)` directly. The mean of these over all emitted tokens is a standard confidence measure used in whisper.cpp and OpenAI's official code.

There is an additional Whisper-specific signal: the **no-speech probability**, derived from the logit of the `<|nospeech|>` token during the initial cross-attention pass. Whisper's official voice activity detection uses this token to decide whether the input contains speech at all.

#### Comparing streaming vs. sentence-level confidence

Both inference passes produce a per-utterance confidence. The delta between them is a useful signal:

- **Streaming confidence high, re-inference confidence low**: unusual; the model converged quickly but the full context disrupted it (possible if the re-inference model never saw the full 30-second context during training). This would argue against replacing the streaming result.
- **Streaming confidence low, re-inference confidence high**: the most actionable case. The sentence-level pass resolved an ambiguity, and confidence went up. Replace the streaming text.
- **Both low**: the acoustic signal is genuinely ambiguous. Neither pass should be treated as authoritative. Consider showing a correction hint or leaving the streaming result.
- **Both high but text differs**: minor wording or punctuation difference. Human preference is subjective. Favour the re-inference result only if word error rate vs streaming is small.

### 2.5 Reconciling Streaming and Re-Inference Results

A practical reconciliation strategy (not yet implemented) would look like:

1. After `TranscriptResult.Final` is emitted by `InferenceRepository`, **trigger a background re-inference** on the full sentence audio buffer.
2. Compare the re-inference result and its confidence score to the streaming final result.
3. Compute a **string similarity** metric (normalised edit distance / word error rate proxy). If the texts are identical or very close (e.g. edit distance ≤ 2 words), skip the replacement.
4. If re-inference confidence > streaming confidence by a meaningful margin (e.g. Δ > 0.15) **and** re-inference text differs substantively, request a replacement via `TextInjector`.
5. Gate the replacement on sentence length: very short utterances (< 1 s) benefit little from re-inference and risk replacing correct streaming results with decoder artefacts.

The `TextInjector.resetAfterTrim()` path already knows how to shrink committed-word tracking. A "replace all committed words for this sentence" operation would follow the same pattern but clear the full committed list and inject the new text.

### 2.6 Practical Verdict

**Re-inference on a complete sentence is architecturally sound and technically feasible, but has a meaningful implementation cost.** The most compelling use case is as an asynchronous background pass that corrects the *already-injected* text only when confidence improvement is clear. The components that would need to be added or extended:

| Component | Change needed |
|---|---|
| `AudioCaptureManager` | Expose session-level audio buffer (never trimmed) |
| `InferenceRepository` | Emit confidence score alongside `TranscriptResult`; queue background re-inference |
| `SpeechEngine` interface | Add `transcribeWithConfidence(chunk)` returning score alongside text |
| `ParakeetEngine` | Extract token log-probabilities from joint logits |
| `WhisperEngine` | Extract token log-probabilities from decoder logits |
| `TextInjector` | Add `replaceCommittedSentence(newText)` operation |
| `KeyboardViewModel` | Orchestrate background re-inference job and update UI |

A **minimum viable version** — extract and expose confidence scores without the re-inference pass — is a much smaller change: only `ParakeetEngine`, `TranscriptResult`, and `PipelineDiagnostics` need to be touched. This alone would give useful diagnostic data before committing to the full re-inference architecture.

---

## 3. On-Device Grammar Correction

### 3.1 Why Grammar Correction Matters Here

The ASR pipeline already includes extensive post-processing (`cleanTranscript`): filler removal, stutter collapse, repetition deduplication, capitalization, and punctuation normalisation. However, these are all rule-based and operate at the word-list level. They cannot fix:

- **Wrong word choice** ("their" vs "there", "affect" vs "effect")
- **Missing articles** ("I went shop" → "I went to the shop")
- **Subject-verb agreement** ("They was" → "They were")
- **Tense errors** ("Yesterday I go to work" → "Yesterday I went to work")
- **Comma placement** where the ASR model produced none
- **Cross-word co-reference errors** that require sentence-level understanding

Grammar correction can be applied at two natural trigger points:

1. **Per-sentence** — immediately after `TranscriptResult.Final` is received (highest impact, visible latency)
2. **As a background pass** — after the committed text is injected, a lightweight correction runs and proposes changes (lower latency, user chooses)

### 3.2 Model Taxonomy

Grammar correction models fall into four broad categories:

#### A. Rule-based / FST systems
Pure rule systems (LanguageTool, Grammatica, GNU Diction). No ML, extremely fast (<1 ms), very small footprint (<15 MB including rule databases). High precision on well-known error patterns, but zero recall on errors not in the rule set. No understanding of context or semantics.

#### B. Sequence-labelling (GEC tagging)
Models that predict an edit operation (KEEP, DELETE, REPLACE:word, INSERT:word) per input token. Much faster than seq2seq because there is no autoregressive decoding. GECToR (Grammarly, 2020) is the canonical example; it uses a BERT encoder (~400 MB) plus edit-label classifiers. Smaller ELECTRA-based variants exist at ~110 MB.

#### C. Encoder-decoder seq2seq (T5/BART family)
Takes the erroneous sentence as input and generates the corrected sentence. The gold standard in GEC benchmarks. T5-small (~60 MB as ONNX INT8) achieves strong results with fine-tuning; T5-base (~240 MB) is near-human-level on common errors. These require autoregressive decoding (5–20 forward passes per sentence), which costs 80–300 ms on a modern phone with ONNX Runtime.

#### D. Instruction-tuned small LLMs
Recent models (Qwen2.5-0.5B, SmolLM2-135M/360M, Phi-3-mini) can perform grammar correction via a short prompt. They are not specialized for GEC and produce slightly more verbose outputs, but their general language understanding handles edge cases that fine-tuned T5 models miss. Token-by-token generation cost makes them slower than T5 for the same effective parameter count.

### 3.3 Candidate Models in Detail

#### LanguageTool Core (Java/JVM, rule-based)
- **Size on disk**: ~8–12 MB (core rules, English); ~25 MB with full English dictionary
- **Inference time**: < 5 ms per sentence
- **Quality**: High precision on punctuation, capitalization, common agreement errors, article errors; zero recall on unknown patterns
- **Android compatibility**: Native Java library; already runs on Android (LanguageTool keyboard is published on the Play Store). Can be embedded as a JAR dependency.
- **Limitation**: Cannot handle contextual word choice errors or complex tense shifts.
- **Verdict**: Excellent **first layer** of grammar correction; free, fast, deterministic. Already covers many of the errors Parakeet TDT introduces (capitalization artefacts are partially covered by `applySentenceCapitalization`, but article/agreement gaps are not).

#### `prithivida/grammar_error_correcter_v1` (T5-small fine-tuned)
- **Base**: T5-small (60 M parameters)
- **Size**: ~242 MB fp32 on HuggingFace; **~60 MB as ONNX INT8** (encoder + decoder separate sessions)
- **Inference time on device**: ~80–150 ms per sentence (Snapdragon 8 Gen 2 with ONNX Runtime Mobile INT8)
- **Quality**: Strong on CoNLL-2014 and BEA-2019 GEC benchmarks; handles article errors, subject-verb agreement, tense, missing prepositions
- **Android compatibility**: Can be exported to ONNX with `optimum` and run via OnnxRuntime, exactly the same stack Outspoke already uses. T5 encoder + decoder follow the same merged-decoder pattern as Whisper in `WhisperEngine`.
- **Limitation**: English only; fine-tuned on written-text corpora, not ASR output specifically (ASR output lacks punctuation and capitalization, which slightly degrades correction quality unless a normalization step precedes it)
- **Verdict**: A strong candidate for English-language grammar correction. The ONNX INT8 model would add ~60 MB to device storage, which is very manageable.

#### `grammarly/coedit-small` (T5-small fine-tuned, instruction-conditioned)
- **Base**: T5-small (60 M parameters)
- **Size**: ~60 MB as ONNX INT8
- **Inference time**: similar to grammar_error_correcter_v1 (~100–160 ms)
- **Quality**: Excellent — CoEdit was published at ACL 2023 and achieves state-of-the-art on multiple GEC benchmarks. Instruction conditioning (`"Fix grammar: <sentence>"`) allows it to also perform fluency improvement and style edits beyond pure grammar. Multilingual variants exist for the base and large sizes.
- **Android compatibility**: Same T5 architecture, same ONNX export path.
- **Limitation**: The small variant is English-only; the instruction prefix costs a few extra tokens of decoder context; slightly larger working memory than a pure seq-labelling model.
- **Verdict**: Best quality in the < 100 MB class for English. If only one seq2seq model is to be evaluated, `coedit-small` is the stronger pick over `grammar_error_correcter_v1`.

#### GECToR-ELECTRA-small (sequence labelling)
- **Base**: ELECTRA-small encoder (~14 M parameters) + classification head
- **Size**: ~55 MB as ONNX
- **Inference time**: ~30–60 ms (no autoregressive decoding)
- **Quality**: On CoNLL-2014, GECToR-BERT achieves F₀.₅ ≈ 65.3; ELECTRA-small drops this to ≈ 59.1 — lower than T5-small fine-tuned models but still useful
- **Android compatibility**: BERT-class encoder, straightforward ONNX export
- **Limitation**: Per-token labelling cannot handle multi-word insertions well (e.g. inserting "have been" where a single word is expected). No published ONNX weights for the ELECTRA-small variant; would need to train or adapt from open GECToR checkpoints.
- **Verdict**: Interesting for latency-critical applications but lower quality ceiling than T5/CoEdit. Not recommended as a primary model unless the 30–60 ms latency advantage over T5 (~100 ms) is a hard constraint.

#### SmolLM2-135M (instruction-tuned LLM)
- **Base**: 135 M parameter causal LLM (custom architecture, Hugging Face)
- **Size**: ~270 MB fp16; **~135 MB INT8; ~70 MB INT4**
- **Inference time**: ~200–500 ms per sentence at INT4 (token-by-token generation is inherently slower than T5's encoder-decoder)
- **Quality**: As a generalist LLM it handles any language the pretraining covered. Grammar correction quality with a one-shot prompt is competitive with T5-small fine-tuned models on common errors, but less reliable on subtle GEC cases (agreement, tense) that the fine-tuned models specifically target.
- **Android compatibility**: llama.cpp / MNN / OnnxRuntime with INT4 quantization. llama.cpp has a robust Android JNI wrapper; there are also ONNX GenAI exports of SmolLM2.
- **Limitation**: Higher latency than T5; requires a well-crafted prompt; output is generative (can hallucinate replacements for correct text)
- **Verdict**: A viable fallback for multilingual grammar correction where a fine-tuned T5 model is unavailable, but not the optimal choice for English-first keyboards.

#### Qwen2.5-0.5B / Qwen2.5-0.5B-Instruct (INT4)
- **Size**: ~320 MB INT4
- **Inference time**: ~400–800 ms per sentence on mid-range SoCs
- **Quality**: Noticeably better than SmolLM2 on CoNLL benchmarks when prompted correctly; strong multilingual capability (Chinese, German, French, Spanish, Japanese, etc.)
- **Limitation**: 320 MB additional footprint on top of Parakeet's 700 MB strains mid-range devices seriously. Latency is borderline for an interactive keyboard.
- **Verdict**: Worth investigating for multilingual scenarios on high-end devices only. Not recommended as default.

#### Apple OpenELM-270M (INT8, ~135 MB)
- **Base**: 270 M parameter mobile-optimised LLM from Apple
- **Size**: ~270 MB fp16; ~135 MB INT8
- **Quality**: Comparable to SmolLM2-135M in grammar correction when prompted
- **Android compatibility**: Not designed for Android; llama.cpp can run it but Apple's CoreML path is iOS-only
- **Verdict**: Not the right fit for an Android keyboard. Pass.

#### MobileLLM-125M / MobileLLM-350M (Meta, research)
- **Size**: ~125 M / 350 M parameters; INT4 weights available (~65 MB / ~180 MB)
- **Quality**: Designed for on-device inference; grammar correction with prompting is reasonable for MobileLLM-350M
- **Limitation**: No off-the-shelf grammar correction fine-tuning published; needs custom fine-tuning for GEC task
- **Verdict**: Promising for future research but not production-ready for this use case today.

### 3.4 Memory Pressure Reality Check

The current device memory budget is already stressed:

| Component | Approximate RAM at runtime |
|---|---|
| Parakeet encoder ONNX session | ~300–400 MB |
| Parakeet decoder-joint ONNX session | ~100–150 MB |
| Parakeet preprocessor (nemo128) | ~5 MB |
| Silero VAD | ~2 MB |
| Android IME framework + Compose UI | ~80–120 MB |
| **Total (current)** | **~490–680 MB** |

Adding a grammar correction model:

| Model | Additional RAM | Practical headroom |
|---|---|---|
| LanguageTool core | ~12 MB | ✅ Safe on all devices |
| T5-small / CoEdit-small INT8 | ~120–180 MB | ✅ Safe on devices with ≥ 4 GB RAM |
| GECToR-ELECTRA-small | ~80–120 MB | ✅ Safe on devices with ≥ 3 GB RAM |
| SmolLM2-135M INT4 | ~150–200 MB | ⚠️ Tight on 3 GB devices |
| Qwen2.5-0.5B INT4 | ~350–450 MB | ❌ High risk of OOM on < 6 GB devices |

An important optimisation: if the grammar correction model is only needed **after** a sentence is committed, it can be loaded and unloaded on demand rather than kept resident alongside Parakeet. ONNX Runtime session creation for a 60 MB model takes ~200–500 ms — acceptable for a background post-processing pass but not for inline correction.

Alternatively, a **lazy-load / LRU** approach could keep one model in memory at a time:
- While recording: only Parakeet is resident
- After commit: swap in the GEC model (or keep it if memory allows)
- Before next recording starts: swap Parakeet back in if unloaded

This is architecturally possible with the existing `InferenceService` / `SpeechEngineFactory` pattern, but requires extending them to manage a second engine type.

### 3.5 Recommended Short-list

Based on the analysis above, two tiers of recommendation emerge:

**Tier 1 — Low-risk, immediate value**

| Model | Rationale |
|---|---|
| **LanguageTool Core (rule-based)** | Zero memory risk, <5 ms, Java-native, complements ML models perfectly. Should be added regardless of whether an ML model is chosen. |

**Tier 2 — ML-based, English-first**

| Model | Rationale |
|---|---|
| **`grammarly/coedit-small` ONNX INT8** | Best quality/size trade-off; instruction-conditioned; same ONNX Runtime stack; ~60 MB INT8; ~100–150 ms latency. Top recommendation for English grammar correction. |
| **`prithivida/grammar_error_correcter_v1` ONNX INT8** | Slightly lower quality than CoEdit, but well-documented and widely used; good fallback if CoEdit ONNX export proves problematic. |

**Tier 3 — Multilingual**

| Model | Rationale |
|---|---|
| **SmolLM2-135M INT4 (prompted)** | Reasonable quality across languages Parakeet supports (DE, FR, ES, IT, etc.); ~70–135 MB INT4; ~200–400 ms latency. Viable for multilingual users on high-end devices. |

---

## 4. Cross-Cutting Considerations

### Integration ordering matters
Grammar correction should run **after** sentence-level re-inference (if implemented), not in parallel with it. The re-inference result is more accurate than the streaming partial and therefore a better input for the GEC model. Running GEC on a low-confidence streaming partial wastes compute and risks over-correcting.

### ASR-specific GEC challenge
General GEC models are trained on written-text corpora. ASR output differs in several important ways:
- No punctuation (until the ASR model adds it)
- Capitalisation is inconsistent
- Contractions are frequently expanded ("don't" → "do not")
- Phonetic confusions are common ("their/there/they're", "affect/effect")
- Disfluencies may remain even after the `cleanTranscript` pass

To get the best out of `coedit-small` or `grammar_error_correcter_v1`, a **pre-normalisation step** that lowercases the ASR output before feeding it to the GEC model, then applies the GEC output back with case-restoration, will improve recall significantly.

### Latency budget for a keyboard
A voice keyboard has a tighter latency contract than a dictation transcription service. The window between the user releasing the mic key and the text appearing in the input field should ideally be < 300 ms for a good experience. The current pipeline already spends 300–600 ms in the final Parakeet inference pass. Adding 100–200 ms for GEC pushes the total to 400–800 ms — still acceptable on fast devices, borderline on slow ones. Running GEC **asynchronously** (inject ASR result immediately, then silently update with GEC result if different) avoids this latency problem but introduces the same "visible correction" UX issue as sentence-level re-inference.

### Language detection
Parakeet TDT is English-biased in its current deployment (it supports 25 languages but the English acoustic model dominates). Grammar correction models are often English-only. If multilingual support is a priority, both the ASR and GEC models must be evaluated in the target languages. `coedit-small` is English-only; SmolLM2-135M covers the main European languages but with degraded GEC quality.

### Testing strategy
Grammar correction is hard to unit-test in isolation because correctness is subjective and domain-dependent. A practical evaluation approach:
1. Collect a set of representative Parakeet output transcriptions (from logcat) that contain known grammar errors
2. Run each candidate GEC model on this set
3. Score with the CoNLL-2014 F₀.₅ metric (precision-weighted F-score preferred in GEC)
4. Also measure latency on the target Android device via `System.currentTimeMillis()` around the ONNX session run

---

## 5. Summary and Recommended Next Steps

### Sentence-level re-inference and confidence scoring

| Step | Effort | Value |
|---|---|---|
| Extract per-token log-probabilities from `ParakeetEngine` / `WhisperEngine` logits | Low | High — confidence scores immediately usable for diagnostics and filtering |
| Add `confidence: Float` field to `TranscriptResult.Final` | Trivial | Enables all downstream confidence-gated logic |
| Introduce sentence-level audio buffer in `AudioCaptureManager` | Medium | Required for re-inference; also useful for debugging |
| Implement background re-inference pass in `InferenceRepository` | High | Meaningful accuracy improvement; requires reconciliation logic |
| Add `replaceCommittedSentence` to `TextInjector` | Medium | Necessary for surfacing re-inference corrections to the user |

**Recommended starting point**: Extract confidence scores from existing logits first (low risk, no architecture change). Evaluate whether streaming confidence is actually low on the cases where errors occur — if yes, proceed with re-inference. If streaming confidence is already high on most utterances, re-inference may offer diminishing returns and the effort is better spent on GEC.

### Grammar correction

| Step | Effort | Value |
|---|---|---|
| Embed LanguageTool core as a library | Low | Immediate, zero-risk improvement for capitalization, articles, punctuation |
| Export `coedit-small` to ONNX INT8 and benchmark on target device | Low | Validates whether 60 MB / ~120 ms is acceptable in practice |
| Integrate `coedit-small` as post-processor on `TranscriptResult.Final` | Medium | Production-quality grammar correction for English |
| Add session lifecycle management for GEC model (lazy-load/unload) | Medium | Controls memory pressure when running alongside Parakeet |
| Evaluate SmolLM2-135M for multilingual GEC | Medium | Unlocks quality grammar correction for DE, FR, ES, IT users |

**Recommended starting point**: Add LanguageTool Core immediately (no model download, no memory overhead, complements existing `cleanTranscript` pipeline). In parallel, benchmark `coedit-small` ONNX INT8 on a representative device. If latency is ≤ 200 ms, integrate it as a background post-processor triggered on `TranscriptResult.Final`.

---

*Document written May 2026. Model sizes and benchmark numbers reflect published results as of early 2026; re-evaluate specific model choices against HuggingFace and academic literature before implementation.*
