package app.skerry.ui.ai

import app.skerry.shared.ai.AiChatRequest
import app.skerry.shared.ai.AiMessage
import app.skerry.shared.ai.AiRole
import app.skerry.shared.ai.local.LlamatikRuntime
import app.skerry.shared.ai.local.LocalAiProvider
import app.skerry.shared.ai.local.LocalModelCatalog
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.minutes

/**
 * Terminal prompt quality against a live local model: a server-load question must yield a `CMD:`
 * reply, not a clarification request. Enabled via `SKERRY_LOCAL_AI_MODEL` (path to a GGUF from the
 * catalog; run against the default Qwen2.5-Coder-1.5B for a fair check).
 */
class TerminalPromptIntegrationTest {

    @Test
    fun `server-load question yields a runnable command, not a clarification`() = runTest(timeout = 5.minutes) {
        val modelPath = System.getenv("SKERRY_LOCAL_AI_MODEL")?.takeIf { it.isNotBlank() }
            ?: return@runTest // not an e2e run

        val provider = LocalAiProvider(LocalModelCatalog.default, modelPath.toPath(), LlamatikRuntime(contextLength = 2048))
        // Same prompt and low temperature as TerminalAiController.
        val request = AiChatRequest(
            model = LocalModelCatalog.default.id,
            messages = listOf(
                AiMessage(AiRole.SYSTEM, TerminalAiController.commandPrompt("Russian")),
                AiMessage(AiRole.USER, "какая нагрузка на сервере?"),
            ),
            temperature = TerminalAiController.COMMAND_TEMPERATURE,
        )

        // At temperature 0.2 the reply must be stable; run three times, any ASK is a failure.
        repeat(3) { round ->
            val raw = provider.chat(request).toList().joinToString("") { it.text }
            println("LOCAL-AI-PROMPT-IT round $round raw: ${raw.take(300)}")
            val reply = AiReplyParser.parse(raw)
            assertIs<AiReplyParser.Reply.Command>(reply, "round $round: expected a CMD reply, got: $raw")
            println("LOCAL-AI-PROMPT-IT round $round command: ${reply.command} | info: ${reply.info}")
        }
    }
}
