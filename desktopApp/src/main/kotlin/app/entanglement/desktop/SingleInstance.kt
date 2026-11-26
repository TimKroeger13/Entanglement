package app.entanglement.desktop

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.concurrent.thread

/**
 * Makes sure only one Entanglement runs. A second start (e.g. from the Explorer context menu)
 * hands its request to the running instance over a loopback socket and exits.
 * The port and a random secret are stored in [file] inside the user's app folder.
 */
class SingleInstance(private val file: Path) {

    /** Returns true if a running instance accepted [message]. */
    fun sendToRunning(message: List<String>): Boolean {
        val lines = runCatching { Files.readAllLines(file) }.getOrNull() ?: return false
        val port = lines.getOrNull(0)?.toIntOrNull() ?: return false
        val secret = lines.getOrNull(1) ?: return false
        return runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 1_000)
                socket.soTimeout = 3_000
                val out = socket.getOutputStream().bufferedWriter(Charsets.UTF_8)
                (listOf(secret) + message).forEach { out.write(it); out.newLine() }
                out.flush()
                socket.shutdownOutput()
                socket.getInputStream().bufferedReader(Charsets.UTF_8).readLine() == "ok"
            }
        }.onFailure { Log.warn("No running instance reachable: ${it::class.simpleName}: ${it.message}") }
            .getOrDefault(false)
    }

    /** Becomes the running instance; [onMessage] is called on a background thread. */
    fun listen(onMessage: (List<String>) -> Unit) {
        val secret = UUID.randomUUID().toString()
        val server = ServerSocket(0, 10, InetAddress.getLoopbackAddress())
        Files.writeString(file, "${server.localPort}\n$secret\n")
        thread(name = "single-instance", isDaemon = true) {
            while (true) {
                val socket = runCatching { server.accept() }.getOrNull() ?: return@thread
                socket.use {
                    runCatching {
                        it.soTimeout = 3_000
                        // Not readLines(): it closes the stream, and closing a socket stream closes the socket.
                        val reader = it.getInputStream().bufferedReader(Charsets.UTF_8)
                        val lines = generateSequence { reader.readLine() }.toList()
                        if (lines.firstOrNull() == secret) {
                            it.getOutputStream().write("ok\n".toByteArray())
                            onMessage(lines.drop(1))
                        }
                    }.onFailure { e -> Log.warn("Instance message failed: ${e::class.simpleName}: ${e.message}") }
                }
            }
        }
    }
}
