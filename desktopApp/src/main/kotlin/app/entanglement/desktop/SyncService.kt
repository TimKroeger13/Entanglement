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

enum class DocStatus { ENTANGLED, CHANGES_WAITING, SUPERPOSITION, LOST, UNOBSERVED, ERROR, DESYNCED }

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

        // Re-synced (here or on another observer): take back documents this PC had before the desync.
        for (kept in local.desynced.toList()) {
            val summary = list.find { it.id == kept.id } ?: continue
            if (summary.decohered || local.docs.any { it.id == kept.id || it.path == kept.path }) continue
            if (!Files.isRegularFile(Paths.get(kept.path))) continue
            Log.info("${kept.name} was re-synced; joining again")
            saveLocal(local.copy(docs = local.docs + kept, desynced = local.desynced - kept))
        }

        val engine = SyncEngine(api, observer(cfg))
        for (doc in local.docs.toList()) {
            val summary = list.find { it.id == doc.id }
            when {
                summary == null -> messages[doc.id] = "Not found on the server"
                summary.decohered -> {
                    Log.info("${doc.name} was desynced elsewhere; keeping the local file")
                    desyncLocal(doc.id)
                }
                else -> syncOne(api, engine, cfg, doc, summary)
            }
        }
        lastSync = clock()
        publish()
    }

    private suspend fun syncOne(api: RemoteApi, engine: SyncEngine, cfg: Config, initial: LocalDoc, summary: DocSummary) {
        var doc = initial
        try {
            // Renamed or moved within its folder (Explorer, VS Code ...): follow it instead of reporting it lost.
            if (!Files.isRegularFile(Paths.get(doc.path))) {
                findMoved(doc)?.let { moved ->
                    val renamed = moved.fileName.toString() != Paths.get(doc.path).fileName.toString()
                    Log.info("${doc.name} was renamed/moved to $moved; following it")
                    doc = doc.copy(
                        path = moved.toString(),
                        name = if (renamed) moved.fileName.toString() else doc.name,
                        renamePending = doc.renamePending || renamed,
                    )
                    updateLocal(doc)
                }
            }
            if (doc.renamePending) {
                api.rename(doc.id, doc.name)
                doc = doc.copy(renamePending = false)
                updateLocal(doc)
            } else if (summary.name != doc.name) {
                doc = followRename(doc, summary.name)
                updateLocal(doc)
            }
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

    /** Desync: stops syncing the document on all observers. Every copy stays; [resync] undoes it. */
    suspend fun decohere(docId: String) = mutex.withLock {
        val cfg = requireOnline()
        api(cfg).decohere(docId)
        desyncLocal(docId)
        remote = remote?.map { if (it.id == docId) it.copy(decohered = true) else it }
        publish()
    }

    /** Re-sync a desynced document. Copies that changed in the meantime are merged, nothing is overwritten. */
    suspend fun resync(docId: String) = mutex.withLock {
        val cfg = requireOnline()
        api(cfg).resync(docId)
        val summary = remote?.find { it.id == docId }
        val kept = local.desynced.find { it.id == docId }
        val knownPath = kept?.path ?: summary?.placements?.find { it.observerId == cfg.observerId }?.path
        if (knownPath != null && Files.isRegularFile(Paths.get(knownPath)) && local.docs.none { it.path == knownPath }) {
            // With the old common base a real three-way merge happens; without it, both versions are kept.
            val doc = kept?.copy(path = knownPath) ?: LocalDoc(docId, summary?.name ?: "", knownPath, Base(0, ""))
            saveLocal(local.copy(docs = local.docs + doc, desynced = local.desynced.filterNot { it.id == docId }))
        }
        remote = remote?.map { if (it.id == docId) it.copy(decohered = false) else it }
        publish()
        requestSync()
    }

    /** Renames the file on this PC and the document everywhere. Works offline; the new name follows later. */
    suspend fun rename(docId: String, newName: String) = mutex.withLock {
        val doc = local.docs.find { it.id == docId } ?: throw UserError("Unknown document")
        val from = Paths.get(doc.path)
        val name = cleanFileName(newName, from)
        val to = from.resolveSibling(name)
        if (to != from) {
            if (Files.exists(to)) throw UserError("$name already exists in this folder")
            if (Files.isRegularFile(from)) Files.move(from, to)
        }
        var updated = doc.copy(name = name, path = to.toString(), renamePending = true)
        updateLocal(updated)
        runCatching { api(requireOnline()).rename(docId, name) }.onSuccess {
            updated = updated.copy(renamePending = false)
            updateLocal(updated)
        }
        publish()
        requestSync()
    }

    /** Moves the file on this PC to [target] (another folder and/or name). */
    suspend fun move(docId: String, target: Path) = mutex.withLock {
        val doc = local.docs.find { it.id == docId } ?: throw UserError("Unknown document")
        val from = Paths.get(doc.path)
        val to = target.toAbsolutePath().normalize()
        if (to == from) return@withLock
        if (Files.exists(to)) throw UserError("A file already exists at $to")
        Files.createDirectories(to.parent)
        Files.move(from, to)
        val renamed = to.fileName.toString() != from.fileName.toString()
        updateLocal(
            doc.copy(
                path = to.toString(),
                name = if (renamed) to.fileName.toString() else doc.name,
                renamePending = doc.renamePending || renamed,
            ),
        )
        publish()
        requestSync()
    }

    /** A lost file's folder may hold it under a new name: same content as the last synced version. */
    private fun findMoved(doc: LocalDoc): Path? {
        val old = Paths.get(doc.path)
        val dir = old.parent ?: return null
        if (!Files.isDirectory(dir)) return null
        val entangled = local.docs.map { Paths.get(it.path) }.toSet()
        val candidates = runCatching {
            Files.list(dir).use { files ->
                files.filter { Files.isRegularFile(it) && it !in entangled && Files.size(it) < 16_000_000 }.toList()
            }
        }.getOrDefault(emptyList())
        return candidates.filter { candidate ->
            runCatching {
                LineEnding.normalize(FileReplica.decode(Files.readAllBytes(candidate), candidate)) == doc.base.content
            }.getOrDefault(false)
        }.singleOrNull()
    }

    /** Renamed on another observer: rename the file here too, if it still carries the old name. */
    private fun followRename(doc: LocalDoc, newName: String): LocalDoc {
        val path = Paths.get(doc.path)
        if (path.fileName.toString() != doc.name || !Files.isRegularFile(path)) return doc.copy(name = newName)
        val target = runCatching { path.resolveSibling(newName) }.getOrNull()
        if (target == null || Files.exists(target)) return doc.copy(name = newName)
        return runCatching {
            Files.move(path, target)
            Log.info("Renamed ${doc.name} to $newName (renamed elsewhere)")
            doc.copy(name = newName, path = target.toString())
        }.getOrElse { doc.copy(name = newName) }
    }

    private fun cleanFileName(input: String, current: Path): String {
        val name = input.trim()
        if (name.isEmpty()) throw UserError("Please enter a name.")
        if (name.any { it in FORBIDDEN }) throw UserError("A file name cannot contain $FORBIDDEN")
        if (name.contains('.')) return name
        val ext = current.fileName.toString().substringAfterLast('.', "")
        return if (ext.isEmpty()) name else "$name.$ext"
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

    /** Leaves sync but keeps the entry (path and last common base) for a later re-sync. */
    private fun desyncLocal(id: String) {
        messages.remove(id)
        val doc = local.docs.find { it.id == id } ?: return
        saveLocal(local.copy(docs = local.docs - doc, desynced = local.desynced.filterNot { it.id == id } + doc))
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
                .filter { it.decohered && local.docs.none { d -> d.id == it.id } }
                .map { summary ->
                    DocView(
                        id = summary.id,
                        name = summary.name,
                        status = DocStatus.DESYNCED,
                        localPath = local.desynced.find { it.id == summary.id }?.path,
                        superpositions = 0,
                        observers = summary.placements.map {
                            ObserverView(it.observerName, it.platform, it.path, it.observerId == cfg.observerId)
                        },
                        updatedByName = summary.updatedByName,
                        updatedAt = summary.updatedAt,
                        message = null,
                    )
                } +
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

    private companion object {
        const val FORBIDDEN = "\\/:*?\"<>|"
    }

    override fun close() {
        loop?.cancel()
        watcher?.close()
    }
}
