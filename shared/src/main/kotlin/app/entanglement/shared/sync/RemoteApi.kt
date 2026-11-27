package app.entanglement.shared.sync

import app.entanglement.shared.model.CreateDocRequest
import app.entanglement.shared.model.DocContent
import app.entanglement.shared.model.DocSummary
import app.entanglement.shared.model.PlacementRequest
import app.entanglement.shared.model.PushRequest
import app.entanglement.shared.model.RegisterObserverRequest

/** The Entanglement server as seen by a client. Implemented over HTTP, and in memory for tests. */
interface RemoteApi {
    suspend fun registerObserver(observerId: String, request: RegisterObserverRequest)
    suspend fun list(): List<DocSummary>
    suspend fun get(docId: String): DocContent
    suspend fun create(request: CreateDocRequest): DocContent
    suspend fun push(docId: String, request: PushRequest): PushResult
    suspend fun setPlacement(docId: String, request: PlacementRequest)
    suspend fun decohere(docId: String)

    /** Changes the display name everywhere (each PC keeps its own file name). */
    suspend fun rename(docId: String, name: String)

    /** Undoes [decohere]: the document is entangled again and every observer merges its copy back in. */
    suspend fun resync(docId: String)
}

sealed interface PushResult {
    /** The server stored the text as a new version. */
    data class Accepted(val doc: DocContent) : PushResult

    /** Someone else pushed first; [current] is the server's newer state. */
    data class Rejected(val current: DocContent) : PushResult
}
