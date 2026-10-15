package dev.loam.core.domain

import dev.loam.core.store.KeyProtection

/**
 * Whether the periodic background pass should be scheduled.
 *
 * Three independent things must all hold, and keeping the answer in one place
 * is the point: it used to be decided at three call sites that each knew only
 * some of them, and one of those scheduled a pass the key could not decrypt.
 *
 * - [enabled] is the user's own choice. Background indexing is the one thing
 *   this app does while nobody is looking, and it costs real CPU and battery,
 *   so it is theirs to turn off whatever the protection level.
 * - [protection] can rule it out regardless of that choice, since an unattended
 *   worker has nobody to authenticate. The preference is kept rather than
 *   cleared, so it takes effect again if protection is relaxed.
 * - [hasVault] because there is nothing to index without one.
 */
object PeriodicIndexing {
    fun shouldRun(enabled: Boolean, protection: KeyProtection, hasVault: Boolean): Boolean =
        enabled && hasVault && protection.allowsBackgroundIndexing
}
