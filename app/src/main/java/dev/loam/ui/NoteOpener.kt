package dev.loam.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

/**
 * Opens a note in whatever app the user already uses, and remembers which one.
 *
 * Loam is explicitly not a note editor — it points at the user's own files and
 * hands off.
 *
 * Remembering the choice here rather than leaning on the system default is not
 * a preference, it is a workaround for a measured failure. On a Pixel 8a
 * (Android 17) the system's own "Always" does not stick: it records a preferred
 * activity keyed on the MIME type alone, then re-resolves using the full intent
 * including the content URI, which admits extra activities that match only on
 * scheme — a file manager's "Save as" here. The recorded set never matches the
 * launch-time set, so the default is silently discarded and the picker returns
 * on every single note. Reproducible from adb with no Loam in the path.
 *
 * An explicit component sidesteps intent resolution altogether, so it does not
 * matter what else is installed or what filters it registers.
 */
class NoteOpener(context: Context) {

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * [label] is the app, [detail] the specific activity when an app offers
     * more than one way in. Material Files labels its viewer "Text editor",
     * which on its own says nothing about which app is being pinned.
     */
    data class Handler(
        val component: ComponentName,
        val label: String,
        val detail: String?,
    )

    /**
     * The app the user pinned, or null to ask every time.
     *
     * A [ComponentName] rather than a package name because a package can own
     * several matching activities — Material Files ships both a text viewer and
     * a "Save as", and `setPackage` alone would still put a picker between
     * them.
     */
    var preferred: ComponentName?
        get() = prefs.getString(KEY_COMPONENT, null)?.let(ComponentName::unflattenFromString)
        set(value) = prefs.edit()
            .apply {
                if (value == null) remove(KEY_COMPONENT) else putString(
                    KEY_COMPONENT,
                    value.flattenToString(),
                )
            }
            .apply()

    /**
     * Apps that can display a Markdown note.
     *
     * Queried by MIME type with no data URI, which is deliberate: adding a URI
     * pulls in activities matching only on `content:` scheme, and those are
     * generally "save a copy" or "import" targets rather than viewers. The
     * shorter list is both the more honest answer to "what opens this note" and
     * the one that keeps the dialog readable.
     *
     * Visible without any permission because the manifest declares a matching
     * <queries> element. QUERY_ALL_PACKAGES would also work and is not worth
     * the F-Droid scrutiny it invites.
     */
    fun handlers(): List<Handler> {
        val pm = app.packageManager
        return MIME_TYPES
            .flatMap { mime ->
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(
                    Intent(Intent.ACTION_VIEW).setType(mime),
                    PackageManager.MATCH_DEFAULT_ONLY,
                )
            }
            .map {
                val appLabel = it.activityInfo.applicationInfo.loadLabel(pm).toString()
                val activityLabel = it.loadLabel(pm).toString()
                Handler(
                    component = ComponentName(it.activityInfo.packageName, it.activityInfo.name),
                    label = appLabel,
                    detail = activityLabel.takeIf { label -> label != appLabel },
                )
            }
            .distinctBy { it.component }
            .sortedWith(compareBy({ it.label.lowercase() }, { it.detail.orEmpty().lowercase() }))
    }

    /**
     * Launches [uri], returning false only when nothing on the device can open
     * it. A pinned app that has since been uninstalled or renamed its activity
     * is dropped rather than reported as a failure — falling back to the picker
     * recovers silently, where surfacing an error would blame the user's tap
     * for a stale preference.
     */
    fun open(activityContext: Context, uri: String): Boolean {
        preferred?.let { pinned ->
            if (launch(activityContext, uri, pinned)) return true
            preferred = null
        }
        return launch(activityContext, uri, component = null)
    }

    /** True the first time a note is opened with nothing pinned. */
    fun consumeFirstOpenHint(): Boolean {
        if (preferred != null || prefs.getBoolean(KEY_HINT_SHOWN, false)) return false
        prefs.edit().putBoolean(KEY_HINT_SHOWN, true).apply()
        return true
    }

    private fun launch(context: Context, uri: String, component: ComponentName?): Boolean =
        // text/markdown first so a real Markdown app wins any picker, falling
        // back to text/plain because plenty of devices register only the latter.
        MIME_TYPES.any { mime ->
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(Uri.parse(uri), mime)
                        // Read-only. Loam has no business writing to the vault,
                        // so the app that opens gets exactly enough to show the
                        // file and nothing more.
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        component?.let { setComponent(it) }
                    }
                )
            }.isSuccess
        }

    private companion object {
        const val PREFS = "loam_open_with"
        const val KEY_COMPONENT = "component"
        const val KEY_HINT_SHOWN = "hint_shown"
        val MIME_TYPES = listOf("text/markdown", "text/plain")
    }
}
