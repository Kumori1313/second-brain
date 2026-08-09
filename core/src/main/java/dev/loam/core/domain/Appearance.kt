package dev.loam.core.domain

/**
 * What the app looks like. A preference, unlike [Tuning], which is a set of
 * measured constants — nothing here was fitted to anything.
 */
data class Appearance(
    val mode: ThemeMode = ThemeMode.SYSTEM,
    /**
     * Material You, where the platform has it.
     *
     * Default on because a phone that offers wallpaper colours generally looks
     * wrong without them. Despite the branding this is not a Google
     * dependency: [dynamic colour][ThemeMode] reads `android.R.color.system_*`
     * from the platform, so it needs no Play Services and nothing that
     * principle #3 excludes. It does need API 31, and `minSdk` is 26, so a
     * static fallback is required rather than optional.
     */
    val dynamicColor: Boolean = true,
)

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK;

    /**
     * The one decision the whole theme turns on, kept as a pure function so it
     * can be tested without a device, a window or a configuration.
     *
     * @param systemDark whether the OS is currently in dark mode. Consulted
     *   only by [SYSTEM]; the explicit modes exist precisely to ignore it.
     */
    fun isDark(systemDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemDark
        LIGHT -> false
        DARK -> true
    }

    /** For the settings chips. */
    val label: String
        get() = when (this) {
            SYSTEM -> "System"
            LIGHT -> "Light"
            DARK -> "Dark"
        }
}
