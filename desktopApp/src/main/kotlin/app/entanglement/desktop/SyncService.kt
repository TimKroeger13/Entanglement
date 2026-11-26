package app.entanglement.desktop

import app.entanglement.shared.merge.Superposition
import app.entanglement.shared.model.DocSummary
import app.entanglement.shared.model.Observer
import app.entanglement.shared.model.PlacementRequest
import app.entanglement.shared.model.Platform
import app.entanglement.shared.model.RegisterObserverRequest
import app.entanglement.shared.sync.Base
import app.entanglement.shared.sync.Outcome
import app.entanglement.shared.sync.RemoteApi
import app.entanglement.shared.sync.RemoteException
import app.entanglement.shared.sync.SyncEngine
import app.entanglement.shared.text.LineEnding
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID

enum class Connection { NOT_CONFIGURED, CONNECTING, ONLINE, OFFLINE, UNAUTHORIZED }

enum class DocStatus { ENTANGLED, CHANGES_WAITING, SUPERPOSITION, LOST, UNOBSERVED, ERROR }

data class ObserverView(val name: String, val platform: Platform, val path: String?, val isThis: Boolean)

data class DocView(
    val id: String,
    val name: String,
    val status: DocStatus,
    val localPath: String?,
    val superpositions: Int,
    val observers: List<ObserverView>,
    val updatedByName: String?,
    val updatedAt: Long?,
    val message: String?,
)

data class UiState(
    val connection: Connection = Connection.CONNECTING,
    val connectionMessage: String? = null,
    val docs: List<DocView> = emptyList(),
    val lastSync: Long? = null,
)

/** Something the user should be told about, e.g. "This file is already entangled". */
class UserError(message: String) : Exception(message)

/**
 * Everything the desktop app does, without any UI: keeps the entangled files of this PC in sync,
 * every [interval] and shortly after a file was saved.
 */
class SyncService(
    private val home: AppHome,
    private val apiFactory: (Config) -> RemoteApi,
    private val scope: CoroutineScope,
    private val interval: Long = 30_000,
    private val saveDebounce: Long = 1_500,
    private val clock: () -> Long = System::currentTimeMillis,
) : AutoCloseable {

    private val mutex = Mutex()
    private val trigger = Channel<Unit>(Channel.CONFLATED)

    private val _config = MutableStateFlow(home.loadConfig())
    val config: StateFlow<Config> = _config.asStateFlow()

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    /** Called when a merge produced new superpositions: (document name, count). */
    var onSuperposition: (String, Int) -> Unit = { _, _ -> }

    /** Called with all entangled paths of this PC whenever that set changes (Explorer overlay). */
    var onEntangledPaths: (List<Path>) -> Unit = {}

    private var local = home.loadState()
    private var remote: List<DocSummary>? = null
    private var connection = Connection.CONNECTING
    private var connectionMessage: String? = null
    private var lastSync: Long? = null
    private val messages = mutableMapOf<String, String>()
    private var registeredAs: Config? = null
    private var cachedApi: Pair<Config, RemoteApi>? = null

    private var watcher: FileWatcher? = null
    private var loop: Job? = null

    fun start() {
        watcher = FileWatcher { scope.launch { delay(saveDebounce); requestSync() } }
        updateWatcher()
        loop = scope.launch {
            requestSync()
            while (isActive) {
                withTimeoutOrNull(interval) { trigger.receive() }
                runCatching { syncNow() }.onFailure { Log.error("Sync failed", it) }
            }
        }
    }

    fun requestSync() {
        trigger.trySend(Unit)
    }

    fun updateConfig(newConfig: Config) {
        home.saveConfig(newConfig)
        _config.value = newConfig
        requestSync()
    }

    /** One full round: register, list, sync every local document, publish the new UI state. */
    suspend fun syncNow() = mutex.withLock {
        val cfg = _config.value
        if (!cfg.isConfigured) {
            connection = Connection.NOT_CONFIGURED
            return@withLock publish()
        }
        val api = api(cfg)
        val list = try {
            ensureRegistered(api, cfg)
            api.list()
        } catch (e: Exception) {
            setOffline(e)
            return@withLock publish()
        }
        remote = list
        connection = Connection.ONLINE
        connectionMessage = null

        val engine = SyncEngine(api, observer(cfg))
        for (doc in local.docs.toList()) {
            val summary = list.find { it.id == doc.id }
            when {
                summary == null -> messages[doc.id] = "Not found on the server"
                summary.decohered -> {
                    Log.info("${doc.name} was decohered elsewhere; keeping the local file")
                    removeLocal(doc.id)
                }
                else -> syncOne(api, engine, cfg, doc, summary)
            }
        }
        lastSync = clock()
        publish()
    }

    private suspend fun syncOne(api: RemoteApi, engine: SyncEngine, cfg: Config, doc: LocalDoc, summary: DocSummary) {
        try {
            if (summary.placements.find { it.observerId == cfg.observerId }?.path != doc.path) {
                api.setPlacement(doc.id, PlacementRequest(cfg.observerId, doc.path))
            }
            val result = engine.sync(doc.id, summary.version, doc.base, FileReplica(Paths.get(doc.path)))
            if (result.base != doc.base) updateLocal(doc.copy(base = result.base))
            messages.remove(doc.id)
            val outcome = result.outcome
            if (outcome != Outcome.UpToDate) Log.info("${doc.name}: $outcome (v${result.base.version})")
            if (outcome is Outcome.Merged && outcome.superpositions > 0) onSuperposition(doc.name, outcome.superpositions)
        } catch (e: NotUtf8Exception) {
            messages[doc.id] = "Not UTF-8 text. Save the file as UTF-8."
        } catch (e: ChangedDuringSyncException) {
            requestSync()
        } catch (e: RemoteException) {
            messages[doc.id] = "Server answered ${e.status.value}"
            Log.error("${doc.name}: server error", e)
        } catch (e: IOException) {
            messages[doc.id] = e.message ?: "I/O error"
            Log.error("${doc.name}: sync failed", e)
        }
    }

    /** Starts syncing a file of this PC. */
    suspend fun entangle(file: Path) = mutex.withLock {
        val path = file.toAbsolutePath().normalize()
        if (!Files.isRegularFile(path)) throw UserError("$path is not a file")
        if (local.docs.any { Paths.get(it.path) == path }) throw UserError("${path.fileName} is already entangled")
        val cfg = requireOnline()
        val text = try {
            FileReplica(path).read() ?: throw UserError("$path cannot be read")
        } catch (e: NotUtf8Exception) {
            throw UserError("${path.fileName} is not UTF-8 text. Save it as UTF-8 first.")
        }
        val api = api(cfg)
        ensureRegistered(api, cfg)
        val id = UUID.randomUUID().toString()
        val base = SyncEngine(api, observer(cfg)).entangle(id, path.fileName.toString(), text, path.toString())
        addLocal(LocalDoc(id, path.fileName.toString(), path.toString(), base))
        Log.info("Entangled $path")
        requestSync()
    }

    /** Places a document from another observer at [target] on this PC. An existing file there is merged, not overwritten. */
    suspend fun place(docId: String, target: Path) = mutex.withLock {
        val path = target.toAbsolutePath().normalize()
        val summary = remote?.find { it.id == docId } ?: throw UserError("Unknown document")
        if (local.docs.any { Paths.get(it.path) == path }) throw UserError("Another entangled file already lives there")
        val cfg = requireOnline()
        if (Files.exists(path)) {
            // Empty base: identical content simply matches, different content ends up in a superposition.
            addLocal(LocalDoc(docId, summary.name, path.toString(), Base(0, "")))
        } else {
            val api = api(cfg)
            val base = SyncEngine(api, observer(cfg)).place(docId, path.toString(), FileReplica(path))
            addLocal(LocalDoc(docId, summary.name, path.toString(), base))
        }
        Log.info("Placed ${summary.name} at $path")
        requestSync()
    }

    /** Points a lost document at its new location. */
    suspend fun relocate(docId: String, newFile: Path) = mutex.withLock {
        val doc = local.docs.find { it.id == docId } ?: throw UserError("Unknown document")
        updateLocal(doc.copy(path = newFile.toAbsolutePath().normalize().toString()))
        requestSync()
    }

    /** Stops syncing the document everywhere. Local files stay. */
    suspend fun decohere(docId: String) = mutex.withLock {
        val cfg = requireOnline()
        api(cfg).decohere(docId)
        removeLocal(docId)
        remote = remote?.map { if (it.id == docId) it.copy(decohered = true) else it }
        publish()
    }

    private fun requireOnline(): Config {
        val cfg = _config.value
        if (!cfg.isConfigured) throw UserError("Set up the server in Settings first.")
        return cfg
    }

    private suspend fun ensureRegistered(api: RemoteApi, cfg: Config) {
        if (registeredAs == cfg) return
        api.registerObserver(cfg.observerId, RegisterObserverRequest(cfg.observerName, Platform.DESKTOP))
        registeredAs = cfg
    }

    private fun api(cfg: Config): RemoteApi {
        cachedApi?.let { (c, api) -> if (c.serverUrl == cfg.serverUrl && c.token == cfg.token) return api }
        return apiFactory(cfg).also { cachedApi = cfg to it }
    }

    private fun observer(cfg: Config) = Observer(cfg.observerId, cfg.observerName, Platform.DESKTOP)

    private fun setOffline(e: Exception) {
        if (e is RemoteException && e.status == HttpStatusCode.Unauthorized) {
            connection = Connection.UNAUTHORIZED
            connectionMessage = "The server rejected the token."
        } else {
            connection = Connection.OFFLINE
            connectionMessage = e.message
            Log.warn("Offline: ${e::class.simpleName}: ${e.message}")
        }
        registeredAs = null
    }

    private fun addLocal(doc: LocalDoc) = saveLocal(local.copy(docs = local.docs + doc))
    private fun updateLocal(doc: LocalDoc) = saveLocal(local.copy(docs = local.docs.map { if (it.id == doc.id) doc else it }))
    private fun removeLocal(id: String) {
        messages.remove(id)
        saveLocal(local.copy(docs = local.docs.filterNot { it.id == id }))
    }

    private fun saveLocal(state: LocalState) {
        local = state
        home.saveState(state)
        updateWatcher()
    }

    private fun updateWatcher() {
        val paths = local.docs.map { Paths.get(it.path) }
        watcher?.watch(paths)
        onEntangledPaths(paths)
    }

    private fun publish() {
        val cfg = _config.value
        val remoteDocs = remote.orEmpty()
        val docs = local.docs.map { doc -> localView(doc, remoteDocs.find { it.id == doc.id }, cfg) } +
            remoteDocs
                .filter { !it.decohered && local.docs.none { d -> d.id == it.id } }
                .map { summary ->
                    DocView(
                        id = summary.id,
                        name = summary.name,
                        status = DocStatus.UNOBSERVED,
                        localPath = null,
                        superpositions = 0,
                        observers = summary.placements.map { ObserverView(it.observerName, it.platform, it.path, false) },
                        updatedByName = summary.updatedByName,
                        updatedAt = summary.updatedAt,
                        message = null,
                    )
                }
        _ui.value = UiState(connection, connectionMessage, docs.sortedBy { it.name.lowercase() }, lastSync)
    }

    private fun localView(doc: LocalDoc, summary: DocSummary?, cfg: Config): DocView {
        val path = Paths.get(doc.path)
        val text = runCatching { if (Files.isRegularFile(path)) FileReplica.decode(Files.readAllBytes(path), path) else null }
            .getOrNull()
        val superpositions = text?.let(Superposition::count) ?: 0
        val message = messages[doc.id]
        val status = when {
            !Files.isRegularFile(path) -> DocStatus.LOST
            message != null -> DocStatus.ERROR
            superpositions > 0 -> DocStatus.SUPERPOSITION
            text != null && LineEnding.normalize(text) != doc.base.content -> DocStatus.CHANGES_WAITING
            else -> DocStatus.ENTANGLED
        }
        val others = summary?.placements.orEmpty()
            .filter { it.observerId != cfg.observerId }
            .map { ObserverView(it.observerName, it.platform, it.path, false) }
        return DocView(
            id = doc.id,
            name = doc.name,
            status = status,
            localPath = doc.path,
            superpositions = superpositions,
            observers = listOf(ObserverView(cfg.observerName, Platform.DESKTOP, doc.path, true)) + others,
            updatedByName = summary?.updatedByName,
            updatedAt = summary?.updatedAt,
            message = message,
        )
    }

    override fun close() {
        loop?.cancel()
        watcher?.close()
    }
}
