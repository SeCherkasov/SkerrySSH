package app.skerry.shared.team

import app.skerry.shared.vault.DataKey
import app.skerry.shared.vault.FakeVault
import app.skerry.shared.vault.RecordType
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Scope keys live inside the team's own TEAM record, not in a record type of their own: an older
 * client drops records of an unknown type while advancing the sync cursor (see SyncEngine), which
 * would lose a scope key permanently. Nested in TEAM they share that record's fate — and its
 * existing recovery path.
 */
class TeamScopeStoreTest {

    private val vault = FakeVault()
    private val store = TeamKeyStore(vault)

    private fun key(byte: Byte) = DataKey(ByteArray(32) { byte })

    private fun seedTeam() = store.put("team-1", "Crew", TeamRole.OWNER, key(1), epoch = 0)

    @Test
    fun `scope key round-trips under its team`() {
        seedTeam()

        store.putScope("team-1", "prod", "Production", key(7), epoch = 2)

        val scope = store.scope("team-1", "prod")
        assertEquals("Production", scope?.name)
        assertEquals(2, scope?.epoch)
        assertContentEquals(ByteArray(32) { 7 }, scope?.dataKey()?.bytes)
        // The team key itself is untouched by scope writes.
        assertContentEquals(ByteArray(32) { 1 }, store.get("team-1")?.dataKey()?.bytes)
    }

    @Test
    fun `scopes are listed per team and removed individually`() {
        seedTeam()
        store.put("team-2", "Other", TeamRole.EDITOR, key(2))
        store.putScope("team-1", "prod", "Production", key(7))
        store.putScope("team-1", "staging", "Staging", key(8))
        store.putScope("team-2", "prod", "Production", key(9))

        assertEquals(setOf("prod", "staging"), store.scopes("team-1").keys)
        assertEquals(setOf("prod"), store.scopes("team-2").keys)

        store.removeScope("team-1", "prod")

        assertEquals(setOf("staging"), store.scopes("team-1").keys)
        assertNull(store.scope("team-1", "prod"))
        // Same scope id in another team is a different scope.
        assertEquals(1, store.scopes("team-2").size)
    }

    @Test
    fun `rekeyScope replaces key and epoch but keeps the name`() {
        seedTeam()
        store.putScope("team-1", "prod", "Production", key(7), epoch = 1)

        store.rekeyScope("team-1", "prod", key(9), epoch = 2)

        val scope = store.scope("team-1", "prod")
        assertEquals("Production", scope?.name)
        assertEquals(2, scope?.epoch)
        assertContentEquals(ByteArray(32) { 9 }, scope?.dataKey()?.bytes)
    }

    @Test
    fun `scope writes are read-modify-write on the TEAM record and stay in one transaction`() {
        seedTeam()

        store.putScope("team-1", "prod", "Production", key(7))

        // Guideline §3: a read-modify-write on the vault must hold the transaction, otherwise a
        // background merge landing between read and write drops the concurrent scope.
        assertTrue(vault.lastPutInTransaction)
        assertEquals(1, vault.records().count { it.type == RecordType.TEAM && it.id == "team-1" })
    }

    @Test
    fun `scope operations on an unknown team are no-ops`() {
        store.putScope("team-gone", "prod", "Production", key(7))
        store.rekeyScope("team-gone", "prod", key(8), epoch = 1)
        store.removeScope("team-gone", "prod")

        assertNull(store.scope("team-gone", "prod"))
        assertTrue(store.scopes("team-gone").isEmpty())
    }

    @Test
    fun `forgetting a team forgets its scope keys with it`() {
        seedTeam()
        store.putScope("team-1", "prod", "Production", key(7))

        store.remove("team-1")

        assertTrue(store.scopes("team-1").isEmpty())
    }

    @Test
    fun `a team record written before scopes existed still decodes`() {
        // Old payload shape: no `scopes` key at all.
        vault.put(
            "team-old",
            RecordType.TEAM,
            """{"name":"Legacy","role":"owner","teamKey":"AAAA","epoch":1}""".encodeToByteArray(),
        )

        assertEquals("Legacy", store.get("team-old")?.name)
        assertTrue(store.scopes("team-old").isEmpty())
    }

    @Test
    fun `scope ref identifies a share space and separates team-wide from scoped`() {
        val teamWide = TeamScopeRef("team-1")
        val prod = TeamScopeRef("team-1", "prod")

        assertTrue(teamWide.isTeamWide)
        assertTrue(!prod.isTeamWide)
        assertEquals("team-1", teamWide.key)
        assertEquals("team-1/prod", prod.key)
        // Distinct storage per space: a scope vault must never collide with the team vault.
        assertEquals("team-1.vault", teamWide.fileName)
        assertEquals("team-1__prod.vault", prod.fileName)
    }
}
