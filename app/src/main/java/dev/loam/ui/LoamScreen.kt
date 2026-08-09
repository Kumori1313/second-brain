package dev.loam.ui

import android.content.ComponentName
import android.net.Uri
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.loam.core.domain.SearchNotes
import dev.loam.work.IndexRunLog

@Composable
fun LoamScreen(viewModel: SearchViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val pickVault = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? -> uri?.let(viewModel::onVaultPicked) }

    val pickModel = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> uri?.let(viewModel::onModelPicked) }

    val opener = remember { NoteOpener(context) }
    var showOpenWith by remember { mutableStateOf(false) }
    var tab by remember { mutableIntStateOf(TAB_SEARCH) }

    // A share means "search this", so it has to win over whatever tab was left
    // open. Keyed on the token rather than the query: sharing the same text
    // twice is still a request to look at the results.
    LaunchedEffect(state.shareToken) {
        if (state.shareToken > 0) tab = TAB_SEARCH
    }

    // Same for the widget: it asks for the search field specifically, so
    // landing on Ask or Settings would make it a slower app icon.
    LaunchedEffect(state.focusToken) {
        if (state.focusToken > 0) tab = TAB_SEARCH
    }

    if (showOpenWith) {
        OpenWithDialog(
            handlers = remember { opener.handlers() },
            selected = opener.preferred,
            onSelect = { component ->
                opener.preferred = component
                showOpenWith = false
            },
            onDismiss = { showOpenWith = false },
        )
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            if (!state.hasVault) {
                EmptyVault(onPick = { pickVault.launch(null) })
                return@Column
            }

            // "*/*" because GGUF has no registered MIME type — filtering on one
            // would hide the file the user came to pick.
            val onPickModel = { pickModel.launch(arrayOf("*/*")) }

            TabRow(selectedTabIndex = tab) {
                Tab(
                    selected = tab == TAB_SEARCH,
                    onClick = { tab = TAB_SEARCH },
                    text = { Text("Search") },
                )
                Tab(
                    selected = tab == TAB_ASK,
                    onClick = { tab = TAB_ASK },
                    text = { Text("Ask") },
                )
                Tab(
                    selected = tab == TAB_SETTINGS,
                    onClick = { tab = TAB_SETTINGS },
                    text = { Text("Settings") },
                )
            }

            if (tab == TAB_SETTINGS) {
                SettingsPane(
                    state = state,
                    tuning = state.tuning,
                    onTuningChange = viewModel::onTuningChange,
                    onResetTuning = viewModel::onResetTuning,
                    rules = state.rules,
                    onRulesChange = viewModel::onIndexingRulesChange,
                    appearance = state.appearance,
                    onAppearanceChange = viewModel::onAppearanceChange,
                    onPickVault = { pickVault.launch(null) },
                    onPickModel = onPickModel,
                    onReindex = viewModel::reindex,
                    keyProtection = state.keyProtection,
                    onKeyProtectionChange = { level ->
                        val activity = context as? androidx.fragment.app.FragmentActivity
                        if (activity == null) {
                            viewModel.onProtectionChanged(
                                IllegalStateException("No activity to authenticate with")
                            )
                        } else {
                            changeIndexProtection(activity, level) { failure ->
                                viewModel.onProtectionChanged(failure)
                            }
                        }
                    },
                )
                return@Column
            }

            if (tab == TAB_ASK) {
                // Re-runs on every resume, not just on tab change: the model is
                // released whenever the app is backgrounded, so coming back to
                // a visible Ask tab has to reopen it.
                LifecycleResumeEffect(Unit) {
                    viewModel.onAskOpened()
                    onPauseOrDispose { }
                }
                AskPane(
                    state = state,
                    onQuestionChange = viewModel::onQuestionChange,
                    onAsk = viewModel::ask,
                    onCancel = viewModel::cancelAsk,
                    onNewConversation = viewModel::onNewConversation,
                    onPickModel = onPickModel,
                    onOpenSource = { source -> openSource(context, opener, source) },
                )
                return@Column
            }

            SearchPane(
                state = state,
                onQueryChange = viewModel::onQueryChange,
                onReindex = viewModel::reindex,
                onOpenResult = { result -> openNote(context, opener, result) },
                onChooseOpener = { showOpenWith = true },
                onShowWeakMatches = viewModel::showWeakMatches,
            )
        }
    }
}

/**
 * Extracted from [LoamScreen] so it is a pure function of
 * [SearchViewModel.UiState], the same shape as [AskPane].
 *
 * That is what makes it testable: every branch here — searching, nothing
 * matched, results, indexing, an index error — can be driven with fabricated
 * state, no database or embedder required.
 */
@Composable
fun SearchPane(
    state: SearchViewModel.UiState,
    onQueryChange: (String) -> Unit,
    onReindex: () -> Unit,
    onOpenResult: (SearchNotes.Result) -> Unit,
    onChooseOpener: () -> Unit,
    onShowWeakMatches: () -> Unit = {},
) {
    // imePadding because the activity is edge-to-edge, so `adjustResize` never
    // shrinks the window and Compose has to apply the inset itself. Without it
    // the empty state — which is centred in the full height — sits under the
    // keyboard, and "Show weak matches" is off-screen in exactly the state it
    // exists for: the moment after typing a query that found nothing.
    Column(
        Modifier
            .fillMaxSize()
            .imePadding()
    ) {
        val focus = remember { FocusRequester() }
        val keyboard = LocalSoftwareKeyboardController.current
        LaunchedEffect(state.focusToken) {
            if (state.focusToken == 0) return@LaunchedEffect
            // Focus alone is not enough on every device — requesting focus
            // programmatically does not reliably raise the IME the way a tap
            // does, and a widget that opens a field you still have to tap is
            // the shortcut not working.
            focus.requestFocus()
            keyboard?.show()
        }

        OutlinedTextField(
            value = state.query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .focusRequester(focus),
            label = { Text("Search your notes") },
            placeholder = { Text("did I ever write about…") },
            singleLine = true,
        )

        IndexStatusBar(state = state, onReindex = onReindex)

        HorizontalDivider()

        when {
            state.searching && state.results.isEmpty() -> Centered {
                CircularProgressIndicator()
            }

            state.searched && state.results.isEmpty() -> Centered {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (state.weakMatches) "Nothing at all" else "No good matches",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        // Saying so is a stated requirement: a weak match
                        // presented confidently is worse than none.
                        "Nothing in the index was close enough to be worth showing.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    // The floor has to sit where the two score bands overlap, so
                    // some questions the vault does answer land under it —
                    // measured at 0.328 and 0.353 against a floor of 0.44.
                    // Offering the reach is honest; quietly widening the search
                    // would make "no good matches" mean nothing.
                    if (!state.weakMatches) {
                        TextButton(onClick = onShowWeakMatches) { Text("Show weak matches") }
                    }
                }
            }

            else -> Column(Modifier.fillMaxSize()) {
                if (state.weakMatches) {
                    // Labelled, always. What clears the floor is only worth
                    // anything if these are visibly not that.
                    Text(
                        "Weak matches — below your relevance floor",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
                ) {
                    items(state.results, key = { it.chunkId }) { result ->
                        ResultCard(
                            result = result,
                            onClick = { onOpenResult(result) },
                            onLongClick = onChooseOpener,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyVault(onPick: () -> Unit) {
    Centered {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Loam", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "Semantic search over Markdown notes you already own.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Notes are read in place and never copied or uploaded. " +
                    "The app holds no network permission at all.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = onPick) { Text("Choose vault folder") }
        }
    }
}

@Composable
private fun IndexStatusBar(
    state: SearchViewModel.UiState,
    onReindex: () -> Unit,
) {
    Column(Modifier.padding(vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = state.error
                    ?: state.indexStatus
                    ?: "${state.noteCount} notes · ${state.chunkCount} chunks indexed",
                style = MaterialTheme.typography.bodySmall,
                color = if (state.error != null) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.weight(1f),
            )
            if (!state.indexing) {
                // "Change vault" moved to Settings; Reindex stays because it
                // is the one action tied to what is on screen.
                TextButton(onClick = onReindex) { Text("Reindex") }
            }
        }
        if (state.indexing) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        // A second line rather than more text in the first: the counts say what
        // is indexed, this says when that was last true. Suppressed while a pass
        // is running, where it is only a stale version of the line above it.
        val lastRun = state.lastRun
        if (lastRun != null && !state.indexing && state.error == null) {
            Text(
                text = summarize(lastRun),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The last pass, said as a dated fact.
 *
 * This is where a periodic run usually becomes visible: it runs every six
 * hours, so almost every one of them completes with the app closed and there is
 * no progress for anyone to watch. Phrasing it as *when* rather than as an
 * outcome is also what keeps a result from a previous launch honest.
 */
private fun summarize(run: IndexRunLog.Run): String {
    val now = System.currentTimeMillis()
    // Under a minute, getRelativeTimeSpanString says "0 minutes ago", which is
    // the one case where a truthful line reads like a bug — and it is the case
    // the user sees every time they tap Reindex.
    val ago = if (now - run.finishedAt < DateUtils.MINUTE_IN_MILLIS) {
        "just now"
    } else {
        DateUtils.getRelativeTimeSpanString(run.finishedAt, now, DateUtils.MINUTE_IN_MILLIS)
    }
    val what = if (run.notesIndexed == 0) {
        "no changes"
    } else {
        "${run.notesIndexed} notes, ${run.chunksEmbedded} chunks"
    }
    return if (run.periodic) "Background pass · $what · $ago" else "Reindexed · $what · $ago"
}

@OptIn(ExperimentalFoundationApi::class)
/**
 * The model's own row, rather than a third button beside Reindex and Change.
 *
 * Loading it is slow enough and fails in enough distinct ways that it needs
 * somewhere to say so — and squeezing a third action into that row was already
 * marginal at this width.
 */
@Composable
fun ModelStatusBar(
    state: SearchViewModel.UiState,
    onPickModel: () -> Unit,
) {
    val model = state.model
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = when (model) {
                    SearchViewModel.ModelState.None -> "No answer model — search only"
                    SearchViewModel.ModelState.Loading -> "Loading ${state.modelName ?: "model"}…"
                    SearchViewModel.ModelState.Ready -> state.modelName ?: "Model ready"
                    is SearchViewModel.ModelState.Failed -> "Model failed to load"
                },
                style = MaterialTheme.typography.bodySmall,
                color = when (model) {
                    is SearchViewModel.ModelState.Failed -> MaterialTheme.colorScheme.error
                    SearchViewModel.ModelState.Ready -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            if (model is SearchViewModel.ModelState.Failed) {
                Text(
                    text = model.message,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        if (model is SearchViewModel.ModelState.Loading) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
        }
        TextButton(onClick = onPickModel) {
            Text(if (state.modelName == null) "Choose model" else "Change")
        }
    }
}

@Composable
private fun ResultCard(
    result: SearchNotes.Result,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = result.displayName.removeSuffix(".md"),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                // Showing the score is part of being auditable rather than a
                // black box: the user can see how strong a match actually is.
                Text(
                    text = "%.2f".format(result.score),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
            if (result.headingPath.isNotBlank()) {
                Text(
                    text = result.headingPath,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                text = result.relativePath,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Text(text = result.snippet, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun Centered(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}

/**
 * Lets the user pin one app to open notes with, since the system's own default
 * does not hold — see [NoteOpener] for what was measured and why.
 */
@Composable
private fun OpenWithDialog(
    handlers: List<NoteOpener.Handler>,
    selected: ComponentName?,
    onSelect: (ComponentName?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Open notes with") },
        text = {
            Column {
                Text(
                    "Applies to every result. Android's own \"Always\" is " +
                        "ignored on some devices, so Loam remembers it here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                // Null first: whatever is pinned, un-pinning it must always be
                // reachable, including when the pinned app is missing from the
                // list because it was uninstalled.
                HandlerRow("Ask every time", null, selected == null) { onSelect(null) }
                handlers.forEach { handler ->
                    HandlerRow(handler.label, handler.detail, selected == handler.component) {
                        onSelect(handler.component)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun HandlerRow(
    label: String,
    detail: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (detail != null) {
                Text(
                    detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

fun openSource(
    context: android.content.Context,
    opener: NoteOpener,
    source: dev.loam.core.domain.AskQuestion.Source,
) = openUri(context, opener, source.uri, source.displayName)

fun openNote(
    context: android.content.Context,
    opener: NoteOpener,
    result: SearchNotes.Result,
) = openUri(context, opener, result.uri, result.displayName)

private fun openUri(
    context: android.content.Context,
    opener: NoteOpener,
    uri: String,
    displayName: String,
) {
    if (!opener.open(context, uri)) {
        // Previously this failed silently: on a device with no text viewer,
        // tapping a result did nothing at all and looked like a broken app
        // rather than a missing one.
        Toast.makeText(
            context,
            "No app installed that can open $displayName",
            Toast.LENGTH_LONG,
        ).show()
        return
    }
    // Long-press is the conventional place for per-item options but it is
    // invisible, and the picker is exactly the moment the user is wondering how
    // to stop seeing it. Said once, then never again.
    if (opener.consumeFirstOpenHint()) {
        Toast.makeText(
            context,
            "Tip: long-press a result to always open notes in one app",
            Toast.LENGTH_LONG,
        ).show()
    }
}

private const val TAB_SEARCH = 0
private const val TAB_ASK = 1
private const val TAB_SETTINGS = 2
