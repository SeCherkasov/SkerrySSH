package app.skerry.ui.teams

import app.skerry.shared.sync.SyncException
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Team operations talk to the same server as sync and hit the same two states — its rate limiter and
 * its 5xx — so they must be named the same way here. Folding them into "protocol error" tells a user
 * whose invite was simply throttled that something is wrong with the client.
 */
class TeamsFailureMappingTest {

    @Test
    fun `throttling and server failures are named, not folded into protocol`() {
        assertEquals(TeamsFailure.TooManyRequests, SyncException.Kind.TOO_MANY_REQUESTS.toTeamsFailure())
        assertEquals(TeamsFailure.ServerError, SyncException.Kind.SERVER_ERROR.toTeamsFailure())
    }

    @Test
    fun `the existing mappings are unchanged`() {
        assertEquals(TeamsFailure.Network, SyncException.Kind.NETWORK.toTeamsFailure())
        assertEquals(TeamsFailure.Forbidden, SyncException.Kind.UNAUTHORIZED.toTeamsFailure())
        assertEquals(TeamsFailure.NoSuchAccount, SyncException.Kind.NOT_FOUND.toTeamsFailure())
        assertEquals(TeamsFailure.AlreadyInvited, SyncException.Kind.CONFLICT.toTeamsFailure())
        // GONE has no team-level meaning (it's a pairing-code state) and stays generic.
        assertEquals(TeamsFailure.Protocol, SyncException.Kind.GONE.toTeamsFailure())
        assertEquals(TeamsFailure.Protocol, SyncException.Kind.PROTOCOL.toTeamsFailure())
    }

    /** A team endpoint answering 403 means the account lacks the right, not that the wire broke. */
    @Test
    fun `a refusal is a permission failure, not a protocol one`() {
        assertEquals(TeamsFailure.Forbidden, SyncException.Kind.FORBIDDEN.toTeamsFailure())
    }

    @Test
    fun `a non-sync exception is still a protocol failure`() {
        assertEquals(TeamsFailure.Protocol, (null as SyncException.Kind?).toTeamsFailure())
    }
}
