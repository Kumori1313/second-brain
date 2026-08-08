package dev.loam.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import dev.loam.core.domain.SearchNotes
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Drives the Search pane through every state it can reach.
 *
 * Same level as [AskPaneTest], and for the same reason: [SearchPane] is a pure
 * function of [SearchViewModel.UiState], so the branching can be exercised with
 * fabricated state and no database, embedder or index.
 *
 * It was inline in `LoamScreen` until these tests were written. Extracting it
 * was the change that made it testable — the branches were always there, just
 * tangled with a ViewModel and a Context.
 */
class SearchPaneTest {

    @get:Rule
    val compose = createComposeRule()

    private fun result(
        id: Long,
        name: String = "LUKS Setup",
        heading: String = "8. Encryption",
        score: Float = 0.66f,
        snippet: String = "cryptsetup luksFormat /dev/root_partition",
    ) = SearchNotes.Result(
        chunkId = id,
        score = score,
        displayName = "$name.md",
        relativePath = "linux/$name.md",
        headingPath = heading,
        snippet = snippet,
        text = snippet,
        uri = "content://vault/$id",
    )

    private fun show(
        state: SearchViewModel.UiState,
        onQueryChange: (String) -> Unit = {},
        onReindex: () -> Unit = {},
        onOpenResult: (SearchNotes.Result) -> Unit = {},
        onChooseOpener: () -> Unit = {},
    ) {
        compose.setContent {
            SearchPane(
                state = state,
                onQueryChange = onQueryChange,
                onReindex = onReindex,
                onOpenResult = onOpenResult,
                onChooseOpener = onChooseOpener,
            )
        }
    }

    @Test
    fun showsIndexedCountsWhenIdle() {
        show(SearchViewModel.UiState(hasVault = true, noteCount = 392, chunkCount = 5297))

        compose.onNodeWithText("392 notes · 5297 chunks indexed").assertIsDisplayed()
    }

    @Test
    fun resultsShowTheirSourceHeadingAndScore() {
        show(
            SearchViewModel.UiState(
                hasVault = true,
                query = "luks",
                searched = true,
                results = listOf(result(1)),
            )
        )

        // Core principle #4 on the search side: a result is checkable, so it
        // carries where it came from and how strong the match was.
        compose.onNodeWithText("LUKS Setup").assertIsDisplayed()
        compose.onNodeWithText("8. Encryption").assertIsDisplayed()
        compose.onNodeWithText("linux/LUKS Setup.md").assertIsDisplayed()
        compose.onNodeWithText("0.66").assertIsDisplayed()
    }

    @Test
    fun nothingAboveTheFloorSaysSoRatherThanShowingNoise() {
        show(
            SearchViewModel.UiState(
                hasVault = true,
                query = "banana bread",
                searched = true,
                results = emptyList(),
            )
        )

        // The stated requirement: a weak match presented confidently is worse
        // than admitting there was none.
        compose.onNodeWithText("No good matches").assertIsDisplayed()
        compose.onNodeWithText(
            "Nothing in the index was close enough to be worth showing."
        ).assertIsDisplayed()
    }

    @Test
    fun anEmptyResultListBeforeSearchingIsNotAFailure() {
        show(SearchViewModel.UiState(hasVault = true, searched = false, results = emptyList()))

        // "Haven't searched yet" and "nothing matched" look identical in the
        // data and must not look identical on screen.
        compose.onNodeWithText("No good matches").assertDoesNotExist()
    }

    @Test
    fun tappingAResultOpensThatNote() {
        var opened: SearchNotes.Result? = null
        show(
            state = SearchViewModel.UiState(
                hasVault = true,
                searched = true,
                results = listOf(result(1, name = "First"), result(2, name = "Second")),
            ),
            onOpenResult = { opened = it },
        )

        compose.onNodeWithText("Second").performClick()

        assertEquals(2L, opened?.chunkId)
    }

    @Test
    fun typingReachesTheViewModel() {
        var typed: String? = null
        show(
            state = SearchViewModel.UiState(hasVault = true),
            onQueryChange = { typed = it },
        )

        // Matched by the set-text action rather than by placeholder or label:
        // both of those are sibling nodes, not part of the editable field's
        // semantics, so neither can receive input. There is one field here.
        compose.onNode(hasSetTextAction()).performTextInput("luks")

        assertEquals("luks", typed)
    }

    @Test
    fun reindexIsOfferedWhenIdleAndFires() {
        var reindexed = 0
        show(
            state = SearchViewModel.UiState(hasVault = true, indexing = false),
            onReindex = { reindexed++ },
        )

        compose.onNodeWithText("Reindex").assertIsDisplayed().performClick()

        assertEquals(1, reindexed)
    }

    @Test
    fun reindexIsHiddenWhileIndexing() {
        show(
            SearchViewModel.UiState(
                hasVault = true,
                indexing = true,
                indexStatus = "Embedding 25/392 notes (403 chunks)",
            )
        )

        // Offering a second pass mid-index invites two indexers at once, which
        // is a bug this project already paid for.
        compose.onNodeWithText("Reindex").assertDoesNotExist()
        compose.onNodeWithText("Embedding 25/392 notes (403 chunks)").assertIsDisplayed()
    }

    @Test
    fun anIndexErrorReplacesTheCountsRatherThanHidingBehindThem() {
        show(
            SearchViewModel.UiState(
                hasVault = true,
                noteCount = 392,
                chunkCount = 5297,
                error = "Vault access was revoked",
            )
        )

        compose.onNodeWithText("Vault access was revoked").assertIsDisplayed()
        compose.onNodeWithText("392 notes · 5297 chunks indexed").assertDoesNotExist()
    }

    @Test
    fun everyResultIsIndividuallyAddressable() {
        show(
            SearchViewModel.UiState(
                hasVault = true,
                searched = true,
                results = (1L..3L).map { result(it, name = "Note $it") },
            )
        )

        compose.onAllNodesWithText("8. Encryption").assertCountEquals(3)
        compose.onNodeWithText("Note 1").assertIsDisplayed()
        compose.onNodeWithText("Note 3").assertIsDisplayed()
    }
}
