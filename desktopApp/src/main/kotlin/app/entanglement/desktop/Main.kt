package app.entanglement.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import app.entanglement.desktop.ui.EntanglementTheme
import app.entanglement.desktop.ui.MainScreen
import app.entanglement.desktop.ui.RingsPainter
import app.entanglement.desktop.ui.SettingsDialog
import app.entanglement.shared.sync.HttpRemoteApi
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.nio.file.Paths
import javax.swing.UIManager

/**
 * Arguments:
 * - `--entangle <file>`: entangle a file (used by the Explorer context menu)
 * - `--background`: start hidden in the tray (used by autostart)
 */
fun main(args: Array<String>) {
    val home = AppHome.default()
    Log.init(home.logFile)

    val entangleArg = args.indexOf("--entangle").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
    val background = "--background" in args

    val instance = SingleInstance(home.instanceFile)
    val message = if (entangleArg != null) listOf("entangle", entangleArg) else listOf("show")
    if (instance.sendToRunning(message)) return

    runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
    WindowsIntegration.refreshIfEnabled()

    val http = HttpClient(OkHttp) {
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 60_000
        }
    }
    val apiFor = { cfg: Config -> HttpRemoteApi(http, cfg.serverUrl, cfg.token) }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val service = SyncService(home, apiFor, scope)

    // Requests from a second start (context menu, or the user starting the app again).
    // Buffered: they may arrive before the UI below is composed.
    val requests = Channel<List<String>>(Channel.UNLIMITED)
    instance.listen {
        Log.info("Request from second start: ${it.firstOrNull()}")
        requests.trySend(it)
    }
    entangleArg?.let { requests.trySend(listOf("entangle", it)) }

    val overlay = ExplorerOverlay(home)
    service.onEntangledPaths = overlay::update

    Log.info("Started (home: ${home.dir})")
    service.start()

    application {
        var visible by remember { mutableStateOf(!background || entangleArg != null) }
        var settingsOpen by remember { mutableStateOf(!service.config.value.isConfigured) }
        val config by service.config.collectAsState()
        val tray = rememberTrayState()
        val icon = remember { RingsPainter() }

        LaunchedEffect(Unit) {
            service.onSuperposition = { name, count ->
                tray.sendNotification(
                    Notification(
                        "Superposition in $name",
                        if (count == 1) "One passage was changed on two observers. Both versions are in the file."
                        else "$count passages were changed on two observers. Both versions are in the file.",
                        Notification.Type.Warning,
                    ),
                )
            }
            for (request in requests) {
                when (request.firstOrNull()) {
                    "entangle" -> request.getOrNull(1)?.let { file ->
                        scope.launch {
                            val path = Paths.get(file)
                            try {
                                service.entangle(path)
                                tray.sendNotification(Notification("Entangled", path.fileName.toString(), Notification.Type.Info))
                            } catch (e: Exception) {
                                Log.error("Entangle $file failed", e)
                                tray.sendNotification(
                                    Notification("Could not entangle ${path.fileName}", e.message ?: "", Notification.Type.Error),
                                )
                            }
                        }
                    }
                    else -> visible = true
                }
            }
        }

        Tray(
            icon = icon,
            state = tray,
            tooltip = "Entanglement",
            onAction = { visible = true },
            menu = {
                Item("Open Entanglement", onClick = { visible = true })
                Item("Sync now", onClick = { service.requestSync() })
                Separator()
                Item("Quit", onClick = {
                    service.close()
                    http.close()
                    exitApplication()
                })
            },
        )

        Window(
            onCloseRequest = { visible = false },
            visible = visible,
            title = "Entanglement",
            icon = icon,
            state = rememberWindowState(width = 820.dp, height = 640.dp),
        ) {
            EntanglementTheme {
                MainScreen(service, window, onOpenSettings = { settingsOpen = true })
            }
            if (settingsOpen) {
                SettingsDialog(
                    config = config,
                    apiFor = apiFor,
                    onSave = service::updateConfig,
                    onClose = { settingsOpen = false },
                )
            }
        }
    }
}
