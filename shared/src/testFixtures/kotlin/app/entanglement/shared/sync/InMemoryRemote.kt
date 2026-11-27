package app.entanglement.shared.sync

import app.entanglement.shared.model.CreateDocRequest
import app.entanglement.shared.model.DocContent
import app.entanglement.shared.model.DocSummary
import app.entanglement.shared.model.Placement
import app.entanglement.shared.model.Platform
import app.entanglement.shared.model.PlacementRequest
import app.entanglement.shared.model.PushRequest
import app.entanglement.shared.model.RegisterObserverRequest
import io.ktor.http.HttpStatusCode

/** In-memory server with the same rules as the real one (versions, 409, placements, decoherence). */
class InMemoryRemote(observerNames: Map<String, String> = emptyMap()) : RemoteApi {
    val docs = mutableMapOf<String, DocContent>()
    val placements = mutableMapOf<String, MutableMap<String, String?>>()
    val decohered = mutableSetOf<String>()
    private val observers = observerNames.mapValues { (_, name) -> name to Platform.DESKTOP }.toMutableMap()
    var pushCount = 0

    /** Hook to simulate another observer pushing right before our push arrives. */
    var beforePush: (() -> Unit)? = null

    /** When true every call fails like a dead network (train tunnel). */
    var offline = false

    override suspend fun registerObserver(observerId: String, request: RegisterObserverRequest) {
        net()
        observers[observerId] = request.name to request.platform
    }

    override suspend fun list(): List<DocSummary> {
        net()
        return docs.values.map { doc ->
            DocSummary(
                id = doc.id,
                name = doc.name,
                version = doc.version,
                updatedAt = doc.updatedAt,
                updatedByName = doc.updatedByName,
                decohered = doc.id in decohered,
                updatedBy = doc.updatedBy,
                placements = placements[doc.id].orEmpty().map { (obs, path) ->
                    val (name, platform) = observers[obs] ?: (obs to Platform.DESKTOP)
                    Placement(obs, name, platform, path)
                },
            )
        }
    }

    override suspend fun get(docId: String): DocContent {
        net()
        return docs[docId] ?: throw RemoteException(HttpStatusCode.NotFound, "No document $docId")
    }

    override suspend fun create(request: CreateDocRequest): DocContent {
        net()
        docs[request.id]?.let { return it }
        val doc = DocContent(request.id, request.name, 1, request.content, 0, name(request.observerId), request.observerId)
        docs[request.id] = doc
        placements.getOrPut(request.id) { mutableMapOf() }[request.observerId] = request.path
        return doc
    }

    override suspend fun push(docId: String, request: PushRequest): PushResult {
        beforePush?.also { beforePush = null }?.invoke()
        net()
        if (docId in decohered) throw RemoteException(HttpStatusCode.Gone, "decohered")
        pushCount++
        val current = get(docId)
        if (request.baseVersion != current.version) return PushResult.Rejected(current)
        if (request.content == current.content) return PushResult.Accepted(current)
        val next = current.copy(
            version = current.version + 1,
            content = request.content,
            updatedByName = name(request.observerId),
            updatedBy = request.observerId,
        )
        docs[docId] = next
        return PushResult.Accepted(next)
    }

    /** Simulates an edit from another observer directly on the server. */
    fun editAs(observerId: String, docId: String, content: String) {
        val current = docs.getValue(docId)
        docs[docId] = current.copy(
            version = current.version + 1,
            content = content,
            updatedByName = name(observerId),
            updatedBy = observerId,
        )
    }

    override suspend fun setPlacement(docId: String, request: PlacementRequest) {
        net()
        get(docId)
        placements.getOrPut(docId) { mutableMapOf() }[request.observerId] = request.path
    }

    override suspend fun decohere(docId: String) {
        net()
        get(docId)
        decohered += docId
    }

    override suspend fun rename(docId: String, name: String) {
        net()
        if (docId in decohered) throw RemoteException(HttpStatusCode.Gone, "decohered")
        docs[docId] = get(docId).copy(name = name)
    }

    override suspend fun resync(docId: String) {
        net()
        get(docId)
        decohered -= docId
    }

    private fun net() {
        if (offline) throw java.io.IOException("offline")
    }

    private fun name(observerId: String) = observers[observerId]?.first ?: observerId
}

class FakeReplica(var text: String?) : Replica {
    var writes = 0
    override suspend fun read(): String? = text
    override suspend fun write(content: String) {
        writes++
        text = content
    }
}
