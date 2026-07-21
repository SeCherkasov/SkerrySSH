package app.skerry.shared.ai.local

import app.skerry.shared.ai.AiChatRequest
import app.skerry.shared.ai.AiException
import app.skerry.shared.ai.AiMessage
import app.skerry.shared.ai.AiRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LlmHostProtocolTest {

    private val request = AiChatRequest(
        model = "qwen2.5-coder-1.5b",
        messages = listOf(
            AiMessage(AiRole.SYSTEM, "You are terse."),
            AiMessage(AiRole.USER, "line one\nline two"),
        ),
        temperature = 0.2,
        maxOutputTokens = 64,
    )

    @Test
    fun `generate command survives a round trip`() {
        val line = LlmHostProtocol.encode(LlmHostCommand.Generate("/models/m.gguf", request))

        assertEquals(LlmHostCommand.Generate("/models/m.gguf", request), LlmHostProtocol.decodeCommand(line))
    }

    @Test
    fun `cancel command survives a round trip`() {
        assertEquals(LlmHostCommand.Cancel, LlmHostProtocol.decodeCommand(LlmHostProtocol.encode(LlmHostCommand.Cancel)))
    }

    @Test
    fun `events survive a round trip`() {
        val events = listOf(
            LlmHostEvent.Delta("hello"),
            LlmHostEvent.Done,
            LlmHostEvent.Failure(AiException.Kind.INVALID_REQUEST, "no such model"),
        )

        events.forEach { assertEquals(it, LlmHostProtocol.decodeEvent(LlmHostProtocol.encode(it))) }
    }

    @Test
    fun `a frame is a single line even when the payload has newlines`() {
        val encoded = listOf(
            LlmHostProtocol.encode(LlmHostCommand.Generate("/m.gguf", request)),
            LlmHostProtocol.encode(LlmHostEvent.Delta("first\nsecond\r\nthird")),
        )

        encoded.forEach { assertTrue(it.none { c -> c == '\n' || c == '\r' }, "frame must stay on one line: $it") }
    }

    @Test
    fun `newlines in a delta are preserved`() {
        val decoded = LlmHostProtocol.decodeEvent(LlmHostProtocol.encode(LlmHostEvent.Delta("a\nb")))

        assertEquals(LlmHostEvent.Delta("a\nb"), decoded)
    }

    @Test
    fun `a malformed frame is rejected, not silently ignored`() {
        assertFailsWith<AiException> { LlmHostProtocol.decodeEvent("not json at all") }
        assertFailsWith<AiException> { LlmHostProtocol.decodeCommand("""{"type":"nope"}""") }
    }
}
