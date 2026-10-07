package app.skerry.ui.host

import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import app.skerry.shared.host.Host
import app.skerry.ui.app.UiTags
import app.skerry.ui.desktop.runDesktopShell
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/** Opt-in mounted-row census, not a measurement of CPU, FPS or input latency. */
@OptIn(ExperimentalTestApi::class)
class HostSidebarBenchmark {
    @Test
    fun mountedRows() {
        if (System.getenv("SKERRY_BENCH") != "1") return
        for (size in listOf(250, 1_000)) runDesktopShell(withSessions = false) { shell ->
            runOnIdle {
                shell.hosts.importHosts(List(size) { index ->
                    Host("census-$index", "census-$index", "example.test", username = "test", group = "Large catalog")
                })
            }
            waitForIdle()
            val mounted = onAllNodes(
                hasText("census-", substring = true) and hasAnyAncestor(hasTestTag(UiTags.HOST_SIDEBAR)),
            ).fetchSemanticsNodes().size
            assertTrue(mounted in 1..40)
            println("BENCH sidebar catalog=$size mounted=$mounted")
            if (size == 250) System.getenv("SKERRY_SIDEBAR_IMAGE")?.let { path ->
                val pixels = onNodeWithTag(UiTags.HOST_SIDEBAR).captureToImage().toPixelMap()
                val output = BufferedImage(pixels.width, pixels.height, BufferedImage.TYPE_INT_ARGB)
                for (y in 0 until pixels.height) for (x in 0 until pixels.width) output.setRGB(x, y, pixels[x, y].toArgb())
                ImageIO.write(output, "png", File(path))
            }
        }
    }
}
