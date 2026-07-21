package app.skerry.shared.ai.local

import app.skerry.shared.ai.AiChatRequest
import app.skerry.shared.ai.AiException
import app.skerry.shared.ai.AiMessage
import app.skerry.shared.ai.AiRole
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    fun `a missing runtime fails as an engine error instead of hanging`() = runBlocking {
        val runtime = IsolatedLlmRuntime(
            ProcessLlmHostLauncher(contextLength = 512, selfCommand = "/nonexistent/bin/java"),
        )

        val error = assertFailsWith<AiException> { runtime.generate("/m.gguf".toPath(), request).toList() }

        assertEquals(AiException.Kind.ENGINE_CRASHED, error.kind)
    }
}
