package dev.loam.core.store

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface LoamDao {

    @Query("SELECT * FROM notes")
    suspend fun allNotes(): List<NoteEntity>

    @Query("SELECT COUNT(*) FROM notes")
    fun noteCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM chunks")
    fun chunkCount(): Flow<Int>

    @Insert
    suspend fun insertNote(note: NoteEntity): Long

    @Insert
    suspend fun insertChunks(chunks: List<ChunkEntity>)

    @Query("DELETE FROM notes WHERE uri IN (:uris)")
    suspend fun deleteNotesByUri(uris: List<String>)

    /**
     * Replaces a note and its chunks in one transaction.
     *
     * Delete-then-insert rather than diffing chunk by chunk: chunk boundaries
     * shift when text above them changes, so an edit near the top of a note
     * invalidates most of it anyway. The cascade on `chunks.noteId` clears the
     * old rows.
     */
    @Transaction
    suspend fun replaceNote(note: NoteEntity, chunks: (Long) -> List<ChunkEntity>) {
        deleteNotesByUri(listOf(note.uri))
        val noteId = insertNote(note)
        insertChunks(chunks(noteId))
    }

    /**
     * Every embedding, with just enough context to rank and display.
     *
     * Loaded whole because search is a brute-force scan — the spike measured
     * 13.81 ms per query over 50k chunks on a Pixel 8a, which is why there is
     * no vector index here and no sqlite-vec dependency.
     */
    @Query(
        """
        SELECT c.id AS chunkId, c.noteId AS noteId, c.embedding AS embedding
        FROM chunks c
        """
    )
    suspend fun allEmbeddings(): List<EmbeddingRow>

    @Query(
        """
        SELECT c.id AS chunkId, c.noteId AS noteId, c.headingPath AS headingPath,
               c.text AS text, n.displayName AS displayName,
               n.relativePath AS relativePath, n.uri AS uri
        FROM chunks c
        JOIN notes n ON n.id = c.noteId
        WHERE c.id IN (:chunkIds)
        """
    )
    suspend fun chunksWithNotes(chunkIds: List<Long>): List<ChunkWithNote>

    @Query("DELETE FROM notes")
    suspend fun clearAll()
}

data class EmbeddingRow(
    val chunkId: Long,
    val noteId: Long,
    val embedding: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EmbeddingRow) return false
        return chunkId == other.chunkId &&
            noteId == other.noteId &&
            embedding.contentEquals(other.embedding)
    }

    override fun hashCode(): Int {
        var result = chunkId.hashCode()
        result = 31 * result + noteId.hashCode()
        result = 31 * result + embedding.contentHashCode()
        return result
    }
}
