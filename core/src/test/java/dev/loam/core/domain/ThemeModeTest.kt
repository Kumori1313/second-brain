package dev.loam.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one decision the whole theme turns on.
 *
 * Trivial enough to look untestable, which is exactly why it is a pure
 * function: everything downstream — schemes, status bar icons, the window
 * background painted before Compose runs — has to agree on this answer, and
 * three places computing it independently is how they stop agreeing.
 */
class ThemeModeTest {

    @Test
    fun systemFollowsTheSystem() {
        assertTrue(ThemeMode.SYSTEM.isDark(systemDark = true))
        assertFalse(ThemeMode.SYSTEM.isDark(systemDark = false))
    }

    @Test
    fun anExplicitChoiceIgnoresTheSystem() {
        // The entire point of offering the override. A "Light" that goes dark
        // at sunset because the OS did is not a setting.
        assertFalse(ThemeMode.LIGHT.isDark(systemDark = true))
        assertTrue(ThemeMode.DARK.isDark(systemDark = false))
    }

    @Test
    fun theDefaultIsToFollowTheSystem() {
        assertEquals(ThemeMode.SYSTEM, Appearance().mode)
    }

    @Test
    fun dynamicColourIsOnByDefault() {
        // Guarded at API 31 by the caller; the preference itself is unguarded
        // so the choice survives moving to a device that has it.
        assertTrue(Appearance().dynamicColor)
    }

    @Test
    fun everyModeHasALabel() {
        assertEquals(listOf("System", "Light", "Dark"), ThemeMode.entries.map { it.label })
    }
}
