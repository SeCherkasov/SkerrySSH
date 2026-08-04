package app.skerry.ui.teams

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
    ) = TeamMember(id, role, status, createdAt = 0, lastSeenAt = lastSeenAt)

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
