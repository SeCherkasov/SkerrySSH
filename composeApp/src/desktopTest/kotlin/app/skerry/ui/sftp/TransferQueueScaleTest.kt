package app.skerry.ui.sftp

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.background
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import app.skerry.ui.design.DesignFonts
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.rememberMaterialSymbols
import app.skerry.ui.design.rememberMono
import app.skerry.ui.design.rememberUiFont
import app.skerry.ui.theme.Skerry
import app.skerry.ui.theme.SkerryTheme
import java.io.File
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.skerry.ui.desktop.runForm
import app.skerry.ui.files.TransferEntry
import app.skerry.ui.files.TransferState
import app.skerry.ui.files.TransferStatus
import app.skerry.ui.mobile.MobileTransferCard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class TransferQueueScaleTest {
    private val active = TransferEntry(
        1, TransferDirection.Upload, "long-release-archive-with-a-name-that-exceeds-the-screen.tar.gz",
        12, 150, 1024, 0, 2048, 1000, TransferStatus.Active,
    )
    private val waiting = List(1000) { index ->
        TransferEntry(index + 2L, TransferDirection.Upload, "queued-$index", 1, 1, 0, 0, 0, 0, TransferStatus.Waiting)
    }

    @Test
    fun `desktop thousand operation queue composes only viewport and keeps active pinned`() {
        val dropped = mutableListOf<Long>()
        runForm({
            Column(Modifier.width(320.dp)) {
                TransferQueueStrip(listOf(active) + waiting, FontFamily.Monospace) { dropped += it }
            }
        }) {
            val composed = onAllNodes(hasContentDescription("Cancel queued-", substring = true)).fetchSemanticsNodes().size
            assertTrue(composed in 1..19, "Composed $composed waiting rows")
            onNode(hasScrollAction()).performScrollToIndex(999)
            onNodeWithContentDescription("Cancel queued-999").assertIsDisplayed().performClick()
            onNodeWithText("12/150").assertIsDisplayed()
            onNodeWithText("Transferred: 2.0 KB").assertIsDisplayed()
            onNodeWithText("1.0 KB · 2 KB/s").assertIsDisplayed()
        }
        assertEquals(listOf(1001L), dropped)
    }

    @Test
    fun `mobile thousand operation queue is virtual and cancellable with visible counter and byte progress`() {
        val dropped = mutableListOf<Long>()
        runForm({
            Column(Modifier.width(320.dp)) {
                MobileTransferCard(
                    TransferState.Active(active.name, active.direction, active.fileIndex, active.fileCount, active.transferred, active.total),
                    listOf(active) + waiting, FontFamily.Monospace,
                ) { dropped += it }
            }
        }) {
            val composed = onAllNodes(hasContentDescription("Cancel queued-", substring = true)).fetchSemanticsNodes().size
            assertTrue(composed in 1..19, "Composed $composed waiting rows")
            onNode(hasScrollAction()).performScrollToIndex(999)
            val cancel = onNodeWithContentDescription("Cancel queued-999").assertIsDisplayed()
            assertTrue(cancel.fetchSemanticsNode().boundsInRoot.height >= 44f)
            cancel.performClick()
            onNodeWithText("12/150").assertIsDisplayed()
            onNodeWithText("Transferred: 2.0 KB").assertIsDisplayed()
            onNodeWithText("1.0 KB · 2 KB/s").assertIsDisplayed()
            onNodeWithText("0%").assertDoesNotExist()
        }
        assertEquals(listOf(1001L), dropped)
    }
    /** Optional visual evidence outside the checkout, using both shared UI forms. */
    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun `queue desktop and mobile screenshots`() {
        val destination = System.getenv("SKERRY_SFTP_SCREENSHOT_DIR") ?: return
        val folder = File(destination).also { it.mkdirs() }
        for (mobile in listOf(false, true)) {
            val scene = ImageComposeScene(
                width = if (mobile) 360 else 720, height = 400, density = Density(1f),
            ) {
                SkerryTheme {
                    CompositionLocalProvider(
                        LocalFonts provides DesignFonts(rememberUiFont(), rememberMono(), rememberMaterialSymbols()),
                    ) {
                        Column(Modifier.background(Skerry.colors.bg)) {
                            if (mobile) {
                                MobileTransferCard(
                                    TransferState.Active(active.name, active.direction, active.fileIndex, active.fileCount,
                                        active.transferred, active.total),
                                    listOf(active) + waiting, FontFamily.Monospace, {},
                                )
                            } else {
                                TransferQueueStrip(listOf(active) + waiting, FontFamily.Monospace, onDismiss = {})
                            }
                        }
                    }
                }
            }
            try {
                for (frame in 0..40) scene.render(frame * 16_000_000L).close()
                scene.render(41 * 16_000_000L).use { image ->
                    val data = checkNotNull(image.encodeToData())
                    val output = File(folder, "sftp-queue-${if (mobile) "mobile" else "desktop"}.png")
                    output.writeBytes(data.bytes)
                    assertTrue(output.length() > 0)
                }
            } finally {
                scene.close()
            }
        }
    }

}
