package dev.loam.core.domain

import dev.loam.core.store.KeyProtection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeriodicIndexingTest {

    @Test
    fun `runs only when enabled, a vault exists, and the key allows it`() {
        for (enabled in listOf(true, false)) {
            for (protection in KeyProtection.entries) {
                for (hasVault in listOf(true, false)) {
                    val expected = enabled && hasVault && protection != KeyProtection.EVERY_TIME
                    assertEquals(
                        "enabled=$enabled protection=$protection hasVault=$hasVault",
                        expected,
                        PeriodicIndexing.shouldRun(enabled, protection, hasVault),
                    )
                }
            }
        }
    }

    @Test
    fun `turning it off wins at every protection level that would allow it`() {
        // The request that prompted this: the toggle must work independently of
        // index protection, not only when protection happens to disable it.
        for (protection in KeyProtection.entries) {
            assertFalse(PeriodicIndexing.shouldRun(false, protection, hasVault = true))
        }
    }

    @Test
    fun `strict protection still rules it out when the user wants it`() {
        assertFalse(PeriodicIndexing.shouldRun(true, KeyProtection.EVERY_TIME, hasVault = true))
        assertTrue(PeriodicIndexing.shouldRun(true, KeyProtection.OFF, hasVault = true))
        assertTrue(PeriodicIndexing.shouldRun(true, KeyProtection.DEVICE_UNLOCK, hasVault = true))
    }
}
