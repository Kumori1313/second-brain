"""Does bucketed padding change retrieval, or only latency?

Padding every input to 256 tokens wastes work: the model's axes are dynamic and
attention is O(n^2). But embeddings are not perfectly padding-invariant on the
INT8 graph -- cosine between the same text padded to its own length and to 256
measures ~0.992, not 1.000.

0.992 is small in isolation and says nothing about whether rankings move, which
is the only thing that actually matters. This embeds real vault text both ways
and compares the resulting top-K.

Usage:
    .venv/bin/python tools/check_bucketing.py [vault_dir] [sample]
"""
import os
import re
import sys

import numpy as np
import onnxruntime as ort
from tokenizers import Tokenizer

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
D = os.path.join(REPO, "models", "all-MiniLM-L6-v2")
vault = sys.argv[1] if len(sys.argv) > 1 else os.path.join(REPO, "Documents")
sample = int(sys.argv[2]) if len(sys.argv) > 2 else 400

MAX_LEN = 256
BUCKET = 32

sess = ort.InferenceSession(
    os.path.join(D, "model_qint8_arm64.onnx"), providers=["CPUExecutionProvider"]
)
tok = Tokenizer.from_file(os.path.join(D, "tokenizer.json"))
tok.no_padding()
tok.no_truncation()
HAS_TYPES = any(i.name == "token_type_ids" for i in sess.get_inputs())


def embed(text, bucketed):
    ids = tok.encode(text).ids[:MAX_LEN]
    n = len(ids)
    pad_to = min(MAX_LEN, ((n + BUCKET - 1) // BUCKET) * BUCKET) if bucketed else MAX_LEN
    pad_to = max(pad_to, n)
    mask = [1] * n + [0] * (pad_to - n)
    ids = ids + [0] * (pad_to - n)
    a = np.array([ids], dtype=np.int64)
    m = np.array([mask], dtype=np.int64)
    feed = {"input_ids": a, "attention_mask": m}
    if HAS_TYPES:
        feed["token_type_ids"] = np.zeros_like(a)
    h = sess.run(None, feed)[0][0]
    mm = m[0].astype(np.float32)[:, None]
    v = (h * mm).sum(0) / mm.sum()
    return v / np.linalg.norm(v), n


# Realistic chunk-sized text from the vault.
chunks = []
for root, dirs, names in os.walk(vault):
    dirs[:] = [d for d in dirs if d != ".obsidian"]
    for name in names:
        if not name.endswith(".md"):
            continue
        text = open(os.path.join(root, name), encoding="utf-8", errors="replace").read()
        buf = ""
        for block in re.split(r"\n\s*\n", text):
            block = block.strip()
            if not block:
                continue
            if len(buf) + len(block) > 1200 and buf:
                chunks.append(buf)
                buf = ""
            buf += block + "\n\n"
        if buf.strip():
            chunks.append(buf.strip())

rng = np.random.default_rng(1313)
chunks = [chunks[i] for i in rng.choice(len(chunks), min(sample, len(chunks)), replace=False)]

print(f"embedding {len(chunks)} real chunks both ways (this takes a minute)...")
fixed, bucketed, lengths = [], [], []
for c in chunks:
    f, n = embed(c, bucketed=False)
    b, _ = embed(c, bucketed=True)
    fixed.append(f)
    bucketed.append(b)
    lengths.append(n)
fixed = np.array(fixed)
bucketed = np.array(bucketed)
lengths = np.array(lengths)

per_vector = (fixed * bucketed).sum(1)
print(f"\nper-chunk cosine(fixed256, bucketed): "
      f"min={per_vector.min():.4f} mean={per_vector.mean():.4f}")
print(f"token lengths: median={int(np.median(lengths))} "
      f"mean={lengths.mean():.0f} p90={int(np.percentile(lengths, 90))}")
saved = 1 - (np.minimum(MAX_LEN, np.ceil(lengths / BUCKET) * BUCKET).sum() / (MAX_LEN * len(lengths)))
print(f"padded-token work avoided: {100 * saved:.1f}%")

QUERIES = [
    "how do I set up a virtual machine",
    "encrypting a disk with LUKS",
    "installing packages with pacman",
    "configuring the network interface",
    "how to build a kernel module",
    "text to speech setup",
]

print("\nranking impact (top-10 overlap between the two regimes):")
overlaps = []
for q in QUERIES:
    qf, _ = embed(q, bucketed=False)
    qb, _ = embed(q, bucketed=True)
    top_f = np.argsort(-(fixed @ qf))[:10]
    top_b = np.argsort(-(bucketed @ qb))[:10]
    overlap = len(set(top_f.tolist()) & set(top_b.tolist()))
    same_first = top_f[0] == top_b[0]
    overlaps.append(overlap)
    print(f"  {overlap:2d}/10 same, top-1 {'unchanged' if same_first else 'CHANGED'}"
          f"   score {float(fixed[top_f[0]] @ qf):.3f} -> {float(bucketed[top_b[0]] @ qb):.3f}"
          f"   \"{q}\"")

print(f"\nmean top-10 overlap: {np.mean(overlaps):.1f}/10")
