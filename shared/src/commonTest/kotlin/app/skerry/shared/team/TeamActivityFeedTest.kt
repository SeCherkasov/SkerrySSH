package app.skerry.shared.team

import app.skerry.shared.vault.RecordType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Turning the server's audit metadata into rows a member can read. */
class TeamActivityFeedTest {

    private val day = 86_400_000L
    private val self = "me@x.io"

    private fun entry(
        event: String,
        actor: String = "bob@x.io",
        at: Long = day * 20_000,
        recordId: String? = null,
        recordType: String? = null,
        scopeId: String? = null,
        detail: String = "",
        durationSec: Long? = null,
    ) = TeamActivityEntry(actor, event, detail, at, recordId, recordType, scopeId, durationSec)

    private fun feed(
        entries: List<TeamActivityEntry>,
        category: TeamActivityCategory = TeamActivityCategory.ALL,
        onlyRecordId: String? = null,
        names: Map<String, String> = emptyMap(),
        scopes: Map<String, String> = emptyMap(),
    ) = buildTeamActivityFeed(
        entries = entries,
        selfAccountId = self,
        category = category,
        onlyRecordId = onlyRecordId,
        resolveRecordName = { _, id -> names[id] },
        resolveScopeName = { id -> scopes[id] },
    )

    @Test
    fun `a record event reads as who did what to which host`() {
        val rows = feed(
            listOf(entry("team.record_change", recordId = "h-1", recordType = "HOST", scopeId = "prod")),
            names = mapOf("h-1" to "prod-db-01"),
            scopes = mapOf("prod" to "Production"),
        ).single().rows.single()

        assertEquals(TeamActivityKind.RECORD_CHANGE, rows.kind)
        assertEquals("prod-db-01", rows.subject)
        assertTrue(rows.subjectResolved)
        assertEquals(RecordType.HOST, rows.recordType)
        assertEquals("Production", rows.scopeName)
        assertFalse(rows.isSelf)
    }

    @Test
    fun `a name we cannot resolve degrades to a short id, never to nothing`() {
        // An unshared record leaves a tombstone with no payload, so its name is gone from the space.
        // Showing the row anyway is the point of an audit log — "someone removed something" beats
        // silence, and the short id still ties several rows about the same record together.
        val row = feed(
            listOf(entry("team.record_remove", recordId = "0123456789abcdef", recordType = "HOST")),
        ).single().rows.single()

        assertEquals("01234567", row.subject)
        assertFalse(row.subjectResolved)
        assertEquals("0123456789abcdef", row.recordId)
        // Team-wide space: no scope to name.
        assertNull(row.scopeName)
    }

    @Test
    fun `an unnamed scope falls back to its id and an empty scope means team-wide`() {
        val rows = feed(
            listOf(
                entry("team.record_share", recordId = "h-1", scopeId = "staging", at = day * 20_000 + 2),
                entry("team.record_share", recordId = "h-2", scopeId = "", at = day * 20_000 + 1),
            ),
        ).single().rows

        assertEquals("staging", rows[0].scopeName)
        assertNull(rows[1].scopeName)
    }

    @Test
    fun `own actions are marked as ours`() {
        val row = feed(listOf(entry("team.record_share", actor = self, recordId = "h-1"))).single().rows.single()
        assertTrue(row.isSelf)
    }

    @Test
    fun `every event the server can write has a kind, and an unknown one still shows up`() {
        val known = listOf(
            "team.create" to TeamActivityKind.TEAM_CREATE,
            "team.delete" to TeamActivityKind.TEAM_DELETE,
            "team.invite" to TeamActivityKind.MEMBER_INVITE,
            "team.accept" to TeamActivityKind.MEMBER_JOIN,
            "team.remove" to TeamActivityKind.MEMBER_REMOVE,
            "team.role_change" to TeamActivityKind.MEMBER_ROLE,
            "team.rekey" to TeamActivityKind.KEY_ROTATE,
            "team.scope_create" to TeamActivityKind.SCOPE_CREATE,
            "team.scope_delete" to TeamActivityKind.SCOPE_DELETE,
            "team.scope_grant" to TeamActivityKind.SCOPE_GRANT,
            "team.scope_revoke" to TeamActivityKind.SCOPE_REVOKE,
            "team.scope_rekey" to TeamActivityKind.SCOPE_KEY_ROTATE,
            "team.record_share" to TeamActivityKind.RECORD_SHARE,
            "team.record_change" to TeamActivityKind.RECORD_CHANGE,
            "team.record_remove" to TeamActivityKind.RECORD_REMOVE,
            "team.push" to TeamActivityKind.RECORDS_BULK,
            "team.session_open" to TeamActivityKind.SESSION_OPEN,
            "team.session_record" to TeamActivityKind.SESSION_RECORD,
        )
        known.forEach { (event, kind) ->
            assertEquals(kind, feed(listOf(entry(event))).single().rows.single().kind, event)
        }

        // A self-hosted server newer than this client: the event is unreadable but not invisible.
        val unknown = feed(listOf(entry("team.quantum_leap", detail = "42"))).single().rows.single()
        assertEquals(TeamActivityKind.UNKNOWN, unknown.kind)
        assertEquals("team.quantum_leap", unknown.event)
        assertEquals("42", unknown.detail)
    }

    @Test
    fun `rows are grouped by day, newest day first`() {
        val rows = listOf(
            entry("team.create", at = day * 20_000 + 3_600_000),
            entry("team.invite", at = day * 20_001 + 60_000),
            entry("team.accept", at = day * 20_001 + 120_000),
        )
        val days = feed(rows)

        assertEquals(2, days.size)
        assertEquals(20_001, days[0].dayIndex)
        assertEquals(listOf(TeamActivityKind.MEMBER_JOIN, TeamActivityKind.MEMBER_INVITE), days[0].rows.map { it.kind })
        assertEquals(20_000, days[1].dayIndex)
    }

    @Test
    fun `events sharing a timestamp keep the order the server sent them in`() {
        // One push writes an event per record with the same millisecond; the server's own order
        // (descending seq) is the only sequence there is, so sorting must not shuffle them.
        val same = day * 20_000
        val days = feed(
            listOf(
                entry("team.record_share", recordId = "c", at = same),
                entry("team.record_share", recordId = "b", at = same),
                entry("team.record_share", recordId = "a", at = same),
            ),
        )
        assertEquals(listOf("c", "b", "a"), days.single().rows.map { it.recordId })
    }

    @Test
    fun `entries out of order are sorted newest first`() {
        val days = feed(
            listOf(
                entry("team.create", at = day * 20_000),
                entry("team.invite", at = day * 20_000 + 5),
            ),
        )
        assertEquals(listOf(TeamActivityKind.MEMBER_INVITE, TeamActivityKind.TEAM_CREATE), days.single().rows.map { it.kind })
    }

    @Test
    fun `a category filter keeps only its own events`() {
        val entries = listOf(
            entry("team.record_change", recordId = "h-1"),
            entry("team.invite"),
            entry("team.rekey"),
            entry("team.session_open", recordId = "h-1"),
            entry("team.scope_grant"),
        )
        fun kinds(c: TeamActivityCategory) = feed(entries, category = c).flatMap { it.rows }.map { it.kind }

        assertEquals(listOf(TeamActivityKind.RECORD_CHANGE), kinds(TeamActivityCategory.RECORDS))
        assertEquals(listOf(TeamActivityKind.MEMBER_INVITE), kinds(TeamActivityCategory.MEMBERS))
        assertEquals(
            listOf(TeamActivityKind.KEY_ROTATE, TeamActivityKind.SCOPE_GRANT),
            kinds(TeamActivityCategory.ACCESS),
        )
        assertEquals(listOf(TeamActivityKind.SESSION_OPEN), kinds(TeamActivityCategory.SESSIONS))
        assertEquals(5, kinds(TeamActivityCategory.ALL).size)
    }

    @Test
    fun `the history of one record shows only what happened to it`() {
        val entries = listOf(
            entry("team.record_change", recordId = "h-1"),
            entry("team.session_open", recordId = "h-1"),
            entry("team.record_change", recordId = "h-2"),
            entry("team.rekey"),
            // A bulk summary covers the whole space, so it says nothing about this record in particular.
            entry("team.push", detail = "40 records"),
        )
        val rows = feed(entries, onlyRecordId = "h-1").flatMap { it.rows }

        assertEquals(
            listOf(TeamActivityKind.RECORD_CHANGE, TeamActivityKind.SESSION_OPEN),
            rows.map { it.kind },
        )
        assertTrue(rows.all { it.recordId == "h-1" })
    }

    @Test
    fun `a reported recording keeps its length and a session event is flagged as client-reported`() {
        val rows = feed(
            listOf(
                entry("team.session_record", recordId = "h-1", durationSec = 754),
                entry("team.record_change", recordId = "h-1", at = day * 20_000 - 1),
            ),
        ).flatMap { it.rows }

        assertEquals(754, rows[0].durationSec)
        assertTrue(rows[0].clientReported)
        assertFalse(rows[1].clientReported)
        assertNull(rows[1].durationSec)
    }

    @Test
    fun `the reportable session kinds keep the wire values the server accepts`() {
        // The server's own map of accepted kinds lives in another module (TeamRoutes.SESSION_EVENTS)
        // and is keyed by these literals, so nothing but this assertion ties the two together: a
        // rename here would turn every session report into a silently swallowed 400.
        assertEquals("open", TeamSessionKind.OPEN.wire)
        assertEquals("record", TeamSessionKind.RECORD.wire)
    }

    @Test
    fun `an empty log is an empty feed, not a day with no rows`() {
        assertTrue(feed(emptyList()).isEmpty())
        assertTrue(feed(listOf(entry("team.invite")), category = TeamActivityCategory.SESSIONS).isEmpty())
    }

    @Test
    fun `the record type comes through as the vault's own, unknown ones as none`() {
        fun typeOf(type: String?) =
            feed(listOf(entry("team.record_change", recordId = "x", recordType = type)))
                .single().rows.single().recordType

        assertEquals(RecordType.HOST, typeOf("HOST"))
        assertEquals(RecordType.SNIPPET, typeOf("SNIPPET"))
        assertEquals(RecordType.CREDENTIAL, typeOf("CREDENTIAL"))
        // A type this client's vault doesn't know must not break the row it belongs to.
        assertNull(typeOf("PLASMA_CONDUIT"))
        assertNull(typeOf(null))
    }
}
