package dev.loam.spike

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

/**
 * Walks a SAF-granted tree and reads `.md` files in place.
 *
 * Proves the other half of the Phase 0 exit criteria. Note what's absent: no
 * storage permission in the manifest. SAF grants access to the one tree the
 * user picked, which is exactly the scope this project wants.
 */
class VaultReader(private val context: Context) {

    data class Note(
        val name: String,
        val uri: Uri,
        val sizeBytes: Long,
        val lastModified: Long,
    )

    data class Scan(
        val notes: List<Note>,
        val millis: Long,
        val skippedNonMarkdown: Int,
    )

    fun scan(treeUri: Uri, limit: Int = 5000): Scan {
        val start = System.nanoTime()
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: return Scan(emptyList(), 0, 0)

        val notes = ArrayList<Note>()
        var skipped = 0

        // Explicit stack rather than recursion — a deep vault shouldn't be able
        // to blow the stack, and this makes the traversal order obvious.
        val stack = ArrayDeque<DocumentFile>()
        stack.addLast(root)

        while (stack.isNotEmpty() && notes.size < limit) {
            val dir = stack.removeLast()
            for (child in dir.listFiles()) {
                if (child.isDirectory) {
                    stack.addLast(child)
                    continue
                }
                val name = child.name ?: continue
                if (!name.endsWith(".md", ignoreCase = true)) {
                    skipped++
                    continue
                }
                notes.add(
                    Note(
                        name = name,
                        uri = child.uri,
                        sizeBytes = child.length(),
                        lastModified = child.lastModified(),
                    )
                )
                if (notes.size >= limit) break
            }
        }

        return Scan(notes, (System.nanoTime() - start) / 1_000_000, skipped)
    }

    fun readText(uri: Uri): String =
        context.contentResolver.openInputStream(uri)?.use {
            it.readBytes().toString(Charsets.UTF_8)
        } ?: ""

    /**
     * Heading/paragraph-aware chunking, roughly the shape Phase 1 needs.
     * Kept simple here — the spike only needs realistic text lengths going into
     * the embedder, not the final chunking strategy.
     */
    fun chunk(markdown: String, targetChars: Int = 1200): List<String> {
        val blocks = markdown.split(Regex("\n\\s*\n"))
        val chunks = ArrayList<String>()
        val current = StringBuilder()

        for (block in blocks) {
            val trimmed = block.trim()
            if (trimmed.isEmpty()) continue
            // Start a new chunk at headings, so a chunk carries one topic.
            val isHeading = trimmed.startsWith("#")
            if ((current.length + trimmed.length > targetChars || isHeading) && current.isNotEmpty()) {
                chunks.add(current.toString().trim())
                current.clear()
            }
            current.append(trimmed).append("\n\n")
        }
        if (current.isNotBlank()) chunks.add(current.toString().trim())
        return chunks
    }
}
