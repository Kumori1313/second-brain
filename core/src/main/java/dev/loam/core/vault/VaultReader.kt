package dev.loam.core.vault

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

/**
 * Walks a SAF-granted tree and reads `.md` files in place.
 *
 * Note what's absent: no storage permission. SAF grants access to the one tree
 * the user picked, which is exactly the scope this project wants — the app can
 * read the vault and nothing else.
 *
 * This queries [DocumentsContract] directly rather than using `DocumentFile`.
 * The spike measured 13.5 s to enumerate 392 notes through `DocumentFile`,
 * which issues a fresh ContentResolver query per attribute per node. Pulling
 * every column in one cursor per directory reduces that to one query per
 * directory, and enumeration is otherwise a large fixed cost on every reindex.
 */
class VaultReader(private val context: Context) {

    data class NoteFile(
        val uri: Uri,
        val displayName: String,
        /** Path relative to the vault root, for display and stable identity. */
        val relativePath: String,
        val sizeBytes: Long,
        val lastModified: Long,
    )

    /**
     * @param onProgress called with the running count of markdown files found,
     *   so a long walk can report something other than a spinner.
     */
    fun walk(
        treeUri: Uri,
        exclude: ExcludeRules = ExcludeRules.NONE,
        onProgress: (Int) -> Unit = {},
    ): List<NoteFile> {
        val resolver = context.contentResolver
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_SIZE,
        )

        val notes = ArrayList<NoteFile>()
        // Explicit stack, not recursion — vaults nest arbitrarily deep (the test
        // vault reaches 9 levels) and a stack overflow here would be absurd.
        val pending = ArrayDeque<Pair<String, String>>()
        pending.addLast(DocumentsContract.getTreeDocumentId(treeUri) to "")

        while (pending.isNotEmpty()) {
            val (parentId, prefix) = pending.removeLast()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)

            resolver.query(children, projection, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val docId = cursor.getString(0)
                    val name = cursor.getString(1) ?: continue
                    val mime = cursor.getString(2)

                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        // Skip dot-directories: .obsidian holds config, themes,
                        // and plugin code, none of which is the user's writing,
                        // and .trash holds notes they deleted on purpose.
                        // Skipped rather than walked-and-filtered: enumeration
                        // is the expensive half — one ContentResolver query per
                        // node — so not descending costs nothing and filtering
                        // afterwards costs the whole subtree.
                        if (!name.startsWith(".") && !exclude.excludesDirectory("$prefix$name")) {
                            pending.addLast(docId to "$prefix$name/")
                        }
                        continue
                    }
                    if (!name.endsWith(MARKDOWN_EXTENSION, ignoreCase = true)) continue
                    if (exclude.excludesFile("$prefix$name")) continue

                    notes.add(
                        NoteFile(
                            uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId),
                            displayName = name,
                            relativePath = "$prefix$name",
                            sizeBytes = cursor.getLong(4),
                            lastModified = cursor.getLong(3),
                        )
                    )
                    onProgress(notes.size)
                }
            }
        }
        return notes
    }

    fun readText(uri: Uri): String =
        context.contentResolver.openInputStream(uri)?.use {
            it.readBytes().toString(Charsets.UTF_8)
        } ?: ""

    private companion object {
        const val MARKDOWN_EXTENSION = ".md"
    }
}
