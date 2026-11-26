package app.entanglement.shared.merge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ThreeWayMergeTest {

    private fun merge(base: String, local: String, remote: String) =
        ThreeWayMerge.merge(base, local, remote, localLabel = "Laptop", remoteLabel = "Phone")

    private val base = """
        # Storyboard
        Scene 1: The train leaves.
        Scene 2: Rain on the window.
        Scene 3: A stranger sits down.
        Scene 4: The tunnel.
    """.trimIndent() + "\n"

    @Test
    fun `identical sides need no merge`() {
        val r = merge(base, base + "x\n", base + "x\n")
        assertEquals(base + "x\n", r.text)
        assertEquals(0, r.superpositions)
    }

    @Test
    fun `only one side changed takes that side`() {
        val changed = base.replace("Rain", "Snow")
        assertEquals(changed, merge(base, changed, base).text)
        assertEquals(changed, merge(base, base, changed).text)
    }

    @Test
    fun `changes on different lines are combined`() {
        val local = base.replace("Scene 1: The train leaves.", "Scene 1: The night train leaves.")
        val remote = base.replace("Scene 4: The tunnel.", "Scene 4: The long tunnel.")
        val r = merge(base, local, remote)
        assertEquals(0, r.superpositions)
        assertEquals(
            base.replace("The train", "The night train").replace("The tunnel", "The long tunnel"),
            r.text,
        )
    }

    @Test
    fun `edits on neighbouring lines are combined`() {
        // Seen in the end-to-end run: laptop edits one scene, desktop the next one.
        val b = "# Storyboard\nScene 1: The train leaves.\nScene 2: Snow on the window.\n"
        val local = "# Storyboard\nScene 1: The night train leaves.\nScene 2: Snow on the window.\n"
        val remote = "# Storyboard\nScene 1: The train leaves.\nScene 2: Heavy snow on the window.\n"
        val r = merge(b, local, remote)
        assertEquals(0, r.superpositions)
        assertEquals("# Storyboard\nScene 1: The night train leaves.\nScene 2: Heavy snow on the window.\n", r.text)
    }

    @Test
    fun `edit next to a deleted line is combined`() {
        val local = base.replace("Scene 2: Rain on the window.", "Scene 2: Rain, then fog.")
        val remote = base.replace("Scene 3: A stranger sits down.\n", "")
        val r = merge(base, local, remote)
        assertEquals(0, r.superpositions)
        assertEquals(local.replace("Scene 3: A stranger sits down.\n", ""), r.text)
    }

    @Test
    fun `alternating edits across a block are combined`() {
        val b = "1\n2\n3\n4\n5\n6\n"
        val r = merge(b, "1a\n2\n3a\n4\n5a\n6\n", "1\n2b\n3\n4b\n5\n6b\n")
        assertEquals(0, r.superpositions)
        assertEquals("1a\n2b\n3a\n4b\n5a\n6b\n", r.text)
    }

    @Test
    fun `insertion right at an edited line stays a superposition`() {
        val b = "1\n2\n3\n"
        val r = merge(b, "1\n2 edited\n3\n", "1\nnew line\n2\n3\n")
        assertEquals(1, r.superpositions)
        assertTrue(r.text.contains("2 edited") && r.text.contains("new line"))
    }

    @Test
    fun `insertions at different places are combined`() {
        val local = base.replace("Scene 2:", "Scene 1b: A whistle.\nScene 2:")
        val remote = base + "Scene 5: Arrival.\n"
        val r = merge(base, local, remote)
        assertEquals(0, r.superpositions)
        assertEquals(local + "Scene 5: Arrival.\n", r.text)
    }

    @Test
    fun `deletion on one side and edit elsewhere are combined`() {
        val local = base.replace("Scene 3: A stranger sits down.\n", "")
        val remote = base.replace("# Storyboard", "# Storyboard v2")
        val r = merge(base, local, remote)
        assertEquals(0, r.superpositions)
        assertEquals(local.replace("# Storyboard", "# Storyboard v2"), r.text)
    }

    @Test
    fun `same line changed differently becomes a superposition without losing text`() {
        val local = base.replace("Rain on the window.", "Fog on the window.")
        val remote = base.replace("Rain on the window.", "Hail on the window.")
        val r = merge(base, local, remote)
        assertEquals(1, r.superpositions)
        val expected = """
            # Storyboard
            Scene 1: The train leaves.
            ⚛⚛⚛ Superposition – Laptop
            Scene 2: Fog on the window.
            ⚛⚛⚛ ─────────── Phone
            Scene 2: Hail on the window.
            ⚛⚛⚛ End
            Scene 3: A stranger sits down.
            Scene 4: The tunnel.
        """.trimIndent() + "\n"
        assertEquals(expected, r.text)
        assertEquals(1, Superposition.count(r.text))
    }

    @Test
    fun `delete versus edit of the same line is a superposition`() {
        val local = base.replace("Scene 3: A stranger sits down.\n", "")
        val remote = base.replace("A stranger", "An old woman")
        val r = merge(base, local, remote)
        assertEquals(1, r.superpositions)
        assertTrue(r.text.contains("An old woman"))
    }

    @Test
    fun `both appending at the end is a superposition with both texts`() {
        val r = merge(base, base + "Scene 5: Laptop idea.\n", base + "Scene 5: Phone idea.\n")
        assertEquals(1, r.superpositions)
        assertTrue(r.text.startsWith(base))
        assertTrue(r.text.contains("Laptop idea") && r.text.contains("Phone idea"))
    }

    @Test
    fun `common lines inside a conflict stay outside the markers`() {
        val local = base.replace("Scene 2: Rain on the window.\n", "Scene 2: Rain.\nShared line.\n")
        val remote = base.replace("Scene 2: Rain on the window.\n", "Scene 2: Storm.\nShared line.\n")
        val r = merge(base, local, remote)
        assertEquals(1, r.superpositions)
        assertTrue(r.text.contains("⚛⚛⚛ End\nShared line.\nScene 3"))
    }

    @Test
    fun `missing trailing newline does not glue a marker to text`() {
        val b = "a\nb"
        val r = merge(b, "a\nB1", "a\nB2")
        assertEquals("a\n⚛⚛⚛ Superposition – Laptop\nB1\n⚛⚛⚛ ─────────── Phone\nB2\n⚛⚛⚛ End\n", r.text)
    }

    @Test
    fun `empty base with content on both sides`() {
        val r = merge("", "hello\n", "hello\n")
        assertEquals("hello\n", r.text)
        val c = merge("", "one\n", "two\n")
        assertEquals(1, c.superpositions)
    }

    @Test
    fun `merging again after a merge is stable`() {
        val local = base.replace("Scene 1:", "Scene 1 (edited):")
        val remote = base.replace("Scene 4:", "Scene 4 (edited):")
        val merged = merge(base, local, remote).text
        // The local file now holds the merged text, the server still the remote one.
        val again = merge(base, merged, remote)
        assertEquals(merged, again.text)
        assertEquals(0, again.superpositions)
    }

    @Test
    fun `large file with scattered edits merges cleanly`() {
        val lines = (1..5000).map { "Line $it\n" }
        val baseText = lines.joinToString("")
        val local = lines.mapIndexed { i, l -> if (i % 100 == 0) "L-$l" else l }.joinToString("")
        val remote = lines.mapIndexed { i, l -> if (i % 100 == 50) "R-$l" else l }.joinToString("")
        val r = merge(baseText, local, remote)
        assertEquals(0, r.superpositions)
        val expected = lines.mapIndexed { i, l ->
            when (i % 100) {
                0 -> "L-$l"
                50 -> "R-$l"
                else -> l
            }
        }.joinToString("")
        assertEquals(expected, r.text)
    }
}
