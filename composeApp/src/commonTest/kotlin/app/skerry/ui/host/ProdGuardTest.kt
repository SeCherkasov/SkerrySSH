package app.skerry.ui.host

import app.skerry.shared.host.Host
import app.skerry.ui.connection.ConnectionUiState
import app.skerry.ui.mobile.MobileConnectAction
import app.skerry.ui.mobile.mobileConnectAction
import app.skerry.ui.mobile.mobileProdConfirmNeeded
import app.skerry.shared.ssh.ConnectionType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Gate in front of every connect path (desktop and mobile) plus the mobile-only "resume is not a
 * new connection" rule. The dialogs themselves are composables; what decides whether one appears
 * is these pure functions.
 */
class ProdGuardTest {

    private fun host(vararg tags: String, type: ConnectionType = ConnectionType.SSH) = Host(
        id = "h1", label = "web-01", address = "10.0.0.1", username = "root",
        tags = tags.toList(), connectionType = type,
    )

    @Test
    fun a_plain_host_connects_without_asking() {
        var connected = false
        val request = prodConnectGate(host("web")) { connected = true }

        assertTrue(connected)
        assertNull(request)
    }

    @Test
    fun a_production_host_holds_the_connection_until_confirmed() {
        var connected = false
        val request = prodConnectGate(host("web", "prod")) { connected = true }

        assertFalse(connected) // nothing happens until the user confirms
        assertNotNull(request)
        assertEquals("web-01", request.host.label)
        assertFalse(request.snippet)

        request.proceed()
        assertTrue(connected)
    }

    @Test
    fun the_snippet_path_is_marked_so_the_dialog_can_say_the_command_runs_on_connect() {
        val request = prodConnectGate(host("prod"), snippet = true) {}
        assertEquals(true, request?.snippet)
    }

    @Test
    fun production_is_read_from_the_tags() {
        assertTrue(isProdHost(host("prod")))
        assertTrue(isProdHost(host("db", "prod")))
        assertFalse(isProdHost(host("staging")))
        assertFalse(isProdHost(null)) // ad-hoc session with no saved profile
    }

    @Test
    fun mobile_asks_when_opening_a_production_session() {
        val fresh = mobileConnectAction(null)
        assertEquals(MobileConnectAction.OpenFresh, fresh)
        assertTrue(mobileProdConfirmNeeded(production = true, isVnc = false, action = fresh))
        assertFalse(mobileProdConfirmNeeded(production = false, isVnc = false, action = fresh))
    }

    @Test
    fun mobile_does_not_ask_again_when_returning_to_a_live_session() {
        val resume = mobileConnectAction(ConnectionUiState.Connecting)
        assertEquals(MobileConnectAction.Resume, resume)
        assertFalse(mobileProdConfirmNeeded(production = true, isVnc = false, action = resume))
        // A dead session is reopened, not resumed — that IS a new connection and asks again.
        val dead = mobileConnectAction(ConnectionUiState.Form)
        assertTrue(mobileProdConfirmNeeded(production = true, isVnc = false, action = dead))
    }

    @Test
    fun mobile_always_asks_for_a_production_vnc_tap() {
        // A VNC tap opens a fresh framebuffer screen; the resume path never applies to it.
        assertTrue(mobileProdConfirmNeeded(production = true, isVnc = true, action = MobileConnectAction.Resume))
    }
}
