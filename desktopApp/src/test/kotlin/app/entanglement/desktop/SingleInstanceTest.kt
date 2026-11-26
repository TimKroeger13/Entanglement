package app.entanglement.desktop

import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SingleInstanceTest {

    @Test
    fun `second start hands its message to the running instance`() {
        val file = createTempDirectory("instance-test").resolve("instance")
        val received = LinkedBlockingQueue<List<String>>()
        SingleInstance(file).listen { received.put(it) }

        assertTrue(SingleInstance(file).sendToRunning(listOf("entangle", "C:\\Texte\\Storyboard ⚛.md")))
        assertEquals(listOf("entangle", "C:\\Texte\\Storyboard ⚛.md"), received.poll(3, TimeUnit.SECONDS))
    }

    @Test
    fun `without a running instance nothing is sent`() {
        val file = createTempDirectory("instance-test").resolve("instance")
        assertFalse(SingleInstance(file).sendToRunning(listOf("show")))
        Files.writeString(file, "1\nstale-secret\n")
        assertFalse(SingleInstance(file).sendToRunning(listOf("show")))
    }
}
