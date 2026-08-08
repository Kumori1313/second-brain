package dev.loam.core.store

/**
 * How hard it is to read the index off this device.
 *
 * A three-way tradeoff with no universally right answer — at-rest protection,
 * prompt friction, and background freshness — which is exactly why it is a
 * setting rather than a decision baked in.
 *
 * The thing that makes the strict end tolerable: **the index is derived data**.
 * Every chunk can be rebuilt from the user's own `.md` files, so the worst case
 * of a key becoming unusable is a reindex, measured at ~151 s for a 392-note
 * vault. The notes themselves are never touched.
 */
enum class KeyProtection {
    /**
     * Keystore-wrapped, no user authentication. The index decrypts whenever the
     * app runs.
     *
     * Still meaningfully protected: the passphrase is sealed by a
     * hardware-backed key that cannot be exported, so reading the index off a
     * stolen device means defeating the Keystore rather than copying a file.
     * What it does not stop is anything running as this app on an unlocked
     * device.
     */
    OFF,

    /**
     * The key requires a recent device unlock, with a validity window.
     *
     * No prompt during normal use — unlocking the phone already satisfies it.
     * An attacker with the extracted database and a locked device gets nothing.
     *
     * The cost is background freshness: a periodic reindex that fires after the
     * phone has sat locked past the window cannot decrypt, and has to wait for
     * the next unlock.
     */
    DEVICE_UNLOCK,

    /**
     * The key requires authentication for each use, prompted on open.
     *
     * Strongest of the three, and the only one that stops someone holding your
     * unlocked phone. It also disables periodic reindexing outright: a
     * background pass has nobody to prompt, and letting it fail silently is the
     * failure mode the re-assert-on-start fix already exists to prevent.
     */
    EVERY_TIME;

    /** Whether unattended background work can reach the index under this level. */
    val allowsBackgroundIndexing: Boolean
        get() = this != EVERY_TIME
}
