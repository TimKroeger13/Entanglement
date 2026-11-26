package app.entanglement.shared.sync

import app.entanglement.shared.model.Observer
import app.entanglement.shared.model.Platform
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SyncEngineTest {

    private val laptop = Observer("laptop", "Laptop", Platform.DESKTOP)
    private val remote = InMemoryRemote(mapOf("laptop" to "Laptop", "phone" to "Phone"))
    private val engine = SyncEngine(remote, laptop)

    private val text = "Scene 1\nScene 2\nScene 3\n"

    private suspend fun entangled(): Pair<Base, FakeReplica> {
        val base = engine.entangle("doc", "Storyboard.md", text, "C:\\Storyboard.md")
        return base to FakeReplica(text)
    }

    @Test
    fun `nothing changed is up to date`() = runTest {
        val (base, replica) = entangled()
        val r = engine.sync("doc", remoteVersion = 1, base, replica)
        assertEquals(Outcome.UpToDate, r.outcome)
        assertEquals(0, replica.writes)
        assertEquals(0, remote.pushCount)
    }

    @Test
    fun `local change is pushed`() = runTest {
        val (base, replica) = entangled()
        replica.text = "Scene 1\nScene 2 edited\nScene 3\n"
        val r = engine.sync("doc", 1, base, replica)
        assertEquals(Outcome.Pushed, r.outcome)
        assertEquals(Base(2, replica.text!!), r.base)
        assertEquals(replica.text, remote.docs.getValue("doc").content)
    }

    @Test
    fun `remote change is pulled`() = runTest {
        val (base, replica) = entangled()
        remote.editAs("phone", "doc", "Scene 1\nScene 2 from phone\nScene 3\n")
        val r = engine.sync("doc", 2, base, replica)
        assertEquals(Outcome.Pulled, r.outcome)
        assertEquals("Scene 1\nScene 2 from phone\nScene 3\n", replica.text)
        assertEquals(2, r.base.version)
    }

    @Test
    fun `both changed on different lines merges and pushes`() = runTest {
        val (base, replica) = entangled()
        replica.text = "Scene 1 laptop\nScene 2\nScene 3\n"
        remote.editAs("phone", "doc", "Scene 1\nScene 2\nScene 3 phone\n")
        val r = engine.sync("doc", 2, base, replica)
        assertEquals(Outcome.Merged(0), r.outcome)
        assertEquals("Scene 1 laptop\nScene 2\nScene 3 phone\n", replica.text)
        assertEquals(replica.text, remote.docs.getValue("doc").content)
        assertEquals(Base(3, replica.text!!), r.base)
    }

    @Test
    fun `stale list version is caught by a rejected push and merged`() = runTest {
        val (base, replica) = entangled()
        replica.text = "Scene 1 laptop\nScene 2\nScene 3\n"
        // The phone pushes between our list call and our push.
        remote.beforePush = { remote.editAs("phone", "doc", "Scene 1\nScene 2\nScene 3 phone\n") }
        val r = engine.sync("doc", remoteVersion = 1, base, replica)
        assertEquals(Outcome.Merged(0), r.outcome)
        assertEquals("Scene 1 laptop\nScene 2\nScene 3 phone\n", remote.docs.getValue("doc").content)
    }

    @Test
    fun `same line on both becomes a superposition on all sides`() = runTest {
        val (base, replica) = entangled()
        replica.text = "Scene 1\nScene 2 laptop\nScene 3\n"
        remote.editAs("phone", "doc", "Scene 1\nScene 2 phone\nScene 3\n")
        val r = engine.sync("doc", 2, base, replica)
        assertEquals(Outcome.Merged(1), r.outcome)
        val merged = remote.docs.getValue("doc").content
        assertEquals(replica.text, merged)
        assertTrue(merged.contains("Scene 2 laptop") && merged.contains("Scene 2 phone"))
    }

    @Test
    fun `missing local file is reported as lost and keeps the base`() = runTest {
        val (base, _) = entangled()
        val r = engine.sync("doc", 1, base, FakeReplica(null))
        assertEquals(Outcome.Lost, r.outcome)
        assertEquals(base, r.base)
    }

    @Test
    fun `crlf only difference is not a change`() = runTest {
        val (base, _) = entangled()
        val r = engine.sync("doc", 1, base, FakeReplica(text.replace("\n", "\r\n")))
        assertEquals(Outcome.UpToDate, r.outcome)
    }

    @Test
    fun `failed push after merge is repeated safely on the next run`() = runTest {
        val (base, replica) = entangled()
        replica.text = "Scene 1 laptop\nScene 2\nScene 3\n"
        remote.editAs("phone", "doc", "Scene 1\nScene 2\nScene 3 phone\n")

        // First run: merge happens locally, then the push fails (e.g. tunnel without signal).
        val failing = object : RemoteApi by remote {
            override suspend fun push(docId: String, request: app.entanglement.shared.model.PushRequest) =
                throw java.io.IOException("offline")
        }
        runCatching { SyncEngine(failing, laptop).sync("doc", 2, base, replica) }
        assertEquals("Scene 1 laptop\nScene 2\nScene 3 phone\n", replica.text)

        // Second run with the old base: no duplicates, no superposition.
        val r = engine.sync("doc", 2, base, replica)
        assertEquals(Outcome.Merged(0), r.outcome)
        assertEquals("Scene 1 laptop\nScene 2\nScene 3 phone\n", remote.docs.getValue("doc").content)
    }
}
