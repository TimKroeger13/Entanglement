package app.entanglement.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Tiny file log in the app folder, trimmed when it grows beyond 1 MB. */
object Log {
    private var file: Path? = null
    private val time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun init(path: Path) {
        file = path
        runCatching { if (Files.size(path) > 1_000_000) Files.delete(path) }
    }

    fun info(message: String) = write("INFO", message)
    fun warn(message: String) = write("WARN", message)
    fun error(message: String, e: Throwable? = null) =
        write("ERROR", message + (e?.let { ": ${it::class.simpleName}: ${it.message}" } ?: ""))

    @Synchronized
    private fun write(level: String, message: String) {
        val line = "${LocalDateTime.now().format(time)} $level $message"
        System.err.println(line)
        file?.let {
            runCatching {
                Files.writeString(it, line + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.APPEND)
            }
        }
    }
}
