package app.skerry.ui.ai

import app.skerry.shared.ai.AiChatRequest
import app.skerry.shared.ai.AiMessage
import app.skerry.shared.ai.AiRole
import app.skerry.shared.ai.local.IsolatedLlmRuntime
import app.skerry.shared.ai.local.ProcessLlmHostLauncher
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.withTimeout

/**
 * Regression test for issue #37. Loading `libllama_jni.so` into a process that already has Skia and
 * AWT in it corrupts memory: generation aborts the whole JVM (`free(): invalid pointer`, always
 * inside the same `std::regex` bracket matcher), and often the `System.load` itself segfaults.
 * Ordering is the trigger — the very same library is fine in a bare JVM.
 *
 * So this test sets up exactly the deadly environment and then generates through
 * [IsolatedLlmRuntime]: the native library is loaded in the child host, and the app survives.
 * Before the fix, this test did not fail — it killed the test worker with exit code 134.
 *
 *     SKERRY_LOCAL_AI_MODEL=~/.local/share/skerry/models/qwen2.5-coder-1.5b-instruct-q4_k_m.gguf \
 *         ./gradlew :composeApp:desktopTest --tests '*LocalAiIsolationTest*'
 */
class LocalAiIsolationTest {

    @Test
    fun `generation survives with skia and awt loaded in the app process`() = runBlocking {
        val modelPath = System.getenv("SKERRY_LOCAL_AI_MODEL")?.takeIf { it.isNotBlank() }
            ?: return@runBlocking // not an e2e run: regular CI has no multi-gigabyte weights

        org.jetbrains.skia.Surface.makeRasterN32Premul(64, 64).canvas.clear(0)
        java.awt.Toolkit.getDefaultToolkit()

        val runtime = IsolatedLlmRuntime(ProcessLlmHostLauncher(contextLength = 4096))
        val request = AiChatRequest(
            model = "local-it",
            messages = listOf(
                AiMessage(AiRole.SYSTEM, "You are a terse shell assistant."),
                AiMessage(AiRole.USER, "Say OK."),
            ),
            maxOutputTokens = 24,
        )

        val text = withTimeout(5.minutes) {
            runtime.generate(modelPath.toPath(), request).toList().joinToString("") { it.text }
        }
        runtime.close()

        assertTrue(text.isNotBlank(), "the isolated host produced no output")
    }
}
