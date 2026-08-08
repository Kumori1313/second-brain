package dev.loam.core.store

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import android.util.Base64
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
 * How it works: a 32-byte random passphrase is generated once and sealed with
 * an AES-GCM key that lives in the Android Keystore and cannot be exported.
 * Only the sealed bytes touch shared preferences. Extracting the passphrase off
 * a locked device therefore means defeating the Keystore, not reading a file.
 *
 * Deliberately not using Jetpack Security's EncryptedSharedPreferences: this is
 * visible cryptography rather than a dependency, and Core principle #4 asks for
 * auditable over convenient.
 *
 * ### What [KeyProtection] changes, and what it costs
 *
 * An earlier version of this comment claimed adding authentication would be "a
 * spec change on the key, not a re-encryption, so it costs no migration". That
 * was wrong: a Keystore key's authentication policy is fixed at generation, so
 * changing level means generating a new key and re-sealing the passphrase under
 * it — which is why [setProtection] exists and why it needs the old policy to
 * still be satisfiable.
 */
object DatabaseKey {

    private const val PREFS = "loam_key_store"
    private const val PREF_SEALED = "sealed_passphrase"
    private const val PREF_PROTECTION = "protection"
    private const val KEY_ALIAS = "loam_db_key"
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
     * successful unwrap per process is also what makes the prompt "on open"
     * rather than "on every query".
     */
    @Volatile
    private var cached: ByteArray? = null

    /** Raised when the key exists but the current policy is not satisfied. */
    class LockedException(cause: Throwable? = null) :
        Exception("Index is locked; authentication required", cause)

    fun protection(context: Context): KeyProtection =
        prefs(context).getString(PREF_PROTECTION, null)
            ?.let { runCatching { KeyProtection.valueOf(it) }.getOrNull() }
            ?: KeyProtection.OFF

    /**
     * True when a caller must authenticate before the index can be opened.
     *
     * Attempts the unwrap rather than inferring from the level, and caches it on
     * success. [KeyProtection.DEVICE_UNLOCK] promises "no prompt in normal use",
     * and the only way to honour that is to try — a recent phone unlock already
     * satisfies the key, so assuming locked-until-prompted showed a lock screen
     * on every cold start and made the middle level behave like the strict one.
     */
    fun isLocked(context: Context): Boolean {
        if (cached != null) return false
        if (protection(context) == KeyProtection.OFF) return false
        return runCatching { getOrCreate(context) }.isFailure
    }

    @Synchronized
    fun getOrCreate(context: Context): ByteArray {
        cached?.let { return it }

        val prefs = prefs(context)
        val sealed = prefs.getString(PREF_SEALED, null)
        if (sealed != null) {
            return try {
                unseal(sealed).also { cached = it }
            } catch (e: UserNotAuthenticatedException) {
                throw LockedException(e)
            }
        }

        val passphrase = ByteArray(PASSPHRASE_BYTES).also {
            java.security.SecureRandom().nextBytes(it)
        }
        try {
            prefs.edit().putString(PREF_SEALED, seal(passphrase, protection(context))).commit()
        } catch (e: UserNotAuthenticatedException) {
            throw LockedException(e)
        }
        cached = passphrase
        return passphrase
    }

    /**
     * Changes the protection level, re-sealing the passphrase under a new key.
     *
     * Requires the *current* policy to be satisfiable, since the passphrase has
     * to be read before it can be re-sealed. In practice the caller is already
     * inside the unlocked app, so this holds.
     */
    @Synchronized
    fun setProtection(context: Context, level: KeyProtection) {
        if (protection(context) == level) return

        val passphrase = getOrCreate(context)
        keyStore().deleteEntry(KEY_ALIAS)
        prefs(context).edit().putString(PREF_PROTECTION, level.name).commit()
        prefs(context).edit().putString(PREF_SEALED, seal(passphrase, level)).commit()
        cached = passphrase
    }

    /**
     * A cipher ready for `BiometricPrompt.CryptoObject`, or null when no prompt
     * is needed.
     *
     * [KeyProtection.EVERY_TIME] keys authorise a single operation, so the very
     * cipher the user authenticates is the one that must do the unwrapping —
     * hence handing it out rather than prompting and then decrypting.
     */
    @Synchronized
    fun unlockCipher(context: Context): Cipher? {
        if (cached != null) return null
        val sealed = prefs(context).getString(PREF_SEALED, null) ?: return null
        val bytes = Base64.decode(sealed, Base64.NO_WRAP)
        return Cipher.getInstance(TRANSFORMATION).apply {
            init(
                Cipher.DECRYPT_MODE,
                secretKey(protection(context)),
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
     * Drops the key, making any existing database permanently unreadable.
     *
     * Recoverable rather than destructive: the index is derived from the user's
     * own files and a reindex rebuilds it.
     */
    @Synchronized
    fun clear(context: Context) {
        prefs(context).edit().remove(PREF_SEALED).commit()
        runCatching { keyStore().deleteEntry(KEY_ALIAS) }
        cached = null
    }

    private fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun seal(plaintext: ByteArray, level: KeyProtection): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey(level))
        val encrypted = cipher.doFinal(plaintext)
        // IV is generated by the cipher and must be stored with the ciphertext;
        // it is not secret, only single-use.
        return Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }

    private fun unseal(sealed: String): ByteArray {
        val bytes = Base64.decode(sealed, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(KeyProtection.OFF),
            GCMParameterSpec(GCM_TAG_BITS, bytes, 0, GCM_IV_BYTES),
        )
        return cipher.doFinal(bytes, GCM_IV_BYTES, bytes.size - GCM_IV_BYTES)
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    /**
     * @param level only consulted when the key does not exist yet. A Keystore
     *   key's authentication policy is immutable, so an existing key is returned
     *   as-is and level changes go through [setProtection].
     */
    private fun secretKey(level: KeyProtection): SecretKey {
        val existing = keyStore().getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey

        val builder = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
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
