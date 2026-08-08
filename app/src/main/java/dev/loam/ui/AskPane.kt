package dev.loam.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.loam.core.domain.AskQuestion

/**
 * Retrieve-then-generate, on screen.
 *
 * The ordering here is the feature, not the layout. Sources arrive the moment
 * retrieval finishes and roughly ten seconds before the first token, so they
 * are rendered immediately and stay put — the wait becomes something to read,
 * and the citations are visibly fixed before the model says anything.
 */
@Composable
fun AskPane(
    state: SearchViewModel.UiState,
    onQuestionChange: (String) -> Unit,
    onAsk: () -> Unit,
    onCancel: () -> Unit,
    onNewConversation: () -> Unit,
    onPickModel: () -> Unit,
    onOpenSource: (AskQuestion.Source) -> Unit,
) {
    val ask = state.ask

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = ask.question,
            onValueChange = onQuestionChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            label = { Text("Ask your notes") },
            placeholder = { Text("what did I decide about…") },
            // Single line so the IME's action key submits. Left multiline,
            // Enter inserts a newline and ImeAction.Send never fires, which
            // makes the on-screen keyboard's send button do nothing.
            singleLine = true,
            // Not debounced like search: a question costs ~10 s of prompt
            // processing, so it is submitted deliberately.
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onAsk() }),
            trailingIcon = {
                if (ask.asking) {
                    TextButton(onClick = onCancel) { Text("Stop") }
                } else {
                    TextButton(onClick = onAsk, enabled = ask.question.isNotBlank()) {
                        Text("Ask")
                    }
                }
            },
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                ModelStatusBar(state = state, onPickModel = onPickModel)
            }
            if (ask.turns.isNotEmpty()) {
                TextButton(onClick = onNewConversation) { Text("New") }
            }
        }
        HorizontalDivider()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 12.dp),
        ) {
            when (ask.outcome) {
                SearchViewModel.AskState.Outcome.NoGoodMatches -> Notice(
                    title = "No good matches",
                    // The model is deliberately not consulted here. There is
                    // nothing to ground an answer in, and answering unaided is
                    // how a notes tool starts inventing notes.
                    body = "Nothing in your notes was close enough to answer from, " +
                        "so no answer was generated.",
                )

                SearchViewModel.AskState.Outcome.NoModel -> Notice(
                    title = "No answer model",
                    body = "Choose a GGUF model file to enable answers. " +
                        "Search works without one.",
                    action = "Choose model" to onPickModel,
                )

                is SearchViewModel.AskState.Outcome.Failed -> Notice(
                    title = "Could not answer",
                    body = ask.outcome.message,
                    isError = true,
                )

                null -> Unit
            }

            // In flight first, then completed exchanges newest-first.
            //
            // Oldest-first is the chat convention, but chat puts the composer
            // at the bottom. Here it is at the top, and each exchange carries
            // six source cards — so oldest-first buried a new answer under a
            // screen and a half of previous evidence. It looked like the
            // question had been ignored.
            if (ask.sources.isNotEmpty()) {
                if (ask.question.isNotBlank()) {
                    Text(
                        text = ask.question,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                }
                AnswerBlock(ask)
                Spacer(Modifier.height(16.dp))
                SourcesPanel(ask.sources, onOpenSource)
                if (ask.turns.isNotEmpty()) {
                    HorizontalDivider(Modifier.padding(vertical = 12.dp))
                }
            } else if (ask.asking) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Searching your notes…", style = MaterialTheme.typography.bodySmall)
                }
            }

            ask.turns.asReversed().forEachIndexed { index, turn ->
                Text(
                    text = turn.question,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(text = turn.answer, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                SourcesPanel(turn.sources, onOpenSource)
                if (index < ask.turns.lastIndex) {
                    HorizontalDivider(Modifier.padding(vertical = 12.dp))
                }
            }
        }
    }
}

@Composable
private fun AnswerBlock(ask: SearchViewModel.AskState) {
    if (ask.answer.isEmpty() && ask.asking) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    "Reading ${ask.sources.size} " +
                        if (ask.sources.size == 1) "note…" else "notes…",
                    style = MaterialTheme.typography.bodySmall,
                )
                // Setting the expectation is worth a line of UI: the first
                // token takes ~10 s on a Pixel 8a, and silence for that long
                // reads as a hang.
                Text(
                    "The first words take a few seconds.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    if (ask.answer.isNotEmpty()) {
        Text(text = ask.answer, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * Always expanded, never behind a disclosure.
 *
 * Core principle #4: every answer shows which notes it came from. Hiding that
 * behind a tap would make the auditability opt-in, and the whole argument for
 * trusting a 1.5B model's summary is that its evidence is right there.
 */
@Composable
private fun SourcesPanel(
    sources: List<AskQuestion.Source>,
    onOpen: (AskQuestion.Source) -> Unit,
) {
    Text(
        text = if (sources.size == 1) "1 source" else "${sources.size} sources",
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(8.dp))
    sources.forEach { source ->
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
                .clickable { onOpen(source) },
            shape = RoundedCornerShape(12.dp),
        ) {
            Column(Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = source.displayName.removeSuffix(".md"),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "%.2f".format(source.score),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                if (source.headingPath.isNotBlank()) {
                    Text(
                        text = source.headingPath,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = source.snippet,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    // Enough to recognise the chunk, not enough that six of
                    // them bury the answer. The whole note is a tap away.
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun Notice(
    title: String,
    body: String,
    isError: Boolean = false,
    action: Pair<String, () -> Unit>? = null,
) {
    Column(Modifier.padding(bottom = 16.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        Text(text = body, style = MaterialTheme.typography.bodySmall)
        if (action != null) {
            TextButton(onClick = action.second) { Text(action.first) }
        }
    }
}
