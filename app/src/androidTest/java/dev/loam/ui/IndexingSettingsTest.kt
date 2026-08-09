package dev.loam.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.semantics.SemanticsActions
import dev.loam.core.domain.Appearance
import dev.loam.core.domain.IndexingRules
import dev.loam.core.domain.ThemeMode
import dev.loam.core.domain.Tuning
import dev.loam.core.store.KeyProtection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/**
 * The staging behaviour of the two settings that cost a rebuild.
 *
 * The value here is not that the controls render — it is that nothing reaches
 * the ViewModel until Apply. Live-applying either one would start a full pass
 * over the vault per keystroke and per slider tick, which is unusable rather
 * than merely wasteful.
 */
class IndexingSettingsTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(
        rules: IndexingRules = IndexingRules(),
        indexing: Boolean = false,
        noteCount: Int = 392,
        onRulesChange: (IndexingRules) -> Unit = {},
        appearance: Appearance = Appearance(),
        onAppearanceChange: (Appearance) -> Unit = {},
    ) {
        compose.setContent {
            SettingsPane(
                state = SearchViewModel.UiState(
                    hasVault = true,
                    noteCount = noteCount,
                    indexing = indexing,
                    rules = rules,
                ),
                tuning = Tuning(),
                onTuningChange = {},
                onResetTuning = {},
                rules = rules,
                onRulesChange = onRulesChange,
                appearance = appearance,
                onAppearanceChange = onAppearanceChange,
                onPickVault = {},
                onPickModel = {},
                onReindex = {},
                keyProtection = KeyProtection.OFF,
                onKeyProtectionChange = {},
            )
        }
    }

    private fun setChunkSize(tokens: Float) {
        compose.onNodeWithTag(CHUNK_SIZE_SLIDER)
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(tokens) }
    }

    @Test
    fun pickingAThemeAppliesImmediately() {
        var applied: Appearance? = null
        show(onAppearanceChange = { applied = it })

        compose.onNodeWithText("Dark").performScrollTo().performClick()

        // Unlike the indexing settings below it, a theme costs nothing to
        // apply, so staging it behind a button would only be in the way.
        assertEquals(ThemeMode.DARK, applied?.mode)
    }

    @Test
    fun theThemeInEffectIsTheSelectedChip() {
        show(appearance = Appearance(mode = ThemeMode.LIGHT))

        // "Light" appears twice — as the current value and as a chip — so
        // matching on text alone would pass while the chips showed the wrong
        // one selected. Selection is the thing that has to be right.
        compose.onNode(hasText("Light") and isSelectable()).performScrollTo().assertIsSelected()
        compose.onNode(hasText("Dark") and isSelectable()).assertIsNotSelected()
    }

    @Test
    fun applyIsInertUntilSomethingChanges() {
        show()

        compose.onNodeWithText("Apply and reindex").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun typingAPatternStagesItWithoutApplyingIt() {
        var applied: IndexingRules? = null
        show(onRulesChange = { applied = it })

        compose.onNode(hasSetTextAction()).performScrollTo().performTextInput("templates/")

        // The whole point: a full reindex per keystroke would make the box
        // impossible to use.
        assertNull(applied)
        compose.onNodeWithText("Apply and reindex").performScrollTo().assertIsEnabled()
    }

    @Test
    fun applyHandsOverWhatWasStaged() {
        var applied: IndexingRules? = null
        show(onRulesChange = { applied = it })

        compose.onNode(hasSetTextAction()).performScrollTo().performTextInput("templates/")
        compose.onNodeWithText("Apply and reindex").performScrollTo().performClick()

        assertEquals("templates/", applied?.excludePatterns)
    }

    @Test
    fun anExcludeChangeDoesNotThreatenARebuild() {
        show()

        compose.onNode(hasSetTextAction()).performScrollTo().performTextInput("templates/")

        // Excludes are cheap — the walk stops finding those notes and the stale
        // sweep deletes them. Saying "minutes" here would be a lie that puts
        // people off using the setting.
        compose.onNodeWithText("Next pass drops what no longer matches.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Re-embeds all", substring = true).assertDoesNotExist()
    }

    @Test
    fun movingTheChunkSliderWarnsThatEverythingIsReEmbedded() {
        var applied: IndexingRules? = null
        show(rules = IndexingRules(chunkTokens = 240), onRulesChange = { applied = it })

        setChunkSize(160f)

        // The expensive half. Saying only "reindex" here would understate a
        // 150-second wipe-and-re-embed as though it were the incremental pass.
        compose.onNodeWithText("Re-embeds all 392 notes", substring = true)
            .performScrollTo()
            .assertIsDisplayed()

        compose.onNodeWithText("Apply and reindex").performScrollTo().performClick()
        assertEquals(160, applied?.chunkTokens)
    }

    @Test
    fun applyIsBlockedWhileAPassIsAlreadyRunning() {
        show(indexing = true)

        compose.onNode(hasSetTextAction()).performScrollTo().performTextInput("templates/")

        // Enqueuing a second pass mid-index is a bug this project already paid
        // for; here it would also mean applying rules the running pass predates.
        compose.onNodeWithText("Apply and reindex").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun theCurrentPatternsAreShownRatherThanAnEmptyBox() {
        show(rules = IndexingRules(excludePatterns = "templates/\njournal/"))

        compose.onNodeWithText("templates/\njournal/").performScrollTo().assertIsDisplayed()
        // Already applied, so there is nothing to apply.
        compose.onNodeWithText("Apply and reindex").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun theChunkSizeInEffectIsShown() {
        show(rules = IndexingRules(chunkTokens = 200))

        compose.onNodeWithText("200 tokens").performScrollTo().assertIsDisplayed()
    }
}
