package dev.loam.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.loam.core.domain.SearchNotes

@Composable
fun LoamScreen(viewModel: SearchViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val pickVault = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? -> uri?.let(viewModel::onVaultPicked) }

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

            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                label = { Text("Search your notes") },
                placeholder = { Text("did I ever write about…") },
                singleLine = true,
            )

            IndexStatusBar(
                state = state,
                onReindex = viewModel::reindex,
                onChangeVault = { pickVault.launch(null) },
            )

            HorizontalDivider()

            when {
                state.searching && state.results.isEmpty() -> Centered {
                    CircularProgressIndicator()
                }

                state.searched && state.results.isEmpty() -> Centered {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("No good matches", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            // Saying so is a stated requirement: a weak match
                            // presented confidently is worse than none.
                            "Nothing in the index was close enough to be worth showing.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
                ) {
                    items(state.results, key = { it.chunkId }) { result ->
                        ResultCard(result) { openInExternalApp(context, result) }
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
    onChangeVault: () -> Unit,
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
                TextButton(onClick = onReindex) { Text("Reindex") }
                TextButton(onClick = onChangeVault) { Text("Change") }
            }
        }
        if (state.indexing) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun ResultCard(result: SearchNotes.Result, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
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
private fun Centered(content: @Composable () -> Unit) {
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
 * Opens the real `.md` file in whatever app the user already uses.
 *
 * Loam is explicitly not a note editor — it points at the user's own files and
 * hands off. Read-only grant, since it has no business writing to the vault.
 */
private fun openInExternalApp(context: android.content.Context, result: SearchNotes.Result) {
    // text/markdown first so a real markdown app wins the chooser, falling back
    // to text/plain because plenty of devices register the latter only.
    //
    // A plain ACTION_VIEW rather than Intent.createChooser: the chooser appears
    // once and the user can then set a default, whereas createChooser would ask
    // again on every single result they open.
    //
    // Read-only grant. Loam is not a note editor and has no business writing to
    // the vault, so the editor that opens gets exactly what it needs to display
    // the file and nothing more.
    val opened = listOf("text/markdown", "text/plain").any { mime ->
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(Uri.parse(result.uri), mime)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            )
        }.isSuccess
    }
    if (!opened) {
        // Previously this failed silently: on a device with no text viewer,
        // tapping a result did nothing at all and looked like a broken app
        // rather than a missing one.
        Toast.makeText(
            context,
            "No app installed that can open ${result.displayName}",
            Toast.LENGTH_LONG,
        ).show()
    }
}
