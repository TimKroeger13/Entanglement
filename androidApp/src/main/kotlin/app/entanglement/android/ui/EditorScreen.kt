package app.entanglement.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.entanglement.android.data.Connection
import app.entanglement.android.data.DocStatus
import app.entanglement.android.data.Repository
import app.entanglement.android.data.UserError
import app.entanglement.shared.merge.Superposition
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
    var renaming by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun reconcile() {
        val typed = editor.text.toString()
        if (typed == known && repoText == known) return
        val result = repository.edit(id, known, typed)
        known = result
        if (result != typed) {
            editor.edit {
                val cursor = keepCursor(typed, result, selection.start)
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
                        if (doc?.status == DocStatus.CHANGES_WAITING && ui.connection == Connection.ONLINE) {
                            Text(
                                "Saved · syncing…",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            ConnectionLine(ui.connection, ui.lastSync)
                        }
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.More, contentDescription = "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Rename…") }, onClick = {
                                menu = false
                                renaming = true
                            })
                            DropdownMenuItem(
                                text = { Text("Desync…", color = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    menu = false
                                    confirmDecohere = true
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        // consumeWindowInsets: the navigation bar is already in `padding`, so imePadding must not add it again.
        Column(Modifier.padding(padding).consumeWindowInsets(padding).imePadding().fillMaxSize()) {
            val blocks = remember(repoText) { Superposition.find(repoText) }
            blocks.firstOrNull()?.let { block ->
                if (WindowInsets.isImeVisible) {
                    // While typing, keep the writing space: a slim hint only; the full choice shows when the keyboard closes.
                    Text(
                        "Superposition in this file – close the keyboard to measure",
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = Amber,
                    )
                } else {
                    MeasureBanner(block, blocks.size) { keep ->
                        repository.edit(id, Superposition.resolve(repository.text(id).value, block, keep))
                    }
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
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    autoCorrectEnabled = true,
                    keyboardType = KeyboardType.Text,
                ),
            )
        }
    }

    if (renaming) {
        RenameDialog(
            current = doc?.name ?: "",
            onRename = { repository.rename(id, it) },
            onDismiss = { renaming = false },
        )
    }

    if (confirmDecohere) {
        AlertDialog(
            onDismissRequest = { confirmDecohere = false },
            title = { Text("Desync ${doc?.name}?") },
            text = {
                Text(
                    "It stops syncing on ALL devices – every PC and this phone. Each device keeps its current " +
                        "copy, nothing is deleted.\n\nYou can undo it later with “Re-sync” in the list; copies " +
                        "that changed in the meantime are merged.",
                )
            },
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
                }) { Text("Desync everywhere", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDecohere = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun RenameDialog(current: String, onRename: suspend (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(current) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename") },
        text = {
            OutlinedTextField(
                name,
                { name = it; error = null },
                singleLine = true,
                label = { Text("New name") },
                isError = error != null,
                supportingText = { Text(error ?: "Renamed on all devices; PCs rename their file too.") },
            )
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    try {
                        onRename(name)
                        onDismiss()
                    } catch (e: UserError) {
                        error = e.message
                    }
                }
            }) { Text("Rename") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
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

/**
 * Where the cursor belongs after the text changed from [old] to [new] underneath it (a sync landed):
 * changes before the cursor shift it, changes after it don't, so you keep typing at your own spot.
 */
internal fun keepCursor(old: String, new: String, cursor: Int): Int {
    var prefix = 0
    val max = minOf(old.length, new.length)
    while (prefix < max && old[prefix] == new[prefix]) prefix++
    var suffix = 0
    while (suffix < max - prefix && old[old.length - 1 - suffix] == new[new.length - 1 - suffix]) suffix++
    return when {
        cursor <= prefix -> cursor
        cursor >= old.length - suffix -> cursor + (new.length - old.length)
        else -> new.length - suffix
    }.coerceIn(0, new.length)
}
