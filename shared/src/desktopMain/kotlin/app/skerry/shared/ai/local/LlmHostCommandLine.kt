package app.skerry.shared.ai.local

import app.skerry.shared.ai.AiException

/**
 * How to start the isolated inference host, given how this app itself was started.
 *
 * A packaged build has no `java` to spawn — jpackage strips the launchers out of the bundled
 * runtime — so the app re-launches **itself** with [HOST_FLAG] and branches into [LlmHostMain]
 * before any UI exists. A development run is started by `java` and simply gets a second JVM on the
 * same classpath.
 */
object LlmHostCommandLine {

    /** Marks a run as "be the inference host, not the app". First argument of the child. */
    const val HOST_FLAG = "--llm-host"

    private const val HOST_MAIN_CLASS = "app.skerry.shared.ai.local.LlmHostMain"

    /** True when this process was started to serve inference. */
    fun isHostRun(args: Array<String>): Boolean = args.firstOrNull() == HOST_FLAG

    /**
     * @param selfCommand the executable that started this process (`ProcessHandle.current()`).
     * @param classpath used only when [selfCommand] is a `java` binary.
     */
    fun build(
        selfCommand: String?,
        classpath: String,
        socketPath: String,
        contextLength: Int,
        heapMegabytes: Int,
    ): List<String> {
        val self = selfCommand?.takeIf { it.isNotBlank() }
            ?: fail("cannot tell how this app was started")
        val tail = listOf(HOST_FLAG, socketPath, contextLength.toString())
        if (!self.substringAfterLast('/').substringAfterLast('\\').removeSuffix(".exe").equals("java", true)) {
            return listOf(self) + tail // packaged app: its own launcher, with the host flag
        }
        if (classpath.isBlank()) fail("the app classpath is empty")
        return listOf(
            self,
            "-Djava.awt.headless=true", // the crash being isolated from needs AWT/Skia in the process
            "-Xmx${heapMegabytes}m", // weights are native, mmapped memory — the JVM side is tiny
            "-cp",
            classpath,
            HOST_MAIN_CLASS,
        ) + tail
    }

    private fun fail(reason: String): Nothing =
        throw AiException(AiException.Kind.ENGINE_CRASHED, "Local inference host: $reason")
}
