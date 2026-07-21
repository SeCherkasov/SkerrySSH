package app.skerry.shared.ai.local

import app.skerry.shared.ai.AiException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LlmHostCommandLineTest {

    @Test
    fun `a development run spawns a second jvm on the same classpath`() {
        val command = LlmHostCommandLine.build(
            selfCommand = "/opt/jdk-21/bin/java",
            classpath = "a.jar:b.jar",
            socketPath = "/tmp/x/host.sock",
            contextLength = 4096,
            heapMegabytes = 256,
        )

        assertEquals(
            listOf(
                "/opt/jdk-21/bin/java",
                "-Djava.awt.headless=true",
                "-Xmx256m",
                "-cp",
                "a.jar:b.jar",
                "app.skerry.shared.ai.local.LlmHostMain",
                "--llm-host",
                "/tmp/x/host.sock",
                "4096",
            ),
            command,
        )
    }

    @Test
    fun `a packaged app relaunches its own launcher, because jpackage ships no java binary`() {
        val command = LlmHostCommandLine.build(
            selfCommand = "/app/skerry/bin/Skerry",
            classpath = "",
            socketPath = "/tmp/x/host.sock",
            contextLength = 2048,
            heapMegabytes = 256,
        )

        assertEquals(listOf("/app/skerry/bin/Skerry", "--llm-host", "/tmp/x/host.sock", "2048"), command)
    }

    @Test
    fun `windows java is recognised as a jvm launch`() {
        val command = LlmHostCommandLine.build(
            selfCommand = """C:\Program Files\Java\bin\java.exe""",
            classpath = "a.jar",
            socketPath = """C:\Temp\host.sock""",
            contextLength = 4096,
            heapMegabytes = 256,
        )

        assertTrue("-cp" in command, "a java launcher needs the classpath: $command")
    }

    @Test
    fun `an unknown launcher and an empty classpath are reported, not guessed`() {
        assertFailsWith<AiException> {
            LlmHostCommandLine.build(null, "a.jar", "/tmp/s", 4096, 256)
        }
        assertFailsWith<AiException> {
            LlmHostCommandLine.build("/opt/jdk-21/bin/java", "  ", "/tmp/s", 4096, 256)
        }
    }

    @Test
    fun `the host flag tells a host run from an app run`() {
        assertTrue(LlmHostCommandLine.isHostRun(arrayOf("--llm-host", "/tmp/s", "4096")))
        assertFalse(LlmHostCommandLine.isHostRun(emptyArray()))
        assertFalse(LlmHostCommandLine.isHostRun(arrayOf("/tmp/s")))
    }
}
