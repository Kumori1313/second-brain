# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repository state

This repository is currently pre-implementation. It contains no Android project, no Gradle build, and no Kotlin source yet — only `loam-architecture-roadmap.md` (the design doc) and `LICENSE` (MIT). There are no build, lint, or test commands to run because no buildable code exists.

When asked to start implementing, the roadmap doc is the authoritative spec — read it in full before scaffolding anything, and follow its phase ordering (Phase 0 spike before Phase 1 MVP, etc.) rather than jumping straight to a full app skeleton.

## What this project is

Codename "Loam": a FOSS, Android-native semantic search and Q&A (RAG) layer over Markdown notes the user already owns (e.g. an Obsidian vault). It reads `.md` files in place via Storage Access Framework — it is explicitly *not* a note editor and must not invent a proprietary storage format.

## Non-negotiable constraints (from the roadmap's "Core principles")

These act as acceptance criteria for any implementation work in this repo:

1. **Zero network permission for core functionality.** Search and (once a model is downloaded) Q&A must work in airplane mode. The *only* legitimate network use is a one-time, explicit, user-initiated model download.
2. **No proprietary storage format.** Notes remain plain `.md` files wherever the user already keeps them.
3. **No Google dependencies.** No Play Services, no Firebase, no GMS-only APIs — assume the target device may lack Play Services entirely.
4. **Auditable, not a black box.** Every RAG answer must show which notes/chunks it came from. No silent telemetry.
5. **F-Droid-distributable.** Every dependency must carry a real OSI-approved license; this rules out some otherwise-attractive libraries (see ObjectBox note below).

Any code or dependency choice that violates one of these should be flagged rather than silently added.

## Intended architecture (per the roadmap)

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

Key decisions worth knowing before touching related code (full rationale and alternatives-considered table is in the roadmap):

- **Vector search:** brute-force cosine similarity for MVP; only add sqlite-vec if a real vault outgrows it. ObjectBox is deliberately ruled out — its native engine's license is not OSI-approved, which F-Droid maintainers have flagged as disqualifying.
- **Embedding model:** EmbeddingGemma is the default target (best small on-device RAG model), with MiniLM-L6-v2 (Apache-2.0) as an alternate build flavor to avoid F-Droid's "Non-Free Assets" anti-feature tag. This is a deliberate per-build-flavor choice, not an implementation detail to collapse away.
- **Local LLM runtime (Phase 2+):** two tracks under consideration — Track A (llama.cpp via JNI, GGUF models) or Track B (Rust core via candle/llama-cpp-2, exposed through UniFFI, reusing the Cubiomes-FFI pattern from the author's Minecraft seed-map project). Google AI Edge/LiteRT is a possible alternative but reintroduces a Google-authored dependency.
- **Encryption at rest:** SQLCipher + AndroidX Biometric for unlock — the index itself is sensitive because it's derived from personal notes, not just the source files.
- **Distribution:** F-Droid, mirrored on GitHub Releases (Obtainium-friendly). Not the Play Store.

## Data flow (summarized — see roadmap for full detail)

- **Indexing:** grant SAF access → walk tree, chunk notes (~200–400 tokens, heading/paragraph-aware, slight overlap) → embed on-device → store chunk text/embedding/source path/heading breadcrumb/mtime-hash in the encrypted store → WorkManager periodically re-checks fingerprints (SAF has no true background filesystem watch; periodic + manual reindex is the intended design, not a gap to "fix").
- **Search:** embed query with the same model used for indexing → rank stored vectors by cosine similarity → show top-K with source file/heading/snippet, tapping opens the real file in the user's own `.md` app.
- **Ask (RAG, Phase 2+):** embed question → retrieve top-K chunks → build prompt from chunks + question → local LLM generates answer → show answer with an expandable "sources used" panel. Low-confidence retrieval should surface "no good matches" rather than force a hallucinated answer.

## Roadmap phases

The roadmap sequences work as: Phase 0 (spike — prove embedding inference and SAF read work on real hardware before writing app code), Phase 1 (MVP: index + semantic search, no LLM), Phase 2 (RAG Q&A), Phase 3 (polish/hardening), Phase 4 (F-Droid packaging), Phase 5 (later/optional: multi-vault, live related-notes sidebar, desktop companion via shared Rust core). Don't skip ahead to later-phase work (e.g. wiring an LLM) before earlier exit criteria are met, unless the user explicitly asks to.
