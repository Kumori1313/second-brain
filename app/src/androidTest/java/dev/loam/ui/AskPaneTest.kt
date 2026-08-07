package dev.loam.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.loam.core.domain.AskQuestion
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Drives the Ask pane through every state it can reach.
 *
 * Worth testing at this level rather than through the ViewModel: [AskPane] is a
 * pure function of [SearchViewModel.UiState], so the branching lives here and
 * can be exercised with fabricated state — no engine, no database, no model
 * file. The three Phase 2 bugs all lived in the gap between tested components
 * and the assembled screen, and this closes the part of that gap which does not
 * need a gigabyte of weights to reach.
 */
class AskPaneTest {

    @get:Rule
    val compose = createComposeRule()

    private fun source(
        id: Long,
        name: String = "LUKS Setup",
        heading: String = "8. Encryption",
        score: Float = 0.64f,
        snippet: String = "cryptsetup luksFormat /dev/root_partition",
    ) = AskQuestion.Source(
        chunkId = id,
        score = score,
        displayName = "$name.md",
        relativePath = "linux/$name.md",
        headingPath = heading,
        snippet = snippet,
        uri = "content://vault/$id",
    )

    private fun show(
        ask: SearchViewModel.AskState,
        model: SearchViewModel.ModelState = SearchViewModel.ModelState.Ready,
        modelName: String? = "qwen2.5-1.5b-instruct-q4_0.gguf",
        onAsk: () -> Unit = {},
        onCancel: () -> Unit = {},
        onPickModel: () -> Unit = {},
        onOpenSource: (AskQuestion.Source) -> Unit = {},
    ) {
        compose.setContent {
            AskPane(
                state = SearchViewModel.UiState(
                    hasVault = true,
                    modelName = modelName,
                    model = model,
                    ask = ask,
                ),
                onQuestionChange = {},
                onAsk = onAsk,
                onCancel = onCancel,
                onPickModel = onPickModel,
                onOpenSource = onOpenSource,
            )
        }
    }

    @Test
    fun sourcesAreVisibleWhileTheAnswerIsStillGenerating() {
        show(
            SearchViewModel.AskState(
                question = "which cipher?",
                asking = true,
                answer = "",
                sources = listOf(source(1, name = "LUKS Setup"), source(2, name = "Disk")),
            )
        )

        // The whole point of emitting sources first: ten seconds of waiting is
        // filled with the evidence, and the citations are visibly fixed before
        // the model says anything.
        compose.onNodeWithText("LUKS Setup").assertIsDisplayed()
        compose.onNodeWithText("Reading 2 notes…").assertIsDisplayed()
        compose.onNodeWithText("2 sources").assertIsDisplayed()
    }

    @Test
    fun singularWordingForOneSource() {
        show(SearchViewModel.AskState(asking = true, sources = listOf(source(1))))

        compose.onNodeWithText("Reading 1 note…").assertIsDisplayed()
        compose.onNodeWithText("1 source").assertIsDisplayed()
    }

    @Test
    fun answerTextReplacesTheWaitingIndicator() {
        show(
            SearchViewModel.AskState(
                asking = false,
                answer = "It uses aes-xts-plain64 and argon2id.",
                sources = listOf(source(1)),
            )
        )

        compose.onNodeWithText("It uses aes-xts-plain64 and argon2id.").assertIsDisplayed()
        compose.onNodeWithText("1 source").assertIsDisplayed()
    }

    @Test
    fun sourcesRemainVisibleAlongsideTheFinishedAnswer() {
        show(
            SearchViewModel.AskState(
                answer = "Answer text.",
                sources = listOf(source(1, name = "Disk Notes")),
            )
        )

        // Core principle #4: the evidence stays on screen with the answer, not
        // behind a disclosure the user has to know to open.
        compose.onNodeWithText("Answer text.").assertIsDisplayed()
        compose.onNodeWithText("Disk Notes").assertIsDisplayed()
        compose.onNodeWithText("8. Encryption").assertIsDisplayed()
        compose.onNodeWithText("0.64").assertIsDisplayed()
    }

    @Test
    fun noGoodMatchesSaysSoAndShowsNoSources() {
        show(SearchViewModel.AskState(outcome = SearchViewModel.AskState.Outcome.NoGoodMatches))

        compose.onNodeWithText("No good matches").assertIsDisplayed()
        // No sources panel at all: the model was never invoked, so there is
        // nothing it could have been grounded in.
        compose.onNodeWithText("1 source").assertDoesNotExist()
        compose.onNodeWithText("LUKS Setup").assertDoesNotExist()
    }

    @Test
    fun missingModelIsOfferedAsSetupNotFailure() {
        var picked = 0
        show(
            ask = SearchViewModel.AskState(outcome = SearchViewModel.AskState.Outcome.NoModel),
            model = SearchViewModel.ModelState.None,
            modelName = null,
            onPickModel = { picked++ },
        )

        compose.onNodeWithText("No answer model").assertIsDisplayed()
        // Two affordances lead to the picker: the notice, which is in the
        // user's eyeline after asking, and the status row, which is where they
        // will look next time. Asserting the count rather than existence keeps
        // the duplication deliberate — if it becomes one or three, this fails.
        compose.onAllNodesWithText("Choose model").assertCountEquals(2)
        compose.onAllNodesWithText("Choose model").onFirst().performClick()
        assertEquals(1, picked)
    }

    @Test
    fun aBrokenModelReportsTheFailureRatherThanLookingUnconfigured() {
        show(
            ask = SearchViewModel.AskState(
                outcome = SearchViewModel.AskState.Outcome.Failed("llama.cpp could not load"),
            ),
            model = SearchViewModel.ModelState.Failed("llama.cpp could not load"),
        )

        // Conflating this with NoModel would send someone holding a corrupt
        // GGUF back to the picker forever.
        // Distinct titles, one shared detail line. Both are wanted: the notice
        // answers "why did my question fail", the status row answers "what is
        // wrong with my setup".
        compose.onNodeWithText("Could not answer").assertIsDisplayed()
        compose.onNodeWithText("Model failed to load").assertIsDisplayed()
        compose.onAllNodesWithText("llama.cpp could not load").assertCountEquals(2)
    }

    @Test
    fun askIsDisabledUntilThereIsAQuestion() {
        show(SearchViewModel.AskState(question = ""))

        compose.onNodeWithText("Ask").assertIsNotEnabled()
    }

    @Test
    fun askIsEnabledAndFiresOnceThereIsAQuestion() {
        var asked = 0
        show(SearchViewModel.AskState(question = "which cipher?"), onAsk = { asked++ })

        compose.onNodeWithText("Ask").assertIsEnabled().performClick()

        assertEquals(1, asked)
    }

    @Test
    fun askBecomesStopWhileGenerating() {
        var cancelled = 0
        show(SearchViewModel.AskState(question = "q", asking = true), onCancel = { cancelled++ })

        compose.onNodeWithText("Stop").assertIsDisplayed().performClick()

        assertEquals(1, cancelled)
    }

    @Test
    fun tappingASourceOpensThatNote() {
        var opened: AskQuestion.Source? = null
        show(
            ask = SearchViewModel.AskState(
                answer = "Answer.",
                sources = listOf(source(1, name = "First"), source(2, name = "Second")),
            ),
            onOpenSource = { opened = it },
        )

        compose.onNodeWithText("Second").performClick()

        assertEquals(2L, opened?.chunkId)
    }

    @Test
    fun theModelNameIsShownSoTheAnswerCanBeAttributed() {
        show(SearchViewModel.AskState())

        compose.onNodeWithText("qwen2.5-1.5b-instruct-q4_0.gguf").assertIsDisplayed()
    }
}
