package dev.loam.core.domain

import android.content.Context
import android.net.Uri
import dev.loam.core.embed.Embedder
import dev.loam.core.search.VectorIndex
import dev.loam.core.store.ChunkEntity
import dev.loam.core.store.LoamDatabase
import dev.loam.core.store.NoteEntity
import dev.loam.core.vault.Chunker
import dev.loam.core.vault.VaultReader
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Walks the vault, embeds what changed, and writes it to the encrypted store.
 *
 * Incremental by fingerprint: a note is re-embedded only when its mtime or size
 * differs from what was stored. SAF offers no filesystem watch, so periodic and
 * manual reindexing is the intended design rather than a gap to close.
 *
 * Measured shape of the work on a Pixel 8a, 392-note vault: enumeration is a
 * large fixed cost and embedding dominates thereafter, at ~25 ms per chunk.
 * Both stages report progress because both are slow enough to need it.
 */
class IndexVault(
    private val context: Context,
    private val embedderFactory: () -> Embedder,
) {

    sealed interface Progress {
        data class Walking(val filesFound: Int) : Progress
        data class Embedding(
            val notesDone: Int,
            val notesTotal: Int,
            val chunksEmbedded: Int,
        ) : Progress

        data class Done(
            val notesIndexed: Int,
            val notesSkipped: Int,
            val notesRemoved: Int,
            val chunksEmbedded: Int,
            val millis: Long,
        ) : Progress
    }

    suspend fun run(
        treeUri: Uri,
        onProgress: (Progress) -> Unit = {},
    ): Progress.Done {
        val start = System.currentTimeMillis()
        val dao = LoamDatabase.get(context).dao()
        val reader = VaultReader(context)

        val found = reader.walk(treeUri) { onProgress(Progress.Walking(it)) }

        // Fingerprint by (mtime, size) — see NoteEntity for why not a hash.
        val known = dao.allNotes().associateBy { it.uri }
        val foundUris = found.mapTo(HashSet()) { it.uri.toString() }

        val stale = known.keys.filterNot { it in foundUris }
        if (stale.isNotEmpty()) dao.deleteNotesByUri(stale)

        val changed = found.filter { note ->
            val prior = known[note.uri.toString()]
            prior == null ||
                prior.lastModified != note.lastModified ||
                prior.sizeBytes != note.sizeBytes
        }

        var chunksEmbedded = 0
        if (changed.isNotEmpty()) {
            embedderFactory().use { embedder ->
                changed.forEachIndexed { index, note ->
                    // Indexing is long enough that cancellation has to be real,
                    // not "finishes the whole vault after you press stop".
                    currentCoroutineContext().ensureActive()

                    val text = reader.readText(note.uri)
                    val chunks = Chunker.chunk(text)
                    val entity = NoteEntity(
                        uri = note.uri.toString(),
                        relativePath = note.relativePath,
                        displayName = note.displayName,
                        lastModified = note.lastModified,
                        sizeBytes = note.sizeBytes,
                        indexedAt = System.currentTimeMillis(),
                    )
                    val embedded = chunks.map { chunk ->
                        chunk to VectorIndex.toBytes(embedder.embed(chunk.text))
                    }
                    dao.replaceNote(entity) { noteId ->
                        embedded.map { (chunk, blob) ->
                            ChunkEntity(
                                noteId = noteId,
                                ordinal = chunk.ordinal,
                                headingPath = chunk.headingPath,
                                text = chunk.text,
                                embedding = blob,
                            )
                        }
                    }
                    chunksEmbedded += embedded.size
                    onProgress(
                        Progress.Embedding(
                            notesDone = index + 1,
                            notesTotal = changed.size,
                            chunksEmbedded = chunksEmbedded,
                        )
                    )
                }
            }
        }

        return Progress.Done(
            notesIndexed = changed.size,
            notesSkipped = found.size - changed.size,
            notesRemoved = stale.size,
            chunksEmbedded = chunksEmbedded,
            millis = System.currentTimeMillis() - start,
        ).also(onProgress)
    }
}
