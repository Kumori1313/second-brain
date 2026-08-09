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

1. **Zero network permission — no exception, including Phase 2.** Search, and Q&A once a model is on-device, work in airplane mode, forever. This principle originally carved out an exception for a one-time user-initiated model download; the Phase 2 spike removed the need for it. The LLM is sideloaded through the same SAF picker as the vault, so the app never holds `INTERNET` at any point in its life.
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
| Local LLM runtime | **Track A, chosen and shipped:** llama.cpp via JNI, running GGUF models. Track B (a Rust core via UniFFI) stays possible behind the `LlmEngine` interface | Track A is the well-trodden path other FOSS on-device apps use. Track B reuses the Cubiomes-FFI pattern from the Minecraft seed-map project, and leaves you with a portable engine you could reuse in a future desktop build | Google AI Edge / LiteRT LLM Inference API — solid, Apache-2.0, and genuinely standalone (no Play Services needed at runtime), but it's still Google's SDK — worth weighing against the point of the project |
| Encryption at rest | SQLCipher (public-domain SQLite core, Apache-2.0 Android bindings), key wrapped by an Android Keystore AES-GCM key. Authentication is a **user choice of three levels** — off, recent device unlock, or per launch — not the single "AndroidX Biometric for unlock" this row originally specified; see Phase 3 | Notes are personal by definition; encrypt the derived index too, don't just assume the OS handles it. The tradeoff runs in three directions at once — protection, prompt friction, background freshness — so no single point on it is right for everyone | Unencrypted Room DB — simpler, but no at-rest protection if the device is lost. A biometric prompt per launch as the *only* option was the original plan, and fights the daily-driver exit criterion for little gain against an already-unlocked device |
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
- **LLM runtime:** llama.cpp via JNI (`:llama`), pinned as a submodule. Model sideloaded through SAF, so no network permission is ever needed

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
- **The SAF walk, not embedding, is the surprising cost.** 13.5 s to merely *enumerate* 392 files, before reading a byte. `DocumentFile` issues a separate ContentResolver query per node. Phase 1 should treat enumeration as its own progress-reported stage. (Later: the `DocumentsContract` rewrite took this to 1.5 s, and the "paginated/streamed indexing" concern it fed has since been struck — see Phase 3.)

Full-index estimate for this vault: ~3,300 chunks × ~25 ms ≈ 80 s of embedding plus 13.5 s of walking. Acceptable for a one-time index, but it needs visible progress rather than a spinner.

### Phase 1 — MVP: index + semantic search (no LLM yet)
- Compose UI: pick vault, search, results list.
- Chunking + embedding pipeline; Room/SQLCipher schema; WorkManager indexing job.
  - The spike's `Chunker` is ready to carry over and is worth reading before rewriting one. Three rules earned by measured defects: headings break a chunk only once it exceeds a minimum size (breaking at every heading gave 6,027 chunks averaging ~108 tokens, 39% under 200 characters); a hard character ceiling applies *within* blocks (a 77k-character table otherwise became one chunk and was silently truncated at embed time, leaving most of that note unretrievable while appearing indexed); and fenced code is treated as a single block (splitting on blank lines tears fences apart and lets `#` comments read as headings). On the test vault this gives 3,427 chunks averaging ~200 tokens with 2.5% undersized, all under the ceiling.
  - Still missing for Phase 1: chunks are plain strings, but the store wants a heading breadcrumb and source path per chunk. And sizing uses characters as a token proxy (~4:1), which holds for English and fails for CJK at roughly 1:1 — a CJK-heavy vault would get chunks several times longer than intended.
- Brute-force cosine similarity search; manual + periodic reindex.
- **Exit criteria:** point it at your real notes, ask "did I ever write about X," get correct, meaning-based hits — with zero network permission anywhere in the manifest.

#### Phase 1 status — exit criteria met, verified on device

Built as a two-module Gradle build at the repo root (`:app` Compose UI, `:core` domain/data); `spike/` stays a separate build until deleted. Indexed the 392-note vault on a Pixel 8a — **3,427 chunks in 397 s** on the first run, later **5,297 chunks in 151 s** once chunking and concurrency were fixed — then searched it.

**These are real notes.** The vault is the author's own Obsidian vault at `Documents/pensive`, `.obsidian` config and all — not a fixture. A previous revision of this section claimed the opposite and marked the exit criteria unmet; that was read off a stale `.gitignore` comment about cloning a public vault, without checking whether the directory matched it. It did not.

One narrower caveat does survive. **97% of the vault — 379 of 392 notes — sits under `linux/`**, so it is topically concentrated technical documentation in a uniform register. The constants fitted against it are therefore correctly tuned for *this* vault, which is the whole point of a personal tool: `DEFAULT_MIN_SCORE = 0.44`, `DEFAULT_MIN_TOKENS = 64`, and the 3.46 chars/token ratio behind the truncation fix. They should not be assumed to transfer to a vault of short captures, daily journals, or non-Latin scripts — which matters only once Loam is distributed to anyone else.

| Query | Top hit | Score |
| --- | --- | --- |
| "how do I set up a virtual machine" | Configure Default Virtual Hardware Using the Wizard | 0.68 |
| "encrypting a disk with LUKS" | MOC Arch Install FULL › LUKS2 Encryption Setup | 0.66 |
| "banana bread recipe with walnuts" | *No good matches* | — |

None of those queries share vocabulary with the notes they found, which is the point.

**Permissions, verified against the built APK rather than the manifest:** WorkManager's manifest merger contributes `ACCESS_NETWORK_STATE`, `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED`, and `FOREGROUND_SERVICE`. `ACCESS_NETWORK_STATE` grants no data access, but shipping a permission with "NETWORK" in its name would undercut Core principle #1 for anyone reading the F-Droid listing, so it is stripped with `tools:node="remove"` — indexing was then confirmed to still run. The other three are kept deliberately: they are what let a long index survive the screen turning off and resume after a reboot. There is no `INTERNET` permission.

Two findings worth carrying into Phase 3:

- **Indexing appeared to run at ~116 ms/chunk against the 23–36 ms the spike measured — and the gap was self-inflicted.** Two indexers were racing each other. See "Why indexing looked slower than the spike suggested" below; the thread-priority and thermal explanations were both investigated first, and both were wrong about the magnitude.
- **The relevance threshold has to be calibrated against real chunks, not sentences.** An initial 0.35 came from the spike's sentence-to-sentence scores (0.250 related vs 0.062 unrelated) and was far too low: chunks are long, so they carry a bit of everything and score moderately against any query. The banana-bread query returned confident-looking Linux notes at 0.19 until it was recalibrated against the measured spread. A later probe showed the cut is not free in the other direction either — a planted note on espresso scored 0.72 for a direct query but *under* 0.35 for "why is my shot running too fast", which it answers outright, while an unrelated Btrfs chunk cleared the bar at 0.36. 0.35 is a floor tuned to one corpus, not a constant. (Phase 3 re-measured it properly at 30 labelled queries and moved it to 0.44 — and found that the two score bands overlap, so no value separates them. See below.)

Not yet done in Phase 1: no settings screen (chunk size, model choice, exclude patterns are all Phase 3), no biometric gate on the database key, and the index is loaded into heap whole — fine at 3,427 chunks, worth revisiting well before 50k.

#### Why indexing looked slower than the spike suggested

Three explanations were investigated in order. The first two were wrong, and the third invalidates most of the numbers the first two were reasoning about.

**The thread-priority hypothesis was wrong.** `CoroutineWorker.doWork` runs on `Dispatchers.Default`, not WorkManager's background executor: the logged thread is `DefaultDispatcher-worker-N` at `prio=0` (default), never the background priority that would confine it to little cores. There was nothing to fix.

**Thermal throttling is real, but it was a symptom.** Sampling the big core during a run:

| elapsed | CPU8 clock | thermal status | ms/chunk |
| --- | --- | --- | --- |
| 10 s | 2.91 GHz | 0 | 106.7 |
| 50 s | 2.29 GHz | 0 | 117.4 |
| 90 s | 1.89 GHz | 1 | 126.8 |
| 130 s | 1.16 GHz | 1 | 135.3 |

The clock falls 2.5x and `Thermal Status` flips to 1. All of that is accurate — but it describes a device being driven twice as hard as the work required. Thermal status has to be sampled *during* a run; reading it afterwards shows 0 and misleads.

**The actual cause: Loam was indexing the vault twice at once.** `onVaultPicked` calls `reindex()` *and* `schedulePeriodic()`, and WorkManager runs a newly-enqueued periodic immediately rather than waiting out its first interval. `UNIQUE_MANUAL` and `UNIQUE_PERIODIC` are separate unique names, so nothing stopped both. Every vault pick started two indexers that walked the same tree, saw the same notes as stale against the same fingerprints, and embedded overlapping work while contending for cores.

It was caught by noticing the progress log interleaving two different totals:

```
window notes=225/393 …  ms/chunk=22.7      ← pass A
window notes=25/167  …  ms/chunk=78.1      ← pass B
window notes=350/393 …  ms/chunk=112.9
```

Same vault, same device, ten minutes apart:

| | ms/chunk | full index |
| --- | --- | --- |
| Two passes | 112.9 | ~420 s |
| One pass (guarded) | **24.1** | **151 s** |

`IndexVault` now holds a mutex for the duration of a pass and returns null rather than starting a second one; a skipped run is reported distinctly, because "indexed zero notes" and "did not look" are not the same claim.

**So the spike's 23–36 ms was right all along.** The conclusion previously recorded here — that it was a burst measurement incapable of describing sustained load — was itself wrong: 24.1 ms/chunk held across a full 393-note index on an already-warm device. The irony is worth preserving rather than editing away. A section arguing that performance numbers must come from a run shaped like the real workload was itself reasoning from a contaminated run. **A measurement taken from the real workload is only as good as your understanding of what that workload is actually doing.**

**Embedding is 95% of the work, and enumeration is not worth optimizing.** Of the 151 s run: `embed=128 s`, `read=11.3 s`, `store=4.8 s`, `chunk=4.0 s`, `walk=1.5 s`. The walk is down from the spike's 13.5 s — the `DocumentsContract` rewrite paying off ~9x.

**Tokenization is not the cost either** — 0.3–0.9 ms per chunk against ~20 ms for inference, so the hand-rolled WordPiece is not worth optimizing.

#### Dynamic sequence length — done, and what it uncovered

Every input was padded to `maxLen = 256` regardless of length, but the graph's axes are dynamic (`['batch_size', 'sequence_length']`) and attention is O(n²) — desktop-measured at 4.3 ms for 64 tokens against 11.4 ms for 256. Inputs are now padded up to a 32-token bucket instead. Bucketing rather than exact length keeps the number of distinct shapes small enough for ONNX Runtime to reuse execution plans.

Measured on the Pixel 8a, both runs from cool:

| | fixed 256 | bucketed |
| --- | --- | --- |
| full index of 392 notes | 420.2 s | **358.1 s** |
| embed per chunk | 116.4 ms | **98.7 ms** |
| query inference | ~78 ms | **17 ms** |

Padding is not perfectly neutral on the INT8 graph — the same text padded to its own length versus 256 measures 0.9988 mean cosine, since the mask is a large negative bias rather than a true `-inf`. That is far inside the margin between a real hit (~0.66) and noise (~0.19), and top-10 overlap across regimes measured 9.5/10, but it does mean vectors are only comparable to others built the same way. `LoamDatabase`'s version was bumped to 2 so the destructive migration forces a reindex; **bump it again whenever stored vectors stop being comparable, not only when columns change.**

**The larger find was on the query path.** Measuring rather than assuming showed a search costing 331 ms of which only 17 ms was inference — the rest was constructing an ONNX session and re-parsing a 30,522-line vocabulary *per keystroke*. Caching one session for the process took warm queries to **12 ms**, a 27x improvement that dwarfs the optimization that exposed it.

That left the *first* query of each launch at ~750 ms, which is the worst shape a search box can have: broken once per launch, instant thereafter. Three things are lazy and only one is obvious — creating the session, reading every vector out of the store, and ONNX Runtime's own graph and arena setup, which happens on the first `run` rather than at session creation. All three are now warmed in the background at startup, taking the first query to **13 ms**. Search also moved to `Dispatchers.Default`; it had been running on the main thread, so that 750 ms was blocking the UI rather than merely being slow.

Warming exposed a second bug worth recording, because it is a general trap rather than a Loam one: **WorkManager replays terminal states to every new subscriber.** Each launch was therefore seeing the *previous* run's `SUCCEEDED` as though it had just happened — invalidating the index the warm-up had just loaded, reloading it, and reporting a days-old duration as current. Completions are now ignored unless the same work was observed running in this session.

#### Chunks overflowed the model's window — fixed by sizing in tokens

Measuring token lengths to size the buckets surfaced a correctness bug that mattered more than any of the above. The chunker sized by characters assuming ~4 per token; this vault measures **3.46**, because code, paths and URLs tokenize far denser than prose. So a 1,200-character target was ~350 tokens and the 2,000-character ceiling ~580 — both past the model's 256-token window. **28.3% of chunks overflowed, and 14.4% of the vault's text was truncated away before ever reaching the embedder**: unsearchable while appearing indexed.

The chunker now budgets in tokens, counting through a `TokenCounter` so it stays independent of the embedding layer. Counts are additive across whitespace-joined blocks, so a growing chunk is never re-tokenized. Oversized blocks fall through progressively coarse-to-fine boundaries — paragraphs, lines, sentences, words, then a hard cut — because the pathological inputs are real (a table with no blank lines, a base64 blob with no spaces) and each finer level costs readability.

| | character-sized | token-sized |
| --- | --- | --- |
| chunks | 3,427 | 5,297 |
| median / mean tokens | 195 / 223 | 154 / 156 |
| chunks over the window | 28.3% | **0** |
| vault text never embedded | 14.4% | **0.00%** |
| full index | 358.1 s | 422.0 s |
| embed per chunk | 98.7 ms | **75.1 ms** |

Indexing costs 18% more wall clock because there are 55% more chunks, but per-chunk embedding got *cheaper* — smaller chunks fall into smaller buckets, so the two changes compound. Tokenizing during chunking costs 4.6 s of a 422 s run, about 1%, against the 14% of the vault it recovers. Retrieval is unchanged where it was already right: 0.69 on the virtual-machine query, 0.65 on LUKS, still "No good matches" for banana bread.

`LoamDatabase` went to version 3, since moving chunk boundaries makes stored rows describe text that no longer corresponds to any current chunk.

This also closes the CJK gap noted earlier — it was the same defect with a worse constant (~1 char/token), and a token budget is blind to script.

Worth correcting in this document: the "~200–400 tokens" target above predates knowing MiniLM's window is 256, so the achievable range is really 64–254. Chunks average below the 240 target because whole blocks are packed rather than split mid-paragraph.

**Every absolute indexing timing in this section and in "Dynamic sequence length" above was measured before the concurrent-indexer bug was found, so all of them are inflated by roughly 4x** — 116.4, 98.7 and 75.1 ms/chunk, and the 358 s and 422 s full-index runs. The *comparisons* still hold, since each pair was measured the same way and the contention applied to both sides, which is why those optimizations were real. But the absolute numbers describe two indexers contending, not the cost of the work. The corrected figure for a full index is **5,297 chunks at 24.1 ms/chunk in 151 s**.

#### Periodic reindex — verified end to end

Claimed since Phase 1 and never actually exercised. Now verified on a Pixel 8a against a temporarily shortened interval:

| | |
| --- | --- |
| Registers with JobScheduler | ✅ `BATTERY_NOT_LOW`, no network constraint |
| Runs unforced | ✅ |
| Detects changes incrementally | ✅ `notes=1` for one edited note; `notes=50` for fifty touched |
| Recurs on its own interval | ✅ 19:05:17 → 19:20:39 |
| Re-arms after each run | ✅ job 11 → 12 → 13 |
| Skips when a pass is already running | ✅ |

Three things make this awkward to test, and all three initially looked like failures:

- **`cmd jobscheduler run -f` cannot drive it.** WorkManager tracks the period itself and refuses with *"executed before schedule"*. The job appears to run and silently does nothing.
- **`am force-stop` deregisters the job entirely** until the app is next opened — so force-stopping Loam pauses background reindexing.
- **`adb logcat` replays its buffer**, so a naive `grep -m1` matches a stale line and exits immediately. Anchor with `-T`. This is the same shape as the WorkManager terminal-state replay above: old state read as current.

`schedulePeriodic` was also reachable only from `onVaultPicked`. The vault URI lives in SharedPreferences and the schedule lives in WorkManager's database; those stores can diverge — cleared data, a partial restore — leaving a valid vault with no schedule and nothing that would ever put one back, while search carries on against an index that has quietly stopped updating. It is now re-asserted on every app start, which is safe only because the policy is `KEEP`. `UPDATE` would restart the period on each call, so anyone opening Loam more often than the interval would reset the clock forever.

Not yet surfaced: the ViewModel observes only `UNIQUE_MANUAL`, so a periodic pass shows no progress in the UI and leaves the Reindex button live.

#### Opening a note — the system default does not hold

Results hand off with a plain `ACTION_VIEW`, on the assumption the user sets a system default so the picker appears once. On a Pixel 8a running Android 17 they cannot. Android records the preferred activity from the **MIME type alone**, then re-resolves using the full intent **including the content URI**, which admits activities matching only on scheme — a file manager's "Save as" here. Querying by type returns 3 candidates; the same query with the URI returns 4. The sets never agree, the stored default is discarded, and the picker returns on every note. Reproducible from adb with no Loam in the path, with and without `FLAG_GRANT_READ_URI_PERMISSION` and for both MIME types.

Loam therefore remembers the choice itself: long-press a result to pin an app. Stored as a `ComponentName` rather than a package, because one package can own several matching activities — Material Files ships both a text viewer and a "Save as", so `setPackage` would have replaced the system picker with a smaller one. An explicit component bypasses resolution entirely, so it cannot be perturbed by whatever else is installed. Package visibility comes from a `<queries>` element scoped to `VIEW text/*`, **not** `QUERY_ALL_PACKAGES`, which would be a permission on the F-Droid listing for a far broader question than the one being asked.

### Phase 2 — RAG Q&A
- Integrate the chosen LLM runtime; GGUF model loading; a model picker flow.
- Build retrieve-then-generate; the "sources used" panel.
- Handle low-confidence retrieval honestly — surface "no good matches" rather than forcing a hallucinated answer.
- **Exit criteria:** a real question about your notes gets a grounded, sourced answer, fully offline.

#### Phase 2 status — exit criteria met, verified on device

Asked on a Pixel 8a against the real vault, in airplane-mode-capable form since the APK has no network permission to disable:

> **Q:** What cipher and key derivation does my LUKS setup use
> **A:** The LUKS setup uses cipher `aes-xts-plain64` and key derivation `argon2id`.
> *6 sources, top match `+ MOC Arch Install FULL › LUKS Operations` at 0.64*

Shape of the built thing: `:core` holds `LlmEngine`, `AskQuestion` and `ModelLocation`; `:llama` implements the engine over llama.cpp with the native build settled below; `:app` installs the engine factory, since `:llama` depends on `:core` and `:core` naming `LlamaEngine` back would be a cycle.

**Sources are emitted before any answer text**, and that ordering is the feature rather than a layout choice. Retrieval finishes roughly ten seconds before the first token, so the panel fills the wait with something to read — and the citations are fixed before the model speaks rather than assembled to match what it said. The panel is always expanded; hiding it behind a disclosure would make Core principle #4 opt-in.

Model warm at startup measured **1054 ms**, close to the embedder's ~600 ms, because mmap maps the gigabyte rather than reading it. Answers reach the first token in ~8–10 s with `MAX_CHUNKS = 6` and then stream at reading speed. That constant is the grounding-versus-latency dial and belongs in Phase 3's settings screen.

Three outcomes are kept distinct because they need different responses: nothing cleared the relevance floor, no model chosen, and a model that failed to load. Conflating the last two sends a user with a corrupt GGUF back to the picker forever.

**Three bugs, all from a test environment more permissive than production.** Worth listing together because they are one mistake wearing three costumes, and every one produced a green suite and a broken app:

| what passed | what production did |
| --- | --- |
| `:llama`'s device tests, built with `useLegacyPackaging` | The app APK had `extractNativeLibs=false`; packaging options do not propagate from a library module, so `dlopen` would find no CPU backend |
| A `/proc/self/fd/N` test using a file the app could open by path | A SAF grant covers the URI, not the path, so re-opening is denied — `EACCES` |
| Generation tests with two-line prompts | `llama_decode` **aborts the process** when a batch exceeds `n_batch` (512), and a real RAG prompt is ~1,100 tokens |

The last one is the sharpest: the first genuine question killed the app with `SIGABRT` inside `llama_context::decode` while every instrumented test passed. The prompt is now fed in `n_batch`-sized pieces, with a guard for prompts that cannot fit the window at all, since reaching `llama_decode` with one aborts rather than returns.

A coda on the regression test written for that bug: its first version failed for the wrong reason, tripping the context-size guard because `token0 token1 …` tokenizes to about four tokens each. A test that fails for the wrong reason is only marginally better than one that passes for the wrong reason.

#### Phase 2 decisions — settled

**Runtime: Track A, llama.cpp via JNI.** Track B's portability payoff belongs to Phase 5, and its two routes both weaken on inspection: candle is immature for quantized ARM inference, and `llama-cpp-2` is Track A with a Rust layer on top. The RAG orchestration lives in Kotlin either way, so only the inference call sits behind the seam — which keeps the door open rather than nailing it shut.

**Model delivery: SAF file picker, not an in-app download.** This document previously assumed `INTERNET` had to enter at Phase 2. It does not. The user fetches a GGUF however they like and points Loam at it with the same picker already used for the vault, taking a persistable read grant. **Loam keeps zero network permission permanently**, which preserves the one guarantee the project leads with and keeps the README's one-line verification meaningful. The cost is that the user sideloads a 1–3 GB file, which suits the F-Droid/Obtainium audience this is built for.

**Quantization: Q4_0, not Q4_K_M** — see the measurements below.

#### Phase 2 spike — measured on a Pixel 8a before writing app code

Following Phase 0's pattern: prove the risky part with throwaway tooling first. llama.cpp cross-compiled for `arm64-v8a` against NDK r27 LTS, driven entirely over `adb shell`, with Qwen2.5-1.5B-Instruct (Apache-2.0, chosen over Gemma and Llama to avoid a Non-Free Assets tag).

**The build flags were worth 4x, and nearly cost the phase.** First measurements looked fatal: `pp512` at 26.6 t/s against `tg128` at 11.6 t/s. Prompt processing is batched matmul and normally runs 10–50x generation on CPU, so 2.3x was the anomaly worth chasing. It was not the hardware and not the quantization — `ANDROID_ABI=arm64-v8a` targets baseline armv8-a, so `HAVE_DOTPROD` failed its feature test and every dotprod/i8mm/SVE kernel was compiled out, on a CPU that advertises all three in `/proc/cpuinfo`. Rebuilding with `-DGGML_CPU_ARM_ARCH="armv8.2-a+dotprod+i8mm"`, measured back to back on the same thermal state:

| | pp512 | tg64 |
| --- | --- | --- |
| baseline armv8-a | 18.67 | 11.31 |
| +dotprod+i8mm | **74.81** | 12.40 |

Generation barely moved, correctly: it is memory-bandwidth-bound, while prompt processing is compute-bound. Only one of them could benefit.

**Two conclusions inverted once the kernels existed.** Against the crippled binary, Q4_0 measured *slower* than Q4_K_M (17.12 vs 20.14 pp512) and the obvious reading was "Q4_0 repacking does not help on Tensor G3". With working kernels it is decisively faster, and steadier:

| | pp512 | tg64 |
| --- | --- | --- |
| Q4_K_M | 83.92 | 14.07 ± 3.17 |
| Q4_0 | **108.54** | **18.39 ± 0.10** |

Thread count was swept and is not a lever — 6 threads (24.25) measured worse than 4 (26.62).

**Throughput depends heavily on thermal state.** On a cool device the same Q4_0 build reaches `pp512` 136.6 and `tg64` 26.7; hot, it settles nearer 108 and 18. Quote the range, not the best run.

**Storage: FUSE costs ~4%,** so the sideload plan holds and no copy into app-private storage is needed:

| | pp512 | tg64 |
| --- | --- | --- |
| ext4 `/data/local/tmp` | 136.60 | 26.74 |
| FUSE `/sdcard` | 131.21 | 25.83 |

**Both halves of the exit criterion behave, on real notes.** Given the actual `+ MOC Arch Install FULL › 8. LUKS2 Encryption Setup` chunk and asked which cipher and KDF the setup uses and why `--allow-discards` was passed, the model returned `aes-xts-plain64`, `argon2id`, and the `discard=async` consequence — the last being the detail that distinguishes reading the note from reciting LUKS boilerplate. Asked something the note cannot answer (SSD brand and price), it replied "I could not find that in your notes." Refusal is the Ask-side counterpart of "No good matches" and the property most likely to be fragile at 1.5B.

Practical shape: roughly **8–10 s to first token** for ~1,100 tokens of retrieved context, then streaming at reading speed. K becomes a design dial trading grounding against latency, rather than a hardware wall.

**Carried into the app build — this one is load-bearing.** The bundled `libllama.so` must not be built the way the first spike binary was, but hardcoding `armv8.2-a+dotprod+i8mm` would `SIGILL` on older arm64 devices lacking those extensions. `GGML_CPU_ALL_VARIANTS` with runtime dispatch is the answer, and it is a requirement rather than a nicety: the difference between the two builds is 4x. It is also not sufficient on its own — see below.

#### Runtime CPU dispatch picks the wrong variant on this device

`GGML_CPU_ALL_VARIANTS` requires `GGML_BACKEND_DL=ON` and `BUILD_SHARED_LIBS=ON`, refuses to coexist with `GGML_CPU_ARM_ARCH`, and ships one `.so` per feature set. llama.cpp defines seven for Android, and dispatch verifiably works — `load_backend: loaded CPU backend from ... libggml-cpu-android_armv9.0_1.so`.

It selects the variant with the most features, and on a Tensor G3 that is the wrong one. Measured back to back in a single invocation:

| variant | pp512 | tg64 |
| --- | --- | --- |
| `android_armv8.6_1` — DOTPROD, FP16, MATMUL_INT8 | **116.08** | **23.22** |
| `android_armv9.0_1` — the above plus SVE2 | 64.89 | 18.49 |

**1.79x faster with fewer features.** Tensor G3 implements SVE at 128 bits, so its SVE2 kernels lose badly to well-tuned NEON+i8mm, and the selector has no way to know that. Three separate measurements agree on the ratio, and `armv8.6_1` matches the single-arch static build (131 against 136), which rules out dynamic loading as the cause.

Nothing about this looks wrong at runtime. Dispatch succeeds, loads a genuinely more capable variant, and produces correct output — it is 42% slower than the build it replaced. It surfaced only because the new build was benchmarked against the old one on the same thermal state, and the gap was twice nearly dismissed as throttling.

**Build decision: enable `GGML_CPU_ALL_VARIANTS`, then omit the `armv9*` objects from the APK.** Dispatch falls back to `armv8.6_1`, old devices still get `armv8.0_1` and never `SIGILL`, and a hypothetical phone with genuinely fast SVE2 loses some speed rather than breaking. Revisit if a device is ever measured where SVE2 wins.

Also worth knowing for packaging: the variant build produces 12 shared objects totalling ~89 MB unstripped, against one static `libllama.so`. That is a Phase 4 APK-size problem, not a correctness one.

Still unproven, and it needs real app code: llama.cpp wants a filesystem path to `mmap`, while SAF yields a `content://` URI. The intended route is `ParcelFileDescriptor` → `/proc/self/fd/N`. The FUSE result above shows the filesystem is not the obstacle, but the fd-path trick itself is untested on this device.

Two operational traps, recorded because both cost real time. `-no-cnv` alone does not stop the current `llama-cli` entering its REPL — it needs `-st`, and without it the process spun out 160 MB of empty prompts. And short generations produce meaningless throughput figures: the same run that answered correctly reported 1.1 t/s, and a 7-token refusal reported 5.3 t/s, both dominated by fixed setup cost. Benchmark generation over a fixed token count or not at all.

### Phase 3 — Polish & real-world hardening

Done:
- ~~Settings screen~~ — three tunables that take effect without reindexing.
- ~~Conversation history in Ask.~~
- Memory: the model loads on demand and is released in the background.
- Native libraries aligned to 16 KB pages.
- ~~Key hardening~~ — shipped as a three-way choice rather than the single design the table above described. See below; it took a revert to get right.
- ~~UI tests for the Search pane~~ — ten, which required extracting the pane first.
- ~~Surface periodic runs in the UI~~ — half of it could not be done through WorkManager at all. See below.
- ~~Tests for the index-work state machine~~ — thirteen, and the extraction that made them possible was the same move as the Search pane.
- ~~Recalibrate `DEFAULT_MIN_SCORE`~~ — 0.35 → 0.44, plus the "show weak matches" reach that makes raising it safe. The measurement said something more useful than a number; see below.
- ~~Exclude patterns and chunk size~~ — the reindex flow they were waiting for turned out to be two flows, because only one of them invalidates anything.
- ~~Share-sheet integration~~ — and the text-selection menu, which is the half that actually changes how the app is used.
- ~~Home-screen search widget~~ — a shortcut whose whole value is the focused field, and which took a second pass to look like one.
- ~~Battery/thermal testing under a full-vault index~~ — a full rebuild costs ~1.7% of the battery. It also found a data-loss bug, which was worth more than the measurement.

Remaining:
- **Exit criteria:** daily-driver comfortable — you reach for it instead of manual grep.

Struck from this list: *paginated/streamed indexing so a large vault doesn't freeze the UI on first run*. Indexing runs in WorkManager with per-stage progress and never blocked the UI. The related worry about the vector index living in heap was also misplaced — measured at 8 MB for 5,297 chunks and 77 MB at 50k. The actual memory problem was somewhere else entirely; see below.

#### Index protection — offered rather than imposed, and it cost an index to build

The architecture table said "SQLCipher + AndroidX Biometric for unlock". Taken literally that is wrong for this app: a prompt on every launch fights this phase's own exit criterion and buys little against a phone that is already unlocked. But binding the key to device credentials is not free either — **any** authentication-bound key degrades unattended indexing, because WorkManager runs precisely when the phone has been sitting locked.

That makes it a genuine three-way tradeoff with no universally right point, which is the one good reason to make something a setting:

| level | protection | prompt | periodic reindex |
| --- | --- | --- | --- |
| Off (default) | index readable if the database is extracted | none | works |
| Device unlock | needs a recent unlock | none in normal use | waits for the next unlock |
| Every time | needs authentication per session | on open | disabled outright |

`EVERY_TIME` cancels the schedule rather than letting background passes fail to decrypt — a pass that silently stops working is the failure `ensurePeriodicIndexing` already exists to prevent, and recreating it as a side effect of a security setting would hide the cause even better.

**The first attempt destroyed a real index**, and the sequence is worth keeping because every step looked reasonable:

1. `setProtection` read the passphrase, **deleted the old Keystore key**, wrote the new level, then sealed under a new key.
2. Sealing with an auth-per-use key itself requires authentication, so it threw.
3. The old key was already gone and the stored ciphertext was now unreadable by anything. `keystore2: VERIFICATION_FAILED` on every launch, with no way out but clearing app data.

A class comment had asserted that adding authentication was "a spec change on the key, not a re-encryption, so it costs no migration". A Keystore key's authentication policy is fixed at generation; that sentence was simply false and it is what made the ordering look safe.

Two more bugs surfaced only while verifying the fix, and the second was worse than the original:

- **Android does not report per-use authentication as `UserNotAuthenticatedException`.** It arrives as `javax.crypto.IllegalBlockSizeException` wrapping `android.security.KeyStoreException: Key user not authenticated (internal Keystore code: -26)`. Catching the type matched nothing. In the change flow that meant no prompt ever appeared. In the unwrap path it was destructive: an unreadable seal is treated as a lost key and regenerated, so the first locked start under either level would have mistaken "not authenticated yet" for "key destroyed" and thrown the index away again by a different route. Detection now walks the cause chain and matches the Keymaster code.
- **A `Cipher` that has already thrown from `doFinal` cannot be handed to a `CryptoObject`.** Attempting the seal first and prompting afterwards left the prompt silently never appearing.

The fix is ordering enforced by structure rather than by care: two Keystore aliases with the active one recorded, a change builds a whole new key at the spare alias and seals under it, and only once that has *succeeded* does the pointer move and the old key get deleted. A cancelled prompt leaves the previous key working — verified by cancelling one and confirming 5,297 chunks still read.

Recovery was added alongside, and makes true a claim the UI was already making. An unreadable seal now regenerates the passphrase and drops the database, costing a reindex rather than stranding the app. "The worst case is a reindex" was written into the setting's own description while the code would in fact brick it.

**The testing lesson, which is the transferable part:** each level was verified in isolation and the *transition* between them never was — and the transition is exactly where it broke. Verifying states while ignoring state changes is the same shape as a test environment more permissive than production.

#### Phase 3 findings

**Warming the model at startup was wrong, and nothing looked wrong.** It was added in Phase 2 by analogy with the embedder. The analogy does not hold: warming the embedder costs ~600 ms and 22 MB, while warming the LLM put the process at **2.1 GB PSS**, about 849 MB of it the GGUF mapping. Those pages are private *clean*, so the kernel drops them rather than OOM — but Android's low-memory killer scores on PSS, so opening Loam to search made it a prime candidate for being reaped, and being reaped costs the warm search index too. Measured across a cycle:

| state | PSS |
| --- | --- |
| launch, Search only | **277 MB** |
| Ask tab opened | 2,195 MB |
| backgrounded | **249 MB** |
| foregrounded on Ask | 2,163 MB |

The model now loads when Ask is first shown and is released whenever the app leaves the foreground. Reopening costs ~1.8 s, cheap because mmap maps the weights rather than reading them.

**Native libraries were 4 KB-aligned and would not have loaded on a 16 KB-page device at all.** Every prebuilt dependency shipped aligned; everything `:llama` compiled did not. Android 15+ can run 16 KB pages and a Pixel 8/9 can enable it in developer options, so Ask would have failed outright there. NDK r27 makes alignment opt-in via `ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES`; r28 made it the default, which is why nothing complained until a device did.

This one is worth separating from the rest of the project's near-misses. It was not a test more permissive than production — it was a *correct* test of a configuration that is not universal. The build succeeded, every test passed, and the app worked, because the test device uses 4 KB pages. No reasonable test would have caught it; the OS reported it. **Phase 4 should assert alignment in the build rather than wait for a warning.**

**Settings surfaced a state that had quietly become ambiguous.** Since the model became lazily loaded, `ModelState.None` means both "no model configured" and "configured but not resident". Reading it alone printed "search works without one" directly beneath the model's own filename.

**A periodic run cannot report what it did, and that is not a WorkManager oversight.** Watching `UNIQUE_MANUAL` alone left a background pass invisible twice: no progress while it ran, and the Reindex button live throughout, so the only thing the user could do about a pass already in flight was enqueue one that could only skip. Observing both unique names fixes the live half.

The other half has no fix in that direction. A `PeriodicWorkRequest` never reaches a terminal state — it returns to ENQUEUED after each pass — so `WorkInfo.outputData` stays empty for it and the entire result-reporting path the manual run uses does not exist. The outcome has to come from somewhere both kinds of run can reach, which here is the worker writing it down itself. Persisting it rather than holding it in memory is the actual point: the pass runs every six hours, so nearly every one completes with the app closed and the run worth reporting is one this process never saw.

That turned the old replay problem inside out. The manual path needed a guard because a days-old SUCCEEDED, replayed to every new subscriber on launch, looked exactly like a run that had just finished. Stated as a dated fact — *background pass · no changes · 2 hours ago* — a result from a previous launch stops being a bug and becomes the feature. The guard is still there, but now it only governs whether to reload the index, not what to display.

**`cmd jobscheduler run -f` cannot drive periodic work, and now there is a reason on the record rather than an observation.** WorkManager answers `Delaying execution for IndexWorker because it is being executed before schedule` and reschedules — the guard is WorkManager's, above JobScheduler, so forcing the job cannot reach it. The `-n androidx.work.systemjobscheduler` namespace flag, which looked like the missing piece, gets as far as `Running job [FORCED]` and no further. Verification therefore meant a locally shortened interval and a real pass: recorded `periodic=true`, and the line appeared in the already-open app without a relaunch, which is also the only way to check that the worker's write reaches the UI's flow rather than merely the file.

Worth naming for its shape rather than its content, because it is the same shape as every entry in the Risks list: the part that cannot be tested cheaply is the part the feature is *about*. Both ends of the chain have tests — the pane renders a fabricated run, the log round-trips a fabricated write — and neither could have told us whether a periodic pass reaches either of them. Ten minutes of waiting bought the one fact no fixture could.

**A screenshot caught a wording bug the tests were structurally unable to.** `DateUtils.getRelativeTimeSpanString` renders anything under a minute as "0 minutes ago" — truthful, and the single phrasing here that reads like a failure. It is also what every tap of Reindex produced, so it would have been the most-seen state of the feature. Every test used a fabricated timestamp, and none happened to use *now*; the device did, immediately. Now "just now", with a test that would have caught it.

**The Search pane was untestable for a reason that had nothing to do with testing.** Ask got twelve tests easily and Search got none, and the difference was not effort: `AskPane` had been written as a separate composable taking `UiState`, while Search stayed inline in `LoamScreen`, holding a ViewModel and a `Context`. Reaching its branches meant standing up a database and an embedder to produce states that are three fields of a data class. Extracting it — no behaviour change, a pure function of `UiState` — turned ten tests into fabricated state and a callback each, running in 18 s with no vault present. Testability here was a structural property, not a test-writing problem, and the tell was that one pane was easy and its neighbour was impossible.

Two things about the tests themselves. The `assertDoesNotExist` assertions — no Reindex button mid-index, no "No good matches" before a search, no stale counts under an error — are the ones that pass for free if a string is renamed, so each has a positive counterpart asserting the same text *is* present in the state where it belongs. That pairing is what makes an absence assertion mean anything, and it is cheap. And the query field cannot be found by its label or placeholder: both are sibling nodes rather than part of the editable field's semantics, so `performTextInput` finds nothing to type into. It is matched by `hasSetTextAction()`.

#### Battery testing found a data-loss bug, which was worth more than the measurement

**The battery answer is boring, which is the good outcome.** A full rebuild of the 392-note vault costs about **1.0% of the battery**. (First reported here as 1.7%, from a debug build — see below.)

| | |
| --- | --- |
| Full rebuild | 5,297 chunks, 139.3 s, screen on, unplugged, **release build** |
| Charge consumed | 54 mAh (1,760 → 1,706 mAh) |
| Idle screen-on baseline | ~240 mA |
| Attributable to indexing | ~1,156 mA, so **~45 mAh** of a 4,492 mAh battery |
| Battery temperature | 31.2 → 33.3 °C |

Incremental passes are ~2 s and round to nothing. Only a first index or a chunk-size change costs anything worth naming, and 1.7% is not a reason to constrain either.

**Thermal throttling is real and the framework never admits it.** `Thermal Status` stayed 0 for every run. Meanwhile per-chunk *inference* rose from 15.7–20.4 ms in the opening windows to 26–30 ms by the end — about 50% slower for identical work — and a second full rebuild started on an already-warm device came in 20% slower overall (287.0 s against 238.8 s) with `embed/chunk` up from 25.9 to 31.4 ms. The HAL's own `BIG`/`LITTLE` readings sat pinned at 86/85 °C for entire runs and are not live values. **The app's own per-window timings were the only honest thermometer here**, which is an argument for keeping that logging.

**The unexplained 2x is chunking, and none of the guesses was right.** Against the 151 s run recorded above:

| stage | on file | measured | Δ |
| --- | --- | --- | --- |
| chunk | 4.0 s | **83.5 s** | +79.5 s |
| embed | 128 s | 137.4 s | +9.4 s |
| read | 11.3 s | 8.1 s | −3.2 s |
| store | 4.8 s | 6.6 s | +1.8 s |
| walk | 1.5 s | 1.6 s | — |
| total | 151 s | 238.8 s | +87.8 s |

Chunking is 91% of the gap and reproduced at 98.0 s on the second rebuild — 35% of the run in both, against 2.6% on file.

**All of which was the debug build, and none of it was real.** Left standing above because the correction is the more useful record. Instrumented tests and `adb install` of `app-debug.apk` had been the whole session's workflow, so every timing here came from `debuggable=true`. Installing the release APK and repeating the identical rebuild:

| stage | on file | debug (cold) | debug (warm) | **release** |
| --- | --- | --- | --- | --- |
| chunk | 4.0 s | 83.5 s | 98.0 s | **2.7 s** |
| embed | 128 s | 137.4 s | 166.3 s | **118.1 s** |
| total | 151 s | 238.8 s | 287.0 s | **139.3 s** |
| embed/chunk | 24.1 ms | 25.9 ms | 31.4 ms | **22.3 ms** |

There is no regression. The 151 s baseline reproduces — release beats it. Chunking was never 35% of anything; it is 2% and always was. The tokenizer is the part `debuggable` punishes hardest: per-chunk tokenization measured **5.4–7.8 ms in debug against 0.2–0.3 ms in release**, about 25x, because it is allocation-heavy inner-loop Kotlin and that is exactly what the debug runtime declines to optimize.

**This is the Phase 1 finding "Never benchmark a `debuggable` build", walked into with both eyes open.** It has been in this document since the sqlite-vec decision, where the same mistake made a 13.81 ms operation look like 533 ms. The tell was there in the data too: `Character.getType`, a table lookup, measured 402 ns per character, and no plausible story about chunking explains a slow lookup in the platform. Reading that as evidence about `Chunker` rather than about the runtime took a second wrong conclusion to notice.

Two things were built on the false finding and then unbuilt. A `WordPieceTokenizer` optimization — code-point appends instead of substrings, a separate continuations map, a length cap on subword probes — was written, verified byte-identical against 2.6 MB of real notes, measured at 18% faster, and **reverted**: 18% of a cost that does not exist, bought with a duplicate 22k-entry vocabulary map in memory. And a `TokenizerStageTest` timing framework calls was deleted outright, since its entire output was absolute numbers from the only build it can ever run in. What survived is `ChunkerProfileTest`, which reports *ratios* — 3.3x the vault tokenized per index, 246 counter calls per note — and those hold across builds.

**And the part that mattered.** A periodic rebuild wiped all 392 notes, embedded 85, and was stopped when the screen went off. The vault sat 78% unindexed, the UI reported "85 notes" as though that were the whole of it, and search silently missed most of the corpus. Three faults had to line up:

1. The rebuild cleared the table before re-embedding. At minutes per pass on a phone that dozes, the window in which the index does not exist is a window that gets hit — routinely, not exceptionally.
2. `CancellationException` was caught by the general `catch (e: Exception)` and recorded as a failed run reading "Job was cancelled", which the UI then showed as an index error. Being stopped is not failing.
3. `Result.failure()` is terminal, so the interrupted pass was never retried. Each fresh attempt would wipe and restart from zero, so a rebuild too long to fit one job window could never converge.

Notes are now replaced one at a time and nothing is cleared up front, so the index stays complete throughout and the worst an interruption leaves is a mix of two chunk shapes — a quality difference rather than an absence. Verified by reproducing it: killing the app 50 notes into a rebuild leaves 392 notes and 5,368 chunks, and WorkManager restarts the pass on its own.

The transferable part is not "handle cancellation". It is that **the fingerprint-on-success design was reasoned about carefully and still lost the data.** The reasoning went: write the marker only when the pass completes, so an interrupted rebuild is retried rather than banked half-done — which is correct, and is what the commit message argued. The question never asked was what the index looks like *while* that retry is pending. Designing the recovery path is not the same as looking at the state you recover from.

#### The widget that worked and looked wrong

A shortcut shaped like a search field. What it buys over the launcher icon is the focused field — Search open, keyboard already up — so the focus plumbing is the feature and the widget is only the trigger.

It deliberately holds nothing. A note count is the obvious thing to put on a widget and is exactly the thing this one must not depend on: reading it means opening the encrypted index, which cannot be opened at all under `EVERY_TIME` protection. Holding nothing is also why `updatePeriodMillis` is zero — there is no state to go stale, so there is no reason to ever wake the app to redraw a constant. RemoteViews rather than Glance for the same kind of reason: a widget cannot host a real text field either way, and a shortcut does not justify a dependency that has to be accounted for at F-Droid review.

Two things were checked rather than assumed, and a third should have been.

**RemoteViews accepts only a fixed set of view types.** A layout it rejects does not fail the build or any Compose test — it renders as "Problem loading widget" on the home screen. A test applies the layout for real, which is the only cheap way to find that out.

**`exported="false"` on a receiver the system broadcasts to looks wrong and is not.** `APPWIDGET_UPDATE` is a protected broadcast, so the system is the only possible sender and is exempt from the export check. Neither available probe could confirm this — an adb broadcast is refused as an unprivileged sender, and `bindAppWidgetIdIfAllowed` from an instrumented test returns false because binding belongs to launchers. It was settled by pulling the manifests of two working widgets installed on the same device, one RemoteViews and one Glance, and finding both declare exactly this. When you cannot exercise a configuration, the next best evidence is a shipping one.

**The third:** the widget worked perfectly and looked wrong. It was as tall as whatever cell the launcher gave it — 1050 px in a 400 dp cell — because the pill was `match_parent`. Six tests passed on that version: it registered, it inflated, it kept its click target, it launched and focused the field. Every one of them checked that it *works*, and the defect was that a search widget shaped like a box does not read as a field. The user reported it in one glance.

The fix is a transparent frame taking the cell with a fixed-height pill centred in it, and the test that now covers it measures the pill after laying it out in an oversized cell — the only thing that distinguishes the two layouts, since both inflate and both click through. Reverting the layout fails it at 1050 against 126 and nothing else.

Worth stating plainly because it is a gap in this document's whole approach: **every technique here is for checking that something is correct, and none of them look at it.** A screenshot would have caught this instantly, and the one screenshot taken landed on a home-screen page that did not have the widget on it. For anything with a visual affordance, "the tests pass" and "it looks like what it is" are separate claims and only one of them is being made.

#### The share sheet was the smaller half of "share-sheet integration"

`ACTION_SEND` is the obvious one and `ACTION_PROCESS_TEXT` is the one that matters. It puts Loam in the text-selection menu, so highlighting a sentence anywhere on the device asks what you have already written about it — the premise of the app applied to text in front of you rather than text you retype. It was worth writing the item down as "share sheet" and then finding the better reading of it while implementing, which is an argument for keeping roadmap items about the *need* rather than the mechanism.

`singleTask` is load-bearing rather than tidy-looking. Without it a share stacks a second `MainActivity` over the one already holding a warm index and an open model, each with its own ViewModel. With it, the share arrives at `onNewIntent` — which no recomposition observes — so the text is held as activity state and delivered through a `LaunchedEffect`, and a token in `UiState` forces the Search tab, since a share means "search this" regardless of which tab was left open.

Shared text is flattened to one line and cut at 1,000 characters. The cut is honesty rather than defence: the embedder reads 256 tokens and stops, which is ~886 characters at this vault's measured density, so a forty-kilobyte article pasted into a one-line field would imply a search that never happened.

Worth stating explicitly because it is the kind of thing that erodes quietly: **a shared query is a query.** Nothing arriving this way is stored, and the index stays derived from the vault and nothing else. The release APK's permission list is unchanged by the feature, and a test asserts no permission containing INTERNET or NETWORK exists rather than trusting that.

Registration is tested by querying the installed package for handlers of each intent, not by reading the manifest source — the source is not what the system resolves against, and this project has already paid for an app APK packaged differently from the test APK that validated it.

#### The two settings that needed a reindex flow needed two different ones

Both were held out of Settings on the grounds that they invalidate the stored index. Only one of them does.

**Excludes invalidate nothing.** A note that stops matching the walk stops being found, and the stale sweep that has always been there deletes it. Rebuilding for that would be ~285 s of re-embedding to achieve a delete. Directories are skipped rather than walked-and-filtered, since enumeration is the expensive half of a SAF pass — one `ContentResolver` query per node, 13.5 s for 392 files before the `DocumentsContract` rewrite.

**Chunk size invalidates everything, and nothing else can tell.** Every note's `(mtime, size)` is unchanged, so the incremental sweep revisits nothing, while every stored chunk is a split of the old shape. The signal has to be carried separately: a chunking fingerprint, compared at the start of a pass and written at the end. Written at the end specifically — an interrupted rebuild must be retried rather than banked, because a half-rebuilt index is valid-looking rows in two different shapes with no symptom that anything downstream could detect.

Both are staged behind a single Apply that says what it costs, because applying either live would start a full pass over the vault per keystroke and per slider tick.

`ExcludeRules` is gitignore-shaped and deliberately a subset — no negation, no character classes, no anchoring subtleties. Each of those is a rule the user would have to learn from behaviour, and over-matching is the failure mode to design against: a pattern that quietly takes more of the vault than it says has no symptom except search coming back empty for a note you know you wrote. The regex translation escapes by default rather than by blocklist, because a real vault contains `C++ Notes.md` and `Step (2).md`, and either one left unescaped is a literal pattern that has silently become a different one.

**One defect the device caught and no test would have.** An absent chunking fingerprint read as "unknown", so the first pass after upgrading rebuilt the whole index — every existing install paying a full re-embed to arrive at the shape it already had. Watched it happen, at **285 s**. Absent means "built before chunking was configurable", which back then could only have been the default. The general shape is the same one the settings-default hazard has: *a new field's null state is a claim about history, and reading it as ignorance is a decision, not a default.*

Verified against the real vault rather than a fixture: `AE *.md` took it from 392 notes to 389, and the notes it removed — which had turned up in the relevance-floor probe output earlier the same session — were gone from the index entirely. Clearing it brought back exactly 3 notes and 252 chunks, and 5,045 + 252 is 5,297, the count it started at.

One measurement left unexplained rather than explained away: that full rebuild ran at 46–52 ms/chunk against the 24.1 ms/chunk this document records for the original full index, 285 s against 151 s. Screen-on, thermal state after a long test session, and foreground contention are all plausible and none of them was isolated. Recorded as an open discrepancy, since the alternative is exactly the confident just-so story this project keeps having to retract. (Closed below: it is chunking, and none of the three guesses was right.)

#### The relevance floor cannot separate relevant from irrelevant, and now says so

Recalibration measured 30 probe queries against the real 5,297-chunk index, labelling each top hit by hand:

| | top-1 score |
| --- | --- |
| direct on-topic questions | 0.520 – 0.820 |
| conversational phrasings of answerable questions | 0.328 – 0.525 |
| questions with nothing useful to show | 0.166 – 0.413 |

**The bands overlap**, so no threshold separates them and the constant only chooses which mistake to make. That is the result worth keeping; the new number is a consequence of it.

The first round of probing said the opposite, and would have supported almost any value. It used crisp on-topic queries against clearly off-domain noise — "encrypting a disk with LUKS" versus "banana bread recipe with walnuts" — which produced a clean gap between 0.328 and 0.520 and a comfortable story. Adding two categories collapsed it: conversational phrasings of questions the vault *does* answer ("I locked myself out and need to get back in" → BitLocker unlock and PAM lockout notes, 0.328), and near-domain questions where a long list-like chunk scores well on vocabulary alone ("configuring an Apache virtual host" → KVM network configuration, 0.453). A probe set built from the cases you can label confidently is a probe set built from the cases that were never in doubt.

`0.35 → 0.44`, the midpoint between the highest wrong top hit (0.413) and the lowest right one (0.465). The old value sat 0.022 above the worst pure noise and let three unrelated top hits through; the new one halves the error count over the set. Everything in 0.42–0.45 scored identically, and thirty queries against one topically narrow vault does not justify more precision than that — which is an argument for the slider, not against the measurement.

**"Show weak matches" is what makes raising it a change rather than a trade.** Two genuinely answerable questions score 0.328 and 0.353 and are now below the line. An empty result offers a second search at 0.7× the floor, and everything it returns is labelled as weak. Relative rather than fixed, so it tracks a floor the user has moved instead of quietly becoming a second setting.

Two things the device caught that no test would have. **Storing a setting equal to its default froze it forever**: the recalibration would have reached nobody who had ever opened Settings, including the development device, which held `relevance_floor: 0.35` from a slider touch. Defaults are now stored as *absent*, so a re-measured constant reaches everyone who never expressed a preference and leaves alone everyone who did. This is a general hazard for a project whose defaults are measured constants and whose method is re-measuring them. And **the new button was off-screen exactly when it was needed** — the activity is edge-to-edge, so `adjustResize` never shrinks the window, and the centred empty state sat under the keyboard, which is the state you are always in the moment after typing a query that found nothing.

**The same extraction, a second time, on the piece that actually branches.** Surfacing periodic runs left the ViewModel holding the only real decision logic in the feature — which of the two unique works is running, which finish to react to, which to ignore as a replay — with the pane and the run log tested on either side of it and nothing on the thing between them. Splitting the decision (`IndexWorkWatcher`) from acting on it made thirteen tests possible with no WorkManager, database or embedder. That this was the second instance in two features suggests the rule generalises: when a component is hard to test, the useful question is what it is holding that it should not be, not how to build a harness big enough to contain it.

Those tests are built from real `WorkInfo` values rather than an interface of our own, which is deliberate given how much of this file is about fixtures that were easier than the thing they stood for. It is what lets the suite exercise the state no manual run can produce: a periodic run "finishing" by returning to ENQUEUED. A watcher waiting for a terminal state would compile, pass anything written against a hand-rolled model, and leave the UI showing a background pass that ended hours ago.

**Assertions were checked by breaking the code, not by reading them.** Three mutations — dropping the background prefix, dropping the watched-id reset, preferring the periodic run over the manual one — each failed exactly one test, and it was the test that claimed to cover it. That is two facts for the price of one run: the tests are load-bearing, and they are not so entangled that one defect lights up half the file. Worth doing wherever a test's whole value is a claim about a branch nobody can easily trigger by hand.

**A stalled screenshot produced a confidently wrong bug report.** Conversation history was reported here as broken — the second turn "produced nothing" — on the strength of a scroll that had stopped moving. Logging at the commit point settled it in one run: both turns had always committed. The layout problem was real (oldest-first buried a new answer under a screen and a half of source cards, since the composer sits at the top here rather than the bottom), but the diagnosis was not. Instrument the state; do not read it off pixels.

### Phase 4 — F-Droid packaging & release
- Reproducible build setup; a full license manifest (SQLCipher, sqlite-vec if used, llama.cpp, embedding model weights, each with correct SPDX identifiers).
- **Assert 16 KB page alignment in the build.** Nothing here checks it, the app works on a 4 KB device regardless, and it took the OS reporting an incompatibility to notice. A `llvm-readelf` check over the packaged `.so` files is a few lines and turns a silent device-class failure into a build failure.
- Note that `useLegacyPackaging = true` doubles the on-disk native footprint, since libraries are extracted at install. It is required — llama.cpp's CPU backends are `dlopen`'d by absolute path — but it belongs in the listing rather than being discovered in review.
- `libonnxruntime.so` is 28.6 MB of the 39 MB native payload, larger than all of llama.cpp. If APK size becomes an issue, the embedder is the target, not the LLM.
- Anti-feature self-assessment (see the Non-Free Assets note above); settle the EmbeddingGemma-vs-MiniLM question deliberately here if you haven't already.
- Submit to fdroiddata; iterate through review.
- **Exit criteria:** installable from F-Droid, or at minimum your own repo via Obtainium, with an honestly-labeled anti-feature list.

### Phase 5 — Later, optional
- Multi-vault support (e.g., separate encrypted stores for work vs. personal notes).
- A live "related notes while you write" sidebar, Smart-Connections-style, if you ever build or hook into an editor.
- A desktop companion sharing the same Rust core (if you took Track B) — this is the payoff for going Rust-first: the same embedding/retrieval engine running on your Fedora box, not just your phone.

## Risks & open questions

- **Everything so far is tuned to one topically narrow corpus.** The vault is real, but 97% of it is Linux documentation in a uniform register. The relevance floor, the minimum chunk size, and the characters-per-token assumption are all fitted to that, which is correct for a personal tool and a liability the moment anyone else installs it. A vault of short captures, daily journals, or mixed scripts is the first thing likely to break them. Recalibrating the floor in Phase 3 sharpened this rather than settling it: on *this* corpus the score bands for "can answer" and "cannot" already overlap, so the constant is not merely fitted to one vault, it is fitted to one vault and still wrong some of the time there.
- **Small-model hallucination survives RAG.** Grounding reduces it, doesn't eliminate it — the "sources used" panel is doing real work here, not decoration.
- **SAF has no true background filesystem watch.** Periodic + manual reindex is the honest architecture, not a stopgap.
- **First index of a large, long-lived vault will be slow and battery-heavy.** Needs a visible progress state; shouldn't run silently in the background on first launch.
- **A destructive step inside a long operation is a window that will be hit.** A rebuild cleared the index before refilling it, which is safe only if the pass always finishes. On a phone, a four-minute pass routinely does not — the screen goes off and the system takes the CPU back. The correct question about any such step is not "will this be interrupted?" but "what does a user see if it is?"
- **Nothing here looks at the app.** Every technique this document accumulates checks that something is correct — a measurement, an assertion, a mutation. None of them can see that a working widget is shaped like a box instead of a field, which is how that shipped past six passing tests and was caught by the user in one glance. Where something has a visual affordance, "the tests pass" and "it looks like what it is" are separate claims.
- **A new field's absent state is a claim about history.** Twice now: a setting stored equal to its default froze that default forever, and an absent chunking fingerprint read as "unknown" would have rebuilt every existing index to reach the shape it already had. Both times the honest reading was available — nobody expressed a preference; chunking could only have been the default — and both times reading it as ignorance was a decision made by not making one.
- **Testing states is not testing transitions.** Index protection was verified at each of its three levels and shipped; it destroyed a real index on the *change* between two of them, which no test touched. The same shape as the entry below — the thing exercised was adjacent to the thing that mattered.
- **A test environment that can do more than production proves nothing about production.** Three separate bugs in Phase 2 came from this, each with a green suite and a broken app: an APK packaged differently from the test APK, a fixture the app could open by path where the real file needs a SAF grant, and a prompt short enough to stay under a batch limit the real one exceeds. When a test constructs its own inputs, it tends to construct ones it can satisfy. Ask what the real path crosses that the fixture does not.
- **"Newer, more capable, more features" keeps measuring slower.** Q4_K_M lost to Q4_0; SVE2 lost to plain NEON+i8mm by 1.79x; ObjectBox, the most capable vector store, was ruled out on licensing. On-device, the sophisticated option is a hypothesis, not a default — and the wrong one produces correct output at half the speed, which no test catches.
- **Recurring lesson, now six for six: a claim is only as good as your model of what produced it.** A debug build inflated cosine search 36x — and then, three phases later, inflated chunking 40x and invented a regression that never existed, because instrumented tests only run against the debug variant and nobody thought about which build the numbers came from; characters stood in for tokens and hid 14% of the vault; a burst of three embeddings stood in for sustained load; two concurrent indexers stood in for one, inflating every indexing figure by 4x and inviting a thermal explanation for a contention problem; and a `.gitignore` comment stood in for the vault's actual contents, producing a confident and entirely wrong claim that the exit criteria were unmet. Two of these are the same mistake made twice, years of project-time apart, with the warning already written down — which says something uncomfortable about how much protection a written-down lesson actually offers. **The one habit that would have caught it: before believing any timing, say out loud which binary produced it.** Documentation is a proxy too. Check the thing.
- **Model licensing shapes your F-Droid listing.** Decide the EmbeddingGemma/MiniLM (and any LLM model) question with the anti-feature consequences in mind, not after the fact.

## Prior art & references

- [Reor](https://github.com/reorproject/reor) — the closest thing to this that exists, but desktop-only
- [Smart Connections](https://smartconnections.app/smart-connections/) — same idea, as an Obsidian plugin
- [sqlite-vec](https://github.com/asg017/sqlite-vec) · [Android/iOS notes](https://alexgarcia.xyz/sqlite-vec/android-ios.html)
- [ObjectBox F-Droid incompatibility thread](https://github.com/objectbox/objectbox-java/issues/1100) — why it's ruled out above
- [EmbeddingGemma model card](https://ai.google.dev/gemma/docs/embeddinggemma) · [announcement](https://developers.googleblog.com/en/introducing-embeddinggemma/)
- [Google AI Edge function calling for Android](https://ai.google.dev/edge/mediapipe/solutions/genai/function_calling/android) — relevant if you extend past pure Q&A later
