# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this project is

Codename "Loam": a FOSS, Android-native semantic search and Q&A (RAG) layer over Markdown notes the user already owns (e.g. an Obsidian vault). It reads `.md` files in place via Storage Access Framework — it is explicitly *not* a note editor and must not invent a proprietary storage format.

## Repository state

Implemented. Phases 0–2 are complete and most of Phase 3 (polish/hardening) has shipped, verified on a Pixel 8a against a real 392-note vault. Remaining: the rest of Phase 3, Phase 4 (F-Droid packaging), Phase 5 (optional).

- `README.md` — user-facing overview, measured numbers, build instructions, permission-verification command.
- `loam-architecture-roadmap.md` — the design doc and the authoritative record of what was measured, what went wrong, and why. It deliberately keeps its own mistakes on the page. Read the relevant phase section before changing related code, and record new measurements/decisions there (commit history shows this is the habit: feature commit, then a "Record … in the roadmap" commit).

### Modules (`settings.gradle.kts`)

```
app/     Compose UI (dev.loam.ui), WorkManager indexing (dev.loam.work), home-screen widget (dev.loam.widget), theme
core/    No-UI logic (dev.loam.core): domain/ use cases, vault/ SAF reading + chunking + exclude rules,
         embed/ ONNX embedder + WordPiece tokenizer, search/ brute-force VectorIndex,
         store/ Room + SQLCipher + key handling, llm/ LlmEngine interface
llama/   llama.cpp over JNI (git submodule, pinned) — implements LlmEngine
spike/   Phase 0 throwaway harness — a SEPARATE Gradle build, not part of the main build; don't extend it
models/  Embedding weights (gitignored; see Building)
```

`:core` is split out so the domain logic is testable without an Android runtime and shareable with a possible Phase 5 desktop companion. Keep Android UI out of it. Room schemas are exported to `core/schemas/` (currently versions 1–3) — a schema change needs a migration and a new exported JSON.

## Building and testing

- JDK 17+ required. If there is no host JDK: `export JAVA_HOME=/opt/android-studio/jbr`; also `export ANDROID_HOME=$HOME/Android/Sdk`.
- Clone with `--recurse-submodules`; `llama/llama.cpp` is pinned to the commit all measurements were taken against.
- Embedding model weights are not in the repo. Place `model_qint8_arm64.onnx` and `vocab.txt` (all-MiniLM-L6-v2, Apache-2.0) in `models/all-MiniLM-L6-v2/`; the build fails with a list of what's missing otherwise. The LLM (a GGUF, e.g. Qwen2.5-1.5B-Instruct Q4_0) is sideloaded at runtime via the file picker, never bundled.
- Build: `./gradlew :app:assembleRelease`. JVM unit tests live in `core/src/test` (`./gradlew :core:test`). Instrumented tests (`app/src/androidTest`, `llama/src/androidTest`) need a device or emulator.
- Targets: `minSdk 26`, `compileSdk`/`targetSdk 37`, `arm64-v8a` only, AGP 9.x (built-in Kotlin — do not apply `org.jetbrains.kotlin.android`), version catalog in `gradle/libs.versions.toml`.
- **Timings are only meaningful from a release build.** Instrumented tests run against the debug variant, which has inflated cosine search ~36x and chunking ~40x, and once produced a phantom regression. State which binary produced any number you report.
- Test environments that can do more than production have repeatedly hidden real bugs (APK packaging differs from the test APK; fixtures openable by path where real files need a SAF grant; prompts shorter than real ones). Ask what the real path crosses that the fixture doesn't. Also test state *transitions*, not just states.

## Non-negotiable constraints

These act as acceptance criteria for any work in this repo. Flag violations rather than silently adding them.

1. **Zero network permission — permanently, with no exception.** The APK holds no `INTERNET` permission at all. This supersedes the earlier idea of a one-time in-app model download: the embedder is bundled in the APK and the LLM is sideloaded through the SAF picker. Don't add download code. WorkManager's `ACCESS_NETWORK_STATE` is stripped via `tools:node="remove"`; keep it stripped. The README lists the exact expected `aapt2 dump permissions` output — if you add a dependency, re-check it against the built APK. (The manifest header comment still says INTERNET "enters in Phase 2"; that is stale — the roadmap and README are correct.)
2. **No proprietary storage format.** Notes remain plain `.md` files wherever the user keeps them.
3. **No Google dependencies.** No Play Services, Firebase, or GMS-only APIs. Inference uses ONNX Runtime (Microsoft's) rather than LiteRT for this reason.
4. **Auditable, not a black box.** Every result shows source file, heading breadcrumb and score; every RAG answer lists its sources (shown before generation starts). No telemetry.
5. **F-Droid-distributable.** Every dependency needs a real OSI-approved license. ObjectBox is ruled out (non-OSI native engine license).

## Architecture and key decisions

```
Compose UI  ──  Search · Ask · Settings · sources panel · reindex status · widget · share/selection intake
     │
Domain      ──  SearchNotes · AskQuestion · IndexVault · VaultLocation · ModelLocation · Settings
     │
     ├─ Vault Reader   SAF tree walk via DocumentsContract, token-budgeted chunking, exclude patterns
     ├─ Embedder       MiniLM-L6-v2 INT8 on ONNX Runtime, 384-dim, mean-pooled, L2-normalized
     ├─ LlmEngine      interface in :core; llama.cpp over JNI in :llama
     └─ Store          Room + SQLCipher, key wrapped by Android Keystore; brute-force cosine search
```

- **Vector search:** brute-force cosine (13.8 ms for 50,000 chunks on-device). No sqlite-vec; don't add it unless a real vault outgrows this.
- **Embedding model:** MiniLM-L6-v2 is what ships. EmbeddingGemma was the original default but is gated and under the non-OSI Gemma license (F-Droid "Non-Free Assets"); the choice is still to be settled deliberately in Phase 4.
- **Chunks are sized in tokens, not characters** (a chars-per-token estimate silently truncated 14.4% of the vault past the 256-token window). Inputs are padded to 32-token buckets, not a flat 256. Changing chunking constants changes the index shape — there is a chunking fingerprint and a rebuild flow for this.
- **Local LLM:** Track A (llama.cpp via JNI, GGUF) was chosen and shipped; a Rust core stays possible behind `LlmEngine`. llama.cpp's SVE2 CPU variants are deliberately excluded from the APK (1.79x slower than NEON+i8mm on Tensor G3), and `useLegacyPackaging = true` is required in `:app` because the backends are `dlopen`'d by path — it does not propagate from `:llama`. Native libs are 16 KB page-aligned; keep them so.
- **Encryption at rest:** SQLCipher, with index protection as a user choice of three levels (off / recent device unlock / every launch) via AndroidX Biometric. Changing between levels must never destroy the index — this has already shipped as a bug once.
- **Indexing:** periodic WorkManager pass plus manual reindex. SAF has no filesystem watch; this is the intended design, not a gap. A cancelled/interrupted pass must not leave the index cleared.
- **Relevance floor:** below a calibrated score floor Search says "No good matches" instead of showing noise. The roadmap documents that the floor cannot cleanly separate relevant from irrelevant on the current corpus, and the constants are fitted to one topically narrow vault.
- **Defaults:** a setting stored equal to its default must not freeze that default, and an absent new field means "nobody expressed a preference", not "unknown".
- **Distribution:** F-Droid, mirrored on GitHub Releases (Obtainium-friendly). Not the Play Store.

## Phases

Phase 0 spike → Phase 1 MVP (index + search) → Phase 2 RAG Q&A → Phase 3 polish/hardening → Phase 4 F-Droid packaging → Phase 5 optional (multi-vault, related-notes sidebar, desktop companion). Don't skip ahead of earlier exit criteria without being asked; the roadmap has per-phase exit criteria and status.
