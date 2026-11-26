package app.entanglement.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.entanglement.android.data.Connection
import app.entanglement.android.data.DocItem
import app.entanglement.android.data.DocStatus
import app.entanglement.android.data.Repository
import app.entanglement.android.data.UserError
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListScreen(repository: Repository, onOpen: (String) -> Unit, onSettings: () -> Unit) {
    val ui by repository.ui.collectAsStateWithLifecycle()
    var refreshing by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Rings(28.dp)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("Entanglement", style = MaterialTheme.typography.titleLarge)
                            ConnectionLine(ui.connection, ui.lastSync)
                        }
                    }
                },
                actions = { TextButton(onClick = onSettings) { Text("Settings") } },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { creating = true }) { Icon(Icons.Add, contentDescription = "New file") }
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                refreshing = true
                scope.launch {
                    repository.syncAll()
                    refreshing = false
                }
            },
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            if (ui.docs.isEmpty()) {
                EmptyState()
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
                    items(ui.docs, key = { it.id }) { doc ->
                        DocRow(doc, onClick = { onOpen(doc.id) })
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }

    if (creating) {
        NewFileDialog(
            onCreate = { name -> repository.create(name).also(onOpen) },
            onDismiss = { creating = false },
        )
    }
}

@Composable
fun ConnectionLine(connection: Connection, lastSync: Long?) {
    val (color, text) = when (connection) {
        Connection.ONLINE -> Teal to ("Synced" + (lastSync?.let { " ${time(it)}" } ?: ""))
        Connection.CONNECTING -> MaterialTheme.colorScheme.onSurfaceVariant to "Connecting…"
        Connection.OFFLINE -> Amber to "Offline – changes stay on the phone"
        Connection.UNAUTHORIZED -> MaterialTheme.colorScheme.error to "Token rejected – check Settings"
        Connection.NOT_CONFIGURED -> MaterialTheme.colorScheme.error to "Not set up – open Settings"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DocRow(doc: DocItem, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                doc.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val sub = if (doc.updatedByName != null && doc.updatedAt != null && doc.updatedAt > 0) {
                "Last change: ${doc.updatedByName}, ${time(doc.updatedAt)}"
            } else {
                ""
            }
            if (sub.isNotEmpty()) {
                Text(
                    sub,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        StatusChip(doc)
    }
}

/** Only shown when something needs attention; an entangled, synced file stays quiet. */
@Composable
fun StatusChip(doc: DocItem) {
    val (label, color) = when (doc.status) {
        DocStatus.ENTANGLED -> return
        DocStatus.CHANGES_WAITING -> "Waiting" to MaterialTheme.colorScheme.primary
        DocStatus.SUPERPOSITION ->
            (if (doc.superpositions > 1) "Superposition ×${doc.superpositions}" else "Superposition") to Amber
    }
    Spacer(Modifier.width(8.dp))
    Surface(color = color.copy(alpha = 0.14f), shape = RoundedCornerShape(50)) {
        Text(
            label,
            Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelMedium,
            color = color,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun EmptyState() {
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(
                Modifier.fillMaxWidth().padding(top = 96.dp, start = 32.dp, end = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Rings(88.dp)
                Text("Nothing entangled yet", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Entangle a file on your PC – it appears here by itself. Or tap + to start one on the phone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun NewFileDialog(onCreate: suspend (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New entangled file") },
        text = {
            OutlinedTextField(
                name,
                { name = it; error = null },
                label = { Text("Name") },
                placeholder = { Text("Train notes") },
                singleLine = true,
                isError = error != null,
                supportingText = { Text(error ?: "Appears on your PCs as unobserved, ready to place.") },
            )
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    try {
                        onCreate(name)
                        onDismiss()
                    } catch (e: UserError) {
                        error = e.message
                    }
                }
            }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

fun time(millis: Long): String {
    val then = Calendar.getInstance().apply { timeInMillis = millis }
    val now = Calendar.getInstance()
    val sameDay = then.get(Calendar.YEAR) == now.get(Calendar.YEAR) && then.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
    val format = if (sameDay) DateFormat.getTimeInstance(DateFormat.SHORT) else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
    return format.format(Date(millis))
}

