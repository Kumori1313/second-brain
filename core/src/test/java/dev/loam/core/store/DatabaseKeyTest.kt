package dev.loam.core.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DatabaseKeyTest {

    @Test
    fun `reads the Keymaster code out of a real KeyStoreException message`() {
        // Verbatim from the device that crashed on every launch. The raw code is
        // the only thing that identifies "authenticate first" on API levels
        // where getNumericErrorCode() reports a different, public code.
        val message = "Key user not authenticated (internal Keystore code: -26 message: " +
            "system/security/keystore2/src/operation.rs:892: KeystoreOperation::update)"
        assertEquals(-26, DatabaseKey.internalKeystoreCode(message))
    }

    @Test
    fun `no code to read is no code, not zero`() {
        // 0 would be a real answer ("OK") and could be mistaken for one.
        assertNull(DatabaseKey.internalKeystoreCode(null))
        assertNull(DatabaseKey.internalKeystoreCode("something else went wrong"))
    }
}
