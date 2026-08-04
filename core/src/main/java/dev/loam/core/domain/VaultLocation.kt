package dev.loam.core.domain

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Remembers which folder the user granted, across restarts.
 *
 * SAF grants are revocable and do not survive a reboot unless explicitly
 * persisted, so [save] takes the persistable permission at the same time it
 * records the URI — doing one without the other leaves a stored URI that can no
 * longer be read.
 */
class VaultLocation(private val context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val treeUri: Uri?
        get() = prefs.getString(KEY_TREE_URI, null)?.let(Uri::parse)

    fun save(uri: Uri) {
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
        prefs.edit().putString(KEY_TREE_URI, uri.toString()).apply()
    }

    /** True if the grant is still usable — the user can revoke it in Settings. */
    fun isGrantValid(): Boolean {
        val uri = treeUri ?: return false
        return context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission
        }
    }

    fun clear() {
        prefs.edit().remove(KEY_TREE_URI).apply()
    }

    private companion object {
        const val PREFS = "loam_vault"
        const val KEY_TREE_URI = "tree_uri"
    }
}
