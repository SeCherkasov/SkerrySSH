package app.skerry.ui.teams

import app.skerry.shared.sync.InMemorySyncStateStore
import app.skerry.shared.sync.KeyedStateStore
import app.skerry.shared.team.TeamScopeRef
import app.skerry.ui.sync.ServerLink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Issue #242, one store over. A delta cursor is a position in ONE server's history, and a team id is
 * whatever server answered `listTeams` — so filing a space's cursor under the space id alone lets two
 * servers share it, and the second one's first pull starts at the first one's tip: every record at or
 * below that number is never pulled, permanently, under a healthy status.
 */
class TeamCursorKeyTest {

    private val work = ServerLink("https://work.test", "maya").cursorKey
    private val home = ServerLink("https://home.test", "maya").cursorKey
    private val space = TeamScopeRef(teamId = "team-1")

    @Test
    fun `one space on two servers is two cursors`() {
        assertNotEquals(teamCursorKey(work, space), teamCursorKey(home, space))
    }

    @Test
    fun `two spaces on one server are two cursors`() {
        assertNotEquals(
            teamCursorKey(work, space),
            teamCursorKey(work, TeamScopeRef(teamId = "team-1", scopeId = "scope-a")),
        )
    }

    @Test
    fun `the same space on the same server is the same cursor`() {
        assertEquals(teamCursorKey(work, space), teamCursorKey(work, TeamScopeRef(teamId = "team-1")))
    }

    /**
     * No live session is not a link: it must not read as one, and it must not collide with a real key —
     * which is why a real one starts with the url's length and this one starts with nothing.
     */
    @Test
    fun `no session is not a server`() {
        assertNotEquals(teamCursorKey(null, space), teamCursorKey(work, space))
        assertEquals(teamCursorKey(null, space), teamCursorKey("", space))
    }

    /**
     * The store the engine is handed answers for one key whatever key the engine asks for — that is what
     * lets a per-space cursor live in a store whose interface is per-account. Dropping the pin would
     * silently re-key every cursor onto whatever the engine passes (the account id).
     */
    @Test
    fun `a pinned store ignores the key it is asked for`() {
        val backing = InMemorySyncStateStore()
        val pinned = KeyedStateStore(backing, teamCursorKey(work, space))

        pinned.setCursor("maya", 42)
        assertEquals(42, pinned.cursor("something else entirely"))
        assertEquals(42, backing.cursor(teamCursorKey(work, space)), "the write did not land on the pinned key")
        assertEquals(0, backing.cursor("maya"), "the write landed on the key the caller passed")
    }
}
