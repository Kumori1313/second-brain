package dev.loam.core.domain

import android.content.Context
import dev.loam.core.store.LoamDatabase
import kotlinx.coroutines.flow.Flow

/**
 * How much is indexed, as live counts.
 *
 * Exists so the UI never touches Room directly — the store stays an
 * implementation detail of :core, and the schema can change without dragging
 * the UI along.
 */
class IndexStats(context: Context) {

    private val dao = LoamDatabase.get(context).dao()

    val noteCount: Flow<Int> = dao.noteCount()
    val chunkCount: Flow<Int> = dao.chunkCount()
}
