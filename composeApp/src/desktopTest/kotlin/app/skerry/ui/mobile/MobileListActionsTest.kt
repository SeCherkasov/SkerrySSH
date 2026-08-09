package app.skerry.ui.mobile

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.skerry.ui.app.MobileRoute
import app.skerry.ui.desktop.runMobileShell
import app.skerry.ui.desktop.string
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.lib_snippets_starter_pack
import app.skerry.ui.snippet.STARTER_SNIPPETS
import app.skerry.ui.tunnel.TunnelStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The phone's list screens as things you act on, not just navigate to — the desktop⇆Android parity
 * the project's rule is about, checked on the same common code Android builds.
 *
 * [MobileNavigationTest] proves the routes render. What it cannot see is a card whose switch is
 * wired to nothing: on the phone the switch IS the whole control, with no editor panel next to it
 * to fall back on.
 */
@OptIn(ExperimentalTestApi::class)
class MobileListActionsTest {

    @Test
    fun `the tunnel card switch raises the tunnel`() = runMobileShell { shell ->
        shell.state.push(MobileRoute.Ports)
        waitForIdle()
        val entry = shell.tunnels.tunnels.first { it.tunnel.label == INACTIVE_TUNNEL }
        assertFalse(entry.status is TunnelStatus.Active, "the seed leaves this one down")

        onNodeWithContentDescription(INACTIVE_TUNNEL).assertIsOff().performClick()
        waitUntil { entry.status is TunnelStatus.Active }
        waitForIdle()
        onNodeWithContentDescription(INACTIVE_TUNNEL).assertIsOn()
    }

    @Test
    fun `the phone fills an empty snippet library from the starter pack`() = runMobileShell { shell ->
        shell.state.push(MobileRoute.Snippets)
        waitForIdle()
        assertTrue(shell.snippets.snippets.isEmpty())

        onNodeWithText(string(Res.string.lib_snippets_starter_pack)).performClick()
        waitForIdle()

        assertEquals(STARTER_SNIPPETS.size, shell.snippets.snippets.size)
        onNodeWithText(DISK_SNIPPET).assertIsDisplayed()
    }
}

// Seeded tunnels and the starter pack, as on the desktop side.
private const val INACTIVE_TUNNEL = "app callback"
private const val DISK_SNIPPET = "Disk usage"
