# Loam

Semantic search over the Markdown notes you already own — running entirely on your phone.

Ask *"did I ever write about setting up a virtual machine"* and get the right note back, even when it never uses those words. Loam reads `.md` files in place through the Storage Access Framework, embeds them on-device, and searches by meaning rather than by string match.

It is **not** a note editor and does not want to be. Your notes stay plain `.md` files wherever you already keep them; tapping a result opens the real file in whatever app you already use.

Android · Kotlin · Jetpack Compose · MIT

---

## Non-negotiables

These are acceptance criteria, not aspirations. Anything that violates one gets flagged rather than quietly added.

1. **Zero network permission.** Search works in airplane mode. The APK has no `INTERNET` permission at all — not "we promise not to phone home", but a guarantee the OS enforces. The only legitimate future network use is a one-time, explicit, user-initiated model download in Phase 2.
2. **No proprietary storage format.** Notes remain plain `.md` files in your own folder. Delete Loam and you have lost nothing.
3. **No Google dependencies.** No Play Services, no Firebase, no GMS-only APIs — the target device may not have Play Services at all. On-device inference runs on ONNX Runtime, which is Microsoft's.
4. **Auditable, not a black box.** Every result shows its source file, heading breadcrumb, and similarity score. No silent telemetry.
5. **F-Droid-distributable.** Every dependency carries a real OSI-approved license. This rules out otherwise-attractive libraries — ObjectBox is excluded because its native engine's license is not OSI-approved.

**Verify the network claim yourself**, against the built artifact rather than the manifest:

```bash
~/Android/Sdk/build-tools/*/aapt2 dump permissions \
  app/build/outputs/apk/release/app-release.apk
```

Expected output, in full:

```
uses-permission: name='android.permission.WAKE_LOCK'
uses-permission: name='android.permission.RECEIVE_BOOT_COMPLETED'
uses-permission: name='android.permission.FOREGROUND_SERVICE'
uses-permission: name='dev.loam.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'
```

The first three come from WorkManager and are what let a long index survive the screen turning off and resume after a reboot. The fourth is a self-scoped signature permission AndroidX injects for local broadcasts; it grants nothing outward. `ACCESS_NETWORK_STATE` also arrives from WorkManager and is stripped with `tools:node="remove"` — it grants no data access, but shipping a permission with "NETWORK" in its name would undercut principle #1 for anyone reading an F-Droid listing.

## Status

**Phase 1 complete** — indexing and semantic search, no LLM yet. Measured on a Pixel 8a (Tensor G3, Android 17) against a real 392-note Obsidian vault:

| | |
| --- | --- |
| Full index | 5,297 chunks in 151 s (24.1 ms/chunk) |
| Incremental reindex, no changes | ~2 s |
| Warm query | 12–13 ms (10 ms embed + 2–3 ms scan) |
| First query after launch | 13 ms (session pre-warmed at startup) |
| Vault walk, 392 notes | 1.5 s |

Retrieval, on queries sharing no vocabulary with the notes they find:

| Query | Top hit | Score |
| --- | --- | --- |
| "how do I set up a virtual machine" | Configure Windows Virtual Hardware › Start the Creation Wizard | 0.69 |
| "encrypting a disk with LUKS" | + MOC Arch Install FULL › 8. LUKS2 Encryption Setup | 0.65 |
| "banana bread recipe with walnuts" | *No good matches* | — |

That last row matters as much as the first two: below a calibrated floor, Loam says it found nothing rather than presenting noise confidently.

**Next:** Phase 2 — local LLM for grounded Q&A over retrieved chunks, with a mandatory "sources used" panel. See [the roadmap](loam-architecture-roadmap.md) for the full phase plan and every measurement behind these numbers.

## Building

There is no host JDK on the development machine, so Gradle is pointed at the JDK bundled with Android Studio:

```bash
export JAVA_HOME=/opt/android-studio/jbr    # or your own JDK 17+
export ANDROID_HOME=$HOME/Android/Sdk
./gradlew :app:assembleRelease
```

`sudo pacman -S jdk21-openjdk` (or your distro's equivalent) works equally well and removes the Android Studio dependency.

**Model weights are not in this repo.** They are large, and vendoring them complicates the license story that F-Droid packaging has to answer. Download them once into `models/all-MiniLM-L6-v2/`:

```bash
# from sentence-transformers/all-MiniLM-L6-v2 — Apache-2.0, ungated
model_qint8_arm64.onnx    # 22 MB, INT8 quantized for ARM64 — this is the one bundled
vocab.txt                 # 30,522-line WordPiece vocabulary
```

The build fails with an explicit list of what is missing if they are absent. This one-time download is the single legitimate use of network access described in principle #1 — done manually, deliberately, and outside the app.

Requirements: `minSdk 26` · `compileSdk`/`targetSdk 37` · `arm64-v8a` only · AGP 9.3.1 · Kotlin 2.4.10 · Gradle 9.6.1

## How it works

```
Compose UI  ──  search · results · reindex status
     │
Domain      ──  SearchNotes · IndexVault · VaultLocation
     │
     ├─ Vault Reader      SAF tree walk via DocumentsContract, token-budgeted chunking
     ├─ Embedder          MiniLM-L6-v2 INT8 on ONNX Runtime, 384-dim, mean-pooled, L2-normalized
     └─ Store             Room + SQLCipher, key wrapped by the Android Keystore
                          search: brute-force cosine over normalized vectors
```

A few decisions worth knowing before changing related code — full rationale in the roadmap:

- **Brute-force cosine, not a vector index.** Measured at 13.8 ms for 50,000 chunks on-device. `sqlite-vec` is a dependency this project does not need to carry.
- **Chunks are sized in tokens, not characters.** A characters-per-token estimate silently truncated 14.4% of the vault past the model's 256-token window — indexed in appearance, unsearchable in fact.
- **Inputs are padded to 32-token buckets**, not to a flat 256. Attention is O(n²), and most chunks are far shorter than the window.
- **The index is encrypted at rest.** It is derived from personal notes, so it is as sensitive as the notes themselves.
- **Indexing is periodic plus manual.** SAF offers no filesystem watch, so there is nothing to subscribe to. This is the honest architecture, not a gap.

## Layout

```
app/     Compose UI, WorkManager indexing job, note hand-off
core/    Domain, chunking, embedding, encrypted store, vector search
spike/   Phase 0 throwaway harness — separate Gradle build, exists to be deleted
models/  Model weights (gitignored — see Building)
```

`loam-architecture-roadmap.md` is the design document and the authoritative record of what was measured, what was wrong, and why. It keeps its own mistakes on the page rather than editing them out.

## License

MIT — see [LICENSE](LICENSE).
