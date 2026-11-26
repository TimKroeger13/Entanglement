package app.entanglement.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.entanglement.desktop.Connection
import app.entanglement.desktop.DocStatus
import app.entanglement.desktop.DocView
import app.entanglement.desktop.SyncService
import app.entanglement.desktop.UserError
import app.entanglement.desktop.WindowsIntegration
import app.entanglement.shared.model.Platform
import kotlinx.coroutines.launch
import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun MainScreen(service: SyncService, window: Frame, onOpenSettings: () -> Unit) {
    val ui by service.ui.collectAsState()
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDecohere by remember { mutableStateOf<DocView?>(null) }

    fun run(action: suspend () -> Unit) {
        scope.launch {
            try {
                action()
            } catch (e: UserError) {
                error = e.message
            } catch (e: Exception) {
                error = "${e::class.simpleName}: ${e.message}"
            }
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            Header(ui.connection, ui.connectionMessage, ui.lastSync, onSync = { service.requestSync() }, onOpenSettings)

            error?.let { ErrorBanner(it) { error = null } }

            Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = {
                    chooseFile(window, "Entangle a Markdown file", null, null)?.let { run { service.entangle(it) } }
                }) { Text("Entangle file…") }
                Spacer(Modifier.width(16.dp))
                Text(
                    "${ui.docs.count { it.status != DocStatus.UNOBSERVED }} entangled on this PC",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (ui.docs.isEmpty()) {
                EmptyState()
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(ui.docs, key = { it.id }) { doc ->
                        DocCard(
                            doc = doc,
                            onShowInFolder = { doc.localPath?.let { WindowsIntegration.showInFolder(Paths.get(it)) } },
                            onOpen = { doc.localPath?.let { run { WindowsIntegration.open(Paths.get(it)) } } },
                            onDecohere = { confirmDecohere = doc },
                            onPlace = {
                                val suggestion = suggestPlacement(doc)
                                chooseSaveLocation(window, "Place ${doc.name} on this PC", suggestion, doc.name)
                                    ?.let { run { service.place(doc.id, it) } }
                            },
                            onRelocate = {
                                chooseFile(window, "Where is ${doc.name} now?", null, doc.name)
                                    ?.let { run { service.relocate(doc.id, it) } }
                            },
                        )
                    }
                }
            }
        }
    }

    confirmDecohere?.let { doc ->
        AlertDialog(
            onDismissRequest = { confirmDecohere = null },
            title = { Text("Decohere ${doc.name}?") },
            text = { Text("It stops syncing on all observers. The local files stay where they are.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDecohere = null
                    run { service.decohere(doc.id) }
                }) { Text("Decohere") }
            },
            dismissButton = { TextButton(onClick = { confirmDecohere = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Header(connection: Connection, message: String?, lastSync: Long?, onSync: () -> Unit, onOpenSettings: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(remember { RingsPainter() }, contentDescription = null, Modifier.size(36.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Entanglement", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            val (color, text) = when (connection) {
                Connection.ONLINE -> Teal to ("Connected" + (lastSync?.let { " · synced ${time(it)}" } ?: ""))
                Connection.CONNECTING -> MaterialTheme.colorScheme.onSurfaceVariant to "Connecting…"
                Connection.OFFLINE -> Color(0xFFE08A00) to "Offline – changes stay on this PC and sync later"
                Connection.UNAUTHORIZED -> MaterialTheme.colorScheme.error to (message ?: "Token rejected")
                Connection.NOT_CONFIGURED -> MaterialTheme.colorScheme.error to "Not set up yet – open Settings"
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(color))
                Spacer(Modifier.width(6.dp))
                Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        TextButton(onClick = onSync) { Text("Sync now") }
        TextButton(onClick = onOpenSettings) { Text("Settings") }
    }
}

@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit) {
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(message, Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer)
            TextButton(onClick = onDismiss) { Text("OK") }
        }
    }
}

@Composable
private fun EmptyState() {
    Column(
        Modifier.fillMaxSize().padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(remember { RingsPainter() }, contentDescription = null, Modifier.size(96.dp))
        Spacer(Modifier.size(16.dp))
        Text("No entangled files yet", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.size(8.dp))
        Text(
            "Click “Entangle file…” or right-click a .md file in Explorer.\nIt stays where it is – every observer gets its own copy.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DocCard(
    doc: DocView,
    onShowInFolder: () -> Unit,
    onOpen: () -> Unit,
    onDecohere: () -> Unit,
    onPlace: () -> Unit,
    onRelocate: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = CardDefaults.outlinedCardBorder(),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    doc.name,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                StatusChip(doc)
            }
            Spacer(Modifier.size(8.dp))
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    for (o in doc.observers) {
                        val where = when {
                            o.platform == Platform.ANDROID && o.path == null -> "in the app"
                            else -> o.path ?: "–"
                        }
                        Row {
                            Text(
                                if (o.isThis) "${o.name} (this PC)" else o.name,
                                Modifier.width(170.dp),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = if (o.isThis) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(where, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            if (doc.updatedByName != null && doc.updatedAt != null && doc.updatedAt > 0) {
                Spacer(Modifier.size(6.dp))
                Text(
                    "Last change by ${doc.updatedByName} · ${time(doc.updatedAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            hint(doc)?.let {
                Spacer(Modifier.size(6.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = statusColor(doc.status))
            }
            Spacer(Modifier.size(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (doc.status) {
                    DocStatus.UNOBSERVED -> Button(onClick = onPlace) { Text("Place here…") }
                    DocStatus.LOST -> {
                        Button(onClick = onRelocate) { Text("Relocate…") }
                        OutlinedButton(onClick = onDecohere) { Text("Decohere") }
                    }
                    else -> {
                        OutlinedButton(onClick = onOpen) { Text("Open") }
                        OutlinedButton(onClick = onShowInFolder) { Text("Show in folder") }
                        OutlinedButton(onClick = onDecohere) { Text("Decohere") }
                    }
                }
            }
        }
    }
}

private fun hint(doc: DocView): String? = when (doc.status) {
    DocStatus.SUPERPOSITION ->
        "Both versions are in the file between ⚛⚛⚛ markers. Delete the one you don't want together with the markers."
    DocStatus.CHANGES_WAITING -> "Changes on this PC are waiting to be synced."
    DocStatus.LOST -> "The file is no longer at its place on this PC."
    DocStatus.UNOBSERVED -> "Entangled on another observer, not yet on this PC."
    DocStatus.ERROR -> doc.message
    DocStatus.ENTANGLED -> null
}

@Composable
private fun StatusChip(doc: DocView) {
    val label = when (doc.status) {
        DocStatus.ENTANGLED -> "Entangled"
        DocStatus.CHANGES_WAITING -> "Changes waiting"
        DocStatus.SUPERPOSITION -> if (doc.superpositions > 1) "Superposition ×${doc.superpositions}" else "Superposition"
        DocStatus.LOST -> "Lost"
        DocStatus.UNOBSERVED -> "Unobserved"
        DocStatus.ERROR -> "Error"
    }
    val color = statusColor(doc.status)
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
private fun statusColor(status: DocStatus): Color = when (status) {
    DocStatus.ENTANGLED -> Teal
    DocStatus.CHANGES_WAITING -> MaterialTheme.colorScheme.primary
    DocStatus.SUPERPOSITION -> Color(0xFFE08A00)
    DocStatus.LOST, DocStatus.ERROR -> MaterialTheme.colorScheme.error
    DocStatus.UNOBSERVED -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun time(millis: Long): String {
    val t = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
    val pattern = if (t.toLocalDate() == LocalDate.now()) "HH:mm" else "dd.MM.yyyy HH:mm"
    return t.format(DateTimeFormatter.ofPattern(pattern))
}

/** Same folder as on another PC if that exists here, otherwise Documents\Entanglement. */
private fun suggestPlacement(doc: DocView): Path {
    doc.observers.mapNotNull { it.path }
        .mapNotNull { runCatching { Paths.get(it).parent }.getOrNull() }
        .firstOrNull { Files.isDirectory(it) }
        ?.let { return it }
    return WindowsIntegration.defaultPlacementDir().also { runCatching { Files.createDirectories(it) } }
}

private fun chooseFile(window: Frame, title: String, dir: Path?, name: String?): Path? {
    val dialog = FileDialog(window, title, FileDialog.LOAD)
    dir?.let { dialog.directory = it.toString() }
    dialog.file = name ?: "*.md"
    dialog.isVisible = true
    val file = dialog.file ?: return null
    return Paths.get(dialog.directory, file)
}

private fun chooseSaveLocation(window: Frame, title: String, dir: Path, name: String): Path? {
    val dialog = FileDialog(window, title, FileDialog.SAVE)
    dialog.directory = dir.toString()
    dialog.file = name
    dialog.isVisible = true
    val file = dialog.file ?: return null
    return Paths.get(dialog.directory, file)
}
