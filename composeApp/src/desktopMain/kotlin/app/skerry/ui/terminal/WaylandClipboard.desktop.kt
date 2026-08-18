package app.skerry.ui.terminal

import app.skerry.shared.process.resolveExecutableOnPath
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

    private val isWaylandSession: Boolean
        get() = System.getenv("WAYLAND_DISPLAY")?.isNotBlank() == true ||
            System.getenv("XDG_SESSION_TYPE")?.equals("wayland", ignoreCase = true) == true

    private val wlCopyPath: String? by lazy { if (isWaylandSession) resolveExecutableOnPath("wl-copy") else null }
    private val wlPastePath: String? by lazy { if (isWaylandSession) resolveExecutableOnPath("wl-paste") else null }

    /** True if this is a Wayland session and both utilities were found on PATH; computed once per process. */
    val available: Boolean by lazy { wlCopyPath != null && wlPastePath != null }

    /**
     * Read text from CLIPBOARD (or PRIMARY if [primary]). `--no-newline` strips the trailing newline
     * the utility adds. Returns `null` when there is nothing to read: no utility, empty output, or
     * exit 1 — which `wl-paste` uses for an empty clipboard and for one holding no text alike, so
     * those two cannot be told apart here.
     *
     * Throws when the utility could not answer at all — it never started, the read failed, or it had
     * to be killed on the timeout. That is not the same fact as an empty clipboard, and a caller that
     * reports "nothing to send" for it tells the user the copy worked when nothing was read (#282).
     */
    fun paste(primary: Boolean): String? {
        val bin = wlPastePath ?: return null
        val args = buildList {
            add(bin)
            add("--no-newline")
            if (primary) add("--primary")
            // Explicit text type; otherwise wl-paste would return non-text for a graphical clipboard.
            add("--type"); add("text/plain")
        }
        // Discard stderr: an unread pipe could otherwise fill up and hang the process.
        return readPastedText { ProcessBuilder(args).redirectError(Redirect.DISCARD).start() }
    }

    /**
     * Write [text] to CLIPBOARD (or PRIMARY if [primary]). `wl-copy` forks a daemon that holds the
     * buffer and detaches, so the process exits immediately. Returns `false` on failure or if missing.
     */
    fun copy(text: String, primary: Boolean): Boolean {
        val bin = wlCopyPath ?: return false
        val args = buildList {
            add(bin)
            if (primary) add("--primary")
            add("--type"); add("text/plain")
        }
        // Discard stdout/stderr (only stdin is needed); unread pipes could otherwise hang the process.
        return runCatching {
            writeCopiedText(text) {
                ProcessBuilder(args)
                    .redirectOutput(Redirect.DISCARD)
                    .redirectError(Redirect.DISCARD)
                    .start()
            }
        }.getOrDefault(false)
    }
}

/**
 * The read half of [WaylandClipboard.paste] over whatever [start] launches — the utility itself in
 * production, a stub in a test, which is the only way the three outcomes below can be told apart
 * without a Wayland session.
 *
 * Reads one byte past [MAX_PASTE_BYTES] so a clipboard over the cap can be told from one that just
 * fits, and fails on it: half a file pasted into a shell is worse than a paste that did not happen,
 * and closing the pipe on the utility would have it exit non-zero and read as an empty clipboard.
 */
internal fun readPastedText(start: () -> Process): String? {
    val proc = start()
    val bytes = proc.inputStream.use { it.readNBytes(MAX_PASTE_BYTES + 1) }
    if (bytes.size > MAX_PASTE_BYTES) {
        proc.destroyForcibly()
        error("the clipboard holds more than $MAX_PASTE_BYTES bytes")
    }
    if (!proc.waitFor(PROCESS_TIMEOUT_S, TimeUnit.SECONDS)) {
        proc.destroyForcibly()
        error("wl-paste did not answer in ${PROCESS_TIMEOUT_S}s")
    }
    // Exit code 1 means the clipboard is empty or lacks the requested type, not an error.
    return if (proc.exitValue() != 0) null else bytes.toString(Charsets.UTF_8).ifEmpty { null }
}

/** The write half of [WaylandClipboard.copy]: `true` once the utility took [text] and exited clean. */
internal fun writeCopiedText(text: String, start: () -> Process): Boolean {
    val proc = start()
    proc.outputStream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
    return proc.waitFor(PROCESS_TIMEOUT_S, TimeUnit.SECONDS) && proc.exitValue() == 0
}

/** Read cap (8 MiB) to avoid OOM if the clipboard owner offers a huge payload. */
private const val MAX_PASTE_BYTES = 8 * 1024 * 1024

/**
 * How long a utility gets to finish before it is killed — long enough for a cold start, short enough
 * that a hung clipboard owner does not hold a copy or paste open.
 */
private const val PROCESS_TIMEOUT_S = 2L
