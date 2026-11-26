package app.entanglement.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import app.entanglement.desktop.Config
import app.entanglement.desktop.ExplorerOverlay
import app.entanglement.desktop.WindowsIntegration
import app.entanglement.shared.sync.RemoteApi
import app.entanglement.shared.sync.RemoteException
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsDialog(
    config: Config,
    apiFor: (Config) -> RemoteApi,
    onSave: (Config) -> Unit,
    onClose: () -> Unit,
) {
    var url by remember { mutableStateOf(config.serverUrl) }
    var token by remember { mutableStateOf(config.token) }
    var name by remember { mutableStateOf(config.observerName) }
    var showToken by remember { mutableStateOf(false) }
    var test by remember { mutableStateOf<String?>(null) }
    val integration = WindowsIntegration.isAvailable
    var contextMenu by remember { mutableStateOf(integration && WindowsIntegration.isContextMenuEnabled()) }
    var autostart by remember { mutableStateOf(integration && WindowsIntegration.isAutostartEnabled()) }
    val scope = rememberCoroutineScope()

    fun edited() = config.copy(serverUrl = url.trim().trimEnd('/'), token = token.trim(), observerName = name.trim())

    DialogWindow(
        onCloseRequest = onClose,
        title = "Entanglement – Settings",
        state = rememberDialogState(width = 600.dp, height = 760.dp),
        icon = remember { RingsPainter() },
    ) {
        EntanglementTheme {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Server", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        url, { url = it }, Modifier.fillMaxWidth(),
                        label = { Text("Server URL") },
                        placeholder = { Text("https://example.com/entanglement") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        token, { token = it }, Modifier.fillMaxWidth(),
                        label = { Text("Token") },
                        singleLine = true,
                        visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = { TextButton(onClick = { showToken = !showToken }) { Text(if (showToken) "Hide" else "Show") } },
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = {
                            test = "Testing…"
                            scope.launch {
                                test = try {
                                    val docs = withContext(Dispatchers.IO) { apiFor(edited()).list() }
                                    "✓ Connected – ${docs.count { !it.decohered }} entangled document(s) on the server"
                                } catch (e: RemoteException) {
                                    if (e.status == HttpStatusCode.Unauthorized) "✗ The server rejected the token" else "✗ Server answered ${e.status.value}"
                                } catch (e: Exception) {
                                    "✗ ${e.message ?: e::class.simpleName}"
                                }
                            }
                        }) { Text("Test connection") }
                        Spacer(Modifier.size(12.dp))
                        test?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }

                    Spacer(Modifier.size(4.dp))
                    Text("This observer", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        name, { name = it }, Modifier.fillMaxWidth(),
                        label = { Text("Name shown on other devices") },
                        singleLine = true,
                    )

                    Spacer(Modifier.size(4.dp))
                    Text("Windows", style = MaterialTheme.typography.titleMedium)
                    CheckRow(contextMenu, integration, "“Entangle” in the Explorer context menu for .md files", "Windows 11: under “Show more options”") {
                        contextMenu = it
                    }
                    CheckRow(autostart, integration, "Start with Windows (in the background)", null) { autostart = it }
                    if (!integration) {
                        Text(
                            "Available in the installed app.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OverlaySection()

                    Spacer(Modifier.weight(1f))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onClose) { Text("Cancel") }
                        Spacer(Modifier.size(8.dp))
                        Button(
                            enabled = url.isNotBlank() && token.isNotBlank() && name.isNotBlank(),
                            onClick = {
                                if (integration) {
                                    WindowsIntegration.setContextMenu(contextMenu)
                                    WindowsIntegration.setAutostart(autostart)
                                }
                                onSave(edited())
                                onClose()
                            },
                        ) { Text("Save") }
                    }
                }
            }
        }
    }
}

/** Rings badge in Explorer: needs admin once (UAC) and an Explorer restart to appear. */
@Composable
private fun OverlaySection() {
    val available = ExplorerOverlay.isAvailable
    var enabled by remember { mutableStateOf(available && ExplorerOverlay.isEnabled()) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var offerRestart by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun toggle(on: Boolean) {
        busy = true
        status = "Waiting for administrator approval…"
        scope.launch {
            val ok = withContext(Dispatchers.IO) { if (on) ExplorerOverlay.enable() else ExplorerOverlay.disable() }
            enabled = withContext(Dispatchers.IO) { ExplorerOverlay.isEnabled() }
            busy = false
            offerRestart = ok
            status = when {
                !ok -> "Not changed (approval declined or failed)."
                on -> "Done. Explorer shows the rings after a restart."
                else -> "Removed. Explorer forgets the rings after a restart."
            }
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Rings on entangled files in Explorer", style = MaterialTheme.typography.bodyMedium)
            Text(
                if (available) "Needs administrator approval once. Windows shows only 11 such badges; " +
                    "Entanglement takes the place of Google Drive's “syncing” badge."
                else if (WindowsIntegration.isAvailable && !ExplorerOverlay.isBundled) "Not included in this build yet."
                else "Available in the installed app.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.size(12.dp))
        OutlinedButton(enabled = available && !busy, onClick = { toggle(!enabled) }) {
            Text(if (enabled) "Turn off…" else "Turn on…")
        }
    }
    status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    if (offerRestart) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Restarting Explorer closes open Explorer windows.",
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = {
                offerRestart = false
                scope.launch(Dispatchers.IO) { ExplorerOverlay.restartExplorer() }
            }) { Text("Restart Explorer") }
        }
    }
}

@Composable
private fun CheckRow(checked: Boolean, enabled: Boolean, label: String, note: String?, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onChange, enabled = enabled)
        Column {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            note?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
