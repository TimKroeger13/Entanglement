package app.entanglement.server

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.io.File
import kotlin.system.exitProcess

/**
 * Configuration via environment:
 * - `ENTANGLEMENT_TOKEN` (required, at least 24 characters): shared secret of all observers
 * - `ENTANGLEMENT_DB` (default `./data/entanglement.db`): SQLite file
 * - `PORT` (default 8080)
 */
fun main() {
    val token = System.getenv("ENTANGLEMENT_TOKEN").orEmpty()
    if (token.length < 24) {
        System.err.println("ENTANGLEMENT_TOKEN must be set and at least 24 characters long.")
        exitProcess(1)
    }
    val dbFile = File(System.getenv("ENTANGLEMENT_DB") ?: "data/entanglement.db")
    dbFile.absoluteFile.parentFile.mkdirs()
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080

    val store = Store("jdbc:sqlite:${dbFile.absolutePath}")
    Runtime.getRuntime().addShutdownHook(Thread { store.close() })

    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        entanglement(store, token)
    }.start(wait = true)
}
