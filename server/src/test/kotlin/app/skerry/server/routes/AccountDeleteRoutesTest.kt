package app.skerry.server.routes

import app.skerry.server.configureServer
import app.skerry.server.db.TeamMemberStatus
import app.skerry.server.db.TeamRoles
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Deleting an account reaches into other people's teams — it can hand one over or delete it
 * outright. Both facts have to leave the server: in the audit log by name, and as a live signal to
 * whoever is still in that team, the same way every other membership change does.
 */
class AccountDeleteRoutesTest {

    private val admin = "s3cret"

    @Test
    fun `the audit line names the teams that were transferred and deleted`() = testApplication {
        val services = testServices(adminToken = admin)
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }
        client.registerAccount("owner@example.com", "pw-1")
        client.registerAccount("heir@example.com", "pw-2")
        services.teams.create("kept", "owner@example.com", NOW)
        services.teams.invite("kept", "heir@example.com", TeamRoles.ADMIN, byteArrayOf(1), "owner@example.com", NOW)
        services.teams.accept("kept", "heir@example.com")
        services.teams.create("solo", "owner@example.com", NOW)

        assertEquals(
            HttpStatusCode.NoContent,
            client.delete("/admin/accounts/owner%40example.com") { header("X-Admin-Token", admin) }.status,
        )

        // Counts alone are useless after the fact: the owner column is already rewritten and the
        // account row is gone, so the ids exist nowhere else.
        val line = services.activity.recent(50).first { it.event == "account.deleted" }.detail
        assertTrue("kept" in line && "solo" in line, line)

        // The team's own members read their feed, not the admin console's — so the fact that their
        // owner vanished has to be in the team bucket too.
        val teamFeed = services.activity.recentForTeam("kept", 50)
        assertTrue(teamFeed.any { it.event == "team.owner_replaced" }, "$teamFeed")
    }

    @Test
    fun `remaining members get a live membership signal`() = testApplication {
        val services = testServices(adminToken = admin)
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }
        client.registerAccount("owner@example.com", "pw-1")
        client.registerAccount("heir@example.com", "pw-2")
        services.teams.create("t1", "owner@example.com", NOW)
        services.teams.invite("t1", "heir@example.com", TeamRoles.ADMIN, byteArrayOf(1), "owner@example.com", NOW)
        services.teams.accept("t1", "heir@example.com")

        // The signal flow has no replay, so the collector must be subscribed before the delete runs
        // — otherwise the test races the publish and hangs on a signal that already happened.
        val signal = CoroutineScope(Dispatchers.Default).async { withTimeout(5_000) { services.notifier.forMembership("heir@example.com").first() } }
        services.notifier.subscriptions.first { it > 0 }

        client.delete("/admin/accounts/owner%40example.com") { header("X-Admin-Token", admin) }

        signal.await()
        assertEquals(TeamMemberStatus.ACTIVE, services.teams.membership("t1", "heir@example.com")?.status)
        assertEquals(TeamRoles.OWNER, services.teams.membership("t1", "heir@example.com")?.role)
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
    }
}
