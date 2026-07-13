package app.skerry.server.db

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TeamRepositoryTest {

    private val alice = "alice@example.com"
    private val bob = "bob@example.com"

    private suspend fun seedTwoAccounts(db: org.jetbrains.exposed.v1.jdbc.Database) {
        seedAccount(db, alice)
        seedAccount(db, bob)
    }

    @Test
    fun `publishKey stores and replaces the public key`() = withTestDb { db ->
        seedAccount(db, alice)
        val repo = TeamRepository(db)

        assertNull(repo.accountKeys(alice))
        repo.publishKey(alice, byteArrayOf(1, 2, 3), byteArrayOf(4, 5), now = 10)
        assertContentEquals(byteArrayOf(1, 2, 3), repo.accountKeys(alice)?.publicKey)
        assertContentEquals(byteArrayOf(4, 5), repo.accountKeys(alice)?.signPublicKey)

        repo.publishKey(alice, byteArrayOf(9), byteArrayOf(8), now = 20)
        assertContentEquals(byteArrayOf(9), repo.accountKeys(alice)?.publicKey)
        assertContentEquals(byteArrayOf(8), repo.accountKeys(alice)?.signPublicKey)
    }

    @Test
    fun `create makes owner an active member and rejects duplicate id`() = withTestDb { db ->
        seedAccount(db, alice)
        val repo = TeamRepository(db)

        assertTrue(repo.create("team-1", alice, now = 10))
        assertFalse(repo.create("team-1", alice, now = 11))

        val membership = repo.membership("team-1", alice)!!
        assertEquals(TeamRoles.OWNER, membership.role)
        assertEquals(TeamMemberStatus.ACTIVE, membership.status)
        assertNull(membership.envelope)
        assertEquals(listOf("team-1"), repo.activeTeamIdsFor(alice))
    }

    @Test
    fun `invite then accept activates membership and clears the envelope`() = withTestDb { db ->
        seedTwoAccounts(db)
        val repo = TeamRepository(db)
        repo.create("team-1", alice, now = 10)

        assertTrue(repo.invite("team-1", bob, TeamRoles.EDITOR, envelope = byteArrayOf(7, 7), invitedBy = alice, now = 20))
        // re-inviting the same account is rejected
        assertFalse(repo.invite("team-1", bob, TeamRoles.VIEWER, envelope = byteArrayOf(8), invitedBy = alice, now = 21))

        // before acceptance: invited, envelope available, not counted as active
        val invited = repo.teamsFor(bob).single()
        assertEquals(TeamMemberStatus.INVITED, invited.status)
        assertContentEquals(byteArrayOf(7, 7), invited.envelope)
        assertEquals(emptyList(), repo.activeTeamIdsFor(bob))
        assertEquals(2, invited.memberCount)

        assertTrue(repo.accept("team-1", bob))
        // accepting again is a no-op
        assertFalse(repo.accept("team-1", bob))

        val active = repo.membership("team-1", bob)!!
        assertEquals(TeamMemberStatus.ACTIVE, active.status)
        assertEquals(TeamRoles.EDITOR, active.role)
        assertNull(active.envelope)
        assertEquals(listOf("team-1"), repo.activeTeamIdsFor(bob))
    }

    @Test
    fun `updateRole changes member role but leaves owner fixed`() = withTestDb { db ->
        seedTwoAccounts(db)
        val repo = TeamRepository(db)
        repo.create("team-1", alice, now = 10)
        repo.invite("team-1", bob, TeamRoles.VIEWER, byteArrayOf(1), alice, now = 20)
        repo.accept("team-1", bob)

        assertTrue(repo.updateRole("team-1", bob, TeamRoles.ADMIN))
        assertEquals(TeamRoles.ADMIN, repo.membership("team-1", bob)!!.role)

        // the owner's role is unchanged; a non-existent member returns false
        assertFalse(repo.updateRole("team-1", alice, TeamRoles.VIEWER))
        assertEquals(TeamRoles.OWNER, repo.membership("team-1", alice)!!.role)
        assertFalse(repo.updateRole("team-1", "ghost@example.com", TeamRoles.VIEWER))
    }

    @Test
    fun `rekey bumps the epoch and stores per-member key envelopes`() = withTestDb { db ->
        seedTwoAccounts(db)
        val repo = TeamRepository(db)
        repo.create("team-1", alice, now = 10)
        repo.invite("team-1", bob, TeamRoles.VIEWER, byteArrayOf(1), alice, now = 20)
        repo.accept("team-1", bob)

        assertEquals(0, repo.team("team-1")!!.keyEpoch)
        assertNull(repo.membership("team-1", bob)!!.keyEnvelope)

        assertEquals(RekeyOutcome.OK, repo.rekey("team-1", newEpoch = 1, envelopes = mapOf(bob to byteArrayOf(5, 5))))

        assertEquals(1, repo.team("team-1")!!.keyEpoch)
        assertContentEquals(byteArrayOf(5, 5), repo.membership("team-1", bob)!!.keyEnvelope)
        // Surfaced to the member via teamsFor for adoption.
        assertContentEquals(byteArrayOf(5, 5), repo.teamsFor(bob).single().keyEnvelope)

        // A missing team can't be rotated.
        assertEquals(RekeyOutcome.NO_TEAM, repo.rekey("ghost", newEpoch = 1, envelopes = emptyMap()))
    }

    @Test
    fun `rekey epoch is a compare-and-set guarding a stale or racing double-rotation`() = withTestDb { db ->
        seedTwoAccounts(db)
        val repo = TeamRepository(db)
        repo.create("team-1", alice, now = 10)
        repo.invite("team-1", bob, TeamRoles.VIEWER, byteArrayOf(1), alice, now = 20)
        repo.accept("team-1", bob)

        // First rotation to epoch 1 wins.
        assertEquals(RekeyOutcome.OK, repo.rekey("team-1", newEpoch = 1, envelopes = mapOf(bob to byteArrayOf(5))))

        // A second rotation racing to the same epoch (built from a stale read of epoch 0) is rejected:
        // its CAS predicate keyEpoch == 0 no longer holds. Its envelope must not clobber the winner's.
        assertEquals(RekeyOutcome.EPOCH_CONFLICT, repo.rekey("team-1", newEpoch = 1, envelopes = mapOf(bob to byteArrayOf(9))))
        assertEquals(1, repo.team("team-1")!!.keyEpoch)
        assertContentEquals(byteArrayOf(5), repo.membership("team-1", bob)!!.keyEnvelope)

        // Skipping an epoch (2 while at 1) is also rejected — only current + 1 is monotonic.
        assertEquals(RekeyOutcome.EPOCH_CONFLICT, repo.rekey("team-1", newEpoch = 3, envelopes = mapOf(bob to byteArrayOf(7))))
        assertEquals(1, repo.team("team-1")!!.keyEpoch)

        // The legitimate next step (epoch 2) proceeds.
        assertEquals(RekeyOutcome.OK, repo.rekey("team-1", newEpoch = 2, envelopes = mapOf(bob to byteArrayOf(6))))
        assertEquals(2, repo.team("team-1")!!.keyEpoch)
        assertContentEquals(byteArrayOf(6), repo.membership("team-1", bob)!!.keyEnvelope)
    }

    @Test
    fun `removeMember drops members but never the owner`() = withTestDb { db ->
        seedTwoAccounts(db)
        val repo = TeamRepository(db)
        repo.create("team-1", alice, now = 10)
        repo.invite("team-1", bob, TeamRoles.VIEWER, byteArrayOf(1), alice, now = 20)
        repo.accept("team-1", bob)

        assertTrue(repo.removeMember("team-1", bob))
        assertNull(repo.membership("team-1", bob))

        // the owner is protected from removal (only deleteTeam removes the team)
        assertFalse(repo.removeMember("team-1", alice))
        assertEquals(TeamRoles.OWNER, repo.membership("team-1", alice)!!.role)
    }

    @Test
    fun `deleteTeam removes team members and records`() = withTestDb { db ->
        seedTwoAccounts(db)
        val repo = TeamRepository(db)
        val records = TeamRecordRepository(db)
        repo.create("team-1", alice, now = 10)
        repo.invite("team-1", bob, TeamRoles.VIEWER, byteArrayOf(1), alice, now = 20)
        records.upsert("team-1", listOf(IncomingRecord("r1", "HOST", 1, "2026-07-04T00:00:00Z", "devA", false, byteArrayOf(1))))

        assertTrue(repo.deleteTeam("team-1"))
        assertFalse(repo.deleteTeam("team-1"))
        assertNull(repo.team("team-1"))
        assertNull(repo.membership("team-1", alice))
        assertEquals(emptyList(), repo.teamsFor(bob))
    }
}
