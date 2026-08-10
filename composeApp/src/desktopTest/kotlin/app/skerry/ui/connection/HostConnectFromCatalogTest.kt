package app.skerry.ui.connection

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.skerry.ui.app.UiTags
import app.skerry.ui.desktop.onCatalog
import app.skerry.ui.desktop.runDesktopShell
import app.skerry.ui.desktop.string
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.guard_prod_connect_title
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a click on a catalog row leads to: straight into a session, into a prompt for the secret, or
 * into the production guard first.
 *
 * Each branch is decided elsewhere and tested there — the guard has its rules
 * ([app.skerry.ui.host.ProdGuardTest]), the prompt has its form ([ConnectSecretFormTest]). What no
 * test covered is that the row leads into the right one of them, which is the difference between
 * asking before touching production and not asking.
 *
 * The sessions the shell opens run over a fake transport, so a connect here dials nothing.
 */
@OptIn(ExperimentalTestApi::class)
class HostConnectFromCatalogTest {

    @Test
    fun `a production host is not connected before it is confirmed`() = runDesktopShell { shell ->
        val sessions = requireNotNull(shell.sessions)
        val before = sessions.tabs.size

        onCatalog(PROD_HOST).performClick()
        waitForIdle()
        onNodeWithText(string(Res.string.guard_prod_connect_title)).assertIsDisplayed()
        assertEquals(before, sessions.tabs.size, "the guard must hold the connection, not trail it")

        onNodeWithTag(UiTags.FORM_CANCEL).performClick()
        waitForIdle()
        assertEquals(before, sessions.tabs.size, "a refused guard must leave the catalog as it was")
    }

    @Test
    fun `confirming the guard opens the session`() = runDesktopShell { shell ->
        val sessions = requireNotNull(shell.sessions)
        val before = sessions.tabs.size

        onCatalog(PROD_HOST).performClick()
        waitForIdle()
        onNodeWithTag(UiTags.FORM_SAVE).performClick()
        waitForIdle()
        assertEquals(before + 1, sessions.tabs.size, "confirming must go through to the connection")
        assertTrue(
            shell.state.recentHostIds.isNotEmpty(),
            "a host just connected to belongs in the recent list",
        )
    }

    /** No stored secret means the connection cannot start without asking: the prompt comes first. */
    @Test
    fun `a host with no stored secret asks for one`() = runDesktopShell { shell ->
        val sessions = requireNotNull(shell.sessions)
        val before = sessions.tabs.size

        onCatalog(NO_SECRET_HOST).performClick()
        waitForIdle()
        onNodeWithTag(UiTags.FORM_FIELD).assertIsDisplayed()
        assertEquals(before, sessions.tabs.size, "nothing may be opened before the secret is given")

        onNodeWithTag(UiTags.FORM_CANCEL).performClick()
        waitForIdle()
        assertEquals(before, sessions.tabs.size, "a dismissed prompt connects nothing")
    }
}

// Seeded catalog ([app.skerry.ui.desktop.seededHosts]): the production pair carries the vault's
// first credential, the homelab box carries none.
private const val PROD_HOST = "prod-web-01"
private const val NO_SECRET_HOST = "homelab-pi"
