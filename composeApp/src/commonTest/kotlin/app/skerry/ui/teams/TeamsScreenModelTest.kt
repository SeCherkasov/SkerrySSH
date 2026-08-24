package app.skerry.ui.teams

import app.skerry.shared.team.Pin
import app.skerry.shared.team.PinOrigin
import app.skerry.shared.team.TeamActivityEntry
import app.skerry.shared.team.TeamMember
import app.skerry.shared.team.TeamMemberStatus
import app.skerry.shared.team.TeamRole
import app.skerry.shared.team.buildTeamActivityFeed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Teams screen's own projection: who is listed in which order, which scopes each member holds,
 * who the actor may act on, and how "last seen" reads. All of it is decided here rather than in the
 * composables, so the table can be checked without a display.
 */
class TeamsScreenModelTest {

    private val owner = "sergey@example.com"
    private val admin = "anna@example.com"
    private val editor = "dmitry@example.com"
    private val invitee = "pavel@example.com"

    private fun member(
        id: String,
        role: TeamRole,
        status: TeamMemberStatus = TeamMemberStatus.ACTIVE,
        lastSeenAt: Long? = null,
        devices: Int? = null,
    ) = TeamMember(id, role, status, createdAt = 0, lastSeenAt = lastSeenAt, devices = devices)

    private fun team(role: TeamRole) = TeamUi(
        id = "team-1",
        name = "skerry-ops",
        ownerAccountId = owner,
        role = role,
        status = TeamMemberStatus.ACTIVE,
        memberCount = 4,
        hasKey = true,
        scopes = listOf(
            TeamScopeUi(id = "s-prod", name = "prod", memberCount = 2, hasKey = true),
            TeamScopeUi(id = "s-db", name = "db", memberCount = 1, hasKey = true),
        ),
    )

    @Test
    fun `the device count is the team's own, and an invite has not brought its devices in yet`() {
        val count = teamDeviceCount(
            listOf(
                member(owner, TeamRole.OWNER, devices = 2),
                member(admin, TeamRole.ADMIN, devices = 3),
                member(invitee, TeamRole.VIEWER, TeamMemberStatus.INVITED, devices = 4),
            ),
        )
        assertEquals(5, count)
    }

    @Test
    fun `a server that does not report device counts leaves the number unknown, not zero`() {
        assertNull(teamDeviceCount(listOf(member(owner, TeamRole.OWNER), member(admin, TeamRole.ADMIN))))
        // One member unreported makes the sum an undercount — say nothing rather than a wrong total.
        assertNull(teamDeviceCount(listOf(member(owner, TeamRole.OWNER, devices = 2), member(admin, TeamRole.ADMIN))))
    }

    @Test
    fun `a member list that has not landed is unknown, not a team with no devices`() {
        // Offline, a failed members call and the first frame all arrive as an empty list. A team the
        // screen can draw has at least its owner, so this is never a fact about the team.
        assertNull(teamDeviceCount(emptyList()))
        assertNull(teamDeviceCount(listOf(member(invitee, TeamRole.VIEWER, TeamMemberStatus.INVITED, devices = 3))))
    }

    @Test
    fun `a member whose every device was revoked counts as zero, which is a fact`() {
        assertEquals(2, teamDeviceCount(listOf(member(owner, TeamRole.OWNER, devices = 2), member(admin, TeamRole.ADMIN, devices = 0))))
    }

    @Test
    fun `the table lists active members by rank and keeps invitees last`() {
        val rows = teamMemberRows(
            team = team(TeamRole.OWNER),
            members = listOf(
                member(invitee, TeamRole.VIEWER, TeamMemberStatus.INVITED),
                member(editor, TeamRole.EDITOR),
                member(owner, TeamRole.OWNER),
                member(admin, TeamRole.ADMIN),
            ),
            scopeGrants = emptyMap(),
            canManage = true,
        )

        assertEquals(listOf(owner, admin, editor, invitee), rows.map { it.member.accountId })
        assertTrue(rows.first().isOwner)
        assertFalse(rows.last().isOwner)
    }

    @Test
    fun `each member carries the scopes they hold a grant on`() {
        val rows = teamMemberRows(
            team = team(TeamRole.OWNER),
            members = listOf(member(owner, TeamRole.OWNER), member(admin, TeamRole.ADMIN)),
            scopeGrants = mapOf("s-prod" to setOf(admin), "s-db" to setOf(admin, owner)),
            canManage = true,
        )

        // Scope names, not ids: the id is a UUID nobody recognizes.
        assertEquals(listOf("db"), rows.single { it.member.accountId == owner }.scopes)
        assertEquals(listOf("prod", "db"), rows.single { it.member.accountId == admin }.scopes)
    }

    @Test
    fun `a member whose access lists could not be read is marked unknown, not scope-less`() {
        val rows = teamMemberRows(
            team = team(TeamRole.OWNER),
            members = listOf(member(owner, TeamRole.OWNER), member(admin, TeamRole.ADMIN)),
            // One scope answered, the other failed: what we know is partial, so no row may claim
            // "this member holds nothing" — that would read as an access fact rather than a gap.
            scopeGrants = mapOf("s-prod" to setOf(admin)),
            canManage = true,
            grantsComplete = false,
        )

        assertTrue(rows.none { it.scopesKnown })
        assertTrue(teamMemberRows(team(TeamRole.OWNER), listOf(member(owner, TeamRole.OWNER)), emptyMap(), canManage = true).all { it.scopesKnown })
    }

    /**
     * The mark a row wears for its key. "Confirmed" may only describe a fingerprint a human read out
     * loud, so a first sight, a record this device cannot read and an account nothing was ever sealed
     * to are three states the row must not draw as one (#323).
     */
    @Test
    fun `each row states how its member's key got on record`() {
        val rows = teamMemberRows(
            team = team(TeamRole.OWNER),
            members = listOf(
                member(owner, TeamRole.OWNER),
                member(admin, TeamRole.ADMIN),
                member(editor, TeamRole.EDITOR),
                member(invitee, TeamRole.VIEWER),
            ),
            scopeGrants = emptyMap(),
            canManage = true,
            selfAccountId = owner,
            pins = mapOf(
                admin to Pin.Known("SHA256:aaa", PinOrigin.CONFIRMED),
                editor to Pin.Known("SHA256:bbb", PinOrigin.FIRST_SIGHT),
                invitee to Pin.Unreadable,
            ),
        )

        fun trustOf(id: String) = rows.single { it.member.accountId == id }.trust
        assertEquals(PeerTrust.SELF, trustOf(owner), "there is no ceremony with oneself")
        assertEquals(PeerTrust.CONFIRMED, trustOf(admin))
        assertEquals(PeerTrust.FIRST_SIGHT, trustOf(editor))
        assertEquals(PeerTrust.UNREADABLE, trustOf(invitee))
    }

    /**
     * Without a live session the screen cannot say whose row is whose, and reading that as "not me"
     * put the amber "confirm this member's key" on the reader themselves.
     */
    @Test
    fun `with no session to say whose row is whose, no row claims anything`() {
        val rows = teamMemberRows(
            team = team(TeamRole.OWNER),
            members = listOf(member(owner, TeamRole.OWNER), member(admin, TeamRole.ADMIN)),
            scopeGrants = emptyMap(),
            canManage = true,
            selfAccountId = null,
            pins = mapOf(admin to Pin.Known("SHA256:aaa", PinOrigin.CONFIRMED)),
        )

        assertTrue(rows.all { it.trust == PeerTrust.UNKNOWN })
        assertTrue(rows.none { it.trust.confirmable })
    }

    /**
     * Every member but oneself is offered the ceremony — a confirmed one included. A colleague who
     * rotates their identity leaves a pin that no longer matches their published key, and the mark is
     * the only way back to a seal that is not refused.
     */
    @Test
    fun `every member but oneself is offered the ceremony`() {
        val rows = teamMemberRows(
            team = team(TeamRole.OWNER),
            members = listOf(member(owner, TeamRole.OWNER), member(admin, TeamRole.ADMIN)),
            scopeGrants = emptyMap(),
            canManage = true,
            selfAccountId = owner,
            // Nothing pinned for the other member: the state every team that predates the store is in.
            pins = emptyMap(),
        )

        assertEquals(PeerTrust.NONE, rows.single { it.member.accountId == admin }.trust)
        assertTrue(rows.single { it.member.accountId == admin }.trust.confirmable)
        assertTrue(PeerTrust.CONFIRMED.confirmable, "a rotated identity has no other way back")
        assertFalse(PeerTrust.SELF.confirmable)
        assertFalse(PeerTrust.UNKNOWN.confirmable)
    }

    @Test
    fun `the owner row and higher roles are not manageable`() {
        val asAdmin = teamMemberRows(
            team = team(TeamRole.ADMIN),
            members = listOf(member(owner, TeamRole.OWNER), member(admin, TeamRole.ADMIN), member(editor, TeamRole.EDITOR)),
            scopeGrants = emptyMap(),
            canManage = true,
        )

        assertFalse(asAdmin.single { it.member.accountId == owner }.manageable, "the owner is never removable")
        // An admin may not act on another admin — the server enforces it, and offering it would lie.
        assertFalse(asAdmin.single { it.member.accountId == admin }.manageable)
        assertTrue(asAdmin.single { it.member.accountId == editor }.manageable)
    }

    @Test
    fun `a member without manage rights sees no row actions at all`() {
        val rows = teamMemberRows(
            team = team(TeamRole.VIEWER),
            members = listOf(member(owner, TeamRole.OWNER), member(editor, TeamRole.EDITOR)),
            scopeGrants = emptyMap(),
            canManage = false,
        )

        assertTrue(rows.none { it.manageable })
    }

    @Test
    fun `last seen reads as never, now, today, yesterday, or a stamp`() {
        val now = 1_754_308_800_000L // 2025-08-04 12:00 UTC

        assertEquals(LastSeen.Never, lastSeen(null, now))
        assertEquals(LastSeen.Now, lastSeen(now - 30_000, now))
        assertEquals(LastSeen.Today("09:41"), lastSeen(now - 2 * 3_600_000 - 19 * 60_000, now))
        assertEquals(LastSeen.Yesterday("14:02"), lastSeen(now - 86_400_000 + 2 * 3_600_000 + 2 * 60_000, now))
        assertEquals(LastSeen.Earlier("2025-07-29 10:18"), lastSeen(1_753_784_280_000L, now))
    }

    @Test
    fun `a clock skewed into the future still reads as now, not as a future date`() {
        val now = 1_754_308_800_000L
        // Server and device clocks disagree by seconds routinely; "in 4 seconds" is not a thing to show.
        assertEquals(LastSeen.Now, lastSeen(now + 4_000, now))
    }

    @Test
    fun `the freshness pill buckets the elapsed time by seconds, minutes, hours and days`() {
        assertEquals(SyncedAgo.Seconds(59), syncedAgo(59_999))
        assertEquals(SyncedAgo.Minutes(1), syncedAgo(60_000))
        assertEquals(SyncedAgo.Minutes(59), syncedAgo(3_599_999))
        assertEquals(SyncedAgo.Hours(1), syncedAgo(3_600_000))
        assertEquals(SyncedAgo.Hours(23), syncedAgo(86_399_999))
        assertEquals(SyncedAgo.Days(1), syncedAgo(86_400_000))
    }

    @Test
    fun `a sync stamped by a clock ahead of ours reads as zero, never as negative`() {
        assertEquals(SyncedAgo.Seconds(0), syncedAgo(-5_000))
    }

    @Test
    fun `the vault card takes its rekey date from the newest key rotation in the feed`() {
        val feed = buildTeamActivityFeed(
            entries = listOf(
                entry("team.rekey", 1_753_000_000_000L),
                entry("team.scope_rekey", 1_754_000_000_000L),
                entry("team.record_share", 1_754_300_000_000L),
            ),
            selfAccountId = owner,
        )

        assertEquals(1_754_000_000_000L, lastRekeyAt(feed))
    }

    @Test
    fun `a team that was never rekeyed reports no date`() {
        val feed = buildTeamActivityFeed(listOf(entry("team.create", 1L)), selfAccountId = owner)

        assertNull(lastRekeyAt(feed))
    }

    private fun entry(event: String, at: Long) =
        TeamActivityEntry(actorAccountId = owner, event = event, detail = "", createdAt = at)
}
