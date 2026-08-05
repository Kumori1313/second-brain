package dev.loam.core.store

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
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
 */
@Database(
    entities = [NoteEntity::class, ChunkEntity::class],
    version = 2,
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
            val passphrase = DatabaseKey.getOrCreate(context)

            return Room.databaseBuilder(context, LoamDatabase::class.java, NAME)
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
}
