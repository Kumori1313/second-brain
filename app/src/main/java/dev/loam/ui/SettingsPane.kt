package dev.loam.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.loam.core.domain.Tuning
import kotlin.math.roundToInt

/**
 * The constants this project measured its way to, made adjustable.
 *
 * Each one shows what it costs, because these are not preferences — they are
 * tradeoffs with numbers attached, and the numbers came from this device. A
 * slider labelled only "chunks per answer" would be a worse setting than none.
 *
 * Only settings that take effect without reindexing appear here. Chunk size and
 * exclude patterns would invalidate the stored index, which makes them a
 * different kind of setting and one that needs a reindex flow first.
 */
@Composable
fun SettingsPane(
    state: SearchViewModel.UiState,
    tuning: Tuning,
    onTuningChange: (Tuning) -> Unit,
    onResetTuning: () -> Unit,
    onPickVault: () -> Unit,
    onPickModel: () -> Unit,
    onReindex: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 12.dp),
    ) {
        SectionTitle("Vault")
        SettingRow(
            label = state.vaultName ?: "No folder chosen",
            detail = "${state.noteCount} notes · ${state.chunkCount} chunks indexed",
            action = "Change",
            onAction = onPickVault,
        )
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onReindex, enabled = !state.indexing) {
                Text(if (state.indexing) "Indexing…" else "Reindex now")
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        SectionTitle("Answer model")
        SettingRow(
            label = state.modelName ?: "No model chosen",
            // modelName is checked first because ModelState.None means two
            // different things since the model became lazily loaded: no model
            // configured, and a configured model that is not resident right
            // now. Reading it alone reported "search works without one" while a
            // model was plainly named on the line above.
            detail = when {
                state.modelName == null -> "Search works without one"
                state.model is SearchViewModel.ModelState.Failed ->
                    (state.model as SearchViewModel.ModelState.Failed).message
                state.model == SearchViewModel.ModelState.Loading -> "Loading…"
                state.model == SearchViewModel.ModelState.Ready -> "Loaded"
                else -> "Loads when you open Ask, and is released in the background"
            },
            action = if (state.modelName == null) "Choose" else "Change",
            onAction = onPickModel,
            isError = state.model is SearchViewModel.ModelState.Failed,
        )
        Text(
            "Sideloaded, never downloaded. Loam holds no network permission, " +
                "so fetch a GGUF yourself and point it here.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        SectionTitle("Answering")

        Tunable(
            label = "Chunks per answer",
            value = "${tuning.chunksPerAnswer}",
            // Measured on this device, so the number is honest rather than a
            // vague "higher is slower".
            detail = "≈ ${"%.0f".format(tuning.chunksPerAnswer * 1.5f)} s to the first " +
                "word. More context grounds better up to a point; small models " +
                "attend worse across long inputs.",
        ) {
            Slider(
                value = tuning.chunksPerAnswer.toFloat(),
                onValueChange = {
                    onTuningChange(tuning.copy(chunksPerAnswer = it.roundToInt()))
                },
                valueRange = Tuning.CHUNKS_RANGE.first.toFloat()..Tuning.CHUNKS_RANGE.last.toFloat(),
                steps = Tuning.CHUNKS_RANGE.count() - 2,
            )
        }

        Tunable(
            label = "Context window",
            value = "${tuning.contextTokens} tokens",
            detail = "Prompt and answer share it. Changing this reloads the model.",
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Tuning.CONTEXT_CHOICES.forEach { choice ->
                    FilterChip(
                        selected = tuning.contextTokens == choice,
                        onClick = { onTuningChange(tuning.copy(contextTokens = choice)) },
                        label = { Text("$choice") },
                    )
                }
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        SectionTitle("Search")

        Tunable(
            label = "Relevance floor",
            value = "%.2f".format(tuning.relevanceFloor),
            detail = "Below this a hit is called noise and discarded. Calibrated " +
                "against one vault of technical notes, where real hits scored " +
                "0.66–0.68 and noise 0.18–0.19. Lower it if searches come back " +
                "empty that should not; raise it if results look confident and wrong.",
        ) {
            Slider(
                value = tuning.relevanceFloor,
                onValueChange = { onTuningChange(tuning.copy(relevanceFloor = it)) },
                valueRange = Tuning.FLOOR_RANGE,
            )
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        TextButton(onClick = onResetTuning) { Text("Reset to measured defaults") }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}

@Composable
private fun SettingRow(
    label: String,
    detail: String,
    action: String,
    onAction: () -> Unit,
    isError: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                detail,
                style = MaterialTheme.typography.labelSmall,
                color = if (isError) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        TextButton(onClick = onAction) { Text(action) }
    }
}

@Composable
private fun Tunable(
    label: String,
    value: String,
    detail: String,
    control: @Composable () -> Unit,
) {
    Column(Modifier.padding(bottom = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                value,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
            )
        }
        control()
        Spacer(Modifier.height(2.dp))
        Text(
            detail,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
