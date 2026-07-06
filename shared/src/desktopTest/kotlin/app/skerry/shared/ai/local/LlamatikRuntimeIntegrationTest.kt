package app.skerry.shared.ai.local

import app.skerry.shared.ai.AiChatRequest
import app.skerry.shared.ai.AiMessage
import app.skerry.shared.ai.AiRole
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Live inference via Llamatik (native llama.cpp) — integration test in the style of the e2e sync
 * tests: skipped in normal runs, enabled by the `SKERRY_LOCAL_AI_MODEL` env var — path to any
 * instruct GGUF (a tiny SmolLM2-135M is enough for a smoke test). Exercises the full native
 * runtime contract: model loading, chat template from GGUF, streaming generation.
 *
 *     SKERRY_LOCAL_AI_MODEL=/path/model.gguf ./gradlew :shared:desktopTest \
 *         --tests 'app.skerry.shared.ai.local.LlamatikRuntimeIntegrationTest'
 */
class LlamatikRuntimeIntegrationTest {

    @Test
    fun `loads a real gguf and streams a non-empty answer`() = runTest(timeout = 5.minutes) {
        val modelPath = System.getenv("SKERRY_LOCAL_AI_MODEL")?.takeIf { it.isNotBlank() }
            ?: return@runTest // not an e2e run: skip (regular CI has no multi-gigabyte weights)

        val runtime = LlamatikRuntime(contextLength = 1024)
        val request = AiChatRequest(
            model = "local-it",
            messages = listOf(
                AiMessage(AiRole.SYSTEM, "You are a terse shell assistant."),
                AiMessage(AiRole.USER, "Say OK."),
            ),
            maxOutputTokens = 32,
        )

        val text = runtime.generate(modelPath.toPath(), request).toList().joinToString("") { it.text }

        assertTrue(text.isNotBlank(), "local model produced no output")
        println("LOCAL-AI-IT output: ${text.take(200)}")
    }
}
