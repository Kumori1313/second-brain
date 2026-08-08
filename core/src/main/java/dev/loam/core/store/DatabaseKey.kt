package dev.loam.core.store

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Supplies the SQLCipher passphrase, held under a hardware-backed key.
 *
 * The index is derived from personal notes, so it is roughly as sensitive as
 * the notes themselves — chunk text is stored verbatim. Encrypting it is a
 * stated principle, not polish.
 *
 * A 32-byte random passphrase is generated once and sealed with an AES-GCM key
 * that lives in the Android Keystore and cannot be exported. Only the sealed
 * bytes touch shared preferences, so extracting the passphrase off a device
 * means defeating the Keystore, not reading a file.
 *
 * Deliberately not using Jetpack Security's EncryptedSharedPreferences: this is
 * visible cryptography rather than a dependency, and Core principle #4 asks for
 * auditable over convenient.
 *
 * ### Changing protection level without losing the index
 *
 * A Keystore key's authentication policy is fixed at generation, so changing
 * level means sealing the passphrase under a *new* key. The first attempt at
 * this destroyed a real index: it deleted the old key, then failed to seal
 * under the new one — sealing with an auth-per-use key needs authentication —
 * leaving ciphertext nothing could read.
 *
 * The fix is ordering, enforced by structure rather than by care. Two aliases
 * are kept and the active one recorded; a change builds a whole new key at the
 * spare alias, seals under it, and only once that has *succeeded* swaps the
 * pointer and deletes the old key. [beginChange] does everything reversible and
 * [completeChange] performs the swap, so an authentication the user cancels
 * leaves the previous key untouched and still working.
 *
 * ### Recovery
 *
 * A key can still stop matching its ciphertext — invalidated by a credential
 * change, or a bug like the one above. That must not be fatal: the index is
 * derived data, so an unreadable seal regenerates the passphrase and asks the
 * caller to drop the database, costing a reindex rather than anything the user
 * cannot get back.
 */
object DatabaseKey {

    private const val TAG = "LoamKey"
    private const val PREFS = "loam_key_store"
    private const val PREF_SEALED = "sealed_passphrase"
    private const val PREF_PROTECTION = "protection"
    private const val PREF_ALIAS = "key_alias"

    /**
     * Two aliases, alternating. The first keeps the original name so existing
     * installs need no migration.
     */
    private const val ALIAS_A = "loam_db_key"
    private const val ALIAS_B = "loam_db_key_alt"

    private const val KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val GCM_IV_BYTES = 12
    private const val PASSPHRASE_BYTES = 32

    /**
     * How long a device unlock keeps [KeyProtection.DEVICE_UNLOCK] usable.
     *
     * Generous on purpose. The threat this level addresses is an extracted
     * database on a locked device, and that is defeated the moment the phone
     * locks regardless of the window; a short window would only add failures
     * for background work without changing what an attacker can reach.
     */
    private const val UNLOCK_VALIDITY_SECONDS = 4 * 60 * 60

    /**
     * Unwrapped once per process and kept in memory.
     *
     * Without this, an authenticated level would fail mid-session the moment
     * its validity window lapsed, which reads as the app randomly breaking. One
     * successful unwrap per process is also what makes a prompt "on open"
     * rather than "on every query".
     */
    @Volatile
    private var cached: ByteArray? = null

    /** Raised when the key exists but the current policy is not satisfied. */
    class LockedException(cause: Throwable? = null) :
        Exception("Index is locked; authentication required", cause)

    /**
     * Whether a failure means "authenticate first" rather than "this key is
     * gone".
     *
     * Android does not surface [UserNotAuthenticatedException] directly from
     * `doFinal` — it arrives wrapped, typically inside an
     * `IllegalBlockSizeException` — so catching the type alone silently misses
     * every real case. Getting this wrong is not cosmetic: the unwrap path
     * treats an unreadable seal as a lost key and regenerates it, so a
     * not-yet-authenticated key would have been mistaken for a destroyed one
     * and the index thrown away on the first locked start.
     */
    fun isAuthRequired(error: Throwable?): Boolean {
        var cause = error
        while (cause != null) {
            // Time-bound keys report this from Cipher.init.
            if (cause is UserNotAuthenticatedException) return true

            // Auth-per-use keys do not. They fail inside doFinal, and the real
            // reason arrives as an android.security.KeyStoreException nested in
            // an IllegalBlockSizeException:
            //
            //   javax.crypto.IllegalBlockSizeException: null
            //     <- android.security.KeyStoreException: Key user not
            //        authenticated (internal Keystore code: -26 ...)
            //
            // Matched on the Keymaster code rather than the message, which is
            // not contractual. Missing this is expensive rather than cosmetic:
            // the unwrap path reads an unreadable seal as a lost key and
            // regenerates it, so an unauthenticated key would be mistaken for a
            // destroyed one and the index thrown away.
            if (cause is android.security.KeyStoreException &&
                keystoreErrorCode(cause) == KM_ERROR_KEY_USER_NOT_AUTHENTICATED
            ) {
                return true
            }

            if (cause == cause.cause) break
            cause = cause.cause
        }
        return false
    }

    /** -26, `KM_ERROR_KEY_USER_NOT_AUTHENTICATED` in the Keymaster HAL. */
    private const val KM_ERROR_KEY_USER_NOT_AUTHENTICATED = -26

    private fun keystoreErrorCode(e: android.security.KeyStoreException): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            e.numericErrorCode
        } else {
            // Pre-33 has no accessor; the code is embedded in the message.
            Regex("internal Keystore code: (-?\\d+)").find(e.message.orEmpty())
                ?.groupValues?.get(1)?.toIntOrNull() ?: 0
        }

    fun protection(context: Context): KeyProtection =
        prefs(context).getString(PREF_PROTECTION, null)
            ?.let { runCatching { KeyProtection.valueOf(it) }.getOrNull() }
            ?: KeyProtection.OFF

    /**
     * True when a caller must authenticate before the index can be opened.
     *
     * Attempts the unwrap rather than inferring from the level.
     * [KeyProtection.DEVICE_UNLOCK] promises "no prompt in normal use", and the
     * only way to honour that is to try — a recent phone unlock already
     * satisfies the key, so assuming locked-until-prompted showed a lock screen
     * on every cold start and made the middle level behave like the strict one.
     */
    fun isLocked(context: Context): Boolean {
        if (cached != null) return false
        if (protection(context) == KeyProtection.OFF) return false
        return runCatching { getOrCreate(context) }.isFailure
    }

    /**
     * @param onKeyLost invoked when a sealed passphrase exists but cannot be
     *   read, immediately before a new one is generated. The caller must drop
     *   the database, since it is encrypted with a passphrase that is now gone.
     */
    @Synchronized
    fun getOrCreate(context: Context, onKeyLost: () -> Unit = {}): ByteArray {
        cached?.let { return it }

        val prefs = prefs(context)
        val sealed = prefs.getString(PREF_SEALED, null)
        if (sealed != null) {
            try {
                return unseal(context, sealed).also { cached = it }
            } catch (e: Exception) {
                // Recoverable by authenticating — emphatically not a lost key,
                // and regenerating here would throw the index away for nothing.
                if (isAuthRequired(e)) throw LockedException(e)
                Log.w(TAG, "sealed passphrase unreadable — regenerating; index will be rebuilt", e)
                clear(context)
                onKeyLost()
            }
        }

        val passphrase = ByteArray(PASSPHRASE_BYTES).also {
            java.security.SecureRandom().nextBytes(it)
        }
        try {
            val fresh = seal(passphrase, keyAt(activeAlias(context), protection(context)))
            prefs.edit().putString(PREF_SEALED, fresh).commit()
        } catch (e: Exception) {
            if (isAuthRequired(e)) throw LockedException(e)
            throw e
        }
        cached = passphrase
        return passphrase
    }

    /**
     * Everything reversible about a protection change: a fresh key at the spare
     * alias, and a cipher ready to seal with.
     *
     * Nothing here touches the active key or the stored ciphertext, so a caller
     * that authenticates and then cancels — or crashes — leaves a working
     * install behind.
     */
    @Synchronized
    fun beginChange(context: Context, level: KeyProtection): Change {
        val passphrase = getOrCreate(context)
        val target = if (activeAlias(context) == ALIAS_A) ALIAS_B else ALIAS_A
        // The spare may hold a key from an attempt that was abandoned.
        runCatching { keyStore().deleteEntry(target) }

        val cipher = Cipher.getInstance(TRANSFORMATION)
            .apply { init(Cipher.ENCRYPT_MODE, keyAt(target, level)) }
        return Change(level, target, cipher, passphrase)
    }

    /**
     * Seals under the new key and swaps, in that order.
     *
     * The seal happens first and is allowed to throw — an auth-per-use key
     * refuses to encrypt without authentication — at which point nothing has
     * changed. Only once there is ciphertext does the pointer move, and only
     * then is the old key deleted.
     */
    @Synchronized
    fun completeChange(context: Context, change: Change) {
        val previous = activeAlias(context)
        val sealed = Base64.encodeToString(
            change.cipher.iv + change.cipher.doFinal(change.passphrase),
            Base64.NO_WRAP,
        )

        prefs(context).edit()
            .putString(PREF_SEALED, sealed)
            .putString(PREF_PROTECTION, change.level.name)
            .putString(PREF_ALIAS, change.alias)
            .commit()

        cached = change.passphrase
        if (previous != change.alias) runCatching { keyStore().deleteEntry(previous) }
    }

    /** Discards a prepared change, leaving the install exactly as it was. */
    @Synchronized
    fun abandonChange(change: Change) {
        runCatching { keyStore().deleteEntry(change.alias) }
    }

    class Change internal constructor(
        internal val level: KeyProtection,
        internal val alias: String,
        /** Hand to `BiometricPrompt.CryptoObject` when authentication is needed. */
        val cipher: Cipher,
        internal val passphrase: ByteArray,
    )

    /**
     * A cipher for unwrapping, to hand to `BiometricPrompt.CryptoObject`, or
     * null when nothing needs unwrapping.
     *
     * [KeyProtection.EVERY_TIME] keys authorise a single operation, so the very
     * cipher the user authenticates is the one that must decrypt.
     */
    @Synchronized
    fun unlockCipher(context: Context): Cipher? {
        if (cached != null) return null
        val sealed = prefs(context).getString(PREF_SEALED, null) ?: return null
        val bytes = Base64.decode(sealed, Base64.NO_WRAP)
        return Cipher.getInstance(TRANSFORMATION).apply {
            init(
                Cipher.DECRYPT_MODE,
                keyAt(activeAlias(context), protection(context)),
                GCMParameterSpec(GCM_TAG_BITS, bytes, 0, GCM_IV_BYTES),
            )
        }
    }

    /** Completes an unlock with the cipher the user just authenticated. */
    @Synchronized
    fun completeUnlock(context: Context, cipher: Cipher) {
        val sealed = prefs(context).getString(PREF_SEALED, null) ?: return
        val bytes = Base64.decode(sealed, Base64.NO_WRAP)
        cached = cipher.doFinal(bytes, GCM_IV_BYTES, bytes.size - GCM_IV_BYTES)
    }

    /** For levels that need no prompt: unwrap now, or report locked. */
    @Synchronized
    fun unlockWithoutPrompt(context: Context) {
        getOrCreate(context)
    }

    /**
     * Drops the key. Recoverable rather than destructive: the index is derived
     * from the user's own files and a reindex rebuilds it.
     */
    @Synchronized
    fun clear(context: Context) {
        // Level and alias go too. A regenerated key is unprotected, and leaving
        // a stale level behind would have Settings claim a protection the key
        // does not actually carry.
        prefs(context).edit()
            .remove(PREF_SEALED)
            .remove(PREF_PROTECTION)
            .remove(PREF_ALIAS)
            .commit()
        runCatching { keyStore().deleteEntry(ALIAS_A) }
        runCatching { keyStore().deleteEntry(ALIAS_B) }
        cached = null
    }

    private fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun activeAlias(context: Context): String =
        prefs(context).getString(PREF_ALIAS, ALIAS_A) ?: ALIAS_A

    private fun seal(plaintext: ByteArray, key: SecretKey): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val encrypted = cipher.doFinal(plaintext)
        // IV is generated by the cipher and must be stored with the ciphertext;
        // it is not secret, only single-use.
        return Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }

    private fun unseal(context: Context, sealed: String): ByteArray {
        val bytes = Base64.decode(sealed, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            keyAt(activeAlias(context), protection(context)),
            GCMParameterSpec(GCM_TAG_BITS, bytes, 0, GCM_IV_BYTES),
        )
        return cipher.doFinal(bytes, GCM_IV_BYTES, bytes.size - GCM_IV_BYTES)
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    /**
     * @param level only consulted when the alias holds no key. An existing key's
     *   authentication policy is immutable, which is the whole reason changes go
     *   through [beginChange] and a second alias.
     */
    private fun keyAt(alias: String, level: KeyProtection): SecretKey {
        val existing = keyStore().getEntry(alias, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey

        val builder = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)

        if (level != KeyProtection.OFF) {
            builder.setUserAuthenticationRequired(true)
            // Survive fingerprint enrollment changes. The default would
            // invalidate the key, costing a reindex, and buys little here: a
            // device credential is already an accepted authenticator, so an
            // attacker who can enroll a fingerprint knows the PIN anyway.
            builder.setInvalidatedByBiometricEnrollment(false)

            val seconds =
                if (level == KeyProtection.DEVICE_UNLOCK) UNLOCK_VALIDITY_SECONDS else 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                builder.setUserAuthenticationParameters(
                    seconds,
                    KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
                )
            } else {
                // Pre-30 has no auth-type selection, and -1 means "every use".
                @Suppress("DEPRECATION")
                builder.setUserAuthenticationValidityDurationSeconds(
                    if (seconds == 0) -1 else seconds
                )
            }
        }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(builder.build())
        return generator.generateKey()
    }
}
