package app.entanglement.desktop

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import app.entanglement.desktop.ui.EntanglementTheme
import app.entanglement.desktop.ui.MainScreen
import app.entanglement.shared.model.PlacementRequest
import app.entanglement.shared.model.Platform
import app.entanglement.shared.model.RegisterObserverRequest
import app.entanglement.shared.sync.InMemoryRemote
import app.entanglement.shared.sync.SyncEngine
import kotlinx.coroutines.test.runTest
import org.jetbrains.skia.EncodedImageFormat
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertTrue

/** Renders the main screen with realistic data to build/screenshots/main.png (for a visual check). */
class ScreenshotTest {

    @Test
    fun `render main screen`() = runTest {
        if (GraphicsEnvironment.isHeadless()) return@runTest
        val root = createTempDirectory("screenshot")
        val server = InMemoryRemote()
        val home = AppHome(root.resolve("home"))
        home.saveConfig(Config("https://example.com/entanglement", "t", "desk", "Desktop"))
        val service = SyncService(home, { server }, this)

        val texte = root.resolve("Texte").also { Files.createDirectories(it) }
        val story = texte.resolve("Storyboard.md").also { it.writeText("# Storyboard\nScene 1\nScene 2\n") }
        val notes = texte.resolve("Notes.md").also { it.writeText("Notes\n") }
        val ideas = texte.resolve("Ideas.md").also { it.writeText("Ideas\n") }
        service.entangle(story)
        service.entangle(notes)
        service.entangle(ideas)

        // Phone and laptop take part as well.
        server.registerObserver("phone", RegisterObserverRequest("Phone", Platform.ANDROID))
        server.registerObserver("laptop", RegisterObserverRequest("Laptop", Platform.DESKTOP))
        service.syncNow()
        val storyId = service.ui.value.docs.single { it.name == "Storyboard.md" }.id
        val notesId = service.ui.value.docs.single { it.name == "Notes.md" }.id
        server.setPlacement(storyId, PlacementRequest("phone", null))
        server.setPlacement(storyId, PlacementRequest("laptop", "D:\\Schreiben\\Storyboard.md"))
        server.setPlacement(notesId, PlacementRequest("phone", null))

        // A superposition in Notes.md, a lost Ideas.md, and a document only the phone has.
        server.editAs("phone", notesId, "Notes from the train\n")
        notes.writeText("Notes from the desk\n")
        Files.delete(ideas)
        SyncEngine(server, app.entanglement.shared.model.Observer("phone", "Phone", Platform.ANDROID))
            .entangle("poem", "Poem.md", "Roses\n", null)
        service.syncNow()

        val scene = ImageComposeScene(900, 760, Density(1f)) {
            EntanglementTheme { MainScreen(service, Frame(), onOpenSettings = {}) }
        }
        scene.render(0)
        val image = scene.render(1_000_000_000)
        val out = Paths.get("build/screenshots/main.png")
        Files.createDirectories(out.parent)
        Files.write(out, image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        scene.close()
        assertTrue(Files.size(out) > 0)
        root.toFile().deleteRecursively()
    }
}
