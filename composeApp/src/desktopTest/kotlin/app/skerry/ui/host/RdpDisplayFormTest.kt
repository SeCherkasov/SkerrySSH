package app.skerry.ui.host

import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.skerry.shared.host.Host
import app.skerry.shared.rdp.RdpImageQuality
import app.skerry.shared.rdp.RdpResolution
import app.skerry.shared.rdp.RdpSpec
import app.skerry.shared.ssh.ConnectionType
import app.skerry.ui.app.UiTags
import app.skerry.ui.desktop.press
import app.skerry.ui.desktop.runDesktopShell
import app.skerry.ui.desktop.runMobileShell
import app.skerry.ui.desktop.string
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.conn_rdp_scale
import app.skerry.ui.generated.resources.conn_rdp_resolution
import app.skerry.ui.generated.resources.conn_rdp_wallpaper
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/** Exercise the actual desktop modal and mobile sheet, including names, scrolling and persistence. */
@OptIn(ExperimentalTestApi::class)
class RdpDisplayFormTest {
    @Test
    fun `desktop display controls save independent choices on the same profile`() = runDesktopShell { shell ->
        val original = rdpHost(shell.hosts.hosts.first())
        shell.state.openEditModal(original)
        waitForIdle()
        chooseDisplay("desktop")
        press(UiTags.FORM_SAVE)
        waitForIdle()
        assertSaved(checkNotNull(shell.hosts.find(original.id)))
    }

    @Test
    fun `mobile display controls remain reachable and save the same choices`() = runMobileShell { shell ->
        val original = rdpHost(shell.hosts.hosts.first())
        shell.state.openEditConn(original)
        waitForIdle()
        chooseDisplay("mobile")
        press(UiTags.FORM_SAVE)
        waitForIdle()
        assertSaved(checkNotNull(shell.hosts.find(original.id)))
    }

    private fun ComposeUiTest.chooseDisplay(platform: String) {
        onNodeWithContentDescription(string(Res.string.conn_rdp_wallpaper)).performScrollTo().performClick()
        waitForIdle()
        capture(platform + "-wallpaper")
        onNodeWithContentDescription(string(Res.string.conn_rdp_scale), substring = true, useUnmergedTree = true).performScrollTo()
        waitForIdle()
        capture(platform + "-scale-trigger")
        onNodeWithContentDescription(string(Res.string.conn_rdp_scale), substring = true, useUnmergedTree = true).performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("100%").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("100%").performClick()
        onNodeWithContentDescription(string(Res.string.conn_rdp_resolution), substring = true, useUnmergedTree = true).performScrollTo()
        waitForIdle()
        onNodeWithContentDescription(string(Res.string.conn_rdp_resolution), substring = true, useUnmergedTree = true).performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("1280 × 720").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("1280 × 720").performClick()
        waitForIdle()
        capture(platform + "-display")
    }

    private fun assertSaved(host: Host) {
        val spec = checkNotNull(host.rdp)
        assertEquals(RdpImageQuality.Low, spec.quality)
        assertEquals(false, spec.wallpaper)
        assertEquals(100, spec.desktopScalePercent)
        assertEquals(RdpResolution(1280, 720), spec.resolution)
    }

    private fun rdpHost(host: Host): Host = host.copy(
        connectionType = ConnectionType.RDP, port = 3389,
        rdp = RdpSpec(quality = RdpImageQuality.Low, wallpaper = true, desktopScalePercent = 150,
            resolution = RdpResolution(1920, 1080)),
    )

    private fun ComposeUiTest.capture(name: String) {
        val directory = System.getenv("SKERRY_444_UI_CAPTURE") ?: return
        val file = Path.of(directory, "$name.png")
        Files.createDirectories(file.parent)
        Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap()).use { image ->
            image.encodeToData(EncodedImageFormat.PNG).use { data ->
                Files.write(file, checkNotNull(data).bytes)
            }
        }
    }
}
