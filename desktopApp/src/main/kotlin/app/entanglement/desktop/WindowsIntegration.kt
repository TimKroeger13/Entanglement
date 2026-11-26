package app.entanglement.desktop

import java.awt.Desktop
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Explorer context menu ("Entangle" on .md files), autostart, and opening files/folders.
 * Registry entries live under HKCU, so no admin rights are needed.
 */
object WindowsIntegration {

    private const val MENU_KEY = """HKEY_CURRENT_USER\Software\Classes\SystemFileAssociations\.md\shell\Entanglement"""
    private const val RUN_KEY = """HKEY_CURRENT_USER\Software\Microsoft\Windows\CurrentVersion\Run"""
    private const val RUN_VALUE = "Entanglement"

    /** Path of the installed Entanglement.exe, or null when running from Gradle (java.exe). */
    val launcher: Path? = ProcessHandle.current().info().command().orElse(null)
        ?.let { Paths.get(it) }
        ?.takeIf {
            val name = it.fileName.toString().lowercase()
            name.endsWith(".exe") && name != "java.exe" && name != "javaw.exe"
        }

    val isAvailable get() = launcher != null && System.getProperty("os.name").startsWith("Windows")

    fun isContextMenuEnabled(): Boolean = reg("query", MENU_KEY.short()) == 0

    fun setContextMenu(enabled: Boolean) {
        val exe = launcher ?: return
        if (enabled) import(contextMenuReg(exe)) else {
            reg("delete", MENU_KEY.short(), "/f")
        }
    }

    fun isAutostartEnabled(): Boolean = reg("query", RUN_KEY.short(), "/v", RUN_VALUE) == 0

    fun setAutostart(enabled: Boolean) {
        val exe = launcher ?: return
        if (enabled) import(autostartReg(exe)) else {
            reg("delete", RUN_KEY.short(), "/v", RUN_VALUE, "/f")
        }
    }

    /** Keeps both entries pointing at the current exe after an update moved it. */
    fun refreshIfEnabled() {
        if (!isAvailable) return
        if (isContextMenuEnabled()) setContextMenu(true)
        if (isAutostartEnabled()) setAutostart(true)
    }

    fun showInFolder(path: Path) {
        ProcessBuilder("explorer.exe", "/select,", path.toString()).start()
    }

    fun open(path: Path) {
        Desktop.getDesktop().open(path.toFile())
    }

    internal fun contextMenuReg(exe: Path) = regFile(
        """
        [$MENU_KEY]
        @="Entangle"
        "Icon"=${regString("\"$exe\",0")}

        [$MENU_KEY\command]
        @=${regString("\"$exe\" --entangle \"%1\"")}
        """,
    )

    internal fun autostartReg(exe: Path) = regFile(
        """
        [$RUN_KEY]
        "$RUN_VALUE"=${regString("\"$exe\" --background")}
        """,
    )

    private fun regFile(body: String) =
        "Windows Registry Editor Version 5.00\r\n\r\n" + body.trimIndent().lines().joinToString("\r\n") + "\r\n"

    /** .reg files have well defined escaping, unlike quotes inside command line arguments. */
    private fun import(text: String) {
        val file = Files.createTempFile("entanglement-", ".reg")
        try {
            Files.write(file, byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE))
            val code = reg("import", file.toString())
            if (code != 0) Log.warn("reg import failed with exit code $code")
        } finally {
            Files.deleteIfExists(file)
        }
    }

    private fun regString(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun String.short() = replace("HKEY_CURRENT_USER", "HKCU")

    private fun reg(vararg args: String): Int = runCatching {
        val process = ProcessBuilder(listOf("reg.exe") + args)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start()
        process.waitFor()
    }.getOrElse {
        Log.error("reg.exe failed", it)
        -1
    }

    /** Default folder for documents placed from other observers: Documents\Entanglement. */
    fun defaultPlacementDir(): Path = Paths.get(System.getProperty("user.home"), "Documents", "Entanglement")

    fun exists(path: String?): Boolean = path != null && File(path).exists()
}
