package app.entanglement.shared.merge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SuperpositionTest {

    private val text = ThreeWayMerge.merge(
        base = "Intro\nScene 2: Rain.\nMiddle\nScene 4: Tunnel.\nOutro\n",
        local = "Intro\nScene 2: Fog.\nMiddle\nScene 4: Long tunnel.\nOutro\n",
        remote = "Intro\nScene 2: Hail.\nMiddle\nScene 4: Dark tunnel.\nOutro\n",
        localLabel = "Laptop",
        remoteLabel = "Phone",
    ).text

    @Test
    fun `finds every superposition with both versions`() {
        val blocks = Superposition.find(text)
        assertEquals(2, blocks.size)
        val first = blocks[0]
        assertEquals("Laptop", first.firstObserver)
        assertEquals("Scene 2: Fog.\n", first.first)
        assertEquals("Phone", first.secondObserver)
        assertEquals("Scene 2: Hail.\n", first.second)
        assertEquals("Scene 4: Dark tunnel.\n", blocks[1].second)
    }

    @Test
    fun `measuring keeps the chosen version and removes the markers`() {
        val b = Superposition.find(text)
        val once = Superposition.resolve(text, b[0], Superposition.Keep.SECOND)
        assertEquals(1, Superposition.count(once))
        val twice = Superposition.resolve(once, Superposition.find(once)[0], Superposition.Keep.FIRST)
        assertEquals("Intro\nScene 2: Hail.\nMiddle\nScene 4: Long tunnel.\nOutro\n", twice)
        assertTrue(!Superposition.isPresent(twice))
    }

    @Test
    fun `keeping both puts one after the other`() {
        val b = Superposition.find(text)[0]
        val r = Superposition.resolve(text, b, Superposition.Keep.BOTH)
        assertTrue(r.startsWith("Intro\nScene 2: Fog.\nScene 2: Hail.\nMiddle\n"))
    }

    @Test
    fun `crlf text and a final end marker without newline are understood`() {
        val crlf = "a\r\n⚛⚛⚛ Superposition – PC\r\nx\r\n⚛⚛⚛ ─────────── Phone\r\ny\r\n⚛⚛⚛ End"
        val b = Superposition.find(crlf).single()
        assertEquals("x\r\n", b.first)
        assertEquals("a\r\ny\r\n", Superposition.resolve(crlf, b, Superposition.Keep.SECOND))
    }

    @Test
    fun `half deleted markers are ignored`() {
        val broken = "⚛⚛⚛ Superposition – PC\nx\n⚛⚛⚛ End\n"
        assertTrue(Superposition.find(broken).isEmpty())
    }
}
