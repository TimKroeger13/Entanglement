package app.entanglement.android

import app.entanglement.android.data.Connection
import app.entanglement.android.data.DocStatus
import app.entanglement.android.data.PhoneConfig
import app.entanglement.android.data.PhoneStore
import app.entanglement.android.data.Repository
import app.entanglement.android.data.SyncScheduler
import app.entanglement.shared.merge.Superposition
import app.entanglement.shared.model.Observer
import app.entanglement.shared.model.Platform
import app.entanglement.shared.model.RegisterObserverRequest
import app.entanglement.shared.sync.InMemoryRemote
import app.entanglement.shared.sync.SyncEngine
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RepositoryTest {

    private val dir: File = createTempDirectory("phone-test").toFile()
    private val server = InMemoryRemote()
    private val laptop = SyncEngine(server, Observer("laptop", "Laptop", Platform.DESKTOP))
    private var syncSoonCalls = 0

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    private fun TestScope.phone(): Repository {
        val store = PhoneStore(dir)
        store.saveConfig(PhoneConfig("https://example.invalid", "token", "phone", "Phone"))
        return Repository(store, { server }, object : SyncScheduler {
            override fun syncSoon() { syncSoonCalls++ }
        }, this, saveDelay = 10)
    }

    private suspend fun laptopEntangles(text: String): String {
        server.registerObserver("laptop", RegisterObserverRequest("Laptop", Platform.DESKTOP))
        laptop.entangle("story", "Storyboard.md", text, "C:\\Texte\\Storyboard.md")
        return "story"
    }

    @Test
    fun `phone observes every document automatically`() = runTest {
        val id = laptopEntangles("Scene 1\n")
        val phone = phone()
        assertTrue(phone.syncAll())
        assertEquals("Scene 1\n", phone.text(id).value)
        val doc = phone.ui.value.docs.single()
        assertEquals(DocStatus.ENTANGLED, doc.status)
        assertEquals(listOf("Laptop" to "C:\\Texte\\Storyboard.md"), doc.elsewhere)
        assertEquals(null, server.placements.getValue(id)["phone"])
        assertTrue("phone" in server.placements.getValue(id))
    }

    @Test
    fun `typing is saved and pushed`() = runTest {
        val id = laptopEntangles("Scene 1\n")
        val phone = phone()
        phone.syncAll()
        phone.edit(id, "Scene 1, written on the train\n")
        assertEquals(DocStatus.ENTANGLED, phone.ui.value.docs.single().status, "not yet published")
        advanceUntilIdle()
        // Saved, then synced by the app itself – WorkManager is only for when the app is left.
        assertEquals("Scene 1, written on the train\n", File(dir, "docs/$id.md").readText())
        assertEquals("Scene 1, written on the train\n", server.docs.getValue(id).content)
        assertEquals(DocStatus.ENTANGLED, phone.ui.value.docs.single().status)
        assertEquals(0, syncSoonCalls)
        phone.leaving()
        assertEquals(1, syncSoonCalls)
    }

    @Test
    fun `offline writing on the train merges with the laptop later`() = runTest {
        val id = laptopEntangles("Scene 1\nScene 2\nScene 3\n")
        val phone = phone()
        phone.syncAll()

        server.offline = true
        phone.edit(id, "Scene 1\nScene 2 – in the tunnel\nScene 3\n")
        assertFalse(phone.syncAll())
        assertEquals(Connection.OFFLINE, phone.ui.value.connection)
        server.offline = false
        server.editAs("laptop", id, "Scene 1 – at the desk\nScene 2\nScene 3\n")

        assertTrue(phone.syncAll())
        val merged = "Scene 1 – at the desk\nScene 2 – in the tunnel\nScene 3\n"
        assertEquals(merged, phone.text(id).value)
        assertEquals(merged, server.docs.getValue(id).content)
        assertEquals(merged, File(dir, "docs/$id.md").readText())
    }

    @Test
    fun `typing at the moment a sync lands merges instead of overwriting`() = runTest {
        val id = laptopEntangles("Scene 1\nScene 2\nScene 3\n")
        val phone = phone()
        phone.syncAll()
        val editorSaw = phone.text(id).value

        // The laptop's change arrives through a sync ...
        server.editAs("laptop", id, "Scene 1 – from the laptop\nScene 2\nScene 3\n")
        phone.syncAll()
        // ... while the editor still shows the old text and the user types into it.
        val shown = phone.edit(id, editorSaw, "Scene 1\nScene 2\nScene 3 – typed on the phone\n")

        val expected = "Scene 1 – from the laptop\nScene 2\nScene 3 – typed on the phone\n"
        assertEquals(expected, shown)
        assertEquals(expected, phone.text(id).value)
    }

    @Test
    fun `a superposition can be measured on the phone`() = runTest {
        val id = laptopEntangles("Scene 1\n")
        val phone = phone()
        phone.syncAll()
        phone.edit(id, "Scene 1 by phone\n")
        server.editAs("laptop", id, "Scene 1 by laptop\n")
        phone.syncAll()
        assertEquals(DocStatus.SUPERPOSITION, phone.ui.value.docs.single().status)

        val text = phone.text(id).value
        val block = Superposition.find(text).single()
        phone.edit(id, Superposition.resolve(text, block, Superposition.Keep.SECOND))
        phone.syncAll()
        assertEquals("Scene 1 by laptop\n", server.docs.getValue(id).content)
        assertEquals(DocStatus.ENTANGLED, phone.ui.value.docs.single().status)
    }

    @Test
    fun `document started offline on the phone is uploaded later`() = runTest {
        val phone = phone()
        server.offline = true
        val id = phone.create("Train notes")
        phone.edit(id, "Idea from the train\n")
        phone.syncAll()
        assertEquals("Train notes.md", phone.ui.value.docs.single().name)
        assertEquals(DocStatus.CHANGES_WAITING, phone.ui.value.docs.single().status)

        server.offline = false
        assertTrue(phone.syncAll())
        assertEquals("Idea from the train\n", server.docs.getValue(id).content)
        assertEquals(DocStatus.ENTANGLED, phone.ui.value.docs.single().status)
    }

    @Test
    fun `decohered elsewhere disappears but the text is archived`() = runTest {
        val id = laptopEntangles("Keep me\n")
        val phone = phone()
        phone.syncAll()
        server.decohere(id)
        phone.syncAll()
        assertEquals(DocStatus.DESYNCED, phone.ui.value.docs.single().status)
        assertEquals("Keep me\n", File(dir, "decohered/$id.md").readText())
    }

    @Test
    fun `rename works offline and reaches the server later`() = runTest {
        val id = laptopEntangles("Text\n")
        val phone = phone()
        phone.syncAll()
        server.offline = true
        phone.rename(id, "Train novel")
        assertEquals("Train novel.md", phone.ui.value.docs.single().name)
        server.offline = false
        phone.syncAll()
        assertEquals("Train novel.md", server.docs.getValue(id).name)
    }

    @Test
    fun `a rename on another device shows up on the phone`() = runTest {
        val id = laptopEntangles("Text\n")
        val phone = phone()
        phone.syncAll()
        server.rename(id, "Act One.md")
        phone.syncAll()
        assertEquals("Act One.md", phone.ui.value.docs.single().name)
    }

    @Test
    fun `desync and re-sync bring the phone copy back and merge it`() = runTest {
        val id = laptopEntangles("Scene 1\nScene 2\nScene 3\n")
        val phone = phone()
        phone.syncAll()
        phone.edit(id, "Scene 1\nScene 2\nScene 3\n")
        phone.decohere(id)
        assertEquals(DocStatus.DESYNCED, phone.ui.value.docs.single().status)

        // While desynced the laptop goes on; the phone's archived copy also had an earlier edit.
        server.resync(id)
        server.editAs("laptop", id, "Scene 1 – laptop\nScene 2\nScene 3\n")
        File(dir, "decohered/$id.md").writeText("Scene 1\nScene 2\nScene 3 – phone\n")

        phone.syncAll()
        val merged = "Scene 1 – laptop\nScene 2\nScene 3 – phone\n"
        assertEquals(merged, phone.text(id).value)
        assertEquals(merged, server.docs.getValue(id).content)
        assertEquals(DocStatus.ENTANGLED, phone.ui.value.docs.single().status)
    }

    @Test
    fun `re-sync from the phone`() = runTest {
        val id = laptopEntangles("Text\n")
        val phone = phone()
        phone.syncAll()
        phone.decohere(id)
        phone.resync(id)
        advanceUntilIdle()
        assertEquals(DocStatus.ENTANGLED, phone.ui.value.docs.single().status)
        assertEquals("Text\n", phone.text(id).value)
    }

    @Test
    fun `state survives an app restart`() = runTest {
        val id = laptopEntangles("Text\n")
        phone().syncAll()
        val restarted = phone()
        assertEquals("Text\n", restarted.text(id).value)
        assertEquals(1, restarted.ui.value.docs.size)
        assertTrue(restarted.syncAll())
        assertEquals(DocStatus.ENTANGLED, restarted.ui.value.docs.single().status)
    }
}

class KeepCursorTest {
    @Test
    fun `text inserted above moves the cursor along`() {
        val old = "Scene 1\nScene 2|"
        val new = "Scene 0\nScene 1\nScene 2|"
        val cursor = old.indexOf('|')
        assertEquals(new.indexOf('|'), app.entanglement.android.ui.keepCursor(old, new, cursor))
    }

    @Test
    fun `text changed below leaves the cursor where it is`() {
        val old = "Scene 1|\nScene 2\n"
        val new = "Scene 1|\nScene 2 from the laptop\n"
        assertEquals(old.indexOf('|'), app.entanglement.android.ui.keepCursor(old, new, old.indexOf('|')))
    }
}
