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
 * Живой инференс через Llamatik (нативный llama.cpp) — интеграционный тест по образцу e2e sync:
 * в обычном прогоне пропускается, включается переменной окружения `SKERRY_LOCAL_AI_MODEL` —
 * путь к любому instruct-GGUF (для дыма достаточно крошечной SmolLM2-135M). Проверяет весь
 * нативный контракт рантайма: загрузка модели, chat-шаблон из GGUF, стриминговая генерация.
 *
 *     SKERRY_LOCAL_AI_MODEL=/path/model.gguf ./gradlew :shared:desktopTest \
 *         --tests 'app.skerry.shared.ai.local.LlamatikRuntimeIntegrationTest'
 */
class LlamatikRuntimeIntegrationTest {

    @Test
    fun `loads a real gguf and streams a non-empty answer`() = runTest(timeout = 5.minutes) {
        val modelPath = System.getenv("SKERRY_LOCAL_AI_MODEL")?.takeIf { it.isNotBlank() }
            ?: return@runTest // не e2e-прогон: пропускаем (обычный CI без гигабайтных весов)

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
