package app.entanglement.shared.sync

import app.entanglement.shared.model.Observer
import app.entanglement.shared.model.Platform
import app.entanglement.shared.model.PushRequest
import app.entanglement.shared.model.RegisterObserverRequest
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Runs the real client and sync engine against a running Go server, so both sides provably speak
 * the same JSON. Skipped unless ENTANGLEMENT_TEST_URL and ENTANGLEMENT_TEST_TOKEN are set.
 */
class ServerContractTest {

    private val url = System.getenv("ENTANGLEMENT_TEST_URL").orEmpty()
    private val token = System.getenv("ENTANGLEMENT_TEST_TOKEN").orEmpty()

    private val laptop = Observer("contract-laptop", "Laptop", Platform.DESKTOP)
    private val phone = Observer("contract-phone", "Phone", Platform.ANDROID)

    private class MemReplica(var text: String?) : Replica {
        override suspend fun read() = text
        override suspend fun write(content: String) { text = content }
    }

    private fun contract(block: suspend (HttpRemoteApi) -> Unit) = runTest {
        assumeTrue(url.isNotBlank() && token.isNotBlank(), "no test server configured")
        HttpClient(OkHttp).use { http ->
            val api = HttpRemoteApi(http, url, token)
            api.registerObserver(laptop.id, RegisterObserverRequest(laptop.name, laptop.platform))
            api.registerObserver(phone.id, RegisterObserverRequest(phone.name, phone.platform))
            block(api)
        }
    }

    private fun newId() = "c-" + UUID.randomUUID().toString().take(8)

    @Test
    fun `laptop and phone stay entangled through the real server`() = contract { api ->
        val id = newId()
        val onLaptop = SyncEngine(api, laptop)
        val onPhone = SyncEngine(api, phone)

        val laptopFile = MemReplica("Scene 1\r\nScene 2 ⚛ Ümlaut\r\nScene 3\r\n")
        var laptopBase = onLaptop.entangle(id, "Storyboard.md", laptopFile.text!!, "C:\\Texte\\Storyboard.md")

        val summary = api.list().single { it.id == id }
        assertEquals("C:\\Texte\\Storyboard.md", summary.placements.single().path)

        val phoneCopy = MemReplica(null)
        var phoneBase = onPhone.place(id, null, phoneCopy)
        assertEquals("Scene 1\nScene 2 ⚛ Ümlaut\nScene 3\n", phoneCopy.text)
        val placements = api.list().single { it.id == id }.placements
        assertEquals(listOf("Laptop" to "C:\\Texte\\Storyboard.md", "Phone" to null), placements.map { it.observerName to it.path })

        // Offline on both sides, different lines.
        phoneCopy.text = "Scene 1\nScene 2 ⚛ Ümlaut, written on the train\nScene 3\n"
        laptopFile.text = "Scene 1\r\nScene 2 ⚛ Ümlaut\r\nScene 3\r\nScene 4 from the laptop\r\n"

        val p = onPhone.sync(id, api.list().single { it.id == id }.version, phoneBase, phoneCopy)
        assertEquals(Outcome.Pushed, p.outcome)
        phoneBase = p.base

        val l = onLaptop.sync(id, api.list().single { it.id == id }.version, laptopBase, laptopFile)
        assertEquals(Outcome.Merged(0), l.outcome)
        laptopBase = l.base

        val merged = "Scene 1\nScene 2 ⚛ Ümlaut, written on the train\nScene 3\nScene 4 from the laptop\n"
        assertEquals(merged, api.get(id).content)
        assertEquals("Laptop", api.get(id).updatedByName)

        val p2 = onPhone.sync(id, api.list().single { it.id == id }.version, phoneBase, phoneCopy)
        assertEquals(Outcome.Pulled, p2.outcome)
        assertEquals(merged, phoneCopy.text)
        assertEquals(laptopBase.version, p2.base.version)
    }

    @Test
    fun `stale push is answered with the current document`() = contract { api ->
        val id = newId()
        SyncEngine(api, laptop).entangle(id, "a.md", "one\n", null)
        assertTrue(api.push(id, PushRequest(1, "two\n", phone.id)) is PushResult.Accepted)
        val stale = api.push(id, PushRequest(1, "three\n", laptop.id)) as PushResult.Rejected
        assertEquals(2, stale.current.version)
        assertEquals("Phone", stale.current.updatedByName)
    }

    @Test
    fun `errors map to RemoteException with status`() = contract { api ->
        val id = newId()
        SyncEngine(api, laptop).entangle(id, "a.md", "x\n", null)
        api.decohere(id)
        assertTrue(api.list().single { it.id == id }.decohered)
        val gone = assertFailsWith<RemoteException> { api.push(id, PushRequest(1, "y\n", laptop.id)) }
        assertEquals(HttpStatusCode.Gone, gone.status)
        val missing = assertFailsWith<RemoteException> { api.get("does-not-exist") }
        assertEquals(HttpStatusCode.NotFound, missing.status)
        val wrongToken = HttpClient(OkHttp).use { http ->
            assertFailsWith<RemoteException> { HttpRemoteApi(http, url, "wrong").list() }
        }
        assertEquals(HttpStatusCode.Unauthorized, wrongToken.status)
    }
}
