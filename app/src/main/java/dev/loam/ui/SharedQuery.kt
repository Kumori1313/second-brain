package dev.loam.ui

import android.content.Intent

/**
 * Text arriving from another app, turned into a search query.
 *
 * Two ways in, and the second is the one worth having:
 *
 *  - `ACTION_SEND`, the share sheet — Loam appears wherever text can be shared.
 *  - `ACTION_PROCESS_TEXT`, the text-selection menu — highlight a sentence
 *    anywhere on the device and ask what you have already written about it.
 *    That is the whole premise of the app applied to text you are looking at
 *    rather than text you retype.
 *
 * Nothing is stored. A shared query is a query; it does not enter the index,
 * which stays derived from the vault and nothing else.
 */
object SharedQuery {

    /**
     * The embedder reads 256 tokens and stops. At the 3.46 chars/token measured
     * on this vault that is ~886 characters, and more for ordinary prose, so
     * anything past a round 1,000 cannot reach the query vector at all.
     *
     * The cap is therefore honesty rather than defence: accepting a shared
     * article of forty kilobytes and putting it in a single-line search field
     * would imply Loam had searched for all of it.
     */
    const val MAX_CHARS = 1000

    fun from(intent: Intent?): String? {
        val raw = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
            else -> null
        } ?: return null

        // Collapsed to single spaces because a shared paragraph carries its
        // newlines with it and the search field is one line — without this the
        // user sees the first line of what they shared and no sign of the rest.
        val text = raw.toString().replace(WHITESPACE, " ").trim()
        if (text.isEmpty()) return null
        return text.take(MAX_CHARS)
    }

    private val WHITESPACE = Regex("\\s+")
}
