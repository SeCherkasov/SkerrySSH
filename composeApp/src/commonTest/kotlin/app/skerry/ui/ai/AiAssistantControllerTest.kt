package app.skerry.ui.ai

import app.skerry.shared.ai.AiChatRequest
import app.skerry.shared.ai.AiDelta
import app.skerry.shared.ai.AiException
import app.skerry.shared.ai.AiProvider
import app.skerry.shared.ai.AiRole
import app.skerry.shared.ai.AiSettings
import app.skerry.shared.ai.SecretRedactor
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
    var lastRequest: AiChatRequest? = null
    override fun chat(request: AiChatRequest): Flow<AiDelta> = flow {
        lastRequest = request
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
    fun `ask redacts secrets before storing the turn and sending to the cloud`() = runTest {
        val provider = FakeProvider(deltas = listOf("ok"))
        val c = AiAssistantController(AiSettings(apiKey = "sk-x"), persist = {}, providerFactory = { provider }, scope = this)

        c.ask("deploy fails, config has password=hunter2 — why?")
        advanceUntilIdle()

        assertFalse(c.turns[0].text.contains("hunter2"), "секрет не должен остаться в ленте")
        assertTrue(c.turns[0].text.contains(SecretRedactor.MASK), "лента показывает, что реально ушло")
        val sent = provider.lastRequest!!.messages
        assertTrue(sent.none { it.content.contains("hunter2") }, "секрет не должен уйти провайдеру")
    }

    @Test
    fun `ask keeps earlier secrets redacted in the history it resends`() = runTest {
        val provider = FakeProvider(deltas = listOf("ok"))
        val c = AiAssistantController(AiSettings(apiKey = "sk-x"), persist = {}, providerFactory = { provider }, scope = this)

        c.ask("token=abc123secret")
        advanceUntilIdle()
        c.ask("and now?")
        advanceUntilIdle()

        val sent = provider.lastRequest!!.messages
        assertTrue(sent.none { it.content.contains("abc123secret") }, "секрет из истории не должен уйти провайдеру")
    }

    @Test
    fun `ask is a no-op when not configured`() = runTest {
        val c = AiAssistantController(AiSettings(), persist = {}, providerFactory = { error("must not build a provider") }, scope = this)

        c.ask("hi")
        advanceUntilIdle()

        assertTrue(c.turns.isEmpty())
    }

    @Test
    fun `device provider asks the local model when installed`() = runTest {
        val provider = FakeProvider(deltas = listOf("ok"))
        var endpoint: app.skerry.shared.ai.AiEndpoint? = null
        val c = AiAssistantController(
            AiSettings(provider = app.skerry.shared.ai.AiProviderKind.DEVICE),
            persist = {},
            providerFactory = { e -> endpoint = e; provider },
            scope = this,
            localInstalled = { true },
        )

        assertTrue(c.ready)
        c.ask("hi")
        advanceUntilIdle()

        assertTrue(endpoint is app.skerry.shared.ai.AiEndpoint.Device, "expected on-device endpoint, got $endpoint")
        assertEquals(2, c.turns.size)
    }

    @Test
    fun `device provider without the model is not ready and ask is a no-op`() = runTest {
        val c = AiAssistantController(
            AiSettings(provider = app.skerry.shared.ai.AiProviderKind.DEVICE),
            persist = {},
            providerFactory = { error("must not build a provider") },
            scope = this,
            localInstalled = { false },
        )

        assertFalse(c.ready)
        c.ask("hi")
        advanceUntilIdle()

        assertTrue(c.turns.isEmpty())
    }

    @Test
    fun `off provider disables the assistant and ask is a no-op`() = runTest {
        val c = AiAssistantController(
            AiSettings(apiKey = "sk-x", provider = app.skerry.shared.ai.AiProviderKind.OFF),
            persist = {},
            providerFactory = { error("must not build a provider") },
            scope = this,
            localInstalled = { true },
        )

        assertFalse(c.enabled)
        assertFalse(c.ready)
        c.ask("hi")
        advanceUntilIdle()

        assertTrue(c.turns.isEmpty())
    }

    @Test
    fun `selecting off keeps byok config for a later re-enable`() = runTest {
        var saved: AiSettings? = null
        val c = AiAssistantController(AiSettings(apiKey = "sk-x"), persist = { saved = it }, providerFactory = { error("unused") }, scope = this)

        c.selectProvider(app.skerry.shared.ai.AiProviderKind.OFF)

        assertEquals(app.skerry.shared.ai.AiProviderKind.OFF, saved!!.provider)
        assertEquals("sk-x", saved!!.apiKey, "ключ BYOK не должен пропасть при выключении AI")
        assertFalse(c.enabled)
    }

    @Test
    fun `save keeps the provider selection intact`() = runTest {
        // Регрессия: save() BYOK-полей не должен сбрасывать выбор «на устройстве» и модель.
        var saved: AiSettings? = null
        val initial = AiSettings(provider = app.skerry.shared.ai.AiProviderKind.DEVICE, localModelId = "qwen3-4b-q4km")
        val c = AiAssistantController(initial, persist = { saved = it }, providerFactory = { error("unused") }, scope = this)

        c.save("sk-new", "gpt-4o", "")

        assertEquals(app.skerry.shared.ai.AiProviderKind.DEVICE, saved!!.provider)
        assertEquals("qwen3-4b-q4km", saved!!.localModelId)
    }

    @Test
    fun `selectProvider persists immediately`() = runTest {
        var saved: AiSettings? = null
        val c = AiAssistantController(AiSettings(apiKey = "sk-x"), persist = { saved = it }, providerFactory = { error("unused") }, scope = this)

        c.selectProvider(app.skerry.shared.ai.AiProviderKind.DEVICE)

        assertEquals(app.skerry.shared.ai.AiProviderKind.DEVICE, saved!!.provider)
        assertEquals("sk-x", saved!!.apiKey, "ключ BYOK не должен пропасть при смене провайдера")
    }

    @Test
    fun `selectLocalModel persists immediately and keeps the rest intact`() = runTest {
        var saved: AiSettings? = null
        val initial = AiSettings(apiKey = "sk-x", provider = app.skerry.shared.ai.AiProviderKind.DEVICE)
        val c = AiAssistantController(initial, persist = { saved = it }, providerFactory = { error("unused") }, scope = this)

        c.selectLocalModel("qwen3-4b-q4km")

        assertEquals("qwen3-4b-q4km", saved!!.localModelId)
        assertEquals(app.skerry.shared.ai.AiProviderKind.DEVICE, saved!!.provider)
        assertEquals("sk-x", saved!!.apiKey)
        assertEquals("qwen3-4b-q4km", c.localModel.id, "контроллер сразу отражает выбор")
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
