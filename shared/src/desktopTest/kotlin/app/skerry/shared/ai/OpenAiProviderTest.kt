package app.skerry.shared.ai

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.builtins.serializer
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OpenAiProviderTest {

    private val request = AiChatRequest(
        model = "gpt-4o-mini",
        messages = listOf(
            AiMessage(AiRole.SYSTEM, "You are a shell helper."),
            AiMessage(AiRole.USER, "How do I list files?"),
        ),
    )

    private lateinit var lastRequest: HttpRequestData

    private fun client(status: HttpStatusCode, body: String): HttpClient =
        HttpClient(MockEngine { req ->
            lastRequest = req
            respond(
                content = body,
                status = status,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })

    /** Строит SSE-поток chat-completions: один `data:`-кадр на дельту, завершается `[DONE]`. */
    private fun sse(vararg deltas: String): String {
        val frames = deltas.joinToString("") { chunk ->
            val quoted = Json.encodeToString(String.serializer(), chunk)
            "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":$quoted}}]}\n\n"
        }
        return frames + "data: [DONE]\n\n"
    }

    private suspend fun requestBody(): String {
        val body = lastRequest.body
        return (body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
    }

    @Test
    fun `sends model messages bearer key and requests streaming`() = runTest {
        val provider = OpenAiProvider(OpenAiConfig(apiKey = "sk-secret", model = "gpt-4o-mini"), client(HttpStatusCode.OK, sse("ls -la")))

        provider.chat(request).toList()

        assertTrue(lastRequest.url.toString().endsWith("/chat/completions"), "url was ${lastRequest.url}")
        assertEquals("Bearer sk-secret", lastRequest.headers[HttpHeaders.Authorization])
        val json = Json.parseToJsonElement(requestBody()).jsonObject
        assertEquals("gpt-4o-mini", json["model"]!!.jsonPrimitive.content)
        assertTrue(json["stream"]!!.jsonPrimitive.content.toBoolean(), "expected stream=true in request body")
        val messages = json["messages"]!!.jsonArray
        assertEquals(2, messages.size)
        assertEquals("system", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("user", messages[1].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("How do I list files?", messages[1].jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `streams assistant deltas token by token in order`() = runTest {
        val provider = OpenAiProvider(OpenAiConfig(apiKey = "sk-secret"), client(HttpStatusCode.OK, sse("Use ", "ls ", "-la")))

        val deltas = provider.chat(request).toList()

        assertEquals(listOf("Use ", "ls ", "-la"), deltas.map { it.text })
        assertEquals("Use ls -la", deltas.joinToString("") { it.text })
    }

    @Test
    fun `ignores keepalive comments blank lines and the done sentinel`() = runTest {
        val body = ": keep-alive\n\n" + "data: {\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}\n\n" +
            "data: {\"choices\":[{\"delta\":{}}]}\n\n" + "data: [DONE]\n\n"
        val provider = OpenAiProvider(OpenAiConfig(apiKey = "sk-x"), client(HttpStatusCode.OK, body))

        val text = provider.chat(request).toList().joinToString("") { it.text }

        assertEquals("hi", text)
    }

    @Test
    fun `maps 401 to UNAUTHORIZED`() = runTest {
        val provider = OpenAiProvider(OpenAiConfig(apiKey = "sk-bad"), client(HttpStatusCode.Unauthorized, """{"error":{"message":"nope"}}"""))

        val ex = assertFailsWith<AiException> { provider.chat(request).toList() }
        assertEquals(AiException.Kind.UNAUTHORIZED, ex.kind)
    }

    @Test
    fun `maps 429 to RATE_LIMITED`() = runTest {
        val provider = OpenAiProvider(OpenAiConfig(apiKey = "sk-x"), client(HttpStatusCode.TooManyRequests, """{"error":{"message":"slow down"}}"""))

        val ex = assertFailsWith<AiException> { provider.chat(request).toList() }
        assertEquals(AiException.Kind.RATE_LIMITED, ex.kind)
    }
}
