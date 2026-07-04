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
 * Качество терминального промпта на ЖИВОЙ локальной модели: вопрос о сервере обязан дать
 * `CMD:`-ответ, а не просьбу «уточните» (регрессия: локальная модель на «какая нагрузка на сервере?»
 * просила информацию о сервере, пока промпт явно не сказал, что команда выполняется на уже
 * подключённом хосте, и не показал few-shot-примеры). Включается `SKERRY_LOCAL_AI_MODEL` —
 * путь к GGUF из каталога (для честности гонять на дефолтной Qwen2.5-Coder-1.5B).
 */
class TerminalPromptIntegrationTest {

    @Test
    fun `server-load question yields a runnable command, not a clarification`() = runTest(timeout = 5.minutes) {
        val modelPath = System.getenv("SKERRY_LOCAL_AI_MODEL")?.takeIf { it.isNotBlank() }
            ?: return@runTest // не e2e-прогон

        val provider = LocalAiProvider(LocalModelCatalog.default, modelPath.toPath(), LlamatikRuntime(contextLength = 2048))
        // Запрос — ровно как из TerminalAiController: тот же промпт и та же низкая температура.
        val request = AiChatRequest(
            model = LocalModelCatalog.default.id,
            messages = listOf(
                AiMessage(AiRole.SYSTEM, TerminalAiController.commandPrompt("Russian")),
                AiMessage(AiRole.USER, "какая нагрузка на сервере?"),
            ),
            temperature = TerminalAiController.COMMAND_TEMPERATURE,
        )

        // На temperature 0.2 ответ обязан быть стабильным — гоняем трижды, ASK-лотерея = провал.
        repeat(3) { round ->
            val raw = provider.chat(request).toList().joinToString("") { it.text }
            println("LOCAL-AI-PROMPT-IT round $round raw: ${raw.take(300)}")
            val reply = AiReplyParser.parse(raw)
            assertIs<AiReplyParser.Reply.Command>(reply, "round $round: expected a CMD reply, got: $raw")
            println("LOCAL-AI-PROMPT-IT round $round command: ${reply.command} | info: ${reply.info}")
        }
    }
}
