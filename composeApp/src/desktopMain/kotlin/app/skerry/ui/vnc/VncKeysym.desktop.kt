package app.skerry.ui.vnc

import androidx.compose.ui.input.key.Key
import java.awt.event.KeyEvent

/** F13–F24 as X11 names them (0xFFCA..0xFFD5, contiguous), keyed by the AWT codes (F-18). */
internal actual val platformKeySymExtras: Map<Key, Long> = buildMap {
    for (index in 0 until 12) {
        put(Key(KeyEvent.VK_F13 + index), 0xFFCAL + index)
    }
}
