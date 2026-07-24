package app.skerry.shared.local

/**
 * Parameters for starting a local shell over a pseudo-terminal. [command] is the argv to exec; an
 * empty list means "the platform default shell" (resolved by the actual: `$SHELL`/`/bin/sh` on
 * Unix, `%COMSPEC%` on Windows). [cols]/[rows] are the initial PTY window size. [workingDir] is the
 * child's working directory (`null` → the user's home). [env] are extra environment overrides
 * layered on top of the (scrubbed) inherited environment.
 */
data class LocalShellConfig(
    val command: List<String> = emptyList(),
    val cols: Int = 80,
    val rows: Int = 24,
    val workingDir: String? = null,
    val env: Map<String, String> = emptyMap(),
)

/**
 * An open local shell on a pseudo-terminal — blocking byte-IO plus window resize. Implemented per
 * platform ([LocalShell]): desktop via pty4j, Android via a native PTY helper. [read] blocks until
 * output arrives, returning the byte count or `-1` when the shell process has exited (clean EOF).
 * [resize] applies the terminal window size (`TIOCSWINSZ`) so full-screen programs (vi/top) redraw.
 */
interface LocalShellHandle {
    val isOpen: Boolean
    fun read(buffer: ByteArray): Int
    fun write(data: ByteArray)
    fun resize(cols: Int, rows: Int)
    fun close()
}

/**
 * Platform launcher for a local shell on a PTY. Lives in the JVM node (desktop + Android); the
 * desktop actual uses pty4j, Android uses a native helper. [LocalShellUnavailableException] signals
 * the platform can't provide a local terminal (e.g. not yet implemented).
 */
expect object LocalShell {
    /**
     * Start a shell described by [config].
     * @throws LocalShellUnavailableException the platform has no local terminal support
     */
    fun start(config: LocalShellConfig): LocalShellHandle
}

/** The platform can't start a local shell (no PTY support / not implemented on this target). */
class LocalShellUnavailableException(message: String, cause: Throwable? = null) :
    Exception(message, cause)
