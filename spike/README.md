# Loam — Phase 0 Spike

Throwaway harness to prove the three things Phase 0 needs to answer **on real
hardware**, before any real app code exists:

1. **Embedding works on-device** — cold-start and per-call latency, output
   dimensions, and a sanity check that the vectors actually behave.
2. **SAF folder read works** — walk a real vault, read `.md` in place, with no
   storage permission in the manifest.
3. **Brute-force cosine search has headroom** — measured at 5k / 20k / 50k
   chunks, which decides whether `sqlite-vec` is ever needed.

This is not the shape of the real app. It exists to be measured and deleted.

## Zero network permission

`AndroidManifest.xml` declares **no `<uses-permission>` entries at all**. Verify
against the built APK rather than trusting the source:

```bash
~/Android/Sdk/build-tools/36.0.0/aapt2 dump permissions \
  app/build/outputs/apk/debug/app-debug.apk
```

The only entry should be `dev.loam.spike.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`
— a self-scoped signature permission AndroidX injects for local broadcast
receivers. It grants nothing outward and is not network access.

## Building

There is no host JDK on this machine, so Gradle needs pointing at the JDK that
ships inside Android Studio (installed from the AUR, at `/opt/android-studio`):

```bash
export JAVA_HOME=/opt/android-studio/jbr
export ANDROID_HOME=$HOME/Android/Sdk
./gradlew assembleDebug
```

Alternatively `sudo pacman -S jdk21-openjdk` for a host JDK and skip the
`JAVA_HOME` export. Building inside Android Studio itself needs none of this.

If Studio was installed some other way, `JAVA_HOME` changes with it — a Flatpak
install puts the JBR under
`/var/lib/flatpak/app/com.google.AndroidStudio/current/active/files/extra/jbr`.
Use that `current/active` path rather than the hashed `x86_64/stable/<sha>/`
one, since the hash changes on every Studio update.

### Version notes

AGP 9 has **built-in Kotlin support**: applying `org.jetbrains.kotlin.android`
is now a hard error, not merely redundant. The Compose compiler plugin
(`org.jetbrains.kotlin.plugin.compose`) is still required separately.

The build filters to `arm64-v8a` only. ONNX Runtime ships four ABIs totalling
~113MB of native code; unfiltered, the debug APK is 125MB instead of 39MB. Add
`x86_64` to `abiFilters` in `app/build.gradle.kts` to run on an emulator.

## Running

Install, then sideload the model — it is pushed rather than bundled, to keep a
22MB blob out of git and out of the APK:

```bash
./gradlew installDebug
./push-model.sh
```

`push-model.sh` copies `model_qint8_arm64.onnx` and `vocab.txt` from
`../models/all-MiniLM-L6-v2/` into the app's external files dir. Fetch those
first per the roadmap's model prerequisites if `../models/` is empty.

Then launch **Loam Spike** and work through the three buttons in order.

## Results (Pixel 8a, Tensor G3, Android 17)

| Measurement | Result |
| --- | --- |
| Embedding, INT8, maxLen 256 | 23–36 ms/chunk, 35 ms cold start, dim 384 |
| SAF walk, 392 `.md` files | 13,472 ms (~34 ms/file) |
| Cosine 5k / 20k / 50k | 1.36 / 5.52 / 13.81 ms per query |
| Heap at 50k | 107 MB |

Brute force is linear at 0.276 µs/chunk through 50k, so **sqlite-vec is not needed**.

### Benchmark a release build, never a debug one

This is the single most important thing this spike learned. The same benchmark:

| chunks | debug | release |
| --- | --- | --- |
| 5,000 | 50.46 ms | 1.36 ms |
| 20,000 | 206.89 ms | 5.52 ms |
| 50,000 | 533.07 ms | 13.81 ms |

`debuggable true` forces deoptimization support and blocks ART inlining, costing
**36x** on the hot dot-product loop. The debug numbers would have justified
adding sqlite-vec to a project whose whole dependency policy is about keeping
the list short.

`CosineBench` also warms up and measures on wall clock rather than a fixed
iteration count. The original single untimed pass never triggered JIT
compilation, and the tell was cost *per chunk* falling as the store grew — a
fixed startup cost being amortized. Fixing warmup alone moved 5k from 75 to
51 ms; the remaining 36x was the build type.

The `release` build type is signed with the debug key so it can be installed
without a keystore. That is a spike-only shortcut — never ship it.

```bash
./gradlew installRelease
```

## Reading the results

**Embedding test** prints cold-start and per-call latency, then a similarity
check: two sentences about distributed systems versus one about banana bread.
Related must score higher than unrelated. If it doesn't, the tokenizer or the
mean pooling is wrong — the model will still run and still return
confident-looking vectors, which is exactly what makes this failure mode worth
an explicit check.

To compare quantization, push `model.onnx` (fp32, 87MB) as well and change
`MODEL_NAME` in `MainActivity.kt`. The interesting number is not just latency
but whether INT8 changes the similarity ordering on your own notes.

**Cosine benchmark** reports ms/query and heap at each vault size. If 50k chunks
stays comfortably interactive, brute force is sufficient and `sqlite-vec` stays
off the dependency list — which is the outcome the roadmap is hoping for.

## Tokenizer verification

The WordPiece tokenizer is hand-rolled, and a wrong one fails silently — it
returns confident, plausible embeddings that simply retrieve badly. So it is
checked against HuggingFace's own implementation rather than trusted:

```bash
./gradlew testDebugUnitTest
```

`WordPieceTokenizerTest` compares token ids across 56 cases against fixtures
generated by the real `tokenizers` library from the same `tokenizer.json` that
ships with the model. All 56 currently match exactly.

To regenerate fixtures (needed if the embedding model ever changes, since a new
model means a new vocab and different rules):

```bash
python3 -m venv .venv && .venv/bin/pip install tokenizers
.venv/bin/python tools/gen_fixtures.py
```

### Real-corpus verification

Hand-written cases only cover edge cases someone thought to look for. A second
test runs the same comparison over real vault text:

```bash
git clone --depth 1 https://github.com/dusklinux/dusky ../Documents
.venv/bin/python tools/gen_corpus_fixtures.py
./gradlew testDebugUnitTest
```

That samples 1500 paragraph-level segments and compares every token id. It
currently passes on all 1500 (~53k tokens) from a 392-note Obsidian vault
containing wikilinks, callouts, frontmatter, tables, code fences, and CJK.

The `[UNK]` rate there is **0.057%**, and inspecting them shows they're
box-drawing characters from ASCII diagrams (`├──`, `┌───▼───┐`) plus a few
emoji — glyphs with no semantic content to lose. That's the number to re-check
if the model or vocab ever changes.

The vault itself is gitignored: it's MIT-licensed third-party content used as a
fixture, and vendoring it would mean carrying its license and copyright notice
into this repo's Phase 4 license manifest for no benefit. The derived
`corpus_fixtures.json` is gitignored for the same reason.

The differential test earned its keep immediately — it caught two real bugs
that no amount of eyeballing would have found:

1. **CJK ideographs weren't split per character.** BERT wraps ideographs in
   whitespace so each becomes its own word, but deliberately excludes hiragana
   and katakana. `中文测试` collapsed to a single `[UNK]` instead of four tokens.
2. **`_clean_text` was missing entirely.** Control and format characters
   (U+200B zero-width space) must be *deleted*, while Unicode space separators
   (U+00A0 non-breaking space) must become *spaces*. Java's
   `Character.isWhitespace` disagrees with BERT on U+00A0, which silently fused
   words into one `[UNK]`.

Both bugs only surface on non-Latin or invisible characters — exactly the kind
of input a personal vault quietly contains, and exactly what eyeballing English
test strings would never reveal.

### Embedder verification

The in-app similarity check only asserts an *ordering*, which a subtly wrong
mean pooling still passes. `tools/verify_embedder.py` reproduces the exact
Android pipeline on desktop — same INT8 graph, same maxLen padding, same
mask-aware pooling — so the on-device numbers have a reference:

```bash
.venv/bin/pip install onnxruntime numpy tokenizers
.venv/bin/python tools/verify_embedder.py
```

Desktop and device agree to three decimals (`related=0.250 unrelated=0.062`),
which confirms tokenizer, pooling, and normalization together.

It also contrasts mean pooling against `[CLS]`, and the numbers show why the
warning comment in `Embedder.kt` is there. `[CLS]` reports a *higher* related
score (0.645 vs 0.250) but a much worse margin over the unrelated pair (0.073
vs 0.188). The wrong pooling looks better by the obvious metric and
discriminates worse — it would degrade retrieval while appearing fine.

Quantization costs 0.013 on the related pair (fp32 0.262 → INT8 0.250), so the
22MB INT8 model does the same job as the 87MB fp32 one.

## Chunking

`Chunker` is deliberately Android-free so it can be tested on the JVM against
the real vault. It previously lived on `VaultReader`, which takes a `Context`,
so its output could only be checked by porting the algorithm to Python — and a
port drifts from the thing it claims to describe.

Three rules, each pinned by a regression test because breaking it produced a
measured defect on the 392-note vault:

1. **A heading breaks a chunk only once the chunk is worth keeping.** Breaking
   at every heading emitted chunks holding nothing but a heading line.
2. **Nothing exceeds `maxChars`.** Splitting only between blank-line blocks left
   one 77k-character table whole; everything past the tokenizer's `maxLen` is
   silently dropped at embed time, so most of that note was unretrievable while
   appearing indexed.
3. **Fenced code is one block.** Splitting on blank lines tears fences apart and
   lets a `# comment` inside one read as a heading. 293 of 392 notes have fences.

Effect on the test vault:

| | before | after |
| --- | --- | --- |
| chunks | 6,027 | 3,427 |
| mean | 434 chars (~108 tokens) | 802 chars (~200 tokens) |
| under 200 chars | 39.4% | 2.5% |
| largest | 77,111 chars | 2,000 chars |

Chunks carry ~150 characters of overlap so a passage spanning a boundary stays
retrievable from either side. Overlap is skipped after a heading break, where
the author signalled a topic change and carrying the old topic forward would
only blur it.

```bash
./gradlew testDebugUnitTest --tests '*ChunkerTest*'
```

The vault-wide test prints the live distribution and asserts the shape of it,
so a future tuning change that quietly wrecks chunk sizes fails the build.

## Known gaps

- Benchmark vectors are uniform random, so they are further apart than real
  note embeddings. Timing is representative; recall quality is not.
- Chunks are plain strings. Phase 1's store wants a heading breadcrumb and
  source path per chunk, per the roadmap's data flow — `Chunker` would need to
  return a record rather than a `String` to carry that.
- Chunk sizing is tuned by character count as a proxy for tokens (~4 chars each).
  That is a decent English approximation and a poor one for CJK, where the
  tokenizer emits roughly one token per character. A CJK-heavy vault would get
  chunks several times longer than intended.
