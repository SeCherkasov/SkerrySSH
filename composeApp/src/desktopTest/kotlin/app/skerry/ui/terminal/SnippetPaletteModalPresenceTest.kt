package app.skerry.ui.terminal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import app.skerry.ui.design.DesignFonts
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.ModalPresence
import app.skerry.shared.snippet.Snippet
import app.skerry.shared.snippet.SnippetStore
import app.skerry.ui.snippet.SnippetManager
import app.skerry.ui.theme.SkerryTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The palette always lives in a focusable Popup (the terminal toolbar and the host row's menu both
 * open it that way), so while it is up the popup owns the keyboard. Closing it disposes that focus
 * and Compose clears focus to no one: unless the palette counts as an open modal, the terminal never
 * learns it should take the keyboard back, and typing stays dead until the user clicks the terminal.
 */
@OptIn(ExperimentalComposeUiApi::class)
class SnippetPaletteModalPresenceTest {

    private class FakeStore : SnippetStore {
        private val entries = mutableListOf<Snippet>()
        override fun all(): List<Snippet> = entries.toList()
        override fun put(snippet: Snippet) {
            val i = entries.indexOfFirst { it.id == snippet.id }
            if (i >= 0) entries[i] = snippet else entries += snippet
        }
        override fun remove(id: String) {
            entries.removeAll { it.id == id }
        }
    }

    @Composable
    private fun PaletteUnderTest(manager: SnippetManager) {
        SkerryTheme {
            CompositionLocalProvider(
                LocalFonts provides DesignFonts(FontFamily.Default, FontFamily.Monospace, FontFamily.Default),
            ) {
                SnippetPalette(manager) {}
            }
        }
    }

    @Test
    fun `an open palette counts as a modal and stops counting once it is gone`() {
        var n = 0
        val manager = SnippetManager(FakeStore()) { "id-${n++}" }
        val shown = mutableStateOf(true)
        val base = ModalPresence.openCount
        ImageComposeScene(width = 420, height = 400, density = Density(1f)).use { scene ->
            scene.setContent {
                if (shown.value) PaletteUnderTest(manager)
            }
            Snapshot.sendApplyNotifications()
            scene.render(16_666_667L)
            assertEquals(base + 1, ModalPresence.openCount, "an open palette must register as a modal")

            // Picking a snippet (or Esc, or a click outside) drops the palette from composition.
            shown.value = false
            Snapshot.sendApplyNotifications()
            scene.render(33_333_334L)
            assertEquals(base, ModalPresence.openCount, "closing the palette must release the modal slot")
        }
    }
}
