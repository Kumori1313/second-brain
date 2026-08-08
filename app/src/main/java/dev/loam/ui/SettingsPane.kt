package dev.loam.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.loam.core.domain.IndexingRules
import dev.loam.core.domain.Tuning
import dev.loam.core.store.KeyProtection
import kotlin.math.roundToInt

/** Addressable so a test can move the one slider that costs a rebuild. */
const val CHUNK_SIZE_SLIDER = "chunkSizeSlider"

/**
 * The constants this project measured its way to, made adjustable.
 *
 * Each one shows what it costs, because these are not preferences — they are
 * tradeoffs with numbers attached, and the numbers came from this device. A
 * slider labelled only "chunks per answer" would be a worse setting than none.
 *
 * Settings split into two kinds and the pane says which is which. Most take
 * effect on the next query. The two under "Indexing" make what is already
 * stored wrong, so they are staged and applied together behind one button that
 * says what it will cost.
 */
@Composable
fun SettingsPane(
    state: SearchViewModel.UiState,
    tuning: Tuning,
    onTuningChange: (Tuning) -> Unit,
    onResetTuning: () -> Unit,
    rules: IndexingRules,
    onRulesChange: (IndexingRules) -> Unit,
    onPickVault: () -> Unit,
    onPickModel: () -> Unit,
    onReindex: () -> Unit,
    keyProtection: KeyProtection,
    onKeyProtectionChange: (KeyProtection) -> Unit,
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
            detail = "Below this a hit is called noise and discarded. Measured on " +
                "one vault of technical notes: direct questions scored 0.52–0.82, " +
                "questions it could not answer 0.17–0.41. Those bands overlap, so " +
                "this picks which mistake to make rather than avoiding both — when " +
                "a search comes back empty, \"Show weak matches\" reaches below it.",
        ) {
            Slider(
                value = tuning.relevanceFloor,
                onValueChange = { onTuningChange(tuning.copy(relevanceFloor = it)) },
                valueRange = Tuning.FLOOR_RANGE,
            )
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        IndexingSection(
            rules = rules,
            noteCount = state.noteCount,
            indexing = state.indexing,
            onRulesChange = onRulesChange,
        )

        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        SectionTitle("Index protection")
        Text(
            "The index stores your note text verbatim, so it is about as " +
                "sensitive as the notes. It is always encrypted with a key the " +
                "device hardware holds; this chooses what unlocks that key.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))

        // Each option states its cost, like the tunables above. Two of the three
        // trade background freshness for protection, and that is not something a
        // user can be expected to infer from a label.
        ProtectionChoice(
            selected = keyProtection == KeyProtection.OFF,
            label = "Off",
            detail = "No prompt. The index opens whenever the app runs. " +
                "Still hardware-sealed, so it cannot simply be copied off the device.",
            onClick = { onKeyProtectionChange(KeyProtection.OFF) },
        )
        ProtectionChoice(
            selected = keyProtection == KeyProtection.DEVICE_UNLOCK,
            label = "Require a recent device unlock",
            detail = "No prompt in normal use — unlocking your phone is enough. " +
                "An extracted index is unreadable on a locked device. Background " +
                "reindexing waits for the next unlock if the phone has been idle.",
            onClick = { onKeyProtectionChange(KeyProtection.DEVICE_UNLOCK) },
        )
        ProtectionChoice(
            selected = keyProtection == KeyProtection.EVERY_TIME,
            label = "Ask every time Loam opens",
            detail = "Fingerprint or PIN on every launch — the only level that " +
                "stops someone holding your unlocked phone. Turns off periodic " +
                "reindexing entirely, since a background pass has nobody to ask.",
            onClick = { onKeyProtectionChange(KeyProtection.EVERY_TIME) },
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Changing this re-seals the key. If it ever becomes unusable the " +
                "worst case is a reindex — your notes are plain files and are " +
                "never touched.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        TextButton(onClick = onResetTuning) { Text("Reset to measured defaults") }
    }
}

@Composable
private fun ProtectionChoice(
    selected: Boolean,
    label: String,
    detail: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The two settings that cost a rebuild, staged behind one Apply.
 *
 * Staged rather than live because each keystroke in the pattern box and each
 * tick of the slider would otherwise start a full pass over the vault. The
 * button is the point of the section: it says what the change costs before it
 * is made, and nothing happens until it is pressed.
 */
@Composable
private fun IndexingSection(
    rules: IndexingRules,
    noteCount: Int,
    indexing: Boolean,
    onRulesChange: (IndexingRules) -> Unit,
) {
    // Keyed on the stored value so an applied change — or a reset from
    // elsewhere in this pane — replaces what is staged instead of leaving the
    // editor showing something that is no longer pending.
    var patterns by remember(rules.excludePatterns) { mutableStateOf(rules.excludePatterns) }
    var tokens by remember(rules.chunkTokens) { mutableIntStateOf(rules.chunkTokens) }

    val rebuilds = tokens != rules.chunkTokens
    val dirty = rebuilds || patterns.trim() != rules.excludePatterns

    SectionTitle("Indexing")
    Text(
        "These change what is in the index, so they only take effect once it " +
            "has been rebuilt.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))

    Text("Exclude", style = MaterialTheme.typography.bodyLarge)
    OutlinedTextField(
        value = patterns,
        onValueChange = { patterns = it },
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        placeholder = { Text("templates/\n*.excalidraw.md") },
        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        minLines = 3,
    )
    Text(
        "One pattern per line, matched against the path inside your vault. A " +
            "name on its own matches that file anywhere; a trailing slash takes " +
            "a folder and everything under it. Dot-folders like .obsidian and " +
            ".trash are always skipped.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )

    Spacer(Modifier.height(16.dp))

    Tunable(
        label = "Chunk size",
        value = "$tokens tokens",
        detail = "How much text is embedded as one unit. Measured on a " +
            "392-note vault: 200 gives 5,853 chunks, 240 gives 5,297, 254 " +
            "gives 5,176. Smaller is more precisely targeted and cheaper to " +
            "embed; larger carries more context into an answer. The ceiling is " +
            "the model's own window — above it the text would be truncated away.",
    ) {
        Slider(
            // Tagged because three sliders share this pane and a test that
            // addressed them by position would pass for the wrong one the day
            // a section moves.
            modifier = Modifier.testTag(CHUNK_SIZE_SLIDER),
            value = tokens.toFloat(),
            onValueChange = { tokens = it.roundToInt() },
            valueRange = IndexingRules.CHUNK_RANGE.first.toFloat()..
                IndexingRules.CHUNK_RANGE.last.toFloat(),
        )
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Button(
            onClick = { onRulesChange(IndexingRules(patterns, tokens)) },
            enabled = dirty && !indexing,
        ) { Text("Apply and reindex") }
        Spacer(Modifier.width(12.dp))
        if (dirty) {
            Text(
                if (rebuilds) {
                    "Re-embeds all $noteCount notes — minutes, not seconds."
                } else {
                    "Next pass drops what no longer matches."
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
