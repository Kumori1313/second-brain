package dev.loam.core.store

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import net.zetetic.database.sqlcipher.SQLiteNotADatabaseException
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * Bump `version` whenever stored vectors stop being comparable to freshly
 * computed ones, not only when columns change. The destructive migration then
 * forces a reindex, which is cheap because the index is derived from the user's
 * own files.
 *
 * v2: inputs are padded to a 32-token bucket instead of a flat 256. Embeddings
 * are not perfectly padding-invariant on the INT8 graph, so v1 vectors would be
 * subtly mismatched against v2 queries.
 *
 * v3: chunks are sized by token count rather than by a characters-per-token
 * guess, so chunk boundaries moved and v2 rows describe text that no longer
 * corresponds to any current chunk.
 */
@Database(
    entities = [NoteEntity::class, ChunkEntity::class],
    version = 3,
    exportSchema = true,
)
abstract class LoamDatabase : RoomDatabase() {

    abstract fun dao(): LoamDao

    companion object {
        private const val NAME = "loam.db"

        @Volatile
        private var instance: LoamDatabase? = null

        fun get(context: Context): LoamDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }

        private fun build(context: Context): LoamDatabase {
            System.loadLibrary("sqlcipher")
            val passphrase = DatabaseKey.getOrCreate(context) {
                // The existing file is encrypted with a passphrase that can no
                // longer be recovered, so SQLCipher could never open it. Drop it
                // and let the next index rebuild — the notes are untouched.
                context.deleteDatabase(NAME)
            }

            val db = create(context, passphrase)
            return try {
                // Opened now rather than on first query, so an unreadable file
                // is met here, where it can be dealt with, instead of crashing
                // whichever screen happens to query first.
                db.openHelper.writableDatabase
                db
            } catch (e: SQLiteNotADatabaseException) {
                // The passphrase unsealed fine but does not open the file, so
                // the file belongs to a key that no longer exists. Seen after a
                // bug regenerated the key without dropping the index; without
                // this the app crashed on every launch, and nothing the user
                // could do from inside it would help. The index is derived, so
                // dropping it costs a reindex and nothing else.
                Log.w("LoamDatabase", "index unreadable with the current key — dropping it", e)
                runCatching { db.close() }
                context.deleteDatabase(NAME)
                create(context, passphrase)
            }
        }

        private fun create(context: Context, passphrase: ByteArray): LoamDatabase =
            Room.databaseBuilder(context, LoamDatabase::class.java, NAME)
                .openHelperFactory(SupportOpenHelperFactory(passphrase))
                // The index is derived data — every chunk can be rebuilt from
                // the user's own .md files, which are the actual source of
                // truth. A schema change costs a reindex, not data loss, so
                // hand-writing migrations here would be effort spent guarding
                // a cache.
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
