package app.skerry.ui.terminal

import java.io.File
import java.lang.ProcessBuilder.Redirect
import java.util.concurrent.TimeUnit

/**
 * Access to Wayland clipboards via `wl-clipboard` utilities (`wl-copy`/`wl-paste`).
 *
 * AWT's `getSystemSelection()` returns `null` for PRIMARY under Wayland, so middle-click paste from
 * other windows doesn't work; `wl-paste --primary`/`wl-copy --primary` handle real Wayland PRIMARY.
 * AWT clipboard reads also print a JDK stack trace to stderr for foreign serialized flavors (e.g. a
 * buffer copied from IntelliJ); `wl-paste` avoids AWT entirely.
 *
 * Active only in a Wayland session with both utilities on PATH; otherwise the caller falls back to the
 * AWT/Compose clipboard path (X11, Windows, macOS, headless). Binary paths are resolved to absolute
 * paths once, so `ProcessBuilder` never re-resolves them against a PATH mutated at runtime.
 *
 * User/server text never goes into process arguments, only stdin (`wl-copy`) or stdout (`wl-paste`).
 */
internal object WaylandClipboard {

    /** Read cap (8 MiB) to avoid OOM if the clipboard owner offers a huge payload. */
    private const val MAX_PASTE_BYTES = 8 * 1024 * 1024

    private val isWaylandSession: Boolean
        get() = System.getenv("WAYLAND_DISPLAY")?.isNotBlank() == true ||
            System.getenv("XDG_SESSION_TYPE")?.equals("wayland", ignoreCase = true) == true

    private val wlCopyPath: String? by lazy { if (isWaylandSession) resolveOnPath("wl-copy") else null }
    private val wlPastePath: String? by lazy { if (isWaylandSession) resolveOnPath("wl-paste") else null }

    /** True if this is a Wayland session and both utilities were found on PATH; computed once per process. */
    val available: Boolean by lazy { wlCopyPath != null && wlPastePath != null }

    /**
     * Read text from CLIPBOARD (or PRIMARY if [primary]). `--no-newline` strips the trailing newline
     * the utility adds. Empty output, missing utility, or any failure (no data, non-text, timeout,
     * over limit) returns `null` so the caller falls back to its normal path.
     */
    fun paste(primary: Boolean): String? {
        val bin = wlPastePath ?: return null
        return runCatching {
            val args = buildList {
                add(bin)
                add("--no-newline")
                if (primary) add("--primary")
                // Explicit text type; otherwise wl-paste would return non-text for a graphical clipboard.
                add("--type"); add("text/plain")
            }
            // Discard stderr: an unread pipe could otherwise fill up and hang the process.
            val proc = ProcessBuilder(args).redirectError(Redirect.DISCARD).start()
            // Read at most the cap; if exceeded, wl-paste blocks on write, waitFor times out, we kill
            // the process and return null (an empty paste beats OOM).
            val bytes = proc.inputStream.use { it.readNBytes(MAX_PASTE_BYTES) }
            if (!proc.waitFor(2, TimeUnit.SECONDS)) { proc.destroyForcibly(); return null }
            // Exit code 1 means the clipboard is empty or lacks the requested type, not an error.
            if (proc.exitValue() != 0) null else bytes.toString(Charsets.UTF_8).ifEmpty { null }
        }.getOrNull()
    }

    /**
     * Write [text] to CLIPBOARD (or PRIMARY if [primary]). `wl-copy` forks a daemon that holds the
     * buffer and detaches, so the process exits immediately. Returns `false` on failure or if missing.
     */
    fun copy(text: String, primary: Boolean): Boolean {
        val bin = wlCopyPath ?: return false
        return runCatching {
            val args = buildList {
                add(bin)
                if (primary) add("--primary")
                add("--type"); add("text/plain")
            }
            // Discard stdout/stderr (only stdin is needed); unread pipes could otherwise hang the process.
            val proc = ProcessBuilder(args)
                .redirectOutput(Redirect.DISCARD)
                .redirectError(Redirect.DISCARD)
                .start()
            proc.outputStream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            proc.waitFor(2, TimeUnit.SECONDS) && proc.exitValue() == 0
        }.getOrDefault(false)
    }

    /**
     * Find executable [name] on PATH and return its absolute path, or null. No `sh -c`, avoiding shell
     * injection and an extra subprocess; the path is fixed so a mutated PATH can't retarget it later.
     */
    private fun resolveOnPath(name: String): String? =
        (System.getenv("PATH") ?: "").split(File.pathSeparatorChar)
            .asSequence()
            .filter { it.isNotEmpty() }
            .map { File(it, name) }
            .firstOrNull { it.isFile && it.canExecute() }
            ?.absolutePath
}
