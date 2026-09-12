# Outspoke — Agent Guide

Android IME (keyboard) that does on-device speech-to-text via ONNX Runtime. No cloud, no Google Play Services.

## Build & Test

```bash
./gradlew assembleDebug          # APK → app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease        # minified + resource-shrunk release APK
./gradlew test                   # JVM unit tests (app/src/test/)
./gradlew connectedAndroidTest   # instrumented tests (requires device/emulator)
```

Target SDK 36, min SDK 30, JDK 11, Kotlin official code style (`kotlin.code.style=official`).

## Package Structure

All source lives under `app/src/main/kotlin/` (package root `dev.brgr.outspoke`):

| Package | Key files | Responsibility |
|---|---|---|
|`inference`|`SpeechEngine`, `ParakeetEngine`, `ChunkStreamingEngine`, `WhisperEngine`, `VoxtralEngine`, `InferenceRepository`, `InferenceService`, `SpeechEngineFactory`, `TranscriptResult`, `EngineState`, `PipelineDiagnostics`, `NumberNormaliser`, `GrammarCorrector`, `AcousticCandidates`|ASR pipeline, sliding window, post-processing, foreground service, acoustic word-alternative capture|
| `audio` | `AudioCaptureManager`, `MicCalibrationManager`, `SileroVadFilter`, `RMSVadFilter`, `VadFilter`, `AudioChunk`, `PermissionHelper` | Mic capture + VAD + optional mic calibration |
| `ime` | `OutspokeInputMethodService`, `TextInjector`, `TranscriptAligner`, `EnterAction`, `WordSuggestionProvider` | Keyboard / text insertion |
| `ime/correction` | `WordCorrector`, `CandidateGenerator`, `ArpaLanguageModel`, `SuggestionFileDownloader`, `SuggestionFileManager`, `SuggestionLanguage`, `DoubleMetaphone`, `KolnerPhonetik`, `EditDistance` | On-device word correction + language pack management |
| `settings/model` | `ModelRegistry`, `ModelId`, `ModelDownloadManager`, `ModelStorageManager`, `ModelState`, `ModelViewModel`, `DownloadService` | Model lifecycle |
| `settings/preferences` | `AppPreferences`, `PreferencesViewModel` | DataStore-backed user preferences |
| `settings/screens` | `HomeScreen`, `ModelScreen`, `PreferencesScreen`, `MicCalibrationScreen` | Settings Compose UI |
| `ui/keyboard` | `KeyboardViewModel`, `KeyboardUiState`, `KeyboardScreen`, `ImeComposeView`, `WordAtCursor` | IME Compose hosting, UI state |
| `ui/keyboard/components` | `TalkButton`, `SuggestionBar`, `WaveformBar`, `StatusIndicator`, `KeyboardActionButton`, `KeyboardTutorialOverlay`, `LanguageSelector` | Keyboard UI sub-components |
| `ui/theme` | `OutspokeKeyboardTheme` | Compose theming |

## Architecture — What Isn't Obvious from Single Files

**`InferenceService` is a foreground `LifecycleService`** that the IME binds to. The engine stays alive across keyboard hide/show cycles. The IME starts it when shown, so unbinding does not stop it. The selected-model observer uses distinct IDs to avoid reloading on unrelated preference edits. Repository sessions serialize engine use and teardown; memory-pressure cleanup skips active recording and final decoding.

**`SpeechEngine` is the only seam for adding a new model.** Implement `load`, `transcribe`, `close`, `setLanguage`, `setLanguageFilter`, register a `ModelId` enum value and a `ModelInfo` in `ModelRegistry`, add a branch in `SpeechEngineFactory`. Nothing in the IME or repository layer needs to change.

**`TranscriptResult` is a sealed class** with four variants that flow from `InferenceRepository` to `TextInjector`:
- `Partial` — show as composing (underlined) text, keep last 6 words mutable
- `Final` — commit text; when `isUtteranceBoundary = true`, do NOT stop capture
- `WindowTrimmed` — call `TextInjector.resetAfterTrim(stableWords)` immediately; skipping this causes silent word drops on every stride after a window trim
- `Failure` — log, surface to user

**`TextInjector` maintains a composing span** of the last 6 words via `InputConnection.setComposingText`. Words before that are permanently committed. `TranscriptAligner.findNewContent` does a three-layer overlap search (full prefix → suffix-prefix ≥ 2 words → interior scan) to locate genuinely new tokens in each partial.

**`InferenceRepository` sliding window:** partials fire every ~1 s once ≥ 2 s of audio is buffered; hard ceiling 30 s. Stable-prefix trims, silence-trims (2 blank strides), and force-trims (window > 12 s, no stable prefix) all emit `WindowTrimmed`. Every raw transcript passes an 8-step post-processing pipeline (filler removal → stutter collapse → phrase dedup → spurious-period removal → leading-dot strip → leading-punct strip → multi-dot normalisation → missing sentence-space repair → sentence-boundary capitalisation) before emission. **Short utterances** (< 2.5 s) use a decode-context retry at final flush: the TDT decoder is extremely context-sensitive on short clips, so the primary attempt re-decodes from frame 0 with 600 ms of lead silence prepended, and a low-confidence/blank result retries once without the lead; the best non-empty attempt wins, and a "Low confidence" failure is emitted only when a non-blank result fails the 0.55 gate in every context.

**Parakeet uses the chunked-TDT streaming path (active since v0.3.0):** audio is buffered and decoded in 2 s chunks with the TDT decoder's LSTM state carried across chunks (`ChunkStreamingEngine` — the capability interface `ParakeetEngine` implements; non-Parakeet engines fall back to the legacy sliding-window path above). Every chunk re-runs the post-processing pipeline over the entire accumulated utterance, so the per-chunk cost must stay bounded: `collapseRepeatedPhrases` (phrase dedup) caps candidate phrase length at 8 words to keep the scan near-linear (v0.3.1) — an unbounded scan grows cubic in utterance length and breaks the streaming path's real-time budget within a few minutes of dictation.

**VAD is dual-layer:** `SileroVadFilter` (Silero v4 ONNX) is primary; `RMSVadFilter` (energy threshold) is the automatic fallback if the ONNX VAD model fails to load.

**Word-suggestion / correction feature (new in v0.2.0):**
- `SuggestionBar` (keyboard UI) appears after a dictation commit and shows up to 5 correction candidates for the word under the cursor.
- Language packs (dictionary + ARPA language model) are downloaded on demand from `https://github.com/minburg/outspoke-data` — the only external URL used at runtime beyond the one-time ASR model download from Hugging Face. Files are pinned to a specific release tag to prevent silent breakage.
- Supported languages: Bulgarian, Croatian, Czech, Danish, Dutch, English, Estonian, Finnish, French, German, Greek, Hungarian, Italian, Latvian, Lithuanian, Maltese, Polish, Portuguese, Romanian, Russian, Slovak, Slovenian, Spanish, Swedish, Ukrainian (`SuggestionLanguage` enum).
- `SuggestionFileManager` stores packs in `<filesDir>/suggestion_files/<tag>/`; `SuggestionFileDownloader` handles resumable HTTP downloads with SHA-256 verification.
- **Acoustic-first candidates:** at decode time `ParakeetEngine` captures the top-3 runner-up tokens per emission (the logits are already in memory) and, for low-confidence words (< 0.6, capped at 3 per chunk), runs a bounded local beam over the word's frame range. `InferenceRepository` segments emissions into words and writes the per-word alternatives (word + length-normalised acoustic log-prob) to a bounded, per-session `AcousticCandidateCache` (`inference/AcousticCandidates.kt`).
- `WordCorrector` rescores the acoustic alternatives in the log domain: `score = acousticLogProb + λ·ln(10)·lmLog10(candidate | context)` (λ = 0.5; the acoustic term dominates). Because the TDT decoder is lexicon-unconstrained (its beam can emit acoustically plausible strings that are not words), acoustic alternatives are first **filtered to genuine dictionary words** (case-insensitive, deduplicated case-insensitively — `CandidateGenerator.isKnownWord`). The dictionary (`CandidateGenerator`: phonetic index via Kölner Phonetik for DE / Double Metaphone otherwise + Damerau-Levenshtein edit distance) is a low-prior fallback (fixed prior -2.0) when a word has no usable acoustic evidence (none, or all filtered out as non-words). `ArpaLanguageModel` scores with standard ARPA backoff (trigram → bigram → unigram, raw log10).
- `WordSuggestionProvider` is the public façade used by the IME; its `acousticLookup` is wired to the `InferenceRepository` by `OutspokeInputMethodService`; it loads only languages selected by the user and delivers results on the main thread.
- The feature is **opt-in** — disabled by default (`suggestionBarEnabled = false`). No data leaves the device once files are downloaded.

## Adding a New Model

1. Add a `ModelId` enum value with a stable `storageDirName` (changing it breaks existing installs).
2. Add a `ModelInfo` + private val in `ModelRegistry` — only add it to `ModelRegistry.all` when the engine works on-device.
3. Implement `SpeechEngine` in a new class under `inference/`.
4. Add a branch in `SpeechEngineFactory`.

`VOXTRAL_MINI` and `WHISPER_SMALL` exist in the registry but are commented out of `ModelRegistry.all` — they couldn't run within acceptable resource limits on real devices.

## Key Conventions

- **No field injection** — constructor injection only.
- **`val` over `var`**; avoid nullable types unless genuinely optional.
- **`application.yml`** is not used (Android project) — preferences go through `DataStore` (`settings/preferences/`).
- **Model files** are stored in `<filesDir>/models/<storageDirName>/` — no external storage permission.
- **Suggestion files** are stored in `<filesDir>/suggestion_files/<tag>/` — same constraint.
- **SHA-256** is verified after each file download; add hashes to `RemoteFile` entries whenever available.
- ABI splits produce per-ABI APKs: `armeabi-v7a` (×1), `arm64-v8a` (×2), universal (×0 offset). `versionCode = defaultVersionCode * 10 + abiOffset`.
- `dependenciesInfo` is disabled in the APK for F-Droid / IzzyOnDroid reproducibility.
- Do not add analytics, crash reporters, or any SDK that phones home.
- External file downloads (suggestion packs) come **only** from `github.com/minburg/outspoke-data`. Do not add other external hosts.

## Release Process

Complete checklist for publishing a new version to GitHub Releases and IzzyOnDroid.

### 1. Bump the version

Edit `app/build.gradle.kts`:

```kotlin
versionCode = <previous + 1>      // integer; IzzyOnDroid uses this to detect updates
versionName = "0.x.y"             // shown to users; must match the git tag (without "v")
```

The ABI-split `versionCode` formula is `defaultVersionCode * 10 + abiOffset` (handled automatically by the build script — only edit `defaultVersionCode` here).

Also update `how-to-release.txt` — change the tag command to use the new version:

```
git tag v0.x.y && git push origin v0.x.y
```

Also update `metadata/dev.brgr.outspoke.yml`:

- Set `CurrentVersion` to the new `versionName`.
- Set `CurrentVersionCode` to the new `versionCode`.
- Append a new entry to the `Builds:` list:

```yaml
  - versionName: '0.x.y'
    versionCode: <N>
    commit: v0.x.y
    subdir: app
    gradle:
      - release
```

### 2. Write the fastlane changelog

Create `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`.

- File name is the plain integer `versionCode` (e.g. `8.txt` for versionCode 8).
- First line: `Nth patch (vX.Y.Z).` — keep phrasing consistent with previous entries.
- Blank line, then a plain-English description of what changed. Focus on user-visible changes; skip internal refactors unless they fix something the user would notice.
- Keep it under ~500 characters — IzzyOnDroid truncates long changelogs.

### 3. Update documentation

Review and update these files so they reflect the new state of the codebase:

- **`AGENTS.md`** — package table, architecture notes, supported language lists.
- **`README.md`** — features list, requirements, permissions table, privacy section.
- **`docs/architecture.md`** — package table, AppPreferences schema, version line in §13, any new subsystems.

Only update what actually changed; do not rewrite sections that are still accurate.

### 4. Commit everything

Stage and commit all changed files together in one commit:

```bash
git add app/build.gradle.kts \
        how-to-release.txt \
        metadata/dev.brgr.outspoke.yml \
        fastlane/metadata/android/en-US/changelogs/<versionCode>.txt \
        AGENTS.md README.md docs/architecture.md \
        # …any other changed source files
git commit -m "release v0.x.y"
git push
```

### 5. Tag the release

The tag must match `versionName` from `app/build.gradle.kts` with a `v` prefix:

```bash
git tag v0.x.y
git push origin v0.x.y
```

### 6. The GitHub Release is created automatically

Pushing the tag triggers the release job in `.github/workflows/release-f-droid.yml`: it decodes the release keystore from repo secrets (never in the source tree), builds the signed release APKs, renames them to `outspoke-<version>.apk` / `outspoke-<version>-<abi>.apk`, writes a `.sha256` checksum next to each, and creates the GitHub Release with all of them attached (release notes are auto-generated from the commits since the previous tag).

Watch the workflow run on the tag push and confirm it ends green. No local build or manual APK attachment is needed.

### 7. IzzyOnDroid picks it up automatically

IzzyOnDroid polls GitHub Releases for new tags. Once the release is published, it will appear in the IzzyOnDroid repo on the next scan (usually within 24 hours). No manual submission is needed.

### Version numbering conventions

| Segment | Rule |
|---|---|
| `versionCode` | Increment by 1 for every release, no exceptions. Never reuse or skip. |
| `versionName` | `0.MAJOR.PATCH` — bump PATCH for fixes and small features, bump MAJOR for large feature additions. |
| Git tag | Always `v` + `versionName` (e.g. `v0.2.3`). Must match exactly. |
| Fastlane file | Always plain `versionCode` integer (e.g. `8.txt`). |

