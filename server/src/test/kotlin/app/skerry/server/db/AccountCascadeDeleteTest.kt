package app.skerry.server.db

import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Deleting an account has to leave nothing of it behind. Eight columns across the schema name an
 * account; cleaning only some of them leaves rows pointing at an id that no longer exists —
 * invisible on SQLite (which doesn't enforce foreign keys) and a constraint violation on
 * PostgreSQL, where the delete itself would fail.
 */
class AccountCascadeDeleteTest {

    private val now = 1_800_000_000_000L

    @Test
    fun `deleting an account removes its keys, memberships and scope grants`() = withTestDb { db ->
        val teams = TeamRepository(db)
        val scopes = TeamScopeRepository(db)
        seedAccount(db, "alice@example.com")
        seedAccount(db, "boss@example.com")
        teams.publishKey("alice@example.com", byteArrayOf(1), byteArrayOf(2), now)
        teams.create("t1", "boss@example.com", now)
        teams.invite("t1", "alice@example.com", TeamRoles.EDITOR, byteArrayOf(5), "boss@example.com", now)
        teams.accept("t1", "alice@example.com")
        scopes.create("t1", "prod", "boss@example.com", byteArrayOf(6), now)
        scopes.grant("t1", "prod", "alice@example.com", byteArrayOf(3), now)

        assertNotNull(AdminRepository(db).deleteAccount("alice@example.com"))

        assertTrue(leftovers(db, "alice@example.com").isEmpty(), "${leftovers(db, "alice@example.com")}")
        // Someone else's team is untouched — alice was only a member of it.
        assertTrue(teams.team("t1") != null)
    }

    @Test
    fun `deleting an owner hands the team to the most senior active member`() = withTestDb { db ->
        val teams = TeamRepository(db)
        seedAccount(db, "owner@example.com")
        seedAccount(db, "viewer@example.com")
        seedAccount(db, "admin@example.com")
        teams.create("t1", "owner@example.com", now)
        teams.invite("t1", "viewer@example.com", TeamRoles.VIEWER, byteArrayOf(1), "owner@example.com", now)
        teams.accept("t1", "viewer@example.com")
        teams.invite("t1", "admin@example.com", TeamRoles.ADMIN, byteArrayOf(2), "owner@example.com", now + 1)
        teams.accept("t1", "admin@example.com")

        assertNotNull(AdminRepository(db).deleteAccount("owner@example.com"))

        assertEquals("admin@example.com", teams.team("t1")?.ownerAccountId)
        assertEquals(TeamRoles.OWNER, teams.membership("t1", "admin@example.com")?.role)
        assertTrue(leftovers(db, "owner@example.com").isEmpty(), "${leftovers(db, "owner@example.com")}")
    }

    /** No admin left: the team still survives for whoever is actually in it. */
    @Test
    fun `a team with only viewers left is handed to a viewer`() = withTestDb { db ->
        val teams = TeamRepository(db)
        seedAccount(db, "owner@example.com")
        seedAccount(db, "viewer@example.com")
        teams.create("t1", "owner@example.com", now)
        teams.invite("t1", "viewer@example.com", TeamRoles.VIEWER, byteArrayOf(1), "owner@example.com", now)
        teams.accept("t1", "viewer@example.com")

        AdminRepository(db).deleteAccount("owner@example.com")

        assertEquals("viewer@example.com", teams.team("t1")?.ownerAccountId)
        assertEquals(TeamRoles.OWNER, teams.membership("t1", "viewer@example.com")?.role)
    }

    /**
     * Nobody active is left to hand it to: an invited member never accepted, so they hold no team
     * key at all — inheriting would give them a team they cannot open.
     */
    @Test
    fun `a team whose only other member never accepted is deleted with all its data`() = withTestDb { db ->
        val teams = TeamRepository(db)
        val teamRecords = TeamRecordRepository(db)
        val scopes = TeamScopeRepository(db)
        seedAccount(db, "owner@example.com")
        seedAccount(db, "invitee@example.com")
        teams.create("t1", "owner@example.com", now)
        teams.invite("t1", "invitee@example.com", TeamRoles.EDITOR, byteArrayOf(7), "owner@example.com", now)
        scopes.create("t1", "prod", "owner@example.com", byteArrayOf(6), now)
        scopes.grant("t1", "prod", "invitee@example.com", byteArrayOf(3), now)
        teamRecords.upsert(
            "t1",
            "",
            listOf(IncomingRecord("r1", "HOST", 1, "2026-07-26T00:00:00Z", "devA", false, byteArrayOf(1, 2))),
        )

        AdminRepository(db).deleteAccount("owner@example.com")

        assertNull(teams.team("t1"))
        assertEquals(0, countIn(db, "team_records"))
        assertEquals(0, countIn(db, "team_scopes"))
        assertEquals(0, countIn(db, "team_scope_grants"))
        assertEquals(0, countIn(db, "team_members"))
    }

    /**
     * Same role on both candidates: the older membership wins. The ids are chosen so alphabetical
     * order contradicts the expected answer — otherwise the test would pass on row order alone and
     * prove nothing about the tiebreak.
     */
    @Test
    fun `between two members of equal rank the older membership inherits`() = withTestDb { db ->
        val teams = TeamRepository(db)
        seedAccount(db, "owner@example.com")
        seedAccount(db, "aaron@example.com")
        seedAccount(db, "zed@example.com")
        teams.create("t1", "owner@example.com", now)
        teams.invite("t1", "aaron@example.com", TeamRoles.ADMIN, byteArrayOf(1), "owner@example.com", now + 500)
        teams.accept("t1", "aaron@example.com")
        teams.invite("t1", "zed@example.com", TeamRoles.ADMIN, byteArrayOf(2), "owner@example.com", now)
        teams.accept("t1", "zed@example.com")

        AdminRepository(db).deleteAccount("owner@example.com")

        assertEquals("zed@example.com", teams.team("t1")?.ownerAccountId)
    }

    /** Rank never beats status: an invited admin holds no key, an active viewer does. */
    @Test
    fun `an active viewer inherits over a higher-ranked member who never accepted`() = withTestDb { db ->
        val teams = TeamRepository(db)
        seedAccount(db, "owner@example.com")
        seedAccount(db, "viewer@example.com")
        seedAccount(db, "pending@example.com")
        teams.create("t1", "owner@example.com", now)
        teams.invite("t1", "pending@example.com", TeamRoles.ADMIN, byteArrayOf(1), "owner@example.com", now)
        teams.invite("t1", "viewer@example.com", TeamRoles.VIEWER, byteArrayOf(2), "owner@example.com", now + 1)
        teams.accept("t1", "viewer@example.com")

        AdminRepository(db).deleteAccount("owner@example.com")

        assertEquals("viewer@example.com", teams.team("t1")?.ownerAccountId)
        // The invite is gone with its inviter? No — it belongs to the team, which survived.
        assertEquals(TeamMemberStatus.INVITED, teams.membership("t1", "pending@example.com")?.status)
    }

    /** One delete, several owned teams: each is decided on its own and all of them are reported. */
    @Test
    fun `every owned team is handled in a single delete and reported back`() = withTestDb { db ->
        val teams = TeamRepository(db)
        seedAccount(db, "owner@example.com")
        seedAccount(db, "heir@example.com")
        teams.create("kept", "owner@example.com", now)
        teams.invite("kept", "heir@example.com", TeamRoles.EDITOR, byteArrayOf(1), "owner@example.com", now)
        teams.accept("kept", "heir@example.com")
        teams.create("solo1", "owner@example.com", now)
        teams.create("solo2", "owner@example.com", now)

        val outcome = assertNotNull(AdminRepository(db).deleteAccount("owner@example.com"))

        assertEquals(listOf("kept"), outcome.teamsTransferred)
        assertEquals(listOf("solo1", "solo2"), outcome.teamsDeleted.sorted())
        assertEquals("heir@example.com", teams.team("kept")?.ownerAccountId)
        assertNull(teams.team("solo1"))
        assertNull(teams.team("solo2"))
    }

    /** Who is left in each affected team — the accounts the route has to push a signal to. */
    @Test
    fun `the outcome names the members that need to be told`() = withTestDb { db ->
        val teams = TeamRepository(db)
        seedAccount(db, "owner@example.com")
        seedAccount(db, "heir@example.com")
        seedAccount(db, "bystander@example.com")
        teams.create("t1", "owner@example.com", now)
        teams.invite("t1", "heir@example.com", TeamRoles.ADMIN, byteArrayOf(1), "owner@example.com", now)
        teams.accept("t1", "heir@example.com")
        teams.invite("t1", "bystander@example.com", TeamRoles.VIEWER, byteArrayOf(2), "owner@example.com", now)
        teams.accept("t1", "bystander@example.com")

        val outcome = assertNotNull(AdminRepository(db).deleteAccount("owner@example.com"))

        assertEquals(
            listOf("bystander@example.com", "heir@example.com"),
            outcome.notifyAccounts.sorted(),
        )
    }

    /** The blanket invariant: after a delete, nothing anywhere still names the account. */
    @Test
    fun `no table keeps a row pointing at the deleted account`() = withTestDb { db ->
        val teams = TeamRepository(db)
        seedAccount(db, "alice@example.com")
        DeviceRepository(db).register("alice@example.com", "devA", "Laptop")
        RecordRepository(db).upsert(
            "alice@example.com",
            listOf(IncomingRecord("r1", "HOST", 1, "2026-07-26T00:00:00Z", "devA", false, byteArrayOf(1))),
        )
        PairingRepository(db).create("code1", "alice@example.com", byteArrayOf(9), expiresAt = Long.MAX_VALUE)
        teams.publishKey("alice@example.com", byteArrayOf(1), byteArrayOf(2), now)
        teams.create("t1", "alice@example.com", now)

        AdminRepository(db).deleteAccount("alice@example.com")

        assertTrue(leftovers(db, "alice@example.com").isEmpty(), "${leftovers(db, "alice@example.com")}")
    }
}

/** Every column that names an account; a row left in any of them is the leak this suite hunts. */
private val ACCOUNT_COLUMNS = listOf(
    "accounts" to "id",
    "devices" to "account_id",
    "records" to "account_id",
    "pairing" to "account_id",
    "account_keys" to "account_id",
    "teams" to "owner_account_id",
    "team_members" to "account_id",
    "team_scope_grants" to "account_id",
)

/** "table.column (n)" for every table still naming [accountId], so a failure says where it leaked. */
private fun leftovers(db: Database, accountId: String): List<String> = transaction(db) {
    ACCOUNT_COLUMNS.mapNotNull { (table, column) ->
        var found = 0
        exec("SELECT COUNT(*) AS n FROM $table WHERE $column = '$accountId'") { rs ->
            if (rs.next()) found = rs.getInt("n")
        }
        "$table.$column ($found)".takeIf { found > 0 }
    }
}

private fun countIn(db: Database, table: String): Int = transaction(db) {
    var n = 0
    exec("SELECT COUNT(*) AS n FROM $table") { rs -> if (rs.next()) n = rs.getInt("n") }
    n
}
