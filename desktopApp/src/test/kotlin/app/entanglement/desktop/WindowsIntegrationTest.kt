package app.entanglement.desktop

import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals

class WindowsIntegrationTest {

    private val exe = Paths.get("""C:\Users\Tim\AppData\Local\Entanglement\Entanglement.exe""")

    @Test
    fun `context menu reg file escapes quotes and backslashes`() {
        val expected = listOf(
            "Windows Registry Editor Version 5.00",
            "",
            """[HKEY_CURRENT_USER\Software\Classes\SystemFileAssociations\.md\shell\Entanglement]""",
            "@=\"Entangle\"",
            """"Icon"="\"C:\\Users\\Tim\\AppData\\Local\\Entanglement\\Entanglement.exe\",0"""",
            "",
            """[HKEY_CURRENT_USER\Software\Classes\SystemFileAssociations\.md\shell\Entanglement\command]""",
            """@="\"C:\\Users\\Tim\\AppData\\Local\\Entanglement\\Entanglement.exe\" --entangle \"%1\""""",
            "",
        ).joinToString("\r\n")
        assertEquals(expected, WindowsIntegration.contextMenuReg(exe))
    }

    @Test
    fun `autostart starts in the background`() {
        val text = WindowsIntegration.autostartReg(Paths.get("""C:\Program Files\Entanglement\Entanglement.exe"""))
        assertEquals(
            """"Entanglement"="\"C:\\Program Files\\Entanglement\\Entanglement.exe\" --background"""",
            text.lines()[3].trimEnd('\r'),
        )
    }
}
