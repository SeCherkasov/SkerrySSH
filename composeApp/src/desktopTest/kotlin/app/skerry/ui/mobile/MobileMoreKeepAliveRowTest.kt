package app.skerry.ui.mobile

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.skerry.ui.app.MobileRoute
import app.skerry.ui.app.MobileTab
import app.skerry.ui.app.UiTags
import app.skerry.ui.desktop.onScreen
import app.skerry.ui.desktop.runMobileShell
import app.skerry.ui.desktop.string
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.keepalive_exempt
import app.skerry.ui.generated.resources.keepalive_not_exempt
import app.skerry.ui.generated.resources.keepalive_title
import app.skerry.ui.keepalive.KeepAliveVendor
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The More hub's keep-alive row, which is the whole of the feature's platform gate: everything the
 * screen behind it configures is Doze and OEM task killers, so on a build with no
 * [app.skerry.ui.keepalive.KeepAlivePower] behind it the row would push a screen whose every
 * control is inert.
 */
@OptIn(ExperimentalTestApi::class)
class MobileMoreKeepAliveRowTest {

    @Test
    fun theRowIsAbsentWhereNothingSuppliesThePower() = runMobileShell {
        openMore()
        assertEquals(
            0,
            onAllNodesWithText(string(Res.string.keepalive_title)).fetchSemanticsNodes().size,
            "desktop and the offscreen renderer supply no power hook, so there is nothing to configure",
        )
    }

    @Test
    fun theRowOpensTheScreenAndReportsTheExemption() {
        val power = FakeKeepAlivePower(KeepAliveVendor.Samsung)
        runMobileShell(keepAlivePower = power) {
            openMore()
            onNodeWithText(string(Res.string.keepalive_not_exempt)).performScrollTo().assertIsDisplayed()
            onNodeWithText(string(Res.string.keepalive_title)).performScrollTo().performClick()
            onScreen(UiTags.mobileScreen(MobileRoute.KeepAlive)).assertIsDisplayed()
        }
    }

    /** An exempt device says so, so the row is not a permanent warning nobody can clear. */
    @Test
    fun anExemptDeviceIsReportedAsOne() {
        val power = FakeKeepAlivePower(KeepAliveVendor.Samsung).apply { exempt = true }
        runMobileShell(keepAlivePower = power) {
            openMore()
            onNodeWithText(string(Res.string.keepalive_exempt)).performScrollTo().assertIsDisplayed()
        }
    }

    private fun ComposeUiTest.openMore() {
        onNodeWithTag(UiTags.mobileTab(MobileTab.More)).performClick()
        waitForIdle()
    }
}
