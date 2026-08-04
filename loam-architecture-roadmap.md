# Loam — Architecture & Development Roadmap

*A FOSS, Android-native semantic search and Q&A layer over notes you already own. Codename only — swap it for anything you like (worth a quick trademark/package-name gut-check before you commit to it).*

## Contents
- [What this is (and isn't)](#what-this-is-and-isnt)
- [Core principles](#core-principles)
- [High-level architecture](#high-level-architecture)
- [Key architecture decisions](#key-architecture-decisions)
- [Data flow](#data-flow)
- [Tech stack](#tech-stack)
- [Prerequisites](#prerequisites)
- [Development roadmap](#development-roadmap)
- [Risks & open questions](#risks--open-questions)
- [Prior art & references](#prior-art--references)

## What this is (and isn't)

Loam indexes an existing folder of Markdown notes — an Obsidian vault, a Markor folder, whatever you already use — and lets you search it by meaning instead of keyword, and optionally ask it questions that get answered using your own notes as grounding (RAG). Everything runs on-device.

It is **not** another note editor. It doesn't own your files, doesn't invent a proprietary format, and doesn't compete with the editor you already like. That's a deliberate scope cut: the semantic-search-and-Q&A layer is the actual gap in the FOSS Android ecosystem right now; a "good enough" markdown editor is not.

## Core principles

These double as acceptance criteria — if a build violates one of these, something's gone wrong:

1. **Core functionality needs zero network permission.** Search, and Q&A once a model is on-device, work in airplane mode, forever. The only legitimate network use is a one-time, explicit, user-initiated model download.
2. **No proprietary storage format.** Notes stay as plain `.md` files, wherever you already keep them.
3. **No Google dependencies.** No Play Services, no Firebase, no GMS-only APIs. Assume the target device may not have Play Services installed at all.
4. **Auditable, not a black box.** Every answer shows which notes it came from. No silent telemetry, ever.
5. **F-Droid-distributable.** Every dependency needs a real OSI-approved license — this rules out some otherwise-tempting libraries (see below).

## High-level architecture

```
UI (Jetpack Compose)
  Search · Ask (RAG chat, Phase 2+) · Settings · Reindex status
        │
        ▼
Domain layer (use cases)
  SearchNotes · AskQuestion · IndexVault · ManageVaultLocation
        │
        ├──▶ Vault Reader          SAF folder access, file diffing (mtime/hash), chunking
        │
        ├──▶ Embedding Engine      EmbeddingGemma or MiniLM-L6-v2 — on-device, no network
        │
        └──▶ Local LLM (Phase 2+)  llama.cpp JNI, or a Rust/candle core — GGUF models
                    │
                    ▼
        Local store — Room + SQLCipher
        chunks · embedding vectors · index metadata (mtimes / hashes)
        search: brute-force cosine similarity → sqlite-vec if a vault gets huge
```

## Key architecture decisions

| Decision | Choice | Why | Alternatives considered |
|---|---|---|---|
| Note storage | Read `.md` files in place via Storage Access Framework | Interop with whatever you already use; a fraction of the work of a real editor | Own editor + DB — reinvents Obsidian/Markor for no real gain |
| Vector search | Brute-force cosine similarity for MVP; add **sqlite-vec** (MIT/Apache-2.0, confirmed to run on Android via precompiled loadable extensions) only if a vault outgrows it | Personal note collections are realistically low thousands of chunks — brute force is fast enough and has zero exotic dependencies | **ObjectBox** — genuinely excellent API and the first real on-device vector DB for Android, but its native engine ships under the proprietary "ObjectBox Binary License," not an OSI-approved one. F-Droid maintainers have explicitly flagged apps using it as unusable for F-Droid. Ruling it out now avoids a rewrite later. |
| Embedding model | **EmbeddingGemma** (308M params, small enough to run in well under 200MB of RAM when quantized, purpose-built for on-device RAG) as default; **MiniLM-L6-v2** (Apache-2.0, ~80MB) as an alternate build flavor | EmbeddingGemma is currently the strongest open embedding model under 500M params; MiniLM is smaller and fully, unambiguously OSI-licensed | Cloud embedding APIs — ruled out immediately, breaks principle #1 |
| Local LLM runtime (Phase 2+) | **Track A:** llama.cpp via JNI, running GGUF models (Gemma 3, Qwen, Llama 3.2, Phi-4 Mini). **Track B:** a Rust core (candle or llama-cpp-2 bindings) exposed via UniFFI | Track A is the well-trodden path other FOSS on-device apps use. Track B reuses the Cubiomes-FFI pattern from the Minecraft seed-map project, and leaves you with a portable engine you could reuse in a future desktop build | Google AI Edge / LiteRT LLM Inference API — solid, Apache-2.0, and genuinely standalone (no Play Services needed at runtime), but it's still Google's SDK — worth weighing against the point of the project |
| Encryption at rest | SQLCipher (public-domain SQLite core, Apache-2.0 Android bindings) + AndroidX Biometric for unlock | Notes are personal by definition; encrypt the derived index too, don't just assume the OS handles it | Unencrypted Room DB — simpler, but no at-rest protection if the device is lost or compromised |
| Distribution | F-Droid, mirrored on GitHub Releases (Obtainium-friendly) | Matches the toolchain you already use | Play Store — would add a Google dependency purely for distribution |

A note on the embedding-model choice and F-Droid: EmbeddingGemma ships under Google's Gemma license, which is permissive but not OSI-approved. Bundled that way, F-Droid would likely tag the app with the **Non-Free Assets** anti-feature (their term for non-libre non-code assets, which covers bundled model weights) — not a rejection, just an honest label. Shipping the MiniLM-L6-v2 flavor as an alternate build avoids the tag entirely, at some cost to embedding quality. Worth deciding on purpose rather than by accident.

## Data flow

**Indexing**
1. Grant access to a folder via `ACTION_OPEN_DOCUMENT_TREE` (the existing vault).
2. Walk the tree, chunk each note (by heading/paragraph, roughly 200–400 tokens, slight overlap).
3. Embed each chunk on-device; store chunk text, embedding, source path, heading breadcrumb, and an mtime/hash fingerprint in the encrypted local store.
4. A WorkManager job re-checks fingerprints periodically (SAF doesn't support real filesystem-watch across app restarts, so this — plus a manual "reindex now" — is the honest way to do it, not a bug to fix later).

**Search**
1. Embed the query with the same model used for indexing.
2. Rank stored chunk vectors by cosine similarity.
3. Show top-K results with source file, heading, and snippet; tapping one opens the real file in whatever app is associated with `.md` files.

**Ask (RAG Q&A, Phase 2+)**
1. Embed the question, retrieve top-K chunks.
2. Build a prompt: retrieved chunks + question.
3. Local LLM generates an answer.
4. Show the answer with an expandable "sources used" panel listing the exact chunks it was grounded in — the whole point is that this is checkable, not a black box.

## Tech stack

- **UI:** Kotlin + Jetpack Compose (Material 3)
- **Storage:** Room over SQLCipher
- **Background work:** WorkManager (indexing), AndroidX Biometric (lock)
- **File access:** Storage Access Framework
- **Embedding runtime:** ONNX Runtime Mobile (zero Google code) or Google AI Edge / LiteRT (Google-authored but standalone, no Play Services required, well-optimized for Gemma models) — pick based on how strict you want the "no Google code in the dependency tree" line to be, versus just "no Google services"
- **LLM runtime (Phase 2+):** llama.cpp (JNI) or a Rust core via `cargo-ndk` + UniFFI

If you take the Rust track, one Android-specific gotcha either way: Google ships **two** distribution modes for the TFLite/LiteRT runtime — bundled-in-APK (works on any device, larger APK) and Play-Services-delivered (smaller APK, requires GMS). For a GrapheneOS-friendly app, bundled is the only mode that makes sense, and it's an easy thing to get wrong by following a generic tutorial aimed at mainstream devices.

## Prerequisites

Everything below can be set up before any "real" app code exists — it's what Phase 0 actually needs to start. Android Studio itself is assumed already installed.

**Android Studio / SDK Manager (Settings → Languages & Frameworks → Android SDK):**
- Latest stable Android Studio channel (not Canary/Beta) — native library debugging and Compose previews are more reliable there.
- SDK Platform: latest stable API level, for `compileSdk`/`targetSdk`. Pick a `minSdk` deliberately rather than defaulting to Studio's suggestion — SAF is fine as far back as API 21, but AndroidX Biometric's `BiometricPrompt` wants API 23+; API 26 (Android 8) is a reasonable floor that keeps the biometric-unlock story simple without dropping much real-world reach.
- SDK Tools tab: install **NDK (Side by side)** and **CMake**. Needed either way for Phase 2+ — llama.cpp JNI (Track A) and a Rust core via `cargo-ndk` (Track B) both compile native code through the NDK. Tick **Show Package Details** to choose a version; take the **LTS release (r27d, `27.3.13750724`)** rather than whatever's newest. Beta NDKs are the default trap here — Phase 4 wants reproducible builds, and pinning to a pre-release toolchain that may be withdrawn or change codegen undermines that, on top of making native inference bugs harder to attribute.
- A device or emulator image running your chosen `minSdk` or higher, for basic UI iteration.

**A real physical Android device.** The roadmap's Phase 0 exit criteria explicitly call for measuring embedding latency and RAM "on your own phone, not a spec sheet" — emulators don't give you honest numbers for on-device inference. Enable Developer Options → USB debugging and confirm `adb devices` sees it from a terminal (Android Studio's Device Manager can also run it directly).

**If you're leaning toward Track B (Rust core) for the LLM runtime**, set this up now even though it's a Phase 2+ concern, since it's independent of app code:
- Install `rustup` (https://rustup.rs), then add the Android targets: `rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android i686-linux-android`
- Install `cargo-ndk`: `cargo install cargo-ndk`
- Point `cargo-ndk` at the NDK via the `ANDROID_NDK_HOME` env var. It auto-detects the newest NDK under Android Studio's default location, but pinning the version explicitly avoids surprise toolchain changes when a new NDK is installed. Set it persistently in your shell rc — for fish, in `~/.config/fish/config.fish`:
  ```fish
  export ANDROID_NDK_HOME="$HOME/Android/Sdk/ndk/27.3.13750724"
  ```
  NDK installs live side by side, so switching toolchains is just this one line. Verify with `echo $ANDROID_NDK_HOME` in a fresh shell, and sanity-check the whole chain by cross-compiling a throwaway `crate-type = ["cdylib"]` crate — `cargo ndk -t arm64-v8a -t armeabi-v7a build --release`, then `file` the resulting `.so` and confirm it reports `ARM aarch64` / `ARM EABI5` and the NDK version you expect. Worth doing before writing real Rust, so a broken toolchain never gets mistaken for broken code.
- **No standalone `uniffi-bindgen` install** — current UniFFI (0.28+) dropped the globally-installable CLI. Kotlin binding generation is wired up per-project once the Rust crate exists: add a `[[bin]] name = "uniffi-bindgen"` target pointing at a small `uniffi-bindgen.rs` containing `fn main() { uniffi::uniffi_bindgen_main() }`, depend on `uniffi` with the `cli` feature, and run it with `cargo run --features=uniffi/cli --bin uniffi-bindgen -- generate --library <path-to-.so> --language kotlin --out-dir <dir>`. There's nothing to set up for this until Phase 2 scaffolding creates the crate.

If instead you're leaning toward Track A (llama.cpp via JNI), no extra toolchain beyond NDK/CMake is needed until Phase 2 — you'd pull llama.cpp as a submodule or prebuilt `.so` at that point, not now.

**Model files for the Phase 0 spike:**
- Start with **MiniLM-L6-v2** from `sentence-transformers/all-MiniLM-L6-v2` — ungated and Apache-2.0, so nothing blocks you. Pull `onnx/model.onnx` (fp32, 87MB) *and* `onnx/model_qint8_arm64.onnx` (INT8, 22MB — quantized specifically for ARM64), plus `tokenizer.json`, `vocab.txt`, `config.json`. Grabbing both precisions is the point: Phase 0's real question is what quantization costs you in retrieval quality versus what it buys in latency and RAM, and you can't answer that with one file.
- **EmbeddingGemma can't be scripted.** `google/embeddinggemma-300m` is `gated: manual` on HuggingFace — it needs an account, manual acceptance of the Gemma license, and approval before any download works. Budget for that lead time rather than discovering it mid-spike. This is also the concrete form of the Non-Free Assets tradeoff discussed above: the default model is the one with paperwork attached.
- Keep weights out of git (`models/` is gitignored). They're large, and bundling them in-repo muddies the license story the Phase 4 anti-feature assessment has to answer.
- This one-time download is the single legitimate use of network access called out in Core principle #1 — do it manually now rather than wiring up in-app download code you don't need yet.
- Not needed yet, but worth knowing ahead of Phase 2: a small quantized GGUF model (e.g. a Q4 build of Gemma 3 or Phi-4 Mini) for the eventual local-LLM spike.

**A test vault.** A folder of real (or realistic) `.md` notes, on-device or on the emulator's storage, to grant via `ACTION_OPEN_DOCUMENT_TREE` when testing SAF folder-picking and file-reading — using your actual notes, per the Phase 1 exit criteria, is more informative than synthetic fixtures once you get that far.

## Development roadmap

Sizing below is relative, not a schedule — solo FOSS projects move in bursts. The real long pole is almost always Phase 0 and Phase 2 (getting inference running well on real hardware), not the UI.

### Phase 0 — Spike (de-risk before committing)
- Get an embedding model running via ONNX Runtime or LiteRT in a throwaway test app; measure real latency and RAM on your own phone, not a spec sheet.
- Get SAF folder-picking and file-reading working end to end.
- Benchmark brute-force cosine similarity against synthetic 5k/20k/50k-chunk sets, on-device, to know your real ceiling before deciding whether sqlite-vec is ever needed.
- **Exit criteria:** a working embedding call and a working SAF read, both proven on your own hardware, before any "real" app code exists.

#### Phase 0 results — measured on a Pixel 8a (Tensor G3, Android 17)

All three exit criteria met. Numbers are from a **release** build; see the note below on why that qualifier is load-bearing.

| Measurement | Result |
| --- | --- |
| Embedding, MiniLM-L6-v2 INT8, maxLen 256 | 23–36 ms/chunk, 35 ms cold start, dim 384 |
| SAF walk, 392-note vault | 13.5 s (~34 ms/file) |
| Cosine search, 5k / 20k / 50k chunks | 1.36 / 5.52 / 13.81 ms per query |
| Peak heap at 50k chunks | 107 MB |

**Decision: brute-force cosine is sufficient; sqlite-vec is not needed.** Cost is linear at 0.276 µs per chunk with no inflection through 50k, so even a 200k-chunk vault (~24k notes at this vault's density) stays near 55 ms. The dependency stays off the list.

Two findings worth carrying forward:

- **Never benchmark a `debuggable` build.** The same code measured 533 ms at 50k as a debug build versus 13.81 ms as release — a **36x** difference, because `debuggable true` forces deoptimization support and blocks ART inlining. The debug numbers argued for adopting sqlite-vec; they were an artifact of the build type, not the algorithm.
- **The SAF walk, not embedding, is the surprising cost.** 13.5 s to merely *enumerate* 392 files, before reading a byte. `DocumentFile` issues a separate ContentResolver query per node. Phase 1 should treat enumeration as its own progress-reported stage, and Phase 3's "paginated/streamed indexing" bullet should assume the walk is slow independent of vault size in bytes.

Full-index estimate for this vault: ~3,300 chunks × ~25 ms ≈ 80 s of embedding plus 13.5 s of walking. Acceptable for a one-time index, but it needs visible progress rather than a spinner.

### Phase 1 — MVP: index + semantic search (no LLM yet)
- Compose UI: pick vault, search, results list.
- Chunking + embedding pipeline; Room/SQLCipher schema; WorkManager indexing job.
  - The spike's `Chunker` is ready to carry over and is worth reading before rewriting one. Three rules earned by measured defects: headings break a chunk only once it exceeds a minimum size (breaking at every heading gave 6,027 chunks averaging ~108 tokens, 39% under 200 characters); a hard character ceiling applies *within* blocks (a 77k-character table otherwise became one chunk and was silently truncated at embed time, leaving most of that note unretrievable while appearing indexed); and fenced code is treated as a single block (splitting on blank lines tears fences apart and lets `#` comments read as headings). On the test vault this gives 3,427 chunks averaging ~200 tokens with 2.5% undersized, all under the ceiling.
  - Still missing for Phase 1: chunks are plain strings, but the store wants a heading breadcrumb and source path per chunk. And sizing uses characters as a token proxy (~4:1), which holds for English and fails for CJK at roughly 1:1 — a CJK-heavy vault would get chunks several times longer than intended.
- Brute-force cosine similarity search; manual + periodic reindex.
- **Exit criteria:** point it at your real notes, ask "did I ever write about X," get correct, meaning-based hits — with zero network permission anywhere in the manifest.

#### Phase 1 status — exit criteria met, verified on device

Built as a two-module Gradle build at the repo root (`:app` Compose UI, `:core` domain/data); `spike/` stays a separate build until deleted. Indexed the 392-note test vault on a Pixel 8a: **3,427 chunks in 397 s**, then searched it.

| Query | Top hit | Score |
| --- | --- | --- |
| "how do I set up a virtual machine" | Configure Default Virtual Hardware Using the Wizard | 0.68 |
| "encrypting a disk with LUKS" | MOC Arch Install FULL › LUKS2 Encryption Setup | 0.66 |
| "banana bread recipe with walnuts" | *No good matches* | — |

None of those queries share vocabulary with the notes they found, which is the point.

**Permissions, verified against the built APK rather than the manifest:** WorkManager's manifest merger contributes `ACCESS_NETWORK_STATE`, `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED`, and `FOREGROUND_SERVICE`. `ACCESS_NETWORK_STATE` grants no data access, but shipping a permission with "NETWORK" in its name would undercut Core principle #1 for anyone reading the F-Droid listing, so it is stripped with `tools:node="remove"` — indexing was then confirmed to still run. The other three are kept deliberately: they are what let a long index survive the screen turning off and resume after a reboot. There is no `INTERNET` permission.

Two findings worth carrying into Phase 3:

- **Indexing ran at ~116 ms/chunk against the 23–36 ms the spike measured.** This was investigated rather than left as a guess; see "Why indexing is slower than the spike suggested" below. The thread-priority hypothesis was wrong.
- **The relevance threshold has to be calibrated against real chunks, not sentences.** An initial 0.35 came from the spike's sentence-to-sentence scores (0.250 related vs 0.062 unrelated) and was far too low: chunks are long, so they carry a bit of everything and score moderately against any query. The banana-bread query returned confident-looking Linux notes at 0.19 until it was recalibrated against the measured spread.

Not yet done in Phase 1: no settings screen (chunk size, model choice, exclude patterns are all Phase 3), no biometric gate on the database key, and the index is loaded into heap whole — fine at 3,427 chunks, worth revisiting well before 50k.

#### Why indexing is slower than the spike suggested

Stage timings were added to `IndexVault` and two full re-indexes run on the Pixel 8a with CPU frequency and thermal status sampled alongside. Results, in order of how much they matter:

**Embedding is 95% of the work.** Of a 420 s run: `embed=399 s`, `read=12.3 s`, `store=4.7 s`, `walk=1.7 s`, `chunk=0.1 s`. Worth noting the walk is now **1.7 s, down from the spike's 13.5 s** — that is the `DocumentsContract` rewrite paying off ~8x. Enumeration is no longer a cost worth optimizing.

**Thermal throttling is real and accounts for the within-run degradation.** Sampling the big core during a run:

| elapsed | CPU8 clock | thermal status | ms/chunk |
| --- | --- | --- | --- |
| 10 s | 2.91 GHz | 0 | 106.7 |
| 50 s | 2.29 GHz | 0 | 117.4 |
| 90 s | 1.89 GHz | 1 | 126.8 |
| 130 s | 1.16 GHz | 1 | 135.3 |

The clock falls 2.5x and `Thermal Status` flips to 1. A second run started on an already-warm device averaged 130 ms/chunk against 116 ms/chunk from cool. Reading thermal status *after* a run shows 0 and is misleading — it has to be sampled during.

**The thread-priority hypothesis was wrong.** `CoroutineWorker.doWork` runs on `Dispatchers.Default`, not WorkManager's background executor: the logged thread is `DefaultDispatcher-worker-N` at `prio=0` (default), never the background priority that would confine it to little cores. There was nothing to fix.

**Tokenization is not the cost either** — 0.6–1.1 ms per chunk against 81–100 ms for inference, so the hand-rolled WordPiece is not worth optimizing.

**The spike's 23–36 ms was a burst measurement, not a sustained one.** Even cool, at 2.36 GHz, with tokenization excluded, inference alone is ~83 ms per chunk. The spike timed three embeddings on an idle device at full boost with no database, no UI recomposition, and no concurrent work; ONNX Runtime is multi-threaded and therefore highly sensitive to core availability. The number was never wrong, it just could not describe sustained load — the same lesson the `debuggable` build taught in Phase 0, in a different costume. **Take on-device performance numbers from a run shaped like the real workload.**

The concrete optimization this points at, for Phase 3: every chunk is padded to `maxLen = 256` regardless of its actual length, but the model's axes are fully dynamic (`['batch_size', 'sequence_length']`) and attention is O(n²). Measured on desktop: 64 tokens 4.3 ms, 128 tokens 6.7 ms, 256 tokens 11.4 ms. Median chunk is ~157 tokens, and search queries are far shorter, so padding to a bucket near the real token count rather than a fixed 256 should cut both indexing and query latency without touching retrieval quality. Batching multiple chunks per `run()` is the other candidate.

### Phase 2 — RAG Q&A
- Integrate the chosen LLM runtime; GGUF model loading; a model download/picker flow (this is where INTERNET permission enters, scoped narrowly to "fetch model").
- Build retrieve-then-generate; the "sources used" panel.
- Handle low-confidence retrieval honestly — surface "no good matches" rather than forcing a hallucinated answer.
- **Exit criteria:** a real question about your notes gets a grounded, sourced answer, fully offline once the model is downloaded.

### Phase 3 — Polish & real-world hardening
- Paginated/streamed indexing so a large vault doesn't freeze the UI on first run.
- Settings: model choice, quantization level, chunk size, folder exclude-patterns.
- Share-sheet integration; consider a home-screen search widget.
- Battery/thermal testing under a full-vault first index — this is the real stress test.
- **Exit criteria:** daily-driver comfortable — you reach for it instead of manual grep.

### Phase 4 — F-Droid packaging & release
- Reproducible build setup; a full license manifest (SQLCipher, sqlite-vec if used, llama.cpp, embedding model weights, each with correct SPDX identifiers).
- Anti-feature self-assessment (see the Non-Free Assets note above); settle the EmbeddingGemma-vs-MiniLM question deliberately here if you haven't already.
- Submit to fdroiddata; iterate through review.
- **Exit criteria:** installable from F-Droid, or at minimum your own repo via Obtainium, with an honestly-labeled anti-feature list.

### Phase 5 — Later, optional
- Multi-vault support (e.g., separate encrypted stores for work vs. personal notes).
- A live "related notes while you write" sidebar, Smart-Connections-style, if you ever build or hook into an editor.
- A desktop companion sharing the same Rust core (if you took Track B) — this is the payoff for going Rust-first: the same embedding/retrieval engine running on your Fedora box, not just your phone.

## Risks & open questions

- **Small-model hallucination survives RAG.** Grounding reduces it, doesn't eliminate it — the "sources used" panel is doing real work here, not decoration.
- **SAF has no true background filesystem watch.** Periodic + manual reindex is the honest architecture, not a stopgap.
- **First index of a large, long-lived vault will be slow and battery-heavy.** Needs a visible progress state; shouldn't run silently in the background on first launch.
- **Model licensing shapes your F-Droid listing.** Decide the EmbeddingGemma/MiniLM (and any LLM model) question with the anti-feature consequences in mind, not after the fact.

## Prior art & references

- [Reor](https://github.com/reorproject/reor) — the closest thing to this that exists, but desktop-only
- [Smart Connections](https://smartconnections.app/smart-connections/) — same idea, as an Obsidian plugin
- [sqlite-vec](https://github.com/asg017/sqlite-vec) · [Android/iOS notes](https://alexgarcia.xyz/sqlite-vec/android-ios.html)
- [ObjectBox F-Droid incompatibility thread](https://github.com/objectbox/objectbox-java/issues/1100) — why it's ruled out above
- [EmbeddingGemma model card](https://ai.google.dev/gemma/docs/embeddinggemma) · [announcement](https://developers.googleblog.com/en/introducing-embeddinggemma/)
- [Google AI Edge function calling for Android](https://ai.google.dev/edge/mediapipe/solutions/genai/function_calling/android) — relevant if you extend past pure Q&A later
