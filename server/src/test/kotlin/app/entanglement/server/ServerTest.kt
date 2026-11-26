package app.entanglement.server

import app.entanglement.shared.model.Observer
import app.entanglement.shared.model.Platform
import app.entanglement.shared.model.PushRequest
import app.entanglement.shared.model.RegisterObserverRequest
import app.entanglement.shared.sync.Base
import app.entanglement.shared.sync.HttpRemoteApi
import app.entanglement.shared.sync.Outcome
import app.entanglement.shared.sync.PushResult
import app.entanglement.shared.sync.RemoteException
import app.entanglement.shared.sync.Replica
import app.entanglement.shared.sync.SyncEngine
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerTest {

    private val token = "test-token-0123456789-abcdef"
    private val laptop = Observer("laptop", "Laptop", Platform.DESKTOP)
    private val phone = Observer("phone", "Phone", Platform.ANDROID)

    private class MemReplica(var text: String?) : Replica {
        override suspend fun read() = text
        override suspend fun write(content: String) { text = content }
    }

    private fun serverTest(keepVersions: Int = 50, block: suspend ApplicationTestBuilder.(Store) -> Unit) {
        val store = Store("jdbc:sqlite::memory:", keepVersions)
        testApplication {
            application { entanglement(store, token) }
            block(store)
        }
        store.close()
    }

    private fun ApplicationTestBuilder.api() = HttpRemoteApi(client, "http://localhost", token)

    private suspend fun ApplicationTestBuilder.registered(): HttpRemoteApi {
        val api = api()
        api.registerObserver(laptop.id, RegisterObserverRequest(laptop.name, laptop.platform))
        api.registerObserver(phone.id, RegisterObserverRequest(phone.name, phone.platform))
        return api
    }

    @Test
    fun `health needs no token, api does`() = serverTest {
        assertEquals("ok", client.get("/health").bodyAsText())
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/docs").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/docs") { bearerAuth("wrong") }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/docs") { bearerAuth(token) }.status)
    }

    @Test
    fun `laptop and phone stay entangled through the real server`() = serverTest {
        val api = registered()
        val onLaptop = SyncEngine(api, laptop)
        val onPhone = SyncEngine(api, phone)

        // Laptop entangles its file.
        val laptopFile = MemReplica("Scene 1\r\nScene 2\r\nScene 3\r\n")
        var laptopBase = onLaptop.entangle("story", "Storyboard.md", laptopFile.text!!, "C:\\Texte\\Storyboard.md")

        // The phone sees it unobserved, then places it.
        val summary = api.list().single()
        assertEquals(listOf("Laptop"), summary.placements.map { it.observerName })
        val phoneCopy = MemReplica(null)
        var phoneBase = onPhone.place("story", null, phoneCopy)
        assertEquals("Scene 1\nScene 2\nScene 3\n", phoneCopy.text)
        assertEquals(setOf("Laptop", "Phone"), api.list().single().placements.map { it.observerName }.toSet())

        // Offline on both sides: different lines.
        phoneCopy.text = "Scene 1\nScene 2 written on the train\nScene 3\n"
        laptopFile.text = "Scene 1\r\nScene 2\r\nScene 3\r\nScene 4 from the laptop\r\n"

        val p = onPhone.sync("story", api.list().single().version, phoneBase, phoneCopy)
        assertEquals(Outcome.Pushed, p.outcome)
        phoneBase = p.base

        val l = onLaptop.sync("story", api.list().single().version, laptopBase, laptopFile)
        assertEquals(Outcome.Merged(0), l.outcome)
        laptopBase = l.base

        val merged = "Scene 1\nScene 2 written on the train\nScene 3\nScene 4 from the laptop\n"
        assertEquals(merged, api.get("story").content)
        assertEquals("Laptop", api.get("story").updatedByName)

        val p2 = onPhone.sync("story", api.list().single().version, phoneBase, phoneCopy)
        assertEquals(Outcome.Pulled, p2.outcome)
        assertEquals(merged, phoneCopy.text)
        assertEquals(laptopBase.version, p2.base.version)
    }

    @Test
    fun `stale push is answered with 409 and the current document`() = serverTest {
        val api = registered()
        SyncEngine(api, laptop).entangle("d1", "a.md", "one\n", null)
        assertTrue(api.push("d1", PushRequest(1, "two\n", "phone")) is PushResult.Accepted)
        val stale = api.push("d1", PushRequest(1, "three\n", "laptop"))
        stale as PushResult.Rejected
        assertEquals(2, stale.current.version)
        assertEquals("two\n", stale.current.content)
        assertEquals("Phone", stale.current.updatedByName)
    }

    @Test
    fun `only the newest versions are kept`() = serverTest(keepVersions = 3) { store ->
        val api = registered()
        SyncEngine(api, laptop).entangle("d1", "a.md", "v1\n", null)
        for (v in 1..5) api.push("d1", PushRequest(v, "v${v + 1}\n", "laptop"))
        assertEquals(6, api.get("d1").version)
        assertEquals(3, store.versionCount("d1"))
    }

    @Test
    fun `create is idempotent for retries`() = serverTest {
        val api = registered()
        val engine = SyncEngine(api, laptop)
        engine.entangle("d1", "a.md", "first\n", null)
        val again = engine.entangle("d1", "a.md", "first\n", null)
        assertEquals(Base(1, "first\n"), again)
        assertEquals(1, api.list().size)
    }

    @Test
    fun `decohered document is listed as such and rejects pushes`() = serverTest {
        val api = registered()
        SyncEngine(api, laptop).entangle("d1", "a.md", "x\n", null)
        api.decohere("d1")
        assertTrue(api.list().single().decohered)
        val e = assertFailsWith<RemoteException> { api.push("d1", PushRequest(1, "y\n", "laptop")) }
        assertEquals(HttpStatusCode.Gone, e.status)
    }

    @Test
    fun `bad input is rejected`() = serverTest {
        val api = registered()
        val unknown = assertFailsWith<RemoteException> { api.get("does-not-exist") }
        assertEquals(HttpStatusCode.NotFound, unknown.status)
        val badId = assertFailsWith<RemoteException> { SyncEngine(api, laptop).entangle("../etc", "a.md", "", null) }
        assertEquals(HttpStatusCode.BadRequest, badId.status)
        val stranger = Observer("stranger", "Stranger", Platform.DESKTOP)
        val unregistered = assertFailsWith<RemoteException> { SyncEngine(api, stranger).entangle("d2", "a.md", "", null) }
        assertEquals(HttpStatusCode.BadRequest, unregistered.status)
        assertNull(api.list().firstOrNull())
    }
}
