package app.skerry.ui.sync

import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.sync_fail_network
import app.skerry.ui.generated.resources.sync_fail_protocol
import app.skerry.ui.generated.resources.sync_fail_server_error
import app.skerry.ui.generated.resources.sync_fail_too_many_requests
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Reason → string resource. The `when` is exhaustive, so a missing branch can't compile, but nothing
 * stops two reasons from being wired to each other's text: "wait a moment and try again" for a dead
 * server sends the user off to retry forever, and "the server is broken" for a rate limit sends them
 * to check a server that is fine.
 */
class SyncFailureResourceTest {

    @Test
    fun `throttling and server failure each get their own text`() {
        assertEquals(Res.string.sync_fail_too_many_requests, syncFailureResource(SyncFailureReason.TooManyRequests))
        assertEquals(Res.string.sync_fail_server_error, syncFailureResource(SyncFailureReason.ServerError))
    }

    @Test
    fun `the reasons they are easiest to confuse with keep their own text`() {
        assertEquals(Res.string.sync_fail_network, syncFailureResource(SyncFailureReason.Network))
        assertEquals(Res.string.sync_fail_protocol, syncFailureResource(SyncFailureReason.Protocol))
    }

    @Test
    fun `every reason resolves to a distinct text`() {
        val byResource = SyncFailureReason.entries.groupBy { syncFailureResource(it) }
        val shared = byResource.filterValues { it.size > 1 }
        assertEquals(emptyMap(), shared, "reasons sharing one text can't be told apart by the user")
    }
}
