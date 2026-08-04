package dev.loam.core.store

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [NoteEntity::class, ChunkEntity::class],
    version = 1,
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
