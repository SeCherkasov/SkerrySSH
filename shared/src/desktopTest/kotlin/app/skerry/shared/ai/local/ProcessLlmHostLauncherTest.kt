package app.skerry.shared.ai.local

import app.skerry.shared.ai.AiChatRequest
import app.skerry.shared.ai.AiException
import app.skerry.shared.ai.AiMessage
import app.skerry.shared.ai.AiRole
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.Path.Companion.toPath
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.minutes

/**
 * The real desktop host: a child JVM is started, connects back over its Unix socket and answers.
 * Deliberately uses a model path that does not exist — the answer travels the full pipeline
 * (spawn -> socket -> protocol -> typed failure) without needing multi-gigabyte weights, and the
 * native library never has to load.
 */
class ProcessLlmHostLauncherTest {

    private val request = AiChatRequest("local-it", listOf(AiMessage(AiRole.USER, "Say OK.")), maxOutputTokens = 8)

    @Test
    fun `reports a missing model through a real child process`() = runBlocking {
        val runtime = IsolatedLlmRuntime(ProcessLlmHostLauncher(contextLength = 512))

        val error = withTimeout(2.minutes) {
            assertFailsWith<AiException> {
                runtime.generate("/nonexistent/model.gguf".toPath(), request).toList()
            }
        }

        assertEquals(AiException.Kind.INVALID_REQUEST, error.kind)
        runtime.close()
    }

    @Test
    fun `the jpackage restart marker does not leak into the child`() = runBlocking {
        if (System.getProperty("os.name").startsWith("Windows")) return@runBlocking
        // Planted by the Gradle test task to mimic a packaged app's JVM. A child launcher that
        // inherits it feeds our --llm-host flag to the JVM and dies before reaching main.
        assertEquals("1", System.getenv("_JPACKAGE_LAUNCHER"), "the test task must plant the marker")

        val recorded = Files.createTempFile("skerry-llm-env", ".txt")
        val launcher = Files.createTempFile("skerry-fake-launcher", ".sh")
        try {
            Files.writeString(launcher, "#!/bin/sh\nprintenv > '$recorded'\n")
            launcher.toFile().setExecutable(true)
            val runtime = IsolatedLlmRuntime(
                ProcessLlmHostLauncher(contextLength = 512, selfCommand = launcher.toString()),
            )

            // The fake launcher exits without connecting back, so generation fails; only the
            // environment it saw matters here.
            assertFailsWith<AiException> { runtime.generate("/m.gguf".toPath(), request).toList() }

            val environment = Files.readString(recorded)
            assertFalse(
                environment.lineSequence().any { it.startsWith("_JPACKAGE_LAUNCHER=") },
                "the child saw the marker:\n$environment",
            )
        } finally {
            Files.deleteIfExists(recorded)
            Files.deleteIfExists(launcher)
        }
    }

    @Test
    fun `a missing runtime fails as an engine error instead of hanging`() = runBlocking {
        val runtime = IsolatedLlmRuntime(
            ProcessLlmHostLauncher(contextLength = 512, selfCommand = "/nonexistent/bin/java"),
        )

        val error = assertFailsWith<AiException> { runtime.generate("/m.gguf".toPath(), request).toList() }

        assertEquals(AiException.Kind.ENGINE_CRASHED, error.kind)
    }
}
