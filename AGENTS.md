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
| `inference` | `SpeechEngine`, `ParakeetEngine`, `WhisperEngine`, `VoxtralEngine`, `InferenceRepository`, `InferenceService`, `SpeechEngineFactory`, `TranscriptResult`, `EngineState`, `PipelineDiagnostics`, `NumberNormaliser`, `GrammarCorrector` | ASR pipeline, sliding window, post-processing, foreground service |
| `audio` | `AudioCaptureManager`, `SileroVadFilter`, `RMSVadFilter`, `VadFilter`, `AudioChunk`, `PermissionHelper` | Mic capture + VAD |
| `ime` | `OutspokeInputMethodService`, `TextInjector`, `TranscriptAligner`, `EnterAction`, `WordSuggestionProvider` | Keyboard / text insertion |
| `ime/correction` | `WordCorrector`, `CandidateGenerator`, `ArpaLanguageModel`, `SuggestionFileDownloader`, `SuggestionFileManager`, `SuggestionLanguage`, `DoubleMetaphone`, `KolnerPhonetik`, `EditDistance` | On-device word correction + language pack management |
| `settings/model` | `ModelRegistry`, `ModelId`, `ModelDownloadManager`, `ModelStorageManager`, `ModelState`, `ModelViewModel`, `DownloadService` | Model lifecycle |
| `settings/preferences` | `AppPreferences`, `PreferencesViewModel` | DataStore-backed user preferences |
| `settings/screens` | `HomeScreen`, `ModelScreen`, `PreferencesScreen` | Settings Compose UI |
| `ui/keyboard` | `KeyboardViewModel`, `KeyboardUiState`, `KeyboardScreen`, `ImeComposeView`, `WordAtCursor` | IME Compose hosting, UI state |
| `ui/keyboard/components` | `TalkButton`, `SuggestionBar`, `WaveformBar`, `StatusIndicator`, `KeyboardActionButton`, `KeyboardTutorialOverlay`, `LanguageSelector` | Keyboard UI sub-components |
| `ui/theme` | `OutspokeKeyboardTheme` | Compose theming |

## Architecture — What Isn't Obvious from Single Files

**`InferenceService` is a foreground `LifecycleService`** that the IME binds to. The engine stays alive across keyboard hide/show cycles. Unbinding does not stop the service — it shuts down only when explicitly stopped.

**`SpeechEngine` is the only seam for adding a new model.** Implement `load`, `transcribe`, `close`, `setLanguage`, `setLanguageFilter`, register a `ModelId` enum value and a `ModelInfo` in `ModelRegistry`, add a branch in `SpeechEngineFactory`. Nothing in the IME or repository layer needs to change.

**`TranscriptResult` is a sealed class** with four variants that flow from `InferenceRepository` to `TextInjector`:
- `Partial` — show as composing (underlined) text, keep last 6 words mutable
- `Final` — commit text; when `isUtteranceBoundary = true`, do NOT stop capture
- `WindowTrimmed` — call `TextInjector.resetAfterTrim(stableWords)` immediately; skipping this causes silent word drops on every stride after a window trim
- `Failure` — log, surface to user

**`TextInjector` maintains a composing span** of the last 6 words via `InputConnection.setComposingText`. Words before that are permanently committed. `TranscriptAligner.findNewContent` does a three-layer overlap search (full prefix → suffix-prefix ≥ 2 words → interior scan) to locate genuinely new tokens in each partial.

**`InferenceRepository` sliding window:** partials fire every ~1 s once ≥ 2 s of audio is buffered; hard ceiling 30 s. Stable-prefix trims, silence-trims (2 blank strides), and force-trims (window > 12 s, no stable prefix) all emit `WindowTrimmed`. Every raw transcript passes an 8-step post-processing pipeline (filler removal → stutter collapse → phrase dedup → spurious-period removal → leading-dot strip → leading-punct strip → multi-dot normalisation → missing sentence-space repair → sentence-boundary capitalisation) before emission.

**VAD is dual-layer:** `SileroVadFilter` (Silero v4 ONNX) is primary; `RMSVadFilter` (energy threshold) is the automatic fallback if the ONNX VAD model fails to load.

**Word-suggestion / correction feature (new in v0.2.0):**
- `SuggestionBar` (keyboard UI) appears after a dictation commit and shows up to 5 correction candidates for the word under the cursor.
- Language packs (dictionary + ARPA language model) are downloaded on demand from `https://github.com/minburg/outspoke-data` — the only external URL used at runtime beyond the one-time ASR model download from Hugging Face. Files are pinned to a specific release tag to prevent silent breakage.
- Supported languages: Bulgarian, Croatian, Czech, Danish, Dutch, English, Estonian, Finnish, French, German, Greek, Hungarian, Italian, Latvian, Lithuanian, Maltese, Polish, Portuguese, Romanian, Russian, Slovak, Slovenian, Spanish, Swedish, Ukrainian (`SuggestionLanguage` enum).
- `SuggestionFileManager` stores packs in `<filesDir>/suggestion_files/<tag>/`; `SuggestionFileDownloader` handles resumable HTTP downloads with SHA-256 verification.
- `WordCorrector` orchestrates the pipeline: `CandidateGenerator` (phonetic index via Kölner Phonetik for DE, Double Metaphone for all others + Damerau-Levenshtein edit distance) → `ArpaLanguageModel` (bigram ARPA LM) → combined frequency + LM score.
- `WordSuggestionProvider` is the public façade used by the IME; it loads only languages selected by the user and delivers results on the main thread.
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

### 6. Create the GitHub Release

1. Go to **Releases → Draft a new release** on GitHub.
2. Select the tag you just pushed (`v0.x.y`).
3. Set the release title to `v0.x.y`.
4. Paste the changelog text (from the `.txt` file above) into the description.
5. Build the release APKs locally:
   ```bash
   ./gradlew assembleRelease
   # APKs appear in app/build/outputs/apk/release/
   ```
6. Attach all three APK files: `arm64-v8a`, `armeabi-v7a`, and `universal`.
7. Publish the release.

### 7. IzzyOnDroid picks it up automatically

IzzyOnDroid polls GitHub Releases for new tags. Once the release is published, it will appear in the IzzyOnDroid repo on the next scan (usually within 24 hours). No manual submission is needed.

### Version numbering conventions

| Segment | Rule |
|---|---|
| `versionCode` | Increment by 1 for every release, no exceptions. Never reuse or skip. |
| `versionName` | `0.MAJOR.PATCH` — bump PATCH for fixes and small features, bump MAJOR for large feature additions. |
| Git tag | Always `v` + `versionName` (e.g. `v0.2.3`). Must match exactly. |
| Fastlane file | Always plain `versionCode` integer (e.g. `8.txt`). |

