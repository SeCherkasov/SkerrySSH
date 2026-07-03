package app.skerry.server.routes

import app.skerry.server.configureServer
import app.skerry.server.model.TokenResponse
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.bearerAuth
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

class SyncWebSocketTest {

    private val accountId = "alice@example.com"
    private val password = "auth-key-hex-abc123"

    @Test
    fun `client close frame ends the session and releases the notifier subscription`() = testApplication {
        val services = testServices()
        application { configureServer(services) }
        val client = createClient {
            install(ContentNegotiation) { json() }
            install(WebSockets)
        }
        val tokens: TokenResponse = client.registerAccount(accountId, password)

        client.webSocket("/sync", request = { bearerAuth(tokens.accessToken) }) {
            // Дожидаемся регистрации серверной подписки, затем проверяем сам push-канал.
            withTimeout(2_000) { services.notifier.subscriptions.first { it == 1 } }
            services.notifier.publish(accountId, 7)
            val frame = withTimeout(2_000) { incoming.receive() } as Frame.Text
            assertEquals("7", frame.readText())
            close(CloseReason(CloseReason.Codes.NORMAL, "done"))
        }

        // Раньше хендлер не читал incoming: Close клиента не обрабатывался и collect висел
        // до следующего publish. Подписка обязана освободиться сразу после закрытия.
        withTimeout(2_000) { services.notifier.subscriptions.first { it == 0 } }
    }

    @Test
    fun `revoked device is disconnected with VIOLATED_POLICY on next notification`() = testApplication {
        val services = testServices()
        application { configureServer(services) }
        val client = createClient {
            install(ContentNegotiation) { json() }
            install(WebSockets)
        }
        val tokens: TokenResponse = client.registerAccount(accountId, password, deviceId = "devA")

        client.webSocket("/sync", request = { bearerAuth(tokens.accessToken) }) {
            withTimeout(2_000) { services.notifier.subscriptions.first { it == 1 } }
            // JWT проверяется только на рукопожатии — отзыв после подключения обязан
            // перепроверяться на каждом уведомлении и закрывать сессию, а не слать push дальше.
            services.devices.revoke(accountId, "devA")
            services.notifier.publish(accountId, 1)
            val reason = withTimeout(2_000) { closeReason.await() }
            assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, reason?.code)
        }
    }
}
