package app.entanglement.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.entanglement.android.data.Repository
import app.entanglement.shared.sync.HttpRemoteApi
import app.entanglement.shared.sync.RemoteException
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(repository: Repository, onDone: () -> Unit) {
    val config = repository.config.value
    var url by remember { mutableStateOf(config.serverUrl) }
    var token by remember { mutableStateOf(config.token) }
    var name by remember { mutableStateOf(config.observerName) }
    var showToken by remember { mutableStateOf(false) }
    var test by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun edited() = config.copy(serverUrl = url.trim().trimEnd('/'), token = token.trim(), observerName = name.trim())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    if (config.isConfigured) {
                        IconButton(onClick = onDone) { Icon(Icons.Back, contentDescription = "Back") }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Server", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                url, { url = it }, Modifier.fillMaxWidth(),
                label = { Text("Server URL") },
                placeholder = { Text("https://example.com/entanglement") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            OutlinedTextField(
                token, { token = it }, Modifier.fillMaxWidth(),
                label = { Text("Token") },
                singleLine = true,
                visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = { TextButton(onClick = { showToken = !showToken }) { Text(if (showToken) "Hide" else "Show") } },
            )
            Row {
                OutlinedButton(onClick = {
                    test = "Testing…"
                    scope.launch {
                        test = withContext(Dispatchers.IO) {
                            HttpClient(OkHttp).use { http ->
                                try {
                                    val docs = HttpRemoteApi(http, edited().serverUrl, edited().token).list()
                                    "✓ Connected – ${docs.count { !it.decohered }} entangled file(s)"
                                } catch (e: RemoteException) {
                                    if (e.status == HttpStatusCode.Unauthorized) "✗ The server rejected the token"
                                    else "✗ Server answered ${e.status.value}"
                                } catch (e: Exception) {
                                    "✗ ${e.message ?: e::class.simpleName}"
                                }
                            }
                        }
                    }
                }) { Text("Test connection") }
            }
            test?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

            Text("This observer", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                name, { name = it }, Modifier.fillMaxWidth(),
                label = { Text("Name shown on other devices") },
                singleLine = true,
            )

            Button(
                onClick = {
                    repository.updateConfig(edited())
                    onDone()
                },
                enabled = url.isNotBlank() && token.isNotBlank() && name.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save") }
        }
    }
}
