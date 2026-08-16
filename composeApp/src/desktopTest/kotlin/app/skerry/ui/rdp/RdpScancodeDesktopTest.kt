package app.skerry.ui.rdp

import androidx.compose.ui.input.key.Key
import app.skerry.shared.graphics.RemoteScan
import java.awt.event.KeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Desktop-only rows of the scancode table (F-18): F13–F24 have no Compose common `Key` constant,
 * but AWT names them, so on desktop they stop being dead keys.
 */
class RdpScancodeDesktopTest {

    @Test
    fun `F13 through F24 map to their set 1 scancodes`() {
        assertEquals(listOf(RemoteScan(0x64)), scancodeFor(Key(KeyEvent.VK_F13))?.scans)
        assertEquals(listOf(RemoteScan(0x6C)), scancodeFor(Key(KeyEvent.VK_F21))?.scans)
        assertEquals(listOf(RemoteScan(0x76)), scancodeFor(Key(KeyEvent.VK_F24))?.scans)
    }
}
