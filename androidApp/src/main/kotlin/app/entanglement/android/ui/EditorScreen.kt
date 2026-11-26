package app.entanglement.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.entanglement.android.data.Repository
import app.entanglement.android.data.UserError
import app.entanglement.shared.merge.Superposition
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(repository: Repository, id: String, onBack: () -> Unit) {
    val ui by repository.ui.collectAsStateWithLifecycle()
    val doc = ui.docs.find { it.id == id }
    val repoText by repository.text(id).collectAsStateWithLifecycle()
    val editor = rememberTextFieldState(repoText)
    // The text this editor last agreed on with the repository (see Repository.edit).
    var known by remember { mutableStateOf(repoText) }
    var menu by remember { mutableStateOf(false) }
    var confirmDecohere by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun reconcile() {
        val typed = editor.text.toString()
        if (typed == known && repoText == known) return
        val result = repository.edit(id, known, typed)
        known = result
        if (result != typed) {
            editor.edit {
                val cursor = selection.start.coerceAtMost(result.length)
                replace(0, length, result)
                selection = TextRange(cursor)
            }
        }
    }

    // Typing → repository; sync results → editor. Both go through reconcile(), so nothing is lost.
    LaunchedEffect(editor) { snapshotFlow { editor.text.toString() }.collect { reconcile() } }
    LaunchedEffect(repoText) { reconcile() }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Back, contentDescription = "Back") } },
                title = {
                    Column {
                        Text(doc?.name ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        ConnectionLine(ui.connection, ui.lastSync)
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.More, contentDescription = "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Decohere…") }, onClick = {
                                menu = false
                                confirmDecohere = true
                            })
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            val blocks = remember(repoText) { Superposition.find(repoText) }
            blocks.firstOrNull()?.let { block ->
                MeasureBanner(block, blocks.size) { keep ->
                    repository.edit(id, Superposition.resolve(repository.text(id).value, block, keep))
                }
            }
            error?.let {
                Text(it, Modifier.padding(horizontal = 20.dp, vertical = 6.dp), color = MaterialTheme.colorScheme.error)
            }
            BasicTextField(
                state = editor,
                modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp),
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 17.sp,
                    lineHeight = 26.sp,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            )
        }
    }

    if (confirmDecohere) {
        AlertDialog(
            onDismissRequest = { confirmDecohere = false },
            title = { Text("Decohere ${doc?.name}?") },
            text = { Text("It stops syncing on all observers. Files on your PCs stay where they are.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDecohere = false
                    scope.launch {
                        try {
                            repository.decohere(id)
                            onBack()
                        } catch (e: UserError) {
                            error = e.message
                        }
                    }
                }) { Text("Decohere") }
            },
            dismissButton = { TextButton(onClick = { confirmDecohere = false }) { Text("Cancel") } },
        )
    }
}

/** Shows the two versions of the first superposition; one tap keeps one of them ("measuring"). */
@Composable
private fun MeasureBanner(block: Superposition.Block, total: Int, onKeep: (Superposition.Keep) -> Unit) {
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        color = Amber.copy(alpha = 0.12f),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (total > 1) "Superposition (1 of $total)" else "Superposition",
                style = MaterialTheme.typography.titleSmall,
                color = Amber,
            )
            Version(block.firstObserver, block.first)
            Version(block.secondObserver, block.second)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { onKeep(Superposition.Keep.FIRST) }) { Text("Keep ${block.firstObserver}") }
                TextButton(onClick = { onKeep(Superposition.Keep.SECOND) }) { Text("Keep ${block.secondObserver}") }
                TextButton(onClick = { onKeep(Superposition.Keep.BOTH) }) { Text("Both") }
            }
        }
    }
}

@Composable
private fun Version(observer: String, text: String) {
    Column {
        Text(observer, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text.trimEnd().ifEmpty { "(nothing)" },
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
