package app.entanglement.desktop

import app.entanglement.shared.sync.InMemoryRemote
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SyncServiceTest {

    private val root: Path = createTempDirectory("entanglement-test")
    private val server = InMemoryRemote()

    @AfterTest
    fun cleanup() {
        root.toFile().deleteRecursively()
    }

    private fun TestScope.pc(name: String): SyncService {
        val home = AppHome(root.resolve("home-$name"))
        home.saveConfig(Config("https://example.invalid", "token", "id-$name", name))
        return SyncService(home, { server }, this)
    }

    private fun file(name: String, text: String): Path =
        root.resolve(name).also { Files.createDirectories(it.parent); it.writeText(text) }

    private fun SyncService.doc(name: String = "Storyboard.md") = ui.value.docs.single { it.name == name }

    /** PC1 entangles a file, PC2 places it. Returns both local paths. */
    private suspend fun entangledOnBoth(pc1: SyncService, pc2: SyncService, text: String = "Scene 1\nScene 2\nScene 3\n"): Pair<Path, Path> {
        val a = file("pc1/Storyboard.md", text)
        pc1.entangle(a)
        pc2.syncNow()
        val b = root.resolve("pc2/Texte/Storyboard.md")
        pc2.place(pc2.doc().id, b)
        pc1.syncNow()
        pc2.syncNow()
        return a to b
    }

    @Test
    fun `entangled file shows up unobserved elsewhere and can be placed`() = runTest {
        val pc1 = pc("PC1")
        val pc2 = pc("PC2")
        val a = file("pc1/Storyboard.md", "Hello ⚛ Ümlaut\n")
        pc1.entangle(a)
        pc1.syncNow()
        assertEquals(DocStatus.ENTANGLED, pc1.doc().status)

        pc2.syncNow()
        assertEquals(DocStatus.UNOBSERVED, pc2.doc().status)
        assertEquals(listOf("PC1" to a.toString()), pc2.doc().observers.map { it.name to it.path })

        val b = root.resolve("pc2/Texte/Storyboard.md")
        pc2.place(pc2.doc().id, b)
        pc2.syncNow()
        assertEquals("Hello ⚛ Ümlaut\n", b.readText())
        assertEquals(DocStatus.ENTANGLED, pc2.doc().status)

        pc1.syncNow()
        assertEquals(
            listOf("PC1" to a.toString(), "PC2" to b.toString()),
            pc1.doc().observers.map { it.name to it.path },
        )
    }

    @Test
    fun `saving on one PC reaches the other and keeps its line endings`() = runTest {
        val pc1 = pc("PC1")
        val pc2 = pc("PC2")
        val (a, b) = entangledOnBoth(pc1, pc2)
        b.writeText("Scene 1\r\nScene 2\r\nScene 3\r\n") // PC2 uses CRLF
        pc2.syncNow()
        assertEquals(DocStatus.ENTANGLED, pc2.doc().status)

        a.writeText("Scene 1\nScene 2 rewritten\nScene 3\n")
        pc1.syncNow()
        pc2.syncNow()
        assertEquals("Scene 1\r\nScene 2 rewritten\r\nScene 3\r\n", b.readText())
    }

    @Test
    fun `offline edits on different lines are merged on both PCs`() = runTest {
        val pc1 = pc("PC1")
        val pc2 = pc("PC2")
        val (a, b) = entangledOnBoth(pc1, pc2)

        server.offline = true
        a.writeText("Scene 1 by PC1\nScene 2\nScene 3\n")
        b.writeText("Scene 1\nScene 2\nScene 3 by PC2\n")
        pc1.syncNow()
        assertEquals(Connection.OFFLINE, pc1.ui.value.connection)
        assertEquals(DocStatus.CHANGES_WAITING, pc1.doc().status)

        server.offline = false
        pc1.syncNow()
        pc2.syncNow()
        pc1.syncNow()
        val merged = "Scene 1 by PC1\nScene 2\nScene 3 by PC2\n"
        assertEquals(merged, a.readText())
        assertEquals(merged, b.readText())
        assertEquals(DocStatus.ENTANGLED, pc1.doc().status)
        assertEquals(DocStatus.ENTANGLED, pc2.doc().status)
    }

    @Test
    fun `same line on both PCs becomes a superposition and is reported`() = runTest {
        val pc1 = pc("PC1")
        val pc2 = pc("PC2")
        val (a, b) = entangledOnBoth(pc1, pc2)
        val reported = mutableListOf<Pair<String, Int>>()
        pc2.onSuperposition = { name, n -> reported += name to n }

        a.writeText("Scene 1\nScene 2 by PC1\nScene 3\n")
        b.writeText("Scene 1\nScene 2 by PC2\nScene 3\n")
        pc1.syncNow()
        pc2.syncNow()
        pc1.syncNow()

        assertEquals(listOf("Storyboard.md" to 1), reported)
        for ((pc, path) in listOf(pc1 to a, pc2 to b)) {
            assertEquals(DocStatus.SUPERPOSITION, pc.doc().status)
            assertContains(path.readText(), "Scene 2 by PC1")
            assertContains(path.readText(), "Scene 2 by PC2")
        }

        // Measure: keep one version, the superposition collapses everywhere.
        b.writeText("Scene 1\nScene 2 by PC2\nScene 3\n")
        pc2.syncNow()
        pc1.syncNow()
        assertEquals("Scene 1\nScene 2 by PC2\nScene 3\n", a.readText())
        assertEquals(DocStatus.ENTANGLED, pc1.doc().status)
    }

    @Test
    fun `placing onto an existing file merges instead of overwriting`() = runTest {
        val pc1 = pc("PC1")
        val pc2 = pc("PC2")
        pc1.entangle(file("pc1/Storyboard.md", "Shared text\n"))
        pc2.syncNow()

        val identical = file("pc2/same/Storyboard.md", "Shared text\n")
        pc2.place(pc2.doc().id, identical)
        pc2.syncNow()
        assertEquals("Shared text\n", identical.readText())
        assertEquals(DocStatus.ENTANGLED, pc2.doc().status)

        val pc3 = pc("PC3")
        pc3.syncNow()
        val different = file("pc3/Storyboard.md", "My own older draft\n")
        pc3.place(pc3.doc().id, different)
        pc3.syncNow()
        assertEquals(DocStatus.SUPERPOSITION, pc3.doc().status)
        assertContains(different.readText(), "My own older draft")
        assertContains(different.readText(), "Shared text")
    }

    @Test
    fun `moved file is lost until relocated`() = runTest {
        val pc1 = pc("PC1")
        val a = file("pc1/Storyboard.md", "Text\n")
        pc1.entangle(a)
        pc1.syncNow()

        val moved = root.resolve("pc1/elsewhere/Storyboard.md")
        Files.createDirectories(moved.parent)
        Files.move(a, moved)
        pc1.syncNow()
        assertEquals(DocStatus.LOST, pc1.doc().status)

        pc1.relocate(pc1.doc().id, moved)
        pc1.syncNow()
        assertEquals(DocStatus.ENTANGLED, pc1.doc().status)
        assertEquals(moved.toString(), server.placements.values.single()["id-PC1"])
    }

    @Test
    fun `decohere stops syncing everywhere but keeps the files`() = runTest {
        val pc1 = pc("PC1")
        val pc2 = pc("PC2")
        val (a, b) = entangledOnBoth(pc1, pc2)
        pc1.decohere(pc1.doc().id)
        assertEquals(DocStatus.DESYNCED, pc1.doc().status)

        pc2.syncNow()
        assertEquals(DocStatus.DESYNCED, pc2.doc().status)
        assertTrue(Files.exists(a) && Files.exists(b))
        b.writeText("Only on PC2 now\n")
        pc2.syncNow()
        assertEquals("Scene 1\nScene 2\nScene 3\n", a.readText(), "desynced copies no longer follow each other")
    }

    @Test
    fun `rename here renames the file and the document, other PCs follow`() = runTest {
        val pc1 = pc("PC1")
        val pc2 = pc("PC2")
        val (a, b) = entangledOnBoth(pc1, pc2)
        pc1.rename(pc1.doc().id, "Act One")
        val renamed = a.resolveSibling("Act One.md")
        assertTrue(Files.exists(renamed) && !Files.exists(a))
        assertEquals("Act One.md", server.docs.values.single().name)

        pc2.syncNow()
        val followed = b.resolveSibling("Act One.md")
        assertTrue(Files.exists(followed) && !Files.exists(b), "PC2 renames its file too")
        assertEquals(DocStatus.ENTANGLED, pc2.doc("Act One.md").status)
    }

    @Test
    fun `renaming in Explorer is followed instead of being lost`() = runTest {
        val pc1 = pc("PC1")
        val a = file("pc1/Storyboard.md", "Scene 1\n")
        pc1.entangle(a)
        pc1.syncNow()
        val renamed = a.resolveSibling("Storyboard v2.md")
        Files.move(a, renamed)
        pc1.syncNow()
        val doc = pc1.doc("Storyboard v2.md")
        assertEquals(DocStatus.ENTANGLED, doc.status)
        assertEquals(renamed.toString(), doc.localPath)
        assertEquals("Storyboard v2.md", server.docs.values.single().name)
    }

    @Test
    fun `move puts the file somewhere else and keeps syncing`() = runTest {
        val pc1 = pc("PC1")
        val a = file("pc1/Storyboard.md", "Scene 1\n")
        pc1.entangle(a)
        pc1.syncNow()
        val target = root.resolve("pc1/archive/2026/Storyboard.md")
        pc1.move(pc1.doc().id, target)
        assertTrue(Files.exists(target) && !Files.exists(a))
        target.writeText("Scene 1 moved\n")
        pc1.syncNow()
        assertEquals("Scene 1 moved\n", server.docs.values.single().content)
        assertEquals(target.toString(), server.placements.values.single()["id-PC1"])
    }

    @Test
    fun `desync keeps every copy and re-sync merges what changed meanwhile`() = runTest {
        val pc1 = pc("PC1")
        val pc2 = pc("PC2")
        val (a, b) = entangledOnBoth(pc1, pc2)
        pc1.decohere(pc1.doc().id)
        pc2.syncNow()
        assertEquals(DocStatus.DESYNCED, pc1.doc().status)
        assertEquals(DocStatus.DESYNCED, pc2.doc().status)

        // While desynced, both edit different lines.
        a.writeText("Scene 1 by PC1\nScene 2\nScene 3\n")
        b.writeText("Scene 1\nScene 2\nScene 3 by PC2\n")

        pc1.resync(pc1.doc().id)
        pc1.syncNow()
        pc2.syncNow()
        pc1.syncNow()
        val merged = "Scene 1 by PC1\nScene 2\nScene 3 by PC2\n"
        assertEquals(merged, a.readText())
        assertEquals(merged, b.readText())
        assertEquals(DocStatus.ENTANGLED, pc2.doc().status)
    }

    @Test
    fun `refuses files that are not UTF-8 or already entangled`() = runTest {
        val pc1 = pc("PC1")
        val latin1 = root.resolve("pc1/latin1.md").also {
            Files.createDirectories(it.parent)
            it.writeBytes("Grüße\n".toByteArray(Charsets.ISO_8859_1))
        }
        assertContains(assertFailsWith<UserError> { pc1.entangle(latin1) }.message!!, "UTF-8")

        val a = file("pc1/Storyboard.md", "x\n")
        pc1.entangle(a)
        assertContains(assertFailsWith<UserError> { pc1.entangle(a) }.message!!, "already entangled")
    }

    @Test
    fun `wrong token is reported as unauthorized`() = runTest {
        val home = AppHome(root.resolve("home-x"))
        home.saveConfig(Config("https://example.invalid", "bad", "id-x", "X"))
        val unauthorized = object : app.entanglement.shared.sync.RemoteApi by server {
            override suspend fun list() =
                throw app.entanglement.shared.sync.RemoteException(io.ktor.http.HttpStatusCode.Unauthorized, "")
            override suspend fun registerObserver(
                observerId: String,
                request: app.entanglement.shared.model.RegisterObserverRequest,
            ) = Unit
        }
        val pc = SyncService(home, { unauthorized }, this)
        pc.syncNow()
        assertEquals(Connection.UNAUTHORIZED, pc.ui.value.connection)
    }
}
