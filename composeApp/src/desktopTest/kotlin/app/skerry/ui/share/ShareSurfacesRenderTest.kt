package app.skerry.ui.share

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.skerry.ui.design.DesignFonts
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.rememberMaterialSymbols
import app.skerry.ui.design.rememberMono
import app.skerry.ui.design.rememberUiFont
import app.skerry.ui.theme.SkerryTheme
import app.skerry.ui.theme.Skerry
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Offscreen render of the two surfaces session sharing adds: the host's panel behind the toolbar
 * toggle, and the directory a team member joins from. Renders through the real theme, so a change
 * that breaks their layout (or their tokens) fails here instead of on someone's screen.
 *
 * The PNG is written to `build/reports/share-surfaces.png` for visual review.
 */
class ShareSurfacesRenderTest {

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun `the share panel and the live-session directory render`() {
        val shares = listOf(
            SharedSessionUi(
                teamId = "t1", teamName = "Platform", shareId = "s1",
                hostAccountId = "anna@corp.io", label = "root@prod-web-01",
                startedAt = 0, viewers = 2,
            ),
            SharedSessionUi(
                teamId = "t1", teamName = "Platform", shareId = "s2",
                hostAccountId = "leo@corp.io", label = "deploy@staging",
                startedAt = 0, viewers = 0,
            ),
        )
        val scene = ImageComposeScene(width = 720, height = 560, density = Density(1f)) {
            SkerryTheme {
                CompositionLocalProvider(
                    // Offscreen scene: the real font resources load asynchronously, so the render
                    // uses the platform families instead (same rule as the other render tests).
                    LocalFonts provides DesignFonts(rememberUiFont(), rememberMono(), rememberMaterialSymbols()),
                ) {
                Column(Modifier.background(Skerry.colors.bg).padding(16.dp)) {
                    SharePanel(
                        state = ShareUiState.Live(
                            teamId = "t1", teamName = "Platform", shareId = "s1",
                            viewers = 2,
                            viewerAccounts = listOf("anna@corp.io", "leo@corp.io"),
                            inputAllowed = false, inputLocked = false,
                            controlRequestPending = true, controlRequestBy = "leo@corp.io",
                        ),
                        teams = listOf("t1" to "Platform"),
                        onShare = { _, _ -> }, onAllowInput = {}, onAnswerRequest = {}, onStop = {}, onDismiss = {},
                    )
                    Column(Modifier.padding(top = 16.dp)) {
                        SharedSessionRows(shares, failure = null, onJoin = {})
                    }
                }
                }
            }
        }
        var image = scene.render(0)
        for (frame in 1..40) image = scene.render(frame * 16_000_000L)
        val data = image.encodeToData() ?: error("encode failed")
        val out = File("build/reports/share-surfaces.png")
        out.parentFile.mkdirs()
        out.writeBytes(data.bytes)
        scene.close()

        assertTrue(out.length() > 0, "nothing rendered")
    }
}
