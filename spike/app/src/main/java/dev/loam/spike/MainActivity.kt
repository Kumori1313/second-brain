package dev.loam.spike

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Phase 0 spike harness. Three questions, each answerable on real hardware:
 *
 *  1. Does on-device embedding work, how fast, and are the vectors sane?
 *  2. Does SAF folder read work without a storage permission?
 *  3. Where does brute-force cosine search stop being fast enough?
 *
 * Throwaway by design — this is not the shape of the real app.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { SpikeScreen(filesRoot = getExternalFilesDir(null)) } }
    }
}

private const val MODEL_NAME = "model_qint8_arm64.onnx"
private const val VOCAB_NAME = "vocab.txt"

@Composable
fun SpikeScreen(filesRoot: File?) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val log = remember { mutableStateListOf<String>() }
    var busy by remember { mutableStateOf(false) }

    val modelFile = remember { File(filesRoot, MODEL_NAME) }
    val vocabFile = remember { File(filesRoot, VOCAB_NAME) }
    val ready = modelFile.exists() && vocabFile.exists()

    fun logLine(s: String) = log.add(s)

    fun runTask(label: String, block: suspend () -> Unit) {
        if (busy) return
        busy = true
        logLine("▶ $label")
        scope.launch {
            try {
                withContext(Dispatchers.Default) { block() }
            } catch (t: Throwable) {
                logLine("✗ ${t::class.simpleName}: ${t.message}")
            } finally {
                busy = false
            }
        }
    }

    val pickVault = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        runTask("SAF scan") {
            val reader = VaultReader(context)
            val scan = reader.scan(uri)
            logLine("  ${scan.notes.size} .md files in ${scan.millis}ms (${scan.skippedNonMarkdown} non-md skipped)")
            val first = scan.notes.firstOrNull()
            if (first == null) {
                logLine("  no markdown found — pick a folder that has .md files")
            } else {
                val text = reader.readText(first.uri)
                val chunks = reader.chunk(text)
                logLine("  read '${first.name}': ${text.length} chars -> ${chunks.size} chunks")
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Loam Phase 0 Spike", style = MaterialTheme.typography.headlineSmall)

        if (!ready) {
            Card {
                Column(Modifier.padding(12.dp)) {
                    Text("Model not found", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Push the model and vocab to:\n${filesRoot?.absolutePath}\n\n" +
                            "See spike/push-model.sh",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }

        Button(
            onClick = {
                runTask("Embedding test") {
                    val tok = WordPieceTokenizer(vocabFile)
                    logLine("  vocab: ${tok.vocabSize} tokens")
                    Embedder(modelFile, tok).use { embedder ->
                        val warm = embedder.embedTimed("warmup pass")
                        logLine("  cold start: ${warm.millis}ms, dim=${warm.vector.size}")

                        val samples = listOf(
                            "I wrote about distributed systems consensus",
                            "notes on raft and paxos algorithms",
                            "banana bread recipe with walnuts",
                        )
                        val vecs = samples.map { s ->
                            val t = embedder.embedTimed(s)
                            logLine("  ${t.millis}ms  \"${s.take(38)}\"")
                            t.vector
                        }
                        // Sanity check: the two systems sentences should be
                        // closer to each other than either is to the recipe.
                        val related = dot(vecs[0], vecs[1])
                        val unrelated = dot(vecs[0], vecs[2])
                        logLine("  similarity related=%.3f unrelated=%.3f".format(related, unrelated))
                        logLine(
                            if (related > unrelated) "  ✓ vectors behave sensibly"
                            else "  ✗ SUSPECT — check tokenizer and pooling"
                        )
                    }
                }
            },
            enabled = ready && !busy,
        ) { Text("1. Test embedding") }

        Button(onClick = { pickVault.launch(null) }, enabled = !busy) {
            Text("2. Pick vault (SAF)")
        }

        Button(
            onClick = {
                runTask("Cosine benchmark") {
                    val dim = 384 // MiniLM-L6-v2 output width
                    for (n in listOf(5_000, 20_000, 50_000)) {
                        val store = CosineBench.synthesize(n, dim)
                        val r = CosineBench.run(store, dim)
                        logLine("  %,d chunks: %.2f ms/query (heap %d MB)".format(r.chunkCount, r.millisPerQuery, r.heapUsedMb))
                    }
                    logLine("  if 50k stays under ~50ms, sqlite-vec is not needed")
                }
            },
            enabled = !busy,
        ) { Text("3. Benchmark cosine search") }

        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())

        HorizontalDivider()

        SelectionContainerText(log)
    }
}

@Composable
private fun SelectionContainerText(log: List<String>) {
    androidx.compose.foundation.text.selection.SelectionContainer {
        Column {
            log.forEach { line ->
                Text(line, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun dot(a: FloatArray, b: FloatArray): Float {
    var s = 0f
    for (i in a.indices) s += a[i] * b[i]
    return s
}
