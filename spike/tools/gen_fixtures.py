"""Generate tokenizer reference fixtures from HuggingFace tokenizers.

Produces the ground truth that WordPieceTokenizerTest checks our hand-rolled
Kotlin implementation against. Deliberately stresses the paths such an
implementation gets wrong: accents, CJK ideograph padding, control/format
characters, punctuation adjacency, subword splitting, and the markdown syntax
this app will actually be fed.

Usage:
    python3 -m venv .venv && .venv/bin/pip install tokenizers
    .venv/bin/python tools/gen_fixtures.py

Re-run and re-check the Kotlin test whenever the embedding model changes — a
different model means a different vocab and different tokenization rules.
"""
import json
import os

from tokenizers import Tokenizer

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
MODEL_DIR = os.path.join(REPO, "models", "all-MiniLM-L6-v2")

tok = Tokenizer.from_file(os.path.join(MODEL_DIR, "tokenizer.json"))
# This tokenizer.json ships with padding/truncation preconfigured; turn both off
# so fixtures are the bare token sequence and padding stays the app's concern.
tok.no_padding()
tok.no_truncation()

CASES = [
    # plain
    "hello world",
    "I wrote about distributed systems consensus",
    # casing
    "HELLO World MiXeD CaSe",
    # punctuation adjacency
    "don't stop--now! (really?) [yes]",
    "email me at foo.bar@example.com",
    # subword / rare words
    "unbelievably antidisestablishmentarianism",
    "tokenization embeddings quantization",
    # numbers
    "version 2.4.10 costs $19.99 or 50%",
    # accents / unicode normalization
    "café naïve résumé Zürich",
    "coöperate façade jalapeño",
    # CJK (each char is its own token in BERT)
    "日本語のテキスト",
    "中文测试",
    # emoji (should become [UNK] or get split)
    "great work 🎉 nice",
    # markdown — the actual domain
    "# Heading One\n\nSome *emphasis* and `inline_code()` here.",
    "- bullet item\n- another [link](https://example.com)",
    "```python\ndef foo():\n    return 42\n```",
    "> blockquote text\n\n| table | cell |",
    "See [[wikilink]] and #tag in an Obsidian note",
    # whitespace edges
    "   leading and trailing   ",
    "multiple\n\nnewlines\there",
    # long single token
    "a" * 120,
    # empty-ish
    "",
    "   ",
    # single chars
    "a",
    "?",

    # --- second round: harder material ---
    # other scripts (non-CJK, so no ideograph padding)
    "Привет мир тест",
    "Ελληνικά κείμενο",
    "مرحبا بالعالم",
    "שלום עולם",
    "한국어 텍스트입니다",
    # mixed scripts in one string
    "English 日本語 Русский mixed",
    "API的设计 hybrid text",
    # CJK adjacent to punctuation and latin
    "日本語、テスト。end",
    "中文(brackets)测试",
    # whitespace exotica
    "tab\tseparated\tvalues",
    "non\u00a0breaking\u00a0space",
    "zero\u200bwidth\u200bspace",
    # control characters
    "control\u0000char\u0007here",
    # currency / math / symbols
    "€50 £30 ¥1000 costs ±5% ≈ 42",
    "a→b ⇒ c ∈ D ∀x",
    # urls and paths
    "https://example.com/path?q=1&r=2#frag",
    "/home/user/notes/2024-01-15.md",
    "C:\\Users\\notes\\file.md",
    # code-ish
    "fun main() { val x = listOf(1,2,3) }",
    "SELECT * FROM notes WHERE id = 42;",
    "snake_case camelCase kebab-case SCREAMING_CASE",
    # markdown structures
    "## Sub *heading* with **bold** and _underscore_",
    "1. first\n2. second\n   - nested",
    "![image](path/to/img.png \"title\")",
    "---\ntitle: frontmatter\ntags: [a, b]\n---",
    "Footnote[^1] and ~~strikethrough~~",
    # repeated punctuation
    "wait...what?! no---way",
    "!!!???",
    # hyphenation and contractions
    "state-of-the-art re-entrant o'clock y'all",
    # numbers dense
    "3.14159 1e-9 0xFF 1_000_000",
    # very long realistic note line
    "Today I read about retrieval augmented generation and how chunking "
    "strategy affects recall in vector search over personal knowledge bases.",
]

out = []
for text in CASES:
    enc = tok.encode(text)
    out.append({"text": text, "ids": enc.ids, "tokens": enc.tokens})

path = os.path.join(
    REPO, "spike", "app", "src", "test", "resources", "tokenizer_fixtures.json"
)
os.makedirs(os.path.dirname(path), exist_ok=True)
with open(path, "w", encoding="utf-8") as f:
    json.dump(out, f, ensure_ascii=False, indent=2)

print(f"wrote {len(out)} cases to {path}")
for c in out[:3]:
    print(" ", repr(c["text"])[:50], "->", c["tokens"][:12])
