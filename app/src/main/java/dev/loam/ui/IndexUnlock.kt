package dev.loam.ui

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import dev.loam.core.store.DatabaseKey
import dev.loam.core.store.KeyProtection

/**
 * Gate shown when the index cannot be opened until the user authenticates.
 *
 * The whole screen rather than a dialog over it: with the key locked, nothing
 * behind this can load — not the note count, not a search, not the model. A
 * dialog would sit over a UI that is entirely empty and half-broken.
 */
@Composable
fun IndexLocked(
    error: String?,
    onUnlock: () -> Unit,
) {
    Centered {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Loam is locked", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                "Your index is encrypted with a key that needs authentication.",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (error != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(24.dp))
            Button(onClick = onUnlock) { Text("Unlock") }
        }
    }
}

/**
 * Authenticates and unwraps the passphrase.
 *
 * [KeyProtection.EVERY_TIME] keys authorise a single cryptographic operation,
 * so the cipher the user authenticates must be the one that unwraps — which is
 * why the prompt carries a `CryptoObject` rather than simply gating a later
 * decrypt. [KeyProtection.DEVICE_UNLOCK] needs no prompt at all when the phone
 * was unlocked recently; the prompt only appears when the window has lapsed.
 */
fun unlockIndex(
    activity: FragmentActivity,
    onResult: (Throwable?) -> Unit,
) {
    val level = DatabaseKey.protection(activity)
    if (level == KeyProtection.OFF) {
        onResult(null)
        return
    }

    // Try without a prompt first. Under DEVICE_UNLOCK this usually succeeds,
    // because opening the app tends to follow unlocking the phone.
    val cipher = try {
        DatabaseKey.unlockCipher(activity)
    } catch (e: Throwable) {
        null
    }
    if (cipher == null) {
        onResult(runCatching { DatabaseKey.unlockWithoutPrompt(activity) }.exceptionOrNull())
        return
    }

    val allowed = BiometricManager.Authenticators.BIOMETRIC_STRONG or
        BiometricManager.Authenticators.DEVICE_CREDENTIAL
    if (BiometricManager.from(activity).canAuthenticate(allowed) !=
        BiometricManager.BIOMETRIC_SUCCESS
    ) {
        onResult(
            IllegalStateException(
                "This device has no screen lock configured, so the index cannot be " +
                    "unlocked. Set one, or turn protection off in Settings."
            )
        )
        return
    }

    val prompt = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val authenticated = result.cryptoObject?.cipher
                if (authenticated == null) {
                    onResult(IllegalStateException("Authentication returned no cipher"))
                    return
                }
                onResult(
                    runCatching { DatabaseKey.completeUnlock(activity, authenticated) }
                        .exceptionOrNull()
                )
            }

            override fun onAuthenticationError(code: Int, message: CharSequence) {
                onResult(IllegalStateException(message.toString()))
            }
        },
    )

    prompt.authenticate(
        BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Loam")
            .setSubtitle("Your note index is encrypted")
            .setAllowedAuthenticators(allowed)
            .build(),
        BiometricPrompt.CryptoObject(cipher),
    )
}
