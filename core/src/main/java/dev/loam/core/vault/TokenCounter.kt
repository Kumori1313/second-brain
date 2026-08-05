package dev.loam.core.vault

/**
 * Counts what the embedding model will actually see.
 *
 * An interface rather than a direct tokenizer reference so [Chunker] stays free
 * of the embedding layer — the chunker's job is to cut text to a budget, not to
 * know what produces the budget.
 *
 * Counts must exclude special tokens and be additive across whitespace-joined
 * fragments, so the chunker can total per-block counts rather than
 * re-tokenizing a growing chunk on every append.
 */
fun interface TokenCounter {
    fun count(text: String): Int
}
