package dev.loam.core.store

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A note as of the last time it was indexed.
 *
 * [lastModified] and [sizeBytes] together are the change fingerprint. SAF gives
 * no filesystem watch, so reindexing compares these rather than hashing every
 * file — hashing means reading every byte of the vault, which is the expensive
 * part. Size catches the edits mtime misses (touch-preserving editors, restores
 * from backup); the pair is wrong only for an edit that preserves both, which
 * a manual reindex covers.
 */
@Entity(
    tableName = "notes",
    indices = [Index(value = ["uri"], unique = true)],
)
data class NoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uri: String,
    val relativePath: String,
    val displayName: String,
    val lastModified: Long,
    val sizeBytes: Long,
    val indexedAt: Long,
)

/**
 * One embedded chunk.
 *
 * The vector is stored as a BLOB of little-endian floats rather than a
 * normalized table of numbers: it is only ever read as a whole, and 384 rows
 * per chunk would make the store enormous for no query benefit.
 */
@Entity(
    tableName = "chunks",
    foreignKeys = [
        ForeignKey(
            entity = NoteEntity::class,
            parentColumns = ["id"],
            childColumns = ["noteId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("noteId")],
)
data class ChunkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val noteId: Long,
    val ordinal: Int,
    val headingPath: String,
    val text: String,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val embedding: ByteArray,
) {
    // Room data classes with an array field need these spelled out; the
    // generated equals() would compare references and silently misbehave.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ChunkEntity) return false
        return id == other.id &&
            noteId == other.noteId &&
            ordinal == other.ordinal &&
            headingPath == other.headingPath &&
            text == other.text &&
            embedding.contentEquals(other.embedding)
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + noteId.hashCode()
        result = 31 * result + ordinal
        result = 31 * result + headingPath.hashCode()
        result = 31 * result + text.hashCode()
        result = 31 * result + embedding.contentHashCode()
        return result
    }
}

/** A chunk joined to the note it came from — what a search result needs. */
data class ChunkWithNote(
    val chunkId: Long,
    val noteId: Long,
    val headingPath: String,
    val text: String,
    val displayName: String,
    val relativePath: String,
    val uri: String,
)
