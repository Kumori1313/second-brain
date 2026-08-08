package dev.loam.core.vault

/**
 * Which notes to leave out of the index, as gitignore-shaped patterns.
 *
 * Deliberately free of Android imports so it can be tested on the JVM.
 *
 * A deliberately small subset of gitignore, because the useful half is small
 * and the rest is surprising. What is supported:
 *
 *  - `*` matches within one path segment; `**` matches across segments.
 *  - `?` matches one character, not `/`.
 *  - A pattern containing no `/` matches the **file name** anywhere in the
 *    vault: `*.excalidraw.md`, `Untitled*.md`.
 *  - A pattern containing `/` matches the whole path from the vault root
 *    rather than the file name, so it can name one folder's contents.
 *  - A trailing `/` excludes a directory and everything under it: `templates/`.
 *
 * Note for anyone editing this file: Kotlin block comments nest, so a slash
 * immediately followed by a star inside this KDoc opens a comment that is never
 * closed, and the reverse pair closes this one early. That is why the glob
 * examples above are described rather than written out; `ExcludeRulesTest`
 * carries them literally instead.
 *
 * What is not: negation (`!`), character classes, or anchoring subtleties.
 * Each of those is a rule the user would have to learn from behaviour, since
 * there is nowhere here to explain it.
 *
 * Matching is case-insensitive. The target is Android, where the same vault may
 * sit on a case-preserving-but-insensitive volume, and a pattern that works on
 * one device and not another is worse than one that over-matches.
 */
class ExcludeRules private constructor(
    private val filePatterns: List<Regex>,
    private val pathPatterns: List<Regex>,
    private val directoryPatterns: List<Regex>,
) {

    val isEmpty: Boolean
        get() = filePatterns.isEmpty() && pathPatterns.isEmpty() && directoryPatterns.isEmpty()

    /**
     * @param relativePath path from the vault root, `/`-separated, no leading
     *   slash — the same string stored on the note and shown under a result.
     */
    fun excludesFile(relativePath: String): Boolean {
        val name = relativePath.substringAfterLast('/')
        return filePatterns.any { it.matches(name) } ||
            pathPatterns.any { it.matches(relativePath) } ||
            directoryPatterns.any { it.matches(relativePath) }
    }

    /**
     * Whether the walk can skip a whole directory rather than filtering its
     * files afterwards.
     *
     * Worth the separate question: enumeration is the expensive half of a walk
     * — 392 files cost 13.5 s before the `DocumentsContract` rewrite, and one
     * ContentResolver query per node after it — so not descending is a real
     * saving, where filtering the results is none.
     *
     * @param relativePath the directory's path from the root, without a
     *   trailing slash.
     */
    fun excludesDirectory(relativePath: String): Boolean =
        directoryPatterns.any { it.matches(relativePath) || it.matches("$relativePath/") } ||
            pathPatterns.any { it.matches(relativePath) }

    companion object {

        val NONE = ExcludeRules(emptyList(), emptyList(), emptyList())

        /** @param text one pattern per line; blanks and `#` comments ignored. */
        fun parse(text: String): ExcludeRules {
            val files = mutableListOf<Regex>()
            val paths = mutableListOf<Regex>()
            val dirs = mutableListOf<Regex>()

            for (raw in text.lineSequence()) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                // A leading slash reads as "from the vault root", which is what
                // every pattern here already means. Accepting and dropping it
                // beats matching nothing for someone who pasted from a
                // .gitignore.
                val pattern = line.removePrefix("/")
                if (pattern.isEmpty()) continue

                when {
                    pattern.endsWith("/") -> {
                        val dir = pattern.dropLast(1)
                        // The directory itself and everything beneath it.
                        dirs += globToRegex(dir)
                        dirs += globToRegex("$dir/**")
                    }

                    pattern.contains('/') -> paths += globToRegex(pattern)
                    else -> files += globToRegex(pattern)
                }
            }
            return if (files.isEmpty() && paths.isEmpty() && dirs.isEmpty()) NONE
            else ExcludeRules(files, paths, dirs)
        }

        /**
         * Translates a glob to a regex, escaping everything else.
         *
         * Escaping by default rather than by blocklist: a vault path can
         * legitimately contain `.`, `+`, `(`, `[` — "C++ Notes.md", "Step (2)"
         * — and any of those left unescaped turns a literal pattern into a
         * quietly different one.
         */
        private fun globToRegex(glob: String): Regex {
            val sb = StringBuilder()
            var i = 0
            while (i < glob.length) {
                when (val c = glob[i]) {
                    '*' -> if (i + 1 < glob.length && glob[i + 1] == '*') {
                        // `a/**` should also match `a` itself having nothing
                        // under it, so the separator is swallowed with the star.
                        if (sb.endsWith("/")) {
                            sb.setLength(sb.length - 1)
                            sb.append("(?:/.*)?")
                        } else {
                            sb.append(".*")
                        }
                        i++
                    } else {
                        sb.append("[^/]*")
                    }

                    '?' -> sb.append("[^/]")
                    else -> sb.append(Regex.escape(c.toString()))
                }
                i++
            }
            return Regex(sb.toString(), RegexOption.IGNORE_CASE)
        }
    }
}
