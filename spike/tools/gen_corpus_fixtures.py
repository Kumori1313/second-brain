"""Generate tokenizer fixtures from a real Obsidian vault.

The 56 hand-written cases in gen_fixtures.py cover edge cases we thought to
look for. This covers the ones we didn't: real notes contain syntax,
whitespace, and unicode nobody would think to write a test for.

Point VAULT at any vault. Output is gitignored — it's derived from
third-party content and is regenerable, so it doesn't belong in the repo.

Usage:
    .venv/bin/python tools/gen_corpus_fixtures.py [vault_dir] [sample_size]
"""
import json
import os
import random
import re
import sys

from tokenizers import Tokenizer

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
MODEL_DIR = os.path.join(REPO, "models", "all-MiniLM-L6-v2")

vault = sys.argv[1] if len(sys.argv) > 1 else os.path.join(REPO, "Documents")
sample_size = int(sys.argv[2]) if len(sys.argv) > 2 else 1500

tok = Tokenizer.from_file(os.path.join(MODEL_DIR, "tokenizer.json"))
tok.no_padding()
tok.no_truncation()

# Paragraph-level segments: long enough to be realistic input, short enough
# that a mismatch points at a specific construct.
segments = []
files = 0
for root, dirs, names in os.walk(vault):
    dirs[:] = [d for d in dirs if d != ".obsidian"]
    for name in names:
        if not name.endswith(".md"):
            continue
        files += 1
        path = os.path.join(root, name)
        try:
            text = open(path, encoding="utf-8", errors="replace").read()
        except OSError:
            continue
        # Include the filename too — vault filenames carry unicode and spaces.
        segments.append(name[:-3])
        for block in re.split(r"\n\s*\n", text):
            block = block.strip()
            if block:
                segments.append(block[:2000])

random.seed(1313)
sample = random.sample(segments, min(sample_size, len(segments)))

out = []
for text in sample:
    enc = tok.encode(text)
    out.append({"text": text, "ids": enc.ids})

path = os.path.join(
    REPO, "spike", "app", "src", "test", "resources", "corpus_fixtures.json"
)
os.makedirs(os.path.dirname(path), exist_ok=True)
with open(path, "w", encoding="utf-8") as f:
    json.dump(out, f, ensure_ascii=False)

total_tokens = sum(len(c["ids"]) for c in out)
unk = sum(c["ids"].count(100) for c in out)
print(f"vault:     {vault}")
print(f"files:     {files}")
print(f"segments:  {len(segments)} (sampled {len(out)})")
print(f"tokens:    {total_tokens}")
print(f"[UNK]:     {unk} ({100 * unk / max(total_tokens, 1):.3f}%)")
print(f"wrote:     {path}")
