package app.entanglement.shared.sync

import app.entanglement.shared.model.CreateDocRequest
import app.entanglement.shared.model.DocContent
import app.entanglement.shared.model.DocSummary
import app.entanglement.shared.model.PlacementRequest
import app.entanglement.shared.model.PushRequest
import app.entanglement.shared.model.RegisterObserverRequest

/** Minimal in-memory server with the same versioning rules as the real one. */
class InMemoryRemote(private val observerNames: Map<String, String>) : RemoteApi {
    val docs = mutableMapOf<String, DocContent>()
    var pushCount = 0

    /** Hook to simulate another observer pushing right before our push arrives. */
    var beforePush: (() -> Unit)? = null

    override suspend fun registerObserver(observerId: String, request: RegisterObserverRequest) = Unit

    override suspend fun list(): List<DocSummary> = docs.values.map {
        DocSummary(it.id, it.name, it.version, it.updatedAt, it.updatedByName, false, emptyList())
    }

    override suspend fun get(docId: String): DocContent = docs.getValue(docId)

    override suspend fun create(request: CreateDocRequest): DocContent {
        val doc = DocContent(request.id, request.name, 1, request.content, 0, name(request.observerId))
        docs[request.id] = doc
        return doc
    }

    override suspend fun push(docId: String, request: PushRequest): PushResult {
        beforePush?.also { beforePush = null }?.invoke()
        pushCount++
        val current = docs.getValue(docId)
        if (request.baseVersion != current.version) return PushResult.Rejected(current)
        val next = current.copy(
            version = current.version + 1,
            content = request.content,
            updatedByName = name(request.observerId),
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
        )
    }

    override suspend fun setPlacement(docId: String, request: PlacementRequest) = Unit
    override suspend fun decohere(docId: String) = Unit

    private fun name(observerId: String) = observerNames[observerId] ?: observerId
}

class FakeReplica(var text: String?) : Replica {
    var writes = 0
    override suspend fun read(): String? = text
    override suspend fun write(content: String) {
        writes++
        text = content
    }
}
