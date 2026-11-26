package app.entanglement.android.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.entanglement.android.EntanglementApp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val repository get() = (application as EntanglementApp).repository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // While the app is on screen: sync now and every 30 s, like the desktop app.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    repository.syncAll()
                    delay(30_000)
                }
            }
        }

        setContent {
            EntanglementTheme {
                val startOnSettings = !repository.config.value.isConfigured
                // "list", "settings" or the id of the open document.
                var screen by rememberSaveable { mutableStateOf(if (startOnSettings) "settings" else "list") }
                BackHandler(enabled = screen != "list") { screen = "list" }
                when (screen) {
                    "list" -> ListScreen(
                        repository,
                        onOpen = { screen = it },
                        onSettings = { screen = "settings" },
                    )
                    "settings" -> SettingsScreen(repository, onDone = { screen = "list" })
                    else -> EditorScreen(repository, screen, onBack = { screen = "list" })
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Save immediately and let WorkManager push it, even if the app is closed now.
        repository.flush()
        repository.requestSync()
    }
}
