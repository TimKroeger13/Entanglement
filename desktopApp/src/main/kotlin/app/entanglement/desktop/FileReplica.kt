package app.entanglement.desktop

import app.entanglement.shared.sync.Replica
import app.entanglement.shared.text.LineEnding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.AccessDeniedException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

class NotUtf8Exception(path: Path) : IOException("$path is not UTF-8 text")

/** The file was saved by the user between our read and our write; the next sync run picks it up. */
class ChangedDuringSyncException(path: Path) : IOException("$path changed during sync")

/**
 * An entangled file on this PC. Keeps the file's line endings (CRLF/LF) and a UTF-8 BOM if it had one,
 * and replaces it atomically so an editor never sees a half-written file.
 * A write never overwrites a save that happened after this replica last read the file.
 */
class FileReplica(private val path: Path) : Replica {

    private var lastRead: ByteArray? = null

    override suspend fun read(): String? = withContext(Dispatchers.IO) {
        if (!Files.isRegularFile(path)) return@withContext null
        val bytes = Files.readAllBytes(path)
        lastRead = bytes
        decode(bytes, path)
    }

    override suspend fun write(content: String) = withContext(Dispatchers.IO) {
        val existing = if (Files.isRegularFile(path)) Files.readAllBytes(path) else null
        val seen = lastRead
        if (seen != null && (existing == null || !existing.contentEquals(seen))) throw ChangedDuringSyncException(path)
        val hadBom = existing != null && hasBom(existing)
        val ending = existing?.let { LineEnding.detect(String(it, Charsets.UTF_8)) } ?: LineEnding.LF

        val bytes = (if (hadBom) BOM else ByteArray(0)) + ending.apply(content).toByteArray(Charsets.UTF_8)
        Files.createDirectories(path.toAbsolutePath().parent)
        val tmp = Files.createTempFile(path.toAbsolutePath().parent, ".entanglement-", ".tmp")
        try {
            Files.write(tmp, bytes)
            moveWithRetry(tmp, path)
            lastRead = bytes
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    /** Windows briefly locks files that an editor or virus scanner is reading; try a few times. */
    private suspend fun moveWithRetry(from: Path, to: Path) {
        var attempt = 0
        while (true) {
            try {
                Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                return
            } catch (e: FileSystemException) {
                if (e !is AccessDeniedException && e.reason?.contains("used by another process") != true) throw e
                if (++attempt >= 10) throw e
                delay(200)
            }
        }
    }

    companion object {
        private val BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

        private fun hasBom(bytes: ByteArray) =
            bytes.size >= 3 && bytes[0] == BOM[0] && bytes[1] == BOM[1] && bytes[2] == BOM[2]

        /** Strict UTF-8: a file in another encoding is refused instead of silently damaging umlauts. */
        fun decode(bytes: ByteArray, path: Path): String {
            val offset = if (hasBom(bytes)) 3 else 0
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            return try {
                decoder.decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset)).toString()
            } catch (_: CharacterCodingException) {
                throw NotUtf8Exception(path)
            }
        }
    }
}
