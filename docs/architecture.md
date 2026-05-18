# Outspoke - Architecture Reference

> **Scope**: Authoritative technical reference for the Outspoke Android IME codebase.
> Intended for developers adding features, new engines, or debugging the inference pipeline.
> For user-facing information see README.md; for potential future improvements see analysis.md.

---

## Table of Contents

1. High-Level Overview
2. Module and Package Map
3. Layer-by-Layer Architecture
4. Data Flow End to End
5. Key Interfaces and Sealed Classes
6. Sliding-Window Inference Details
7. Post-Processing Pipeline
8. Text Injection and Alignment
9. Model Registry and Download
10. Engine Implementations
11. Service and Lifecycle Management
12. State Machines
13. Build Configuration and ABI Splits
14. Testing Strategy
15. Extension Guide: Adding a New Engine
16. Key Conventions and Invariants

---

## 1. High-Level Overview

Outspoke is a privacy-first Android Input Method Editor (IME). All speech recognition runs
on-device via ONNX Runtime; no audio ever leaves the device after the one-time model download.

    Active App (Text Field)
         | InputConnection API
    OutspokeInputMethodService    <- Android IME entry-point (LifecycleOwner + Compose UI)
         | KeyboardViewModel      <- UI state + capture lifecycle
         | binds to
    InferenceService              <- Foreground LifecycleService
         | InferenceRepository    <- Sliding-window buffer (<=30 s)
         |   SpeechEngine         <- Interface; swap models here (currently ParakeetEngine)
         | Flow<AudioChunk>
    AudioCaptureManager           <- 16 kHz / 16-bit / mono PCM
    SileroVadFilter               <- Neural VAD (Silero v4, ONNX) - primary
    RMSVadFilter                  <- Energy-threshold VAD - fallback

**Core design principles:**

- SpeechEngine is the *only* seam for adding a new ASR model. Nothing in the IME or service layer changes.
- InferenceService keeps the engine alive across keyboard hide/show cycles. Unbinding does **not** stop the service.
- Constructor injection only throughout; no field injection.
- No external SDKs that phone home (no analytics, no crash reporters).

---

## 2. Module and Package Map

Single Gradle module (). All Kotlin source lives under .

| Package | Key files | Responsibility |
|---|---|---|
| audio | AudioCaptureManager, SileroVadFilter, RMSVadFilter, VadFilter, AudioChunk, PermissionHelper | Mic capture, PCM chunking, Voice Activity Detection |
| inference | SpeechEngine, ParakeetEngine, WhisperEngine, VoxtralEngine, SpeechEngineFactory, InferenceRepository, InferenceService, TranscriptResult, EngineState, PipelineDiagnostics, NumberNormaliser, GrammarCorrector | ASR pipeline, sliding window, post-processing, foreground service |
| ime | OutspokeInputMethodService, TextInjector, TranscriptAligner, EnterAction, WordSuggestionProvider | Keyboard service, composing text management, alignment, word-correction facade |
| ime/correction | WordCorrector, CandidateGenerator, ArpaLanguageModel, SuggestionFileDownloader, SuggestionFileManager, SuggestionLanguage, DoubleMetaphone, KolnerPhonetik, EditDistance | On-device word correction + downloadable language pack management |
| settings/model | ModelId, ModelRegistry, ModelDownloadManager, ModelStorageManager, ModelState, ModelViewModel, DownloadService | Model enumeration, download, SHA-256 verification, on-disk paths |
| settings/preferences | AppPreferences, PreferencesViewModel | DataStore-backed user preferences |
| settings/screens | HomeScreen, ModelScreen, PreferencesScreen | Settings Compose UI |
| ui/keyboard | KeyboardViewModel, KeyboardUiState, KeyboardScreen, ImeComposeView, WordAtCursor | IME Compose hosting, UI state |
| ui/keyboard/components | TalkButton, SuggestionBar, WaveformBar, StatusIndicator, KeyboardActionButton, KeyboardTutorialOverlay, LanguageSelector | Keyboard UI sub-components |
| ui/theme | OutspokeKeyboardTheme | Compose theming |

---

## 3. Layer-by-Layer Architecture

### 3.1 Audio Capture and VAD

**AudioCaptureManager**

- Opens AudioRecord with source VOICE_RECOGNITION, 16 kHz mono PCM-16.
- Emits 480-sample (30 ms) chunks as a cold Flow<AudioChunk>.
- Applies a peak-envelope follower for amplitude normalisation: fast attack (~7 chunks), slow decay (~2 s half-life), hard floor.
- On stop: drains the hardware buffer then waits HANGOVER_DRAIN_SAFETY_FRAMES = 20 frames (600 ms) to flush VAD hangover before the flow completes.

**VadFilter interface**

    interface VadFilter {
        fun process(chunk: AudioChunk, rms: Float): AudioChunk?  // null = silence, drop
        fun flush(): List<AudioChunk>                             // drain tail on stop
        fun isSpeechActive(): Boolean
        fun close()
    }

**SileroVadFilter** (primary)

- Silero VAD v4 ONNX. RNN state tensors h/c shape [2,1,64] carried across chunks.
- Speech probability threshold: 0.3.
- Three layers: onset gate (2 frames/60 ms), pre-roll buffer (15 frames/450 ms lead-in), hangover.
- Falls back to RMSVadFilter if the ONNX model fails to load.

**RMSVadFilter** (fallback)

- Energy threshold with the same 3-layer onset/pre-roll/hangover structure.

---

### 3.2 Inference Pipeline

**SpeechEngine interface** - the single extension point for new models:

    interface SpeechEngine {
        val isLoaded: Boolean
        fun load(modelDir: File)
        fun transcribe(chunk: AudioChunk): TranscriptResult
        fun setLanguage(tag: String)
        fun setLanguageFilter(tags: List<String>)
        fun close()
    }

**SpeechEngineFactory** routes ModelId to a concrete engine implementation.

**InferenceRepository**

- Receives Flow<AudioChunk> from AudioCaptureManager.
- Maintains a rolling audio window (see Section 6).
- Emits Flow<TranscriptResult> consumed by KeyboardViewModel -> TextInjector.
- Applies an 8-step post-processing pipeline to every raw transcript (see Section 7).

**InferenceService**

- LifecycleService running as an Android foreground service.
- Notification channel: outspoke_inference, notification ID 1001.
- Owns the SpeechEngine instance; reloads on selectedModelId preference change.
- Uses a Mutex to protect engine swaps.
- Exposes StateFlow<EngineState> to bound clients.
- FileObserver on models/ directory detects newly downloaded models.
- Stays alive independently of IME bind/unbind; shuts down only when explicitly stopped.

---

### 3.3 IME and Text Injection

**OutspokeInputMethodService**

- Extends InputMethodService; implements LifecycleOwner and SavedStateRegistryOwner to host Compose.
- Hosts the keyboard UI via ImeComposeView.
- Binds to InferenceService on keyboard show; starts an unload timer (30 s) after keyboard hidden.
- Forwards InputConnection changes to TextInjector and KeyboardViewModel.

**TextInjector**

- Manages all writes to the active InputConnection.
- Keeps the last 6 words (MUTABLE_WORD_COUNT = 6) as a composing (underlined) span via setComposingText; all earlier words are permanently committed.
- On WindowTrimmed: commits composing text minus the last TRIM_TAIL_WORD_COUNT uncertain tail words, clears lastPartial, re-anchors committedWords from actual field content.
- Two-layer alignment recovery (field-scan -> composing-commit fallback) handles complete divergence.
- Delegates new-content discovery to TranscriptAligner.

**TranscriptAligner** (pure / stateless)

- normalizeWord(), splitToWords(), wordsMatch().
- findNewContent(committed, partial) performs a 3-layer overlap search (see Section 8).
- Tolerates Parakeet attention drift and post-trim leading garbage tokens.

---

### 3.4 Settings and Model Lifecycle

**ModelId enum:**

| Value | storageDirName | Status |
|---|---|---|
| PARAKEET_V3 | parakeet-v3 | Active |
| VOXTRAL_MINI | voxtral-mini-4b | Disabled (resource limits) |
| WHISPER_SMALL | whisper-small-int8 | Disabled (resource limits) |

> Warning: storageDirName must never change after release - it is the on-disk key for existing installations.

**ModelRegistry** maps each ModelId to a ModelInfo: display name, DownloadSource, RemoteFile list with URLs and SHA-256 hashes, size estimate. ModelRegistry.all contains only models confirmed to run within acceptable resource limits on real devices.

**ModelStorageManager** stores all files in <filesDir>/models/<storageDirName>/. Checks requiredFiles list for readiness; uses INSTALLED_MARKER file for ZIP-installed models.

**ModelDownloadManager** downloads via OkHttp with resume support, verifies SHA-256 after each file, emits ModelState.Downloading(progress).

**AppPreferences** (DataStore, store name outspoke_prefs): trigger_mode (String, default HOLD), vad_sensitivity (Float, default 0.0), selected_model_id (String), whisper_language (String, default "auto"), postprocessing_enabled (Boolean, default true), show_pipeline_diagnostics (Boolean, default false), keyboard_tutorial_shown (Boolean, default false), forced_language (String?, default null), format_numbers_as_digits (Boolean, default true), suggestion_bar_enabled (Boolean, default false), suggestion_bar_languages (String, comma-separated BCP-47 tags, default ""), suggestion_bar_dismissed (Boolean, default false).

---

### 3.5 UI Layer

**KeyboardViewModel** bridges IME lifecycle, AudioCaptureManager, and InferenceRepository results into KeyboardUiState. Owns captureJob - the coroutine driving audio capture for a recording session.

**KeyboardUiState** (sealed class):

| State | Meaning |
|---|---|
| Idle | Keyboard visible, not recording |
| Listening | Mic open, VAD active |
| Processing(partial) | Partial transcript available |
| Transcribing | Final inference running |
| EngineLoading(reason) | Engine is being loaded |
| Error(reason, detail) | Unrecoverable error |

---

## 4. Data Flow End to End

    Microphone (PCM-16, 16 kHz, 480-sample chunks)
        |
    AudioCaptureManager  [peak-envelope normalisation]
        |  AudioChunk (float32)
    SileroVadFilter / RMSVadFilter  [silence suppression]
        |  AudioChunk (speech only)
    InferenceRepository  [rolling window, fires every 1 s once >=2 s buffered]
        |
    SpeechEngine.transcribe()  [ParakeetEngine: nemo128 -> encoder -> decoder/joint]
        |  raw transcript string
    cleanTranscript()  [8-step post-processing]
        |  TranscriptResult (Partial | Final | WindowTrimmed | Failure)
    KeyboardViewModel
        |-- KeyboardUiState --> KeyboardScreen (Compose)
        +-- TextInjector
                |  TranscriptAligner.findNewContent()
            InputConnection (setComposingText / commitText)
                |
            Active App Text Field

---

## 5. Key Interfaces and Sealed Classes

### TranscriptResult (sealed class)

| Variant | Fields | TextInjector action |
|---|---|---|
| Partial(text) | text: String | setComposingText - keep last 6 words mutable |
| Final(text, isUtteranceBoundary) | isUtteranceBoundary: Boolean | commitText; when isUtteranceBoundary=true do NOT stop capture |
| WindowTrimmed(stableWords) | stableWords: List<String> | Call resetAfterTrim(stableWords) immediately - skipping causes silent word drops |
| Failure(exception) | exception: Throwable | Log; surface to user via KeyboardUiState.Error |

### EngineState (sealed class)

Unloaded | Loading | Ready | Error(message: String)

### ModelState (sealed class)

NotDownloaded | Downloading(progress: Float) | Ready | Corrupted

### PipelineDiagnostics

Counters: windowTrims, alignmentRecoveries, blanksDiscarded.
summary() returns e.g. "2T . 1R . 3B" or "OK" when all zero.

---

## 6. Sliding-Window Inference Details

Key constants in InferenceRepository:

| Constant | Value | Purpose |
|---|---|---|
| MIN_SAMPLES | 2 s | Minimum window before first partial fires |
| STRIDE_SAMPLES | 1 s | New inference every second of new audio |
| MAX_WINDOW_SAMPLES | 30 s | Hard ceiling |
| STABLE_STRIDES | 3 | Consecutive strides that must agree before audio trim |
| TRIGGER_WINDOW_SAMPLES | 6 s | Trim logic activates above this size |
| MIN_CONTEXT_SAMPLES | 4 s | Tail context kept after a stable-chunk trim |
| FORCE_TRIM_WINDOW_SAMPLES | 12 s | Aggressive trim when strides diverge |
| SILENCE_TRIM_STRIDES | 2 | Consecutive blank strides before proactive trim |

**Trim triggers** (all emit TranscriptResult.WindowTrimmed):

1. Stable-prefix trim: last 3 partials share a common leading-word prefix -> trim audio corresponding to stable words, retain MIN_CONTEXT_SAMPLES tail.
2. Silence trim: 2 consecutive blank strides -> proactive trim regardless of prefix agreement.
3. Force trim: window > FORCE_TRIM_WINDOW_SAMPLES with no stable prefix -> unconditional trim.

**Mid-session final**: when isSilenceBoundary is set on an AudioChunk, the repository emits Final(isUtteranceBoundary = true) without stopping capture (useful for long continuous dictation).

**Recording-stop final**: one final inference runs over the remaining window when the audio flow completes. Clips shorter than 1.25 s are zero-padded to give the encoder sufficient frames.

---

## 7. Post-Processing Pipeline

Applied to every raw transcript string before wrapping in a TranscriptResult:

| Step | Operation |
|---|---|
| 1 | Filler-word removal (um, uh, hmm, etc.) |
| 2 | Stutter collapse (>=3x consecutive word repeats -> single instance) |
| 3 | Phrase-loop deduplication (repeated phrase sequences) |
| 3.5 | Spurious-period removal (`filterSpuriousPeriods`): strips mid-utterance periods produced by Parakeet TDT on prosodic pauses. A period is removed when it is **not** the final token AND either (a) the preceding word is a conjunction/preposition/article/determiner in `NON_SENTENCE_CLOSING_WORDS`, or (b) the sentence segment before the period is fewer than 5 words. Must run before step 8 (capitalisation) to prevent false sentence-start capitalisation. |
| 4 | Leading-dot strip (LEADING_DOTS_RE) |
| 5 | Leading-punctuation strip (LEADING_PUNCT_RE: ^[,;]+) |
| 6 | Multi-dot normalisation (two or more consecutive dots -> single dot) |
| 7 | Missing sentence-space repair (punctuation followed by capital letter without space) |
| 8 | Sentence-boundary capitalisation |

---

## 8. Text Injection and Alignment

### Composing span management (TextInjector)

    [... permanently committed words ...] [last 6 words -- composing span (underlined)]
                                           ^
                                           MUTABLE_WORD_COUNT boundary

- Every Partial: TranscriptAligner.findNewContent identifies genuinely new tokens; setComposingText updates the composing span.
- Every Final: finishComposingText + commitText flushes the composing span.
- Every WindowTrimmed: resetAfterTrim(stableWords) must be called immediately.
  - Commits all composing text except the last TRIM_TAIL_WORD_COUNT uncertain tail words.
  - Clears lastPartial.
  - Re-reads committedWords from the actual field content to re-anchor alignment.

### Three-layer overlap search (TranscriptAligner.findNewContent)

1. Full prefix match: check if partial starts with all committed words -> fast path, emit only the suffix.
2. Suffix-prefix overlap >=2 words: find the longest suffix of committed words that is a prefix of the new partial.
3. Interior scan >=2 words: slide a window across the partial looking for a matching subsequence.

If all three layers fail, the entire partial is returned as new content (alignment recovery, counted in PipelineDiagnostics.alignmentRecoveries).

---

## 9. Model Registry and Download

### Storage layout

    <filesDir>/models/
      parakeet-v3/
        nemo128.onnx
        encoder-model.int8.onnx
        decoder_joint-model.int8.onnx
        config.json
        vocab.txt
      voxtral-mini-4b/    (placeholder - disabled)
      whisper-small-int8/ (placeholder - disabled)

    <filesDir>/suggestion_files/
      en/
        dict_en.txt
        lm_en.arpa
      de/
        dict_de.txt
        lm_de.arpa
      (one subdirectory per downloaded language)

### Download flow

1. ModelDownloadManager fetches each RemoteFile via OkHttp.
2. Supports HTTP range requests for resume.
3. SHA-256 verified after each file; on mismatch the file is deleted and download fails with ModelState.Corrupted.
4. ZIP archives are extracted in place; INSTALLED_MARKER is written on success.
5. ModelStorageManager.isReady(modelId) checks all requiredFiles exist (and the marker for ZIP installs).

---

## 10. Engine Implementations

### ParakeetEngine (active)

NVIDIA Parakeet-TDT 0.6B v3, INT8 quantized, ~700 MB on disk.

3-ONNX pipeline:

| Session | File | Input -> Output |
|---|---|---|
| Preprocessor | nemo128.onnx | Raw PCM float32 -> 128-dim log-mel spectrogram |
| Encoder | encoder-model.int8.onnx | Spectrogram -> [B, 1024, T_enc] encoder features |
| Decoder/Joint | decoder_joint-model.int8.onnx | Encoder features + LSTM state -> [B, T_enc, T_tgt, 8198] logits |

- Tokenizer: SentencePiece-like vocabulary from vocab.json (1024 tokens + blank).
- Decoding: greedy TDT (Token-and-Duration Transducer). LSTM state is carried across strides.
- Logit tensor: first 1025 entries = token probabilities (vocab 1024 + blank); remainder = duration predictions. Only the greedy argmax is currently consumed.

### WhisperEngine (disabled - resource limits)

Targets whisper-large-v3-turbo INT8. Files: encoder_model_int8.onnx, decoder_model_merged_int8.onnx, tokenizer.json. 128 mel bins, 30 s window, 3000 frames. Merged decoder with use_cache_branch flag (autoregressive decoding with KV-cache).

### VoxtralEngine (disabled - resource limits)

Voxtral-Mini-4B-Realtime ONNX (~4 GB RAM requirement). Same Whisper-compatible log-mel preprocessing. Mistral decoder architecture with KV-cache.

---

## 11. Service and Lifecycle Management

### Binding lifecycle

    OutspokeInputMethodService.onCreateInputView()
        -> bindService(InferenceService)

    OutspokeInputMethodService.onWindowHidden()
        -> starts 30 s unload timer
           (cancels if keyboard re-shown within 30 s)

    Timer fires
        -> stopService(InferenceService)

Unbinding alone does NOT stop InferenceService. The 30 s timer is the only stop path during normal use.

### Engine reload on model change

    AppPreferences.selectedModelId (DataStore Flow)
        -> InferenceService observer
               -> acquire Mutex
               -> close old SpeechEngine
               -> SpeechEngineFactory.create(newModelId)
               -> engine.load(modelDir)
               -> emit EngineState.Ready
               -> release Mutex

### FileObserver integration

InferenceService watches <filesDir>/models/ for CLOSE_WRITE / MOVED_TO events. When a new model finishes downloading, the observer triggers a reload check without requiring an app restart.

---

## 12. State Machines

### Engine state

    Unloaded --load()--> Loading --success--> Ready
                             |                  |
                             +--failure--> Error(message)
                                                |
                                           retry load()--> Loading

### Model download state

    NotDownloaded --start--> Downloading(0..1) --complete--> Ready
                                  |                   |
                             cancel/error        SHA-256 fail
                                  v                   v
                            NotDownloaded        Corrupted --retry--> Downloading

### Keyboard UI state

    Idle --tap mic--> Listening --audio buffered--> Processing(partial)
     ^                    |                               |
     |              release mic                    recording stop
     |                    v                               v
     +--------------- Transcribing <---------- (final inference)
                           |
                      inject text
                           v
                         Idle

---

## 13. Build Configuration and ABI Splits

- compileSdk 36, minSdk 30 (Android 11+), targetSdk 36
- versionCode 8, versionName 0.2.3
- Kotlin 2.3.20, AGP 8.13.2, JVM target 11
- buildFeatures: compose = true, buildConfig = true
- Release: isMinifyEnabled = true, isShrinkResources = true, ProGuard enabled

**ABI splits:**

| ABI | Code offset | versionCode formula |
|---|---|---|
| armeabi-v7a | x1 | defaultVersionCode * 10 + 1 |
| arm64-v8a | x2 | defaultVersionCode * 10 + 2 |
| universal | x0 | defaultVersionCode * 10 + 0 |

dependenciesInfo { includeInApk = false; includeInBundle = false } - required for IzzyOnDroid / F-Droid reproducibility.

Repositories: Google, MavenCentral, Gradle Plugin Portal only.

---

## 13.5 Word-Correction Subsystem

Introduced in v0.2.0. Opt-in; disabled by default (`suggestion_bar_enabled = false`).

### Overview

After each dictation commit, a `SuggestionBar` chip row animates into view above the keyboard. Tapping a word in the transcription (tracked as `WordAtCursor` in `KeyboardUiState`) triggers a candidate query. Up to 5 ranked candidates are shown; tapping one replaces the word in the text field.

### Language Packs

Each supported language requires two files, stored in `<filesDir>/suggestion_files/<tag>/`:

| File | Approximate size | Purpose |
|---|---|---|
| `dict_<tag>.txt` | ~2 MB | Frequency-sorted word list (`word\tlog10_freq` per line) |
| `lm_<tag>.arpa` | ~6 MB | Bigram ARPA language model for re-ranking |

Files are downloaded on demand from `https://github.com/minburg/outspoke-data/releases/download/v2` — **the only external URL used at runtime besides the Hugging Face model download**. The URL is pinned to a specific release tag in `SuggestionFileManager.BASE_URL`; bump the tag there (and in the `outspoke-data` repository) whenever file format or content changes.

Supported languages (`SuggestionLanguage` enum):

| Tag | Language |
|---|---|
| `bg` | Bulgarian |
| `hr` | Croatian |
| `cs` | Czech |
| `da` | Danish |
| `nl` | Dutch |
| `en` | English |
| `et` | Estonian |
| `fi` | Finnish |
| `fr` | French |
| `de` | German |
| `el` | Greek |
| `hu` | Hungarian |
| `it` | Italian |
| `lv` | Latvian |
| `lt` | Lithuanian |
| `mt` | Maltese |
| `pl` | Polish |
| `pt` | Portuguese |
| `ro` | Romanian |
| `ru` | Russian |
| `sk` | Slovak |
| `sl` | Slovenian |
| `es` | Spanish |
| `sv` | Swedish |
| `uk` | Ukrainian |

### Correction Pipeline

    WordSuggestionProvider.getSuggestions(word, sentenceContext)
        |
        for each active language tag:
            WordCorrector.correct(word, leftContext)
                |
                CandidateGenerator.getCandidates(word)
                    1. Phonetic index lookup (Kölner Phonetik for "de", Double Metaphone otherwise)
                    2. Damerau-Levenshtein sweep (distance ≤ 2, ±3 char length filter)
                    → up to 50 candidates ranked by corpus log-frequency
                |
                ArpaLanguageModel.scoreInContext(candidate, leftContext)
                    → bigram log-probability for context-aware re-ranking
                |
                combined score = 0.7 * lmScore + 0.3 * freqScore
        |
        merge candidates across languages (best score wins on duplicate)
        → top 5 delivered on main thread via onSuggestions callback

### Key classes

| Class | Responsibility |
|---|---|
| `WordSuggestionProvider` | Public façade; manages `WordCorrector` instances per language; owns background `CoroutineScope` |
| `WordCorrector` | Combines `CandidateGenerator` + `ArpaLanguageModel` for one language |
| `CandidateGenerator` | Loads dict file; phonetic index + edit-distance sweep |
| `ArpaLanguageModel` | Loads ARPA file; bigram log-prob lookup |
| `SuggestionFileManager` | On-disk path constants; `isLanguageReady()` check |
| `SuggestionFileDownloader` | OkHttp download with range-resume + SHA-256 verification; emits `SuggestionDownloadState` flow |
| `SuggestionBar` | Compose chip row; animated appear/disappear (300 ms ease-out / ease-in) |

### Invariants

- No data leaves the device once files are downloaded.
- `WordSuggestionProvider` loads only the languages explicitly selected by the user via `AppPreferences.suggestionBarLanguages`.
- Downloads come **only** from `github.com/minburg/outspoke-data`. Do not add other external hosts.
- The feature is a strict no-op (no background work, no memory overhead) when `suggestionBarEnabled = false`.

---

## 14. Testing Strategy

All unit tests in app/src/test/kotlin/dev/brgr/outspoke/.
testOptions { unitTests.isReturnDefaultValues = true }.

| Test file | What it covers |
|---|---|
| audio/AudioChunkTest | AudioChunk data class, normalisation |
| audio/RMSVadFilterTest | Energy VAD onset/hangover logic |
| ime/TranscriptAlignerTest | All 3 layers of findNewContent |
| ime/TextInjectorTest | Composing span management, trim resets |
| ime/FakeInputConnection | Test double for InputConnection |
| inference/InferenceRepositoryTest | Sliding window, trim triggers |
| inference/InferenceRepositoryPipelineTest | Full pipeline integration |
| inference/HumanSpeechPipelineTest | Representative speech scenarios |
| inference/CleanTranscriptTest | All 8 post-processing steps |
| inference/CollapsePhrasesTest | Phrase-loop deduplication |
| inference/CollapseStuttersTest | Stutter collapse |
| inference/TranscriptResultTest | TranscriptResult sealed class |
| inference/FakeSpeechEngine | Controllable SpeechEngine test double |
| e2e/GoldenPathTest | End-to-end happy path |
| ExampleUnitTest | Placeholder |

Instrumented tests (device/emulator required) in app/src/androidTest/, run with ./gradlew connectedAndroidTest.

---

## 15. Extension Guide: Adding a New Engine

1. **ModelId**: add a new enum value with a stable storageDirName. Never change existing values.
2. **ModelRegistry**: add a ModelInfo (display name, DownloadSource, RemoteFile list with SHA-256 hashes, size estimate). Add to ModelRegistry.all only when confirmed to run within acceptable resource limits on real devices.
3. **New engine class** under inference/: implement SpeechEngine.
4. **SpeechEngineFactory**: add a branch for the new ModelId.

No changes required in InferenceRepository, InferenceService, TextInjector, or any IME class.

---

## 16. Key Conventions and Invariants

| Rule | Details |
|---|---|
| No field injection | Constructor injection only throughout |
| val over var | Avoid nullable types unless genuinely optional |
| No external telemetry | No analytics, crash reporters, or SDKs that phone home |
| Model storage | <filesDir>/models/<storageDirName>/ - no external storage permission |
| SHA-256 verification | Required for all downloaded model files; add hashes to RemoteFile entries |
| storageDirName immutability | Changing it breaks existing installations |
| WindowTrimmed handling | TextInjector.resetAfterTrim(stableWords) must be called immediately on every WindowTrimmed event - skipping causes silent word drops on every stride after a window trim |
| Final(isUtteranceBoundary=true) | Do NOT stop audio capture - used for mid-session sentence boundaries in long dictation |
| InferenceService stop | Unbinding does not stop the service; shuts down only when explicitly stopped or after the 30 s keyboard-hidden timer |
| Kotlin code style | kotlin.code.style=official |
