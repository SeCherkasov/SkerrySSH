package app.skerry.ui.remote

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import app.skerry.ui.design.DesignFonts
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.rememberUiFont
import app.skerry.ui.design.rememberMono
import app.skerry.ui.design.rememberMaterialSymbols
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import app.skerry.ui.desktop.runForm
import app.skerry.ui.desktop.string
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.rd_reconnect
import app.skerry.ui.generated.resources.vnc_connection_lost
import app.skerry.ui.generated.resources.vnc_connect_failed
import app.skerry.ui.generated.resources.vnc_session_closed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import app.skerry.ui.vnc.VncFailure

@OptIn(ExperimentalTestApi::class)
class RemoteDesktopDisconnectedTest {
    @Test
    fun a_failed_reconnect_keeps_an_explicit_retry_action() {
        var reconnects = 0
        runForm({
            RemoteDesktopConnectionError(RemoteDesktopUiState.Error(VncFailure.Other)) { reconnects++ }
        }) {
            onNodeWithText(string(Res.string.vnc_connect_failed)).assertIsDisplayed()
            onNodeWithText(string(Res.string.rd_reconnect)).performClick()
            assertEquals(1, reconnects)
        }
    }

    @Test
    fun desktop_and_phone_notice_show_status_sanitized_reason_and_manual_reconnect() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val screen = RemoteDesktopScreenState(FakeRemoteDesktop(), scope)
            for ((width, clean) in listOf(900 to false, 390 to true)) {
                var reconnects = 0
                runForm({
                    CompositionLocalProvider(LocalFonts provides DesignFonts(rememberUiFont(), rememberMono(), rememberMaterialSymbols())) {
                        Box(Modifier.size(width.dp, 600.dp)) {
                            RemoteDesktopDisconnected(
                                RemoteDesktopUiState.Disconnected(screen, clean, "idle\u202E timeout"),
                                onReconnect = { reconnects++ },
                            )
                        }
                    }
                }) {
                    onNodeWithText(string(if (clean) Res.string.vnc_session_closed else Res.string.vnc_connection_lost))
                        .assertIsDisplayed()
                    onNodeWithText("idle timeout").assertIsDisplayed()
                    assertEquals(0, reconnects)
                    onNodeWithText(string(Res.string.rd_reconnect)).assertIsDisplayed().performClick()
                    assertEquals(1, reconnects)
                    System.getenv("SKERRY_REMOTE_DISCONNECT_IMAGE")?.let { prefix ->
                        val pixels = onRoot().captureToImage().toPixelMap()
                        val output = BufferedImage(pixels.width, pixels.height, BufferedImage.TYPE_INT_ARGB)
                        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
                            output.setRGB(x, y, pixels[x, y].toArgb())
                        }
                        ImageIO.write(output, "png", File("$prefix-$width.png"))
                    }
                }
            }
        } finally {
            scope.cancel()
        }
    }
}
