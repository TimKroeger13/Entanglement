package app.entanglement.shared.sync

import app.entanglement.shared.model.CreateDocRequest
import app.entanglement.shared.model.DocContent
import app.entanglement.shared.model.DocSummary
import app.entanglement.shared.model.PlacementRequest
import app.entanglement.shared.model.PushRequest
import app.entanglement.shared.model.RegisterObserverRequest
import app.entanglement.shared.model.RenameRequest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/** Thrown for any non-success answer from the server (except the expected 409 on push). */
class RemoteException(val status: HttpStatusCode, message: String) : Exception("$status: $message")

/**
 * [RemoteApi] over HTTP. The caller supplies the engine (OkHttp on desktop and Android,
 * the test client in server tests), this class adds JSON, the base URL and the token.
 */
class HttpRemoteApi(client: HttpClient, baseUrl: String, token: String) : RemoteApi {

    private val http = client.config {
        expectSuccess = false
        install(ContentNegotiation) { json(JSON) }
        defaultRequest {
            url(baseUrl.trimEnd('/') + "/api/")
            bearerAuth(token)
            contentType(ContentType.Application.Json)
        }
        HttpResponseValidator {
            validateResponse { response ->
                if (!response.status.isSuccess() && response.status != HttpStatusCode.Conflict) {
                    throw RemoteException(response.status, response.bodyAsText().take(200))
                }
            }
        }
    }

    override suspend fun registerObserver(observerId: String, request: RegisterObserverRequest) {
        http.put("observers/$observerId") { setBody(request) }
    }

    override suspend fun list(): List<DocSummary> = http.get("docs").body()

    override suspend fun get(docId: String): DocContent = http.get("docs/$docId").body()

    override suspend fun create(request: CreateDocRequest): DocContent =
        http.post("docs") { setBody(request) }.body()

    override suspend fun push(docId: String, request: PushRequest): PushResult {
        val response: HttpResponse = http.put("docs/$docId") { setBody(request) }
        return if (response.status == HttpStatusCode.Conflict) {
            PushResult.Rejected(response.body())
        } else {
            PushResult.Accepted(response.body())
        }
    }

    override suspend fun setPlacement(docId: String, request: PlacementRequest) {
        http.put("docs/$docId/placement") { setBody(request) }
    }

    override suspend fun decohere(docId: String) {
        http.delete("docs/$docId")
    }

    override suspend fun rename(docId: String, name: String) {
        http.put("docs/$docId/name") { setBody(RenameRequest(name)) }
    }

    override suspend fun resync(docId: String) {
        http.post("docs/$docId/resync")
    }

    companion object {
        val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}

private fun HttpStatusCode.isSuccess() = value in 200..299
