package dev.loam.ui

import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Text arriving from other apps, and the filters that let it arrive at all.
 *
 * The registration tests query the installed package rather than reading the
 * manifest source, because the manifest source is not what the system resolves
 * against — merged manifests and packaging have surprised this project before,
 * most expensively when the app APK turned out to be packaged differently from
 * the test APK it was validated with.
 */
class SharedQueryTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun send(text: CharSequence?) = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }

    private fun processText(text: CharSequence?) = Intent(Intent.ACTION_PROCESS_TEXT).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_PROCESS_TEXT, text)
    }

    @Test
    fun sharedTextBecomesAQuery() {
        assertEquals("how do I resize a btrfs subvolume", SharedQuery.from(send("how do I resize a btrfs subvolume")))
    }

    @Test
    fun selectedTextBecomesAQuery() {
        // The better half of the feature: highlight a sentence anywhere and ask
        // what you have already written about it.
        assertEquals("argon2id", SharedQuery.from(processText("argon2id")))
    }

    @Test
    fun theTwoExtrasAreNotInterchangeable() {
        // PROCESS_TEXT carries EXTRA_PROCESS_TEXT and SEND carries EXTRA_TEXT.
        // Reading the wrong one yields null and the share silently does nothing.
        assertNull(SharedQuery.from(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_PROCESS_TEXT, "x")))
        assertNull(SharedQuery.from(Intent(Intent.ACTION_PROCESS_TEXT).putExtra(Intent.EXTRA_TEXT, "x")))
    }

    @Test
    fun aSharedParagraphIsFlattenedToOneLine() {
        val query = SharedQuery.from(send("first line\n\nsecond   line\tthird"))

        // The search field is one line. Left as-is the user sees "first line"
        // and no indication that they shared three.
        assertEquals("first line second line third", query)
    }

    @Test
    fun anArticleIsCutWhereTheModelStopsReading() {
        val query = SharedQuery.from(send("word ".repeat(5_000)))

        // The embedder reads 256 tokens. Accepting forty kilobytes would imply
        // Loam had searched all of it.
        assertEquals(SharedQuery.MAX_CHARS, query?.length)
    }

    @Test
    fun emptyAndWhitespaceSharesAreNotQueries() {
        assertNull(SharedQuery.from(send("")))
        assertNull(SharedQuery.from(send("   \n\t ")))
        assertNull(SharedQuery.from(send(null)))
    }

    @Test
    fun anUnrelatedIntentIsIgnored() {
        assertNull(SharedQuery.from(Intent(Intent.ACTION_MAIN)))
        assertNull(SharedQuery.from(null))
    }

    @Test
    fun loamIsRegisteredAsAShareTargetForText() {
        val handlers = context.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_SEND).apply { type = "text/plain" },
            PackageManager.MATCH_DEFAULT_ONLY,
        )

        assertTrue(
            "dev.loam does not appear in the share sheet for text/plain",
            handlers.any { it.activityInfo.packageName == context.packageName },
        )
    }

    @Test
    fun loamIsRegisteredInTheTextSelectionMenu() {
        val handlers = context.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_PROCESS_TEXT).apply { type = "text/plain" },
            PackageManager.MATCH_DEFAULT_ONLY,
        )

        assertTrue(
            "dev.loam does not appear in the text-selection menu",
            handlers.any { it.activityInfo.packageName == context.packageName },
        )
    }

    @Test
    fun sharingDoesNotRequireAnyNewPermission() {
        val declared = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            .orEmpty()

        // Principle #1 holds through this feature: receiving text from another
        // app grants nothing outward, and nothing here reaches the network.
        assertTrue(
            "unexpected permission: ${declared.toList()}",
            declared.none { it.contains("INTERNET") || it.contains("NETWORK") },
        )
    }
}
