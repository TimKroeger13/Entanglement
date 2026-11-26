package app.entanglement.desktop

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.win32.StdCallLibrary
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

/**
 * The rings badge on entangled files in Explorer.
 *
 * The overlay itself is a small native DLL (shell/overlay.c) that Explorer loads. It reads the list of
 * entangled paths from `overlay.txt` in the app folder, which [update] keeps current. Turning it on or off
 * registers the DLL machine-wide, which needs admin rights once (UAC), and Explorer only picks it up after
 * a restart.
 */
class ExplorerOverlay(private val home: AppHome) {

    private var shown: Set<Path> = emptySet()

    /** Writes the list the DLL reads and asks Explorer to redraw files whose badge changed. */
    @Synchronized
    fun update(paths: Collection<Path>) {
        val now = paths.map { it.toAbsolutePath().normalize() }.toSet()
        runCatching {
            val tmp = Files.createTempFile(home.dir, ".overlay-", ".txt")
            Files.writeString(tmp, now.joinToString("\n", postfix = if (now.isEmpty()) "" else "\n"))
            Files.move(tmp, home.dir.resolve(LIST_FILE), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.onFailure { Log.error("Writing overlay list failed", it) }
        val changed = (now - shown) + (shown - now)
        shown = now
        if (changed.isNotEmpty() && isEnabled()) changed.forEach(::notifyExplorer)
    }

    companion object {
        const val LIST_FILE = "overlay.txt"

        // Name and CLSID {3BD83F2D-1F11-4C8C-A165-5CE02251907C} must match shell/overlay.c.
        private const val OVERLAY_KEY =
            """HKLM\SOFTWARE\Microsoft\Windows\CurrentVersion\Explorer\ShellIconOverlayIdentifiers\     Entanglement"""
        private const val DLL = "EntanglementOverlay.dll"
        private const val ICON = "EntanglementOverlay.ico"

        private val bundled: Path? = System.getProperty("compose.application.resources.dir")
            ?.let { Paths.get(it) }
            ?.takeIf { Files.isRegularFile(it.resolve(DLL)) }

        /** True if this build contains the overlay DLL (built by shell/build.cmd). */
        val isBundled get() = bundled != null

        /** Only in the installed app on Windows, and only if the DLL was built into it. */
        val isAvailable get() = WindowsIntegration.isAvailable && bundled != null

        fun isEnabled(): Boolean = runCatching {
            ProcessBuilder("reg.exe", "query", OVERLAY_KEY)
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start().waitFor() == 0
        }.getOrDefault(false)

        /**
         * Copies DLL and icon to %LOCALAPPDATA%\Entanglement\shell (outside the install folder, so app updates
         * are never blocked by Explorer holding the DLL) and registers it with an elevated regsvr32.
         */
        fun enable(): Boolean {
            val source = bundled ?: return false
            val dir = installDir()
            Files.createDirectories(dir)
            val dll = copyDll(source.resolve(DLL), dir)
            runCatching { Files.copy(source.resolve(ICON), dir.resolve(ICON), StandardCopyOption.REPLACE_EXISTING) }
                .onFailure { Log.warn("Overlay icon not replaced (in use?): ${it.message}") }
            return elevated("regsvr32.exe", "/s", dll.toString())
        }

        fun disable(): Boolean {
            val dll = newestDll() ?: bundled?.resolve(DLL) ?: return false
            return elevated("regsvr32.exe", "/u", "/s", dll.toString())
        }

        /** Explorer loads overlay handlers only at start. Open Explorer windows are closed. */
        fun restartExplorer() {
            runCatching {
                ProcessBuilder("taskkill.exe", "/f", "/im", "explorer.exe")
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start().waitFor()
                Thread.sleep(500)
                ProcessBuilder("explorer.exe").start()
            }.onFailure { Log.error("Restarting Explorer failed", it) }
        }

        private fun installDir(): Path {
            val local = System.getenv("LOCALAPPDATA") ?: Paths.get(System.getProperty("user.home"), "AppData", "Local").toString()
            return Paths.get(local, "Entanglement", "shell")
        }

        /** A DLL loaded by Explorer cannot be replaced; then the new one gets a fresh name. */
        private fun copyDll(source: Path, dir: Path): Path {
            val bytes = Files.readAllBytes(source)
            newestDll()?.let { if (Files.readAllBytes(it).contentEquals(bytes)) return it }
            var target = dir.resolve(DLL)
            var n = 1
            while (true) {
                try {
                    Files.write(target, bytes)
                    return target
                } catch (e: java.io.IOException) {
                    target = dir.resolve("EntanglementOverlay-${n++}.dll")
                    if (n > 20) throw e
                }
            }
        }

        private fun newestDll(): Path? = runCatching {
            Files.list(installDir()).use { files ->
                files.filter { it.fileName.toString().let { n -> n.startsWith("EntanglementOverlay") && n.endsWith(".dll") } }
                    .max(compareBy { Files.getLastModifiedTime(it) })
                    .orElse(null)
            }
        }.getOrNull()

        /** Runs a program with a UAC prompt and waits. False if the user declined or it failed. */
        private fun elevated(program: String, vararg args: String): Boolean = runCatching {
            fun quote(s: String) = "'" + s.replace("'", "''") + "'"
            val argList = args.joinToString(",") { quote(if (it.contains(' ')) "\"$it\"" else it) }
            val script = "\$p = Start-Process -FilePath ${quote(program)} -ArgumentList @($argList) " +
                "-Verb RunAs -Wait -PassThru; exit \$p.ExitCode"
            // Base64 (UTF-16LE): no quoting problems on the Windows command line.
            val encoded = java.util.Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
            val process = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded)
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start()
            val code = process.waitFor()
            if (code != 0) Log.warn("$program ${args.joinToString(" ")} exited with $code")
            code == 0
        }.getOrElse {
            Log.error("Elevation failed", it)
            false
        }

        private fun notifyExplorer(path: Path) {
            runCatching {
                Shell32.INSTANCE.SHChangeNotify(SHCNE_UPDATEITEM, SHCNF_PATHW or SHCNF_FLUSHNOWAIT, WString(path.toString()), null)
            }.onFailure { Log.warn("SHChangeNotify failed: ${it.message}") }
        }

        private const val SHCNE_UPDATEITEM = 0x00002000
        private const val SHCNF_PATHW = 0x0005
        private const val SHCNF_FLUSHNOWAIT = 0x3000

        @Suppress("FunctionName")
        private interface Shell32 : StdCallLibrary {
            fun SHChangeNotify(eventId: Int, flags: Int, item1: WString?, item2: Pointer?)

            companion object {
                val INSTANCE: Shell32 = Native.load("shell32", Shell32::class.java)
            }
        }
    }
}
