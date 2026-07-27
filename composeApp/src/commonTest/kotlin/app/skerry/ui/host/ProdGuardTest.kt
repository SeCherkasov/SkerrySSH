package app.skerry.ui.host

import app.skerry.shared.host.Host
import app.skerry.ui.connection.ConnectionUiState
import app.skerry.ui.mobile.MobileConnectAction
import app.skerry.ui.mobile.mobileConnectAction
import app.skerry.shared.guard.ProductionGuardPolicy
import app.skerry.ui.mobile.mobileProdConfirmNeeded
import app.skerry.ui.mobile.mobileResolvedAction
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
        assertNull(request.snippetLine)

        request.proceed()
        assertTrue(connected)
    }

    @Test
    fun the_snippet_path_carries_its_command_into_the_dialog() {
        // It runs the moment the session opens, before the session's own guard is bound to it, so
        // the connect confirmation is the only place the command can be read.
        val request = prodConnectGate(host("prod"), snippetLine = "systemctl stop nginx\n") {}
        assertEquals("systemctl stop nginx\n", request?.snippetLine)
        assertNotNull(prodDisplayRisk("systemctl stop nginx\n")?.assessment?.reason)
        assertNull(prodDisplayRisk("uptime\n"))
    }

    @Test
    fun the_root_login_rule_reads_the_profile_username() {
        assertTrue(isRootLogin(host("prod")))
        assertTrue(isRootLogin(host("prod").copy(username = " Root ")))
        assertFalse(isRootLogin(host("prod").copy(username = "deploy")))
        assertFalse(isRootLogin(null))
    }

    @Test
    fun the_policy_of_a_session_follows_its_host_profile() {
        val prod = prodGuardPolicy(host("prod"), confirmWarnings = true)
        assertTrue(prod.production)
        assertTrue(prod.confirmWarnings)
        assertTrue(prod.rootLogin)

        val staging = prodGuardPolicy(host("staging").copy(username = "deploy"), confirmWarnings = false)
        assertFalse(staging.production)
        assertFalse(staging.rootLogin)
        // An ad-hoc session with no saved profile is never guarded.
        assertFalse(prodGuardPolicy(null, confirmWarnings = true).production)
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
    fun mobile_carries_the_decision_from_the_question_to_the_answer() {
        // Whatever was decided when the dialog opened is what runs on OK — the session list is not
        // re-read to decide again, or the connect would act on a state the user never saw.
        assertEquals(
            MobileConnectAction.OpenFresh,
            mobileResolvedAction(MobileConnectAction.OpenFresh, stillLive = true),
        )
        assertEquals(
            MobileConnectAction.Resume,
            mobileResolvedAction(MobileConnectAction.Resume, stillLive = true),
        )
        // The single exception: the session died while the confirmation was up, so there is nothing
        // left to resume onto.
        assertEquals(
            MobileConnectAction.OpenFresh,
            mobileResolvedAction(MobileConnectAction.Resume, stillLive = false),
        )
    }

    @Test
    fun mobile_always_asks_for_a_production_vnc_tap() {
        // A VNC tap opens a fresh framebuffer screen; the resume path never applies to it.
        assertTrue(mobileProdConfirmNeeded(production = true, isVnc = true, action = MobileConnectAction.Resume))
    }

    // Synchronized input: the whole group runs under the strictest policy of its panes

    @Test
    fun strictest_policy_of_a_group_arms_on_any_pane_that_needs_it() {
        val lax = ProductionGuardPolicy(production = false, confirmWarnings = false, rootLogin = false)
        val prod = ProductionGuardPolicy(production = true, confirmWarnings = false, rootLogin = false)
        val warns = ProductionGuardPolicy(production = false, confirmWarnings = true, rootLogin = false)
        val root = ProductionGuardPolicy(production = false, confirmWarnings = false, rootLogin = true)

        // Each axis is raised by whichever pane needs it — a lax sibling can't lower the bar, which
        // is what keeps a command typed into the group from reaching a production pane unheld.
        assertEquals(prod, strictestOf(lax, prod))
        assertEquals(warns, strictestOf(warns, lax))
        assertEquals(root, strictestOf(lax, root))
        assertEquals(lax, strictestOf(lax, lax))
        assertEquals(
            ProductionGuardPolicy(production = true, confirmWarnings = true, rootLogin = true),
            listOf(prod, warns, root).reduce(::strictestOf),
        )
    }

    @Test
    fun strictest_policy_does_not_depend_on_the_order_of_the_panes() {
        val a = ProductionGuardPolicy(production = true, confirmWarnings = false, rootLogin = false)
        val b = ProductionGuardPolicy(production = false, confirmWarnings = true, rootLogin = true)
        assertEquals(strictestOf(a, b), strictestOf(b, a))
    }
}
