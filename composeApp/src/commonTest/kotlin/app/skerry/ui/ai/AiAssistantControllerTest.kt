package app.skerry.ui.ai

import app.skerry.shared.ai.AiChatRequest
import app.skerry.shared.ai.AiDelta
import app.skerry.shared.ai.AiException
import app.skerry.shared.ai.AiProvider
import app.skerry.shared.ai.AiRole
import app.skerry.shared.ai.AiSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeProvider(
    private val deltas: List<String> = emptyList(),
    private val failWith: AiException? = null,
) : AiProvider {
    var closed = false
    override fun chat(request: AiChatRequest): Flow<AiDelta> = flow {
        failWith?.let { throw it }
        deltas.forEach { emit(AiDelta(it)) }
    }
    override suspend fun close() { closed = true }
}

class AiAssistantControllerTest {

    @Test
    fun `ask appends assistant reply assembled from streamed deltas`() = runTest {
        val provider = FakeProvider(deltas = listOf("Hel", "lo"))
        val c = AiAssistantController(AiSettings(apiKey = "sk-x"), persist = {}, providerFactory = { provider }, scope = this)

        c.ask("hi")
        advanceUntilIdle()

        assertEquals(2, c.turns.size)
        assertEquals(AiRole.USER, c.turns[0].role)
        assertEquals("hi", c.turns[0].text)
        assertEquals(AiRole.ASSISTANT, c.turns[1].role)
        assertEquals("Hello", c.turns[1].text)
        assertFalse(c.busy)
        assertNull(c.streaming)
        assertTrue(provider.closed, "provider must be closed after the request")
    }

    @Test
    fun `ask surfaces a friendly error and keeps only the user turn`() = runTest {
        val provider = FakeProvider(failWith = AiException(AiException.Kind.UNAUTHORIZED, "401"))
        val c = AiAssistantController(AiSettings(apiKey = "sk-bad"), persist = {}, providerFactory = { provider }, scope = this)

        c.ask("hi")
        advanceUntilIdle()

        assertEquals(1, c.turns.size)
        assertNotNull(c.error)
        assertFalse(c.busy)
    }

    @Test
    fun `ask is a no-op when not configured`() = runTest {
        val c = AiAssistantController(AiSettings(), persist = {}, providerFactory = { error("must not build a provider") }, scope = this)

        c.ask("hi")
        advanceUntilIdle()

        assertTrue(c.turns.isEmpty())
    }

    @Test
    fun `save trims key and defaults blank fields`() = runTest {
        var saved: AiSettings? = null
        val c = AiAssistantController(AiSettings(), persist = { saved = it }, providerFactory = { error("unused") }, scope = this)

        c.save("  sk-new  ", "gpt-4o", "")

        assertEquals("sk-new", saved!!.apiKey)
        assertEquals("gpt-4o", saved!!.model)
        assertEquals(AiSettings().baseUrl, saved!!.baseUrl)
        assertTrue(c.isConfigured)
    }
}
