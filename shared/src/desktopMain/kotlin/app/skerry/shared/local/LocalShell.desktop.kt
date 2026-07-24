package app.skerry.shared.local

import app.skerry.shared.ai.local.LlmHostCommandLine
import com.pty4j.PtyProcess
import com.pty4j.PtyProcessBuilder
import com.pty4j.WinSize
import java.util.concurrent.TimeUnit

/**
 * Desktop local shell via pty4j (real PTY with job control and window size on Linux/macOS/Windows).
 * The child inherits the user's environment minus the jpackage relaunch marker (see
 * [LlmHostCommandLine.scrubEnvironment]) so packaging internals don't leak into their shell, with
 * `TERM` defaulted for programs that key off it.
 */
actual object LocalShell {
    actual fun start(config: LocalShellConfig): LocalShellHandle {
        val command = config.command.ifEmpty { listOf(defaultShell()) }
        val environment = HashMap(System.getenv())
        LlmHostCommandLine.scrubEnvironment(environment)
        environment.putIfAbsent("TERM", "xterm-256color")
        environment.putAll(config.env)
        val process = try {
            PtyProcessBuilder(command.toTypedArray())
                .setEnvironment(environment)
                .setDirectory(config.workingDir ?: System.getProperty("user.home"))
                .setInitialColumns(config.cols)
                .setInitialRows(config.rows)
                .setConsole(false)
                .start()
        } catch (e: Exception) {
            throw LocalShellUnavailableException(
                "Failed to start local shell (${command.firstOrNull() ?: "?"})",
                e,
            )
        }
        return Pty4jHandle(process)
    }

    private fun defaultShell(): String {
        val windows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
        return if (windows) System.getenv("COMSPEC") ?: "cmd.exe"
        else System.getenv("SHELL") ?: "/bin/sh"
    }
}

private class Pty4jHandle(private val process: PtyProcess) : LocalShellHandle {
    private val input = process.inputStream
    private val output = process.outputStream

    override val isOpen: Boolean get() = process.isAlive

    override fun read(buffer: ByteArray): Int = input.read(buffer)

    override fun write(data: ByteArray) {
        output.write(data)
        output.flush()
    }

    override fun resize(cols: Int, rows: Int) {
        process.winSize = WinSize(cols, rows)
    }

    override fun close() {
        // Terminate and reap: destroy() (SIGHUP-equivalent) then a bounded wait; if the child
        // ignores it, escalate to a forcible kill so repeated open/close cycles can't leak
        // orphaned processes. All wrapped so close() itself never hangs or throws.
        runCatching { process.destroy() }
        runCatching {
            if (!process.waitFor(1, TimeUnit.SECONDS)) process.destroyForcibly()
        }
        runCatching { input.close() }
        runCatching { output.close() }
    }
}
