package app.skerry.server.db

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Scopes: a share space inside a team with its own key, its own epoch, and an explicit grant list.
 * The server stores grants and sealed keys; it never sees a scope key or a scope name.
 */
class TeamScopeRepositoryTest {

    private val alice = "alice@example.com"
    private val bob = "bob@example.com"
    private val carol = "carol@example.com"

    private suspend fun seed(db: org.jetbrains.exposed.v1.jdbc.Database): TeamScopeRepository {
        seedAccount(db, alice)
        seedAccount(db, bob)
        seedAccount(db, carol)
        val teams = TeamRepository(db)
        teams.create("team-1", alice, now = 10)
        teams.invite("team-1", bob, TeamRoles.EDITOR, byteArrayOf(1), alice, now = 11)
        teams.accept("team-1", bob)
        teams.invite("team-1", carol, TeamRoles.VIEWER, byteArrayOf(1), alice, now = 12)
        teams.accept("team-1", carol)
        return TeamScopeRepository(db)
    }

    @Test
    fun `creating a scope grants it to the creator and is visible only to grantees`() = withTestDb { db ->
        val scopes = seed(db)

        assertTrue(scopes.create("team-1", "prod", alice, envelope = byteArrayOf(9), now = 20))

        val forAlice = scopes.scopesFor("team-1", alice, all = false).single()
        assertEquals("prod", forAlice.scopeId)
        assertEquals(0L, forAlice.keyEpoch)
        assertEquals(1, forAlice.memberCount)
        assertContentEquals(byteArrayOf(9), forAlice.envelope)

        // A member without a grant is not even told the scope exists.
        assertTrue(scopes.scopesFor("team-1", bob, all = false).isEmpty())
        // A manager listing all scopes sees it, but without a key envelope of their own.
        val forManager = scopes.scopesFor("team-1", bob, all = true).single()
        assertEquals("prod", forManager.scopeId)
        assertNull(forManager.envelope)
    }

    @Test
    fun `a scope id is unique per team but free across teams`() = withTestDb { db ->
        val scopes = seed(db)
        TeamRepository(db).create("team-2", alice, now = 13)

        assertTrue(scopes.create("team-1", "prod", alice, byteArrayOf(1), now = 20))
        assertFalse(scopes.create("team-1", "prod", alice, byteArrayOf(2), now = 21))
        assertTrue(scopes.create("team-2", "prod", alice, byteArrayOf(3), now = 22))
    }

    @Test
    fun `grant and revoke move a member in and out of the scope`() = withTestDb { db ->
        val scopes = seed(db)
        scopes.create("team-1", "prod", alice, byteArrayOf(1), now = 20)

        assertTrue(scopes.grant("team-1", "prod", bob, envelope = byteArrayOf(2), now = 21))
        assertTrue(scopes.hasGrant("team-1", "prod", bob))
        assertEquals(listOf(alice, bob).sorted(), scopes.grants("team-1", "prod").map { it.accountId }.sorted())

        assertTrue(scopes.revoke("team-1", "prod", bob))
        assertFalse(scopes.hasGrant("team-1", "prod", bob))
        assertFalse(scopes.revoke("team-1", "prod", bob))
        assertEquals(listOf(alice), scopes.grants("team-1", "prod").map { it.accountId })
    }

    @Test
    fun `granting an unknown scope fails instead of creating one`() = withTestDb { db ->
        val scopes = seed(db)

        assertFalse(scopes.grant("team-1", "ghost", bob, byteArrayOf(2), now = 21))
        assertFalse(scopes.hasGrant("team-1", "ghost", bob))
    }

    @Test
    fun `regranting replaces the envelope rather than duplicating the grant`() = withTestDb { db ->
        val scopes = seed(db)
        scopes.create("team-1", "prod", alice, byteArrayOf(1), now = 20)
        scopes.grant("team-1", "prod", bob, byteArrayOf(2), now = 21)

        assertTrue(scopes.grant("team-1", "prod", bob, byteArrayOf(3), now = 22))

        assertEquals(2, scopes.grants("team-1", "prod").size)
        assertContentEquals(byteArrayOf(3), scopes.scopesFor("team-1", bob, all = false).single().envelope)
    }

    @Test
    fun `rekey is a monotonic compare-and-set and re-seals the key to grantees`() = withTestDb { db ->
        val scopes = seed(db)
        scopes.create("team-1", "prod", alice, byteArrayOf(1), now = 20)
        scopes.grant("team-1", "prod", bob, byteArrayOf(2), now = 21)

        assertEquals(
            RekeyOutcome.OK,
            scopes.rekey("team-1", "prod", newEpoch = 1, envelopes = mapOf(alice to byteArrayOf(11), bob to byteArrayOf(12))),
        )
        assertEquals(1L, scopes.scopesFor("team-1", alice, all = false).single().keyEpoch)
        assertContentEquals(byteArrayOf(12), scopes.scopesFor("team-1", bob, all = false).single().envelope)

        // Replaying the same epoch (or skipping one) must not split the key across grantees.
        assertEquals(RekeyOutcome.EPOCH_CONFLICT, scopes.rekey("team-1", "prod", 1, mapOf(alice to byteArrayOf(13))))
        assertEquals(RekeyOutcome.EPOCH_CONFLICT, scopes.rekey("team-1", "prod", 3, mapOf(alice to byteArrayOf(13))))
        assertEquals(RekeyOutcome.NO_TEAM, scopes.rekey("team-1", "ghost", 1, mapOf(alice to byteArrayOf(13))))
        assertContentEquals(byteArrayOf(11), scopes.scopesFor("team-1", alice, all = false).single().envelope)
    }

    @Test
    fun `rekey does not resurrect a revoked grant`() = withTestDb { db ->
        val scopes = seed(db)
        scopes.create("team-1", "prod", alice, byteArrayOf(1), now = 20)
        scopes.grant("team-1", "prod", bob, byteArrayOf(2), now = 21)
        scopes.revoke("team-1", "prod", bob)

        scopes.rekey("team-1", "prod", 1, mapOf(alice to byteArrayOf(11), bob to byteArrayOf(12)))

        assertFalse(scopes.hasGrant("team-1", "prod", bob))
        assertTrue(scopes.scopesFor("team-1", bob, all = false).isEmpty())
    }

    @Test
    fun `deleting a scope drops its grants and its records`() = withTestDb { db ->
        val scopes = seed(db)
        val records = TeamRecordRepository(db)
        scopes.create("team-1", "prod", alice, byteArrayOf(1), now = 20)
        records.upsert("team-1", "prod", listOf(incoming("r1")))
        records.upsert("team-1", "", listOf(incoming("r2")))

        assertTrue(scopes.delete("team-1", "prod"))

        assertTrue(scopes.scopesFor("team-1", alice, all = true).isEmpty())
        assertTrue(scopes.grants("team-1", "prod").isEmpty())
        assertTrue(records.delta("team-1", "prod", 0).records.isEmpty())
        // Team-wide records are untouched.
        assertEquals(listOf("r2"), records.delta("team-1", "", 0).records.map { it.id })
    }

    @Test
    fun `removing a member from the team revokes every scope grant they held`() = withTestDb { db ->
        val scopes = seed(db)
        scopes.create("team-1", "prod", alice, byteArrayOf(1), now = 20)
        scopes.create("team-1", "staging", alice, byteArrayOf(1), now = 21)
        scopes.grant("team-1", "prod", bob, byteArrayOf(2), now = 22)
        scopes.grant("team-1", "staging", bob, byteArrayOf(3), now = 23)
        scopes.grant("team-1", "prod", carol, byteArrayOf(4), now = 24)

        // Returned so the caller knows which scope keys need rotating.
        assertEquals(listOf("prod", "staging"), scopes.revokeAll("team-1", bob).sorted())

        assertFalse(scopes.hasGrant("team-1", "prod", bob))
        assertFalse(scopes.hasGrant("team-1", "staging", bob))
        assertTrue(scopes.hasGrant("team-1", "prod", carol))
    }

    @Test
    fun `deleting the team removes its scopes, grants and scoped records`() = withTestDb { db ->
        val scopes = seed(db)
        val records = TeamRecordRepository(db)
        scopes.create("team-1", "prod", alice, byteArrayOf(1), now = 20)
        scopes.grant("team-1", "prod", bob, byteArrayOf(2), now = 21)
        records.upsert("team-1", "prod", listOf(incoming("r1")))

        assertTrue(TeamRepository(db).deleteTeam("team-1"))

        assertTrue(scopes.scopesFor("team-1", alice, all = true).isEmpty())
        assertTrue(scopes.grants("team-1", "prod").isEmpty())
        assertTrue(records.delta("team-1", "prod", 0).records.isEmpty())
    }

    private fun incoming(id: String) =
        IncomingRecord(id, "HOST", 1, "2026-07-26T00:00:00Z", "devA", deleted = false, blob = byteArrayOf(1))
}
