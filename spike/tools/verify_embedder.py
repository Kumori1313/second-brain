"""Differential check for the Android Embedder: mean pooling and normalization.

The in-app similarity check only asserts an ordering (related > unrelated),
which a subtly wrong pooling still passes. This reproduces the exact Android
pipeline on desktop -- same INT8 graph, same maxLen padding, same mask-aware
mean pool -- so the on-device similarity numbers can be compared against a
reference rather than merely eyeballed.

Also contrasts mean pooling against [CLS] pooling, since the whole reason
Embedder.kt carries a warning comment is that [CLS] fails silently.

Usage:
    .venv/bin/python tools/verify_embedder.py
"""
import os
import sys

import numpy as np
import onnxruntime as ort
from tokenizers import Tokenizer

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
MODEL_DIR = os.path.join(REPO, "models", "all-MiniLM-L6-v2")

MAX_LEN = 256  # must match Embedder.embed()'s default

SENTENCES = [
    "I wrote about distributed systems consensus",
    "notes on raft and paxos algorithms",
    "banana bread recipe with walnuts",
]


def load(model_name):
    path = os.path.join(MODEL_DIR, model_name)
    if not os.path.exists(path):
        return None
    return ort.InferenceSession(path, providers=["CPUExecutionProvider"])


def encode(tok, text):
    """Pad to MAX_LEN exactly as WordPieceTokenizer.encode does."""
    enc = tok.encode(text)
    ids = enc.ids[:MAX_LEN]
    mask = [1] * len(ids)
    pad = MAX_LEN - len(ids)
    ids += [0] * pad
    mask += [0] * pad
    return np.array([ids], dtype=np.int64), np.array([mask], dtype=np.int64)


def embed(sess, tok, text, pooling="mean"):
    ids, mask = encode(tok, text)
    feed = {"input_ids": ids, "attention_mask": mask}
    declared = {i.name for i in sess.get_inputs()}
    if "token_type_ids" in declared:
        feed["token_type_ids"] = np.zeros_like(ids)

    hidden = sess.run(None, feed)[0][0]  # [seq, dim]

    if pooling == "cls":
        vec = hidden[0]
    else:
        m = mask[0].astype(np.float32)[:, None]
        vec = (hidden * m).sum(axis=0) / m.sum()

    norm = np.linalg.norm(vec)
    return vec / norm if norm > 0 else vec


def report(label, model_name):
    sess = load(model_name)
    if sess is None:
        print(f"\n{label}: {model_name} not found, skipping")
        return None

    tok = Tokenizer.from_file(os.path.join(MODEL_DIR, "tokenizer.json"))
    tok.no_padding()
    tok.no_truncation()

    print(f"\n{label}  ({model_name})")
    out = {}
    for pooling in ("mean", "cls"):
        v = [embed(sess, tok, s, pooling) for s in SENTENCES]
        related = float(np.dot(v[0], v[1]))
        unrelated = float(np.dot(v[0], v[2]))
        print(
            f"  {pooling:4s} pooling: dim={len(v[0])} "
            f"related={related:.3f} unrelated={unrelated:.3f} "
            f"margin={related - unrelated:.3f}"
        )
        out[pooling] = (related, unrelated)
    return out


print("Reference values for the Android in-app similarity check.")
print(f"maxLen={MAX_LEN}, mask-aware mean pool, L2 normalized.")

int8 = report("INT8 (what the phone ran)", "model_qint8_arm64.onnx")
fp32 = report("FP32 (quantization baseline)", "model.onnx")

if int8 and fp32:
    print("\nQuantization cost on the related pair: "
          f"{fp32['mean'][0]:.3f} -> {int8['mean'][0]:.3f} "
          f"({int8['mean'][0] - fp32['mean'][0]:+.3f})")

print("\nCompare 'INT8 / mean' above against the phone's reported numbers.")
print("A close match means tokenizer + pooling + normalization all agree.")
