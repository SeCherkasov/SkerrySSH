package app.skerry.server.routes

import app.skerry.server.configureServer
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class RegistrationPolicyTest {

    @Test
    fun `open registration accepts a new account`() = testApplication {
        val services = testServices(extraEnv = mapOf("SKERRY_REGISTRATION" to "open"))
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val resp = client.registerAccountResponse("alice@example.com", "pw-hex-1")
        assertEquals(HttpStatusCode.OK, resp.status)
    }

    @Test
    fun `closed registration rejects a new account with 403`() = testApplication {
        val services = testServices(extraEnv = mapOf("SKERRY_REGISTRATION" to "closed"))
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val resp = client.registerAccountResponse("alice@example.com", "pw-hex-1")
        assertEquals(HttpStatusCode.Forbidden, resp.status)
    }

    @Test
    fun `account cap rejects registration once the limit is reached`() = testApplication {
        val services = testServices(extraEnv = mapOf("SKERRY_MAX_ACCOUNTS" to "1"))
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }

        assertEquals(HttpStatusCode.OK, client.registerAccountResponse("first@example.com", "pw-hex-1").status)
        assertEquals(HttpStatusCode.Forbidden, client.registerAccountResponse("second@example.com", "pw-hex-2").status)
    }

    @Test
    fun `default config leaves registration open`() = testApplication {
        val services = testServices()
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }

        assertEquals(HttpStatusCode.OK, client.registerAccountResponse("alice@example.com", "pw-hex-1").status)
    }
}
