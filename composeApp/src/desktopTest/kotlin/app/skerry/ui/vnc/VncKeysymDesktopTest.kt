package app.skerry.ui.vnc

import androidx.compose.ui.input.key.Key
import java.awt.event.KeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals

/** Desktop-only keysym rows (F-18): F13–F24, absent from the common `Key` set, as X11 names them. */
class VncKeysymDesktopTest {

    @Test
    fun `F13 through F24 map to their X11 keysyms`() {
        assertEquals(0xFFCAL, keySymFor(Key(KeyEvent.VK_F13), codePoint = 0))
        assertEquals(0xFFD0L, keySymFor(Key(KeyEvent.VK_F19), codePoint = 0))
        assertEquals(0xFFD5L, keySymFor(Key(KeyEvent.VK_F24), codePoint = 0))
    }
}
