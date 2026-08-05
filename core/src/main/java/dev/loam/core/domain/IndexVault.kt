package dev.loam.core.domain

import android.content.Context
import android.net.Uri
import android.util.Log
import dev.loam.core.embed.Embedder
import dev.loam.core.search.VectorIndex
import dev.loam.core.store.ChunkEntity
import dev.loam.core.store.LoamDatabase
import dev.loam.core.store.NoteEntity
import dev.loam.core.vault.Chunker
import dev.loam.core.vault.TokenCounter
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
    private val tokenCounter: TokenCounter,
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
            val timings: Timings = Timings(),
        ) : Progress
    }

    /**
     * Per-stage wall clock, so a slow index can be attributed instead of
     * guessed at.
     *
     * The first measured run reported 397 s for 3,427 chunks and it was
     * tempting to read that as ~116 ms per embedding — but the figure blends
     * embedding with 392 SAF reads and 392 encrypted writes. Splitting the
     * stages is what makes the difference visible.
     */
    data class Timings(
        var walkMs: Long = 0,
        var readMs: Long = 0,
        var chunkMs: Long = 0,
        var embedMs: Long = 0,
        var storeMs: Long = 0,
        var embedderInitMs: Long = 0,
    ) {
        fun summary(chunks: Int): String = buildString {
            append("walk=${walkMs}ms read=${readMs}ms chunk=${chunkMs}ms ")
            append("embed=${embedMs}ms store=${storeMs}ms init=${embedderInitMs}ms")
            if (chunks > 0) append(" | embed/chunk=%.1fms".format(embedMs.toDouble() / chunks))
        }
    }

    suspend fun run(
        treeUri: Uri,
        onProgress: (Progress) -> Unit = {},
    ): Progress.Done {
        val start = System.currentTimeMillis()
        val t = Timings()
        val dao = LoamDatabase.get(context).dao()
        val reader = VaultReader(context)

        val walkStart = System.nanoTime()
        val found = reader.walk(treeUri) { onProgress(Progress.Walking(it)) }
        t.walkMs = (System.nanoTime() - walkStart) / 1_000_000

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
            val initStart = System.nanoTime()
            val created = embedderFactory()
            t.embedderInitMs = (System.nanoTime() - initStart) / 1_000_000

            // Window markers, to separate a steady cost from one that degrades
            // as the device heats up.
            var windowStart = System.nanoTime()
            var windowChunks = 0
            var lastTokNanos = 0L
            var lastInfNanos = 0L

            created.use { embedder ->
                changed.forEachIndexed { index, note ->
                    // Indexing is long enough that cancellation has to be real,
                    // not "finishes the whole vault after you press stop".
                    currentCoroutineContext().ensureActive()

                    val readStart = System.nanoTime()
                    val text = reader.readText(note.uri)
                    t.readMs += (System.nanoTime() - readStart) / 1_000_000

                    val chunkStart = System.nanoTime()
                    val chunks = Chunker.chunk(text, tokenCounter)
                    t.chunkMs += (System.nanoTime() - chunkStart) / 1_000_000

                    val entity = NoteEntity(
                        uri = note.uri.toString(),
                        relativePath = note.relativePath,
                        displayName = note.displayName,
                        lastModified = note.lastModified,
                        sizeBytes = note.sizeBytes,
                        indexedAt = System.currentTimeMillis(),
                    )
                    val embedStart = System.nanoTime()
                    val embedded = chunks.map { chunk ->
                        chunk to VectorIndex.toBytes(embedder.embed(chunk.text))
                    }
                    t.embedMs += (System.nanoTime() - embedStart) / 1_000_000

                    val storeStart = System.nanoTime()
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
                    t.storeMs += (System.nanoTime() - storeStart) / 1_000_000

                    chunksEmbedded += embedded.size
                    windowChunks += embedded.size
                    if ((index + 1) % WINDOW_NOTES == 0) {
                        val elapsed = (System.nanoTime() - windowStart) / 1_000_000.0
                        val n = windowChunks.coerceAtLeast(1)
                        val tok = (embedder.tokenizeNanos - lastTokNanos) / 1_000_000.0
                        val inf = (embedder.inferNanos - lastInfNanos) / 1_000_000.0
                        lastTokNanos = embedder.tokenizeNanos
                        lastInfNanos = embedder.inferNanos
                        Log.i(
                            TAG,
                            "window notes=${index + 1}/${changed.size} " +
                                "chunks=$windowChunks " +
                                ("ms/chunk=%.1f tokenize=%.1f infer=%.1f"
                                    .format(elapsed / n, tok / n, inf / n))
                        )
                        windowStart = System.nanoTime()
                        windowChunks = 0
                    }
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

        val total = System.currentTimeMillis() - start
        Log.i(
            TAG,
            "done notes=${changed.size} chunks=$chunksEmbedded total=${total}ms | " +
                t.summary(chunksEmbedded) +
                " | thread=${Thread.currentThread().name} " +
                "prio=${android.os.Process.getThreadPriority(android.os.Process.myTid())}"
        )

        return Progress.Done(
            notesIndexed = changed.size,
            notesSkipped = found.size - changed.size,
            notesRemoved = stale.size,
            chunksEmbedded = chunksEmbedded,
            millis = total,
            timings = t,
        ).also(onProgress)
    }

    private companion object {
        const val TAG = "LoamIndex"
        const val WINDOW_NOTES = 25
    }
}
