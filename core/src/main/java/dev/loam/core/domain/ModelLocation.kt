package dev.loam.core.domain

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns

/**
 * Remembers which GGUF file the user picked, across restarts.
 *
 * Deliberately the same shape as [VaultLocation], because it is the same
 * mechanism for the same reason: the Phase 2 spike established that a model can
 * be sideloaded through SAF and mapped in place from `/proc/self/fd/N`, so Loam
 * never needs `INTERNET` to acquire one. The permission the project leads with
 * survives the local LLM.
 *
 * As with the vault, a SAF grant does not survive a reboot unless it is
 * persisted, so [save] takes the persistable permission at the same moment it
 * records the URI. Recording one without the other leaves a path that resolves
 * to nothing on next launch.
 */
class ModelLocation(private val context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val modelUri: Uri?
        get() = prefs.getString(KEY_MODEL_URI, null)?.let(Uri::parse)

    /** Cached at pick time — resolving it later needs a still-valid grant. */
    val displayName: String?
        get() = prefs.getString(KEY_DISPLAY_NAME, null)

    fun save(uri: Uri) {
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
        prefs.edit()
            .putString(KEY_MODEL_URI, uri.toString())
            .putString(KEY_DISPLAY_NAME, queryDisplayName(uri))
            .apply()
    }

    /** True if the grant is still usable — the user can revoke it in Settings. */
    fun isGrantValid(): Boolean {
        val uri = modelUri ?: return false
        return context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission
        }
    }

    fun clear() {
        prefs.edit().remove(KEY_MODEL_URI).remove(KEY_DISPLAY_NAME).apply()
    }

    private fun queryDisplayName(uri: Uri): String? =
        runCatching {
            context.contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')

    private companion object {
        const val PREFS = "loam_model"
        const val KEY_MODEL_URI = "model_uri"
        const val KEY_DISPLAY_NAME = "display_name"
    }
}
