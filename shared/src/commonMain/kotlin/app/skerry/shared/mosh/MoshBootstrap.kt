package app.skerry.shared.mosh

/**
 * Outcome of launching `mosh-server new …` over SSH. The taxonomy exists so the UI can explain
 * each failure in the user's language: Skerry ships only the Mosh client — the server package
 * must be installed on the remote host, and that is the most common first-run failure.
 */
sealed interface MoshBootstrapResult {
    /** `MOSH CONNECT <port> <key>` was found: the UDP endpoint is ready. */
    data class Success(val port: Int, val key: MoshKey) : MoshBootstrapResult

    /** The shell could not find `mosh-server` (exit 127 / "command not found"). */
    data object NotInstalled : MoshBootstrapResult

    /** `mosh-server` refused to start without a UTF-8 native locale. */
    data object LocaleUnsupported : MoshBootstrapResult

    /** Anything else; [output] carries the server's combined stdout+stderr for diagnostics. */
    data class Failed(val output: String) : MoshBootstrapResult
}

object MoshBootstrap {
    private val CONNECT_LINE = Regex("""^MOSH CONNECT (\d{1,5}) ([A-Za-z0-9+/]{22})$""")

    /** Classify one `mosh-server` launch attempt from its exec exit code and output. */
    fun parse(exitCode: Int?, stdout: String, stderr: String): MoshBootstrapResult {
        for (line in stdout.lineSequence()) {
            val match = CONNECT_LINE.matchEntire(line.trim()) ?: continue
            val port = match.groupValues[1].toInt()
            val key = MoshKey.parse(match.groupValues[2])
            if (port in 1..65535 && key != null) return MoshBootstrapResult.Success(port, key)
        }
        val combined = listOf(stdout, stderr).filter { it.isNotBlank() }.joinToString("\n").trim()
        return when {
            exitCode == 127 || combined.contains("command not found", ignoreCase = true) ||
                combined.contains("No such file or directory", ignoreCase = true) ->
                MoshBootstrapResult.NotInstalled
            combined.contains("UTF-8", ignoreCase = true) &&
                combined.contains("locale", ignoreCase = true) ->
                MoshBootstrapResult.LocaleUnsupported
            else -> MoshBootstrapResult.Failed(combined)
        }
    }
}
