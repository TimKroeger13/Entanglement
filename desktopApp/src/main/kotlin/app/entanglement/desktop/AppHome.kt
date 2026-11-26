package app.entanglement.desktop

import app.entanglement.shared.sync.Base
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.UUID

@Serializable
data class Config(
    val serverUrl: String = "",
    val token: String = "",
    val observerId: String = UUID.randomUUID().toString(),
    val observerName: String = defaultObserverName(),
) {
    val isConfigured get() = serverUrl.isNotBlank() && token.isNotBlank() && observerName.isNotBlank()
}

/** One document entangled on this PC. [base] is the last version this PC and the server agreed on. */
@Serializable
data class LocalDoc(
    val id: String,
    val name: String,
    val path: String,
    val base: Base,
)

@Serializable
data class LocalState(val docs: List<LocalDoc> = emptyList())

/**
 * The app's own folder: `%APPDATA%\Entanglement` (override with ENTANGLEMENT_HOME, e.g. to run a
 * second instance for testing). Holds config.json, state.json and the log.
 */
class AppHome(val dir: Path) {

    init {
        Files.createDirectories(dir)
    }

    private val configFile = dir.resolve("config.json")
    private val stateFile = dir.resolve("state.json")
    val logFile: Path = dir.resolve("entanglement.log")
    val instanceFile: Path = dir.resolve("instance")

    fun loadConfig(): Config = read(configFile) ?: Config().also(::saveConfig)
    fun saveConfig(config: Config) = write(configFile, JSON.encodeToString(Config.serializer(), config))

    fun loadState(): LocalState = read(stateFile) ?: LocalState()
    fun saveState(state: LocalState) = write(stateFile, JSON.encodeToString(LocalState.serializer(), state))

    private inline fun <reified T> read(file: Path): T? =
        if (Files.exists(file)) JSON.decodeFromString<T>(Files.readString(file)) else null

    private fun write(file: Path, text: String) {
        val tmp = Files.createTempFile(dir, ".tmp-", ".json")
        Files.writeString(tmp, text)
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    companion object {
        val JSON = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

        fun default(): AppHome {
            System.getenv("ENTANGLEMENT_HOME")?.takeIf { it.isNotBlank() }?.let { return AppHome(Paths.get(it)) }
            val appData = System.getenv("APPDATA") ?: System.getProperty("user.home")
            return AppHome(Paths.get(appData, "Entanglement"))
        }
    }
}

private fun defaultObserverName(): String =
    runCatching { InetAddress.getLocalHost().hostName }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: System.getenv("COMPUTERNAME")
        ?: "Desktop"
