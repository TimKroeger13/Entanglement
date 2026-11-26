package app.entanglement.desktop

import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.WatchKey
import java.nio.file.WatchService
import kotlin.concurrent.thread

/**
 * Watches the folders of the entangled files and reports when one of those files changes,
 * e.g. because it was saved in VS Code or Obsidian.
 */
class FileWatcher(private val onChange: (Path) -> Unit) : AutoCloseable {

    private val service: WatchService = FileSystems.getDefault().newWatchService()
    private val keys = mutableMapOf<Path, WatchKey>()

    @Volatile
    private var files: Set<Path> = emptySet()

    private val worker = thread(name = "file-watcher", isDaemon = true) {
        while (true) {
            val key = try {
                service.take()
            } catch (_: Exception) {
                return@thread
            }
            val dir = key.watchable() as Path
            for (event in key.pollEvents()) {
                val name = event.context() as? Path ?: continue
                val file = dir.resolve(name).toAbsolutePath().normalize()
                if (file in files) onChange(file)
            }
            key.reset()
        }
    }

    /** Sets the files to watch; folders are (un)registered as needed. */
    @Synchronized
    fun watch(paths: Collection<Path>) {
        val normalized = paths.map { it.toAbsolutePath().normalize() }.toSet()
        files = normalized
        val dirs = normalized.mapNotNull { it.parent }.toSet()
        (keys.keys - dirs).forEach { keys.remove(it)?.cancel() }
        for (dir in dirs - keys.keys) {
            runCatching { dir.register(service, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE) }
                .onSuccess { keys[dir] = it }
                .onFailure { Log.warn("Cannot watch $dir: ${it.message}") }
        }
    }

    override fun close() {
        service.close()
        worker.interrupt()
    }
}
