package app.skerry.ui.ai

import app.skerry.shared.ai.AiChatRequest
import app.skerry.shared.ai.AiDelta
import app.skerry.shared.ai.AiException
import app.skerry.shared.ai.AiProvider
import app.skerry.shared.ai.AiSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Тесты общего прогона стримингового AI-запроса (жизненный цикл провайдера и колбэки). */
class AiStreamRunnerTest {

    private class ScriptedProvider(
        private val deltas: List<String> = emptyList(),
        private val failWith: Exception? = null,
    ) : AiProvider {
        var closed = false
        override fun chat(request: AiChatRequest): Flow<AiDelta> = flow {
            failWith?.let { throw it }
            deltas.forEach { emit(AiDelta(it)) }
        }
        override suspend fun close() { closed = true }
    }

    private val config = AiSettings(apiKey = "sk-x").toOpenAiConfig()

    @Test
    fun `accumulates deltas, completes with the full text and closes the provider`() = runTest {
        val provider = ScriptedProvider(deltas = listOf("Hel", "lo"))
        val runner = AiStreamRunner({ provider }, this)
        val seen = mutableListOf<String>()
        var completed: String? = null
        var finallyCalled = false

        runner.launch(config, emptyList(), onDelta = { seen += it }, onComplete = { completed = it },
            onError = { }, onFinally = { finallyCalled = true })
        advanceUntilIdle()

        assertEquals(listOf("Hel", "Hello"), seen)
        assertEquals("Hello", completed)
        assertTrue(provider.closed)
        assertTrue(finallyCalled)
    }

    @Test
    fun `maps AiException to a friendly message and still closes the provider`() = runTest {
        val provider = ScriptedProvider(failWith = AiException(AiException.Kind.UNAUTHORIZED, "401"))
        val runner = AiStreamRunner({ provider }, this)
        var error: String? = null
        var completed: String? = null

        runner.launch(config, emptyList(), onDelta = { }, onComplete = { completed = it },
            onError = { error = it }, onFinally = { })
        advanceUntilIdle()

        assertEquals(AiException(AiException.Kind.UNAUTHORIZED, "401").friendlyMessage(), error)
        assertNull(completed)
        assertTrue(provider.closed)
    }

    @Test
    fun `maps an unexpected exception to a generic message`() = runTest {
        val runner = AiStreamRunner({ ScriptedProvider(failWith = RuntimeException("boom")) }, this)
        var error: String? = null

        runner.launch(config, emptyList(), onDelta = { }, onComplete = { },
            onError = { error = it }, onFinally = { })
        advanceUntilIdle()

        assertEquals("AI request failed: boom", error)
    }

    @Test
    fun `cancellation runs onFinally without reporting an error`() = runTest {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val provider = object : AiProvider {
            override fun chat(request: AiChatRequest): Flow<AiDelta> = flow {
                emit(AiDelta("part"))
                gate.await()
            }
            override suspend fun close() {}
        }
        val runner = AiStreamRunner({ provider }, this)
        var error: String? = null
        var finallyCalled = false

        val job = runner.launch(config, emptyList(), onDelta = { }, onComplete = { },
            onError = { error = it }, onFinally = { finallyCalled = true })
        runCurrent()
        job.cancel()
        advanceUntilIdle()

        assertNull(error)
        assertTrue(finallyCalled)
    }
}
