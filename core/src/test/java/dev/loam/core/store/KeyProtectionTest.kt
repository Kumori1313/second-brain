package dev.loam.core.store

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyProtectionTest {

    @Test
    fun `only per-use authentication rules out unattended indexing`() {
        // The periodic schedule is cancelled from this flag, so getting it
        // wrong either strands background passes that cannot decrypt, or
        // silently disables reindexing for someone who never asked for that.
        assertTrue(KeyProtection.OFF.allowsBackgroundIndexing)
        assertTrue(KeyProtection.DEVICE_UNLOCK.allowsBackgroundIndexing)
        assertFalse(KeyProtection.EVERY_TIME.allowsBackgroundIndexing)
    }
}
