"""Measure what VaultReader.chunk() actually produces across a real vault.

Faithful port of the Kotlin chunker, so the chunk-size distribution can be
checked without a device. The spike reported 3005 chars -> 12 chunks for one
note (~250 chars each), well under the roadmap's 200-400 *token* target, which
is roughly 800-1600 chars. This quantifies how far off it is vault-wide, and
what the fix would give.

Usage:
    .venv/bin/python tools/chunk_stats.py [vault_dir]
"""
import os
import re
import statistics
import sys

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
vault = sys.argv[1] if len(sys.argv) > 1 else os.path.join(REPO, "Documents")

BLOCK_RE = re.compile(r"\n\s*\n")


def chunk_current(markdown, target_chars=1200):
    """Port of the shipped Kotlin chunk(): breaks at EVERY heading."""
    chunks, current = [], []
    length = 0
    for block in BLOCK_RE.split(markdown):
        t = block.strip()
        if not t:
            continue
        is_heading = t.startswith("#")
        if (length + len(t) > target_chars or is_heading) and current:
            chunks.append("\n\n".join(current))
            current, length = [], 0
        current.append(t)
        length += len(t)
    if current:
        chunks.append("\n\n".join(current))
    return chunks


def chunk_fixed(markdown, target_chars=1200, min_chars=400):
    """Only break at a heading once the chunk is worth keeping."""
    chunks, current = [], []
    length = 0
    for block in BLOCK_RE.split(markdown):
        t = block.strip()
        if not t:
            continue
        is_heading = t.startswith("#")
        too_big = length + len(t) > target_chars
        heading_break = is_heading and length >= min_chars
        if (too_big or heading_break) and current:
            chunks.append("\n\n".join(current))
            current, length = [], 0
        current.append(t)
        length += len(t)
    if current:
        chunks.append("\n\n".join(current))
    return chunks


def report(name, fn):
    sizes, files, total_chars = [], 0, 0
    for root, dirs, names in os.walk(vault):
        dirs[:] = [d for d in dirs if d != ".obsidian"]
        for n in names:
            if not n.endswith(".md"):
                continue
            files += 1
            text = open(os.path.join(root, n), encoding="utf-8", errors="replace").read()
            total_chars += len(text)
            sizes.extend(len(c) for c in fn(text))

    if not sizes:
        print("no markdown found")
        return
    # ~4 chars per token is the usual English rule of thumb for WordPiece.
    tiny = sum(1 for s in sizes if s < 200)
    print(f"\n{name}")
    print(f"  files:          {files}  ({total_chars:,} chars)")
    print(f"  chunks:         {len(sizes):,}")
    print(f"  mean chars:     {statistics.mean(sizes):.0f}  (~{statistics.mean(sizes)/4:.0f} tokens)")
    print(f"  median chars:   {statistics.median(sizes):.0f}")
    print(f"  under 200 chars:{tiny:,} ({100*tiny/len(sizes):.1f}%)  <- too small to retrieve well")
    print(f"  max chars:      {max(sizes):,}")


print(f"vault: {vault}")
print("roadmap target: 200-400 tokens/chunk, i.e. roughly 800-1600 chars")
report("CURRENT (breaks at every heading)", chunk_current)
report("FIXED (heading breaks only past min_chars)", chunk_fixed)
