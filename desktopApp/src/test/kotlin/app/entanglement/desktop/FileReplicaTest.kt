package app.entanglement.desktop

import kotlinx.coroutines.test.runTest
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class FileReplicaTest {

    private val dir: Path = createTempDirectory("replica-test")

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `keeps crlf and bom when writing`() = runTest {
        val file = dir.resolve("a.md")
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        file.writeBytes(bom + "one\r\ntwo\r\n".toByteArray())
        val replica = FileReplica(file)
        assertEquals("one\r\ntwo\r\n", replica.read())
        replica.write("one\ntwo\nthree\n")
        assertContentEquals(bom + "one\r\ntwo\r\nthree\r\n".toByteArray(), file.readBytes())
    }

    @Test
    fun `missing file reads as null and is created on write`() = runTest {
        val file = dir.resolve("sub/new.md")
        val replica = FileReplica(file)
        assertNull(replica.read())
        replica.write("hello\n")
        assertEquals("hello\n", file.readText())
    }

    @Test
    fun `never overwrites a save made after reading`() = runTest {
        val file = dir.resolve("a.md")
        file.writeText("original\n")
        val replica = FileReplica(file)
        replica.read()
        file.writeText("saved by the editor meanwhile\n")
        assertFailsWith<ChangedDuringSyncException> { replica.write("merged\n") }
        assertEquals("saved by the editor meanwhile\n", file.readText())
    }

    @Test
    fun `no temp files are left behind`() = runTest {
        val file = dir.resolve("a.md")
        FileReplica(file).write("x\n")
        assertEquals(listOf("a.md"), dir.toFile().list()!!.toList())
    }
}
