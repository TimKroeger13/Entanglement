package app.entanglement.server

import app.entanglement.shared.model.CreateDocRequest
import app.entanglement.shared.model.PlacementRequest
import app.entanglement.shared.model.PushRequest
import app.entanglement.shared.model.RegisterObserverRequest
import app.entanglement.shared.sync.HttpRemoteApi
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import org.slf4j.event.Level
import java.security.MessageDigest

/** Upper bound for one document. Storyboards are text; this is plenty and protects the small server. */
const val MAX_CONTENT_CHARS = 5_000_000
private const val MAX_NAME_CHARS = 255
private val ID_PATTERN = Regex("^[A-Za-z0-9_-]{1,64}$")

fun Application.entanglement(store: Store, token: String) {
    install(ContentNegotiation) { json(HttpRemoteApi.JSON) }
    install(CallLogging) {
        level = Level.INFO
        filter { it.request.local.uri != "/health" }
    }
    install(StatusPages) {
        exception<NotFoundException> { call, e -> call.respondText(e.message.orEmpty(), status = HttpStatusCode.NotFound) }
        exception<GoneException> { call, e -> call.respondText(e.message.orEmpty(), status = HttpStatusCode.Gone) }
        exception<IllegalArgumentException> { call, e ->
            call.respondText(e.message.orEmpty(), status = HttpStatusCode.BadRequest)
        }
        exception<SerializationException> { call, _ ->
            call.respondText("Malformed request body", status = HttpStatusCode.BadRequest)
        }
    }
    install(Authentication) {
        bearer("token") {
            authenticate { credential ->
                if (constantTimeEquals(credential.token, token)) UserIdPrincipal("owner") else null
            }
        }
    }

    routing {
        get("/health") { call.respondText("ok") }

        authenticate("token") {
            route("/api") {
                put("/observers/{id}") {
                    val id = call.id()
                    val body = call.receive<RegisterObserverRequest>()
                    require(body.name.isNotBlank() && body.name.length <= MAX_NAME_CHARS) { "Invalid observer name" }
                    db { store.registerObserver(id, body) }
                    call.respond(HttpStatusCode.NoContent)
                }
                docRoutes(store)
            }
        }
    }
}

private fun Route.docRoutes(store: Store) {
    get("/docs") { call.respond(db { store.list() }) }

    post("/docs") {
        val body = call.receive<CreateDocRequest>()
        require(ID_PATTERN.matches(body.id)) { "Invalid document id" }
        require(body.name.isNotBlank() && body.name.length <= MAX_NAME_CHARS) { "Invalid document name" }
        require(body.content.length <= MAX_CONTENT_CHARS) { "Document too large" }
        call.respond(HttpStatusCode.Created, db { store.create(body) })
    }

    get("/docs/{id}") { call.respond(db { store.get(call.id()) }) }

    put("/docs/{id}") {
        val id = call.id()
        val body = call.receive<PushRequest>()
        require(body.content.length <= MAX_CONTENT_CHARS) { "Document too large" }
        when (val result = db { store.push(id, body) }) {
            is PushOutcome.Accepted -> call.respond(result.doc)
            is PushOutcome.Stale -> call.respond(HttpStatusCode.Conflict, result.current)
        }
    }

    put("/docs/{id}/placement") {
        val id = call.id()
        val body = call.receive<PlacementRequest>()
        require((body.path?.length ?: 0) <= 4096) { "Path too long" }
        db { store.setPlacement(id, body.observerId, body.path) }
        call.respond(HttpStatusCode.NoContent)
    }

    delete("/docs/{id}") {
        db { store.decohere(call.id()) }
        call.respond(HttpStatusCode.NoContent)
    }
}

private fun RoutingCall.id(): String {
    val id = parameters["id"].orEmpty()
    require(ID_PATTERN.matches(id)) { "Invalid id" }
    return id
}

private suspend fun <T> db(block: () -> T): T = withContext(Dispatchers.IO) { block() }

private fun constantTimeEquals(a: String, b: String): Boolean =
    MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
