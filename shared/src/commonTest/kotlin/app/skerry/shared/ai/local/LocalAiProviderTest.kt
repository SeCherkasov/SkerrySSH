package app.skerry.shared.ai.local

import app.skerry.shared.ai.AiChatRequest
import app.skerry.shared.ai.AiDelta
import app.skerry.shared.ai.AiMessage
import app.skerry.shared.ai.AiRole
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okio.Path
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class FakeRuntime(private val deltas: List<String>) : LocalLlmRuntime {
    var lastPath: Path? = null
    var lastRequest: AiChatRequest? = null
    override fun generate(modelPath: Path, request: AiChatRequest): Flow<AiDelta> {
        lastPath = modelPath
        lastRequest = request
        return flowOf(*deltas.map { AiDelta(it) }.toTypedArray())
    }
}

class LocalAiProviderTest {

    private val model = LocalModelCatalog.qwen3_4b // model with extraSystem = "/no_think"
    private val path = "/data/models/m.gguf".toPath()

    private fun request(vararg messages: AiMessage) = AiChatRequest(model.id, messages.toList())

    @Test
    fun `delegates to the runtime with the model path`() = runTest {
        val runtime = FakeRuntime(listOf("hi"))
        val provider = LocalAiProvider(model, path, runtime)

        val out = provider.chat(request(AiMessage(AiRole.USER, "hello"))).toList()

        assertEquals(path, runtime.lastPath)
        assertEquals(listOf("hi"), out.map { it.text })
    }

    @Test
    fun `appends the catalog extraSystem hint to the system message`() = runTest {
        val runtime = FakeRuntime(listOf("ok"))
        val provider = LocalAiProvider(model, path, runtime)

        provider.chat(request(AiMessage(AiRole.SYSTEM, "You are a helper."), AiMessage(AiRole.USER, "hi"))).toList()

        val system = runtime.lastRequest!!.messages.first()
        assertEquals(AiRole.SYSTEM, system.role)
        assertTrue(system.content.startsWith("You are a helper."))
        assertTrue(system.content.endsWith(model.extraSystem!!), "system prompt must carry ${model.extraSystem}")
    }

    @Test
    fun `adds a system message when the request has none`() = runTest {
        val runtime = FakeRuntime(listOf("ok"))
        val provider = LocalAiProvider(model, path, runtime)

        provider.chat(request(AiMessage(AiRole.USER, "hi"))).toList()

        val messages = runtime.lastRequest!!.messages
        assertEquals(AiRole.SYSTEM, messages.first().role)
        assertEquals(model.extraSystem, messages.first().content)
        assertEquals(2, messages.size)
    }

    @Test
    fun `leaves messages untouched for models without extraSystem`() = runTest {
        val runtime = FakeRuntime(listOf("ok"))
        val provider = LocalAiProvider(LocalModelCatalog.phi4mini, path, runtime)

        provider.chat(request(AiMessage(AiRole.SYSTEM, "You are a helper."), AiMessage(AiRole.USER, "hi"))).toList()

        assertEquals("You are a helper.", runtime.lastRequest!!.messages.first().content)
    }

    @Test
    fun `filters the leading think block out of the stream`() = runTest {
        val runtime = FakeRuntime(listOf("<think>hm", "m</think>\n", "CMD: ls"))
        val provider = LocalAiProvider(model, path, runtime)

        val text = provider.chat(request(AiMessage(AiRole.USER, "list"))).toList().joinToString("") { it.text }

        assertEquals("CMD: ls", text)
    }

    @Test
    fun `chat flow can be collected twice with independent filtering`() = runTest {
        val runtime = FakeRuntime(listOf("<think>x</think>ok"))
        val provider = LocalAiProvider(model, path, runtime)
        val flow = provider.chat(request(AiMessage(AiRole.USER, "hi")))

        assertEquals("ok", flow.toList().joinToString("") { it.text })
        assertEquals("ok", flow.toList().joinToString("") { it.text })
    }
}
