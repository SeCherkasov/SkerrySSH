package app.skerry.ui.rdp

import androidx.compose.ui.input.key.Key
import app.skerry.shared.graphics.RemoteScan
import java.awt.event.KeyEvent

/**
 * F13–F24 (F-18): no common `Key` constant exists for them, but AWT names them, so a desktop
 * keyboard that carries them stops sending dead keys. Set 1 scancodes; note F24's jump to 0x76.
 */
internal actual val platformScancodeExtras: Map<Key, RdpKeyCode> = buildMap {
    val scancodes = intArrayOf(0x64, 0x65, 0x66, 0x67, 0x68, 0x69, 0x6A, 0x6B, 0x6C, 0x6D, 0x6E, 0x76)
    for (index in scancodes.indices) {
        put(Key(KeyEvent.VK_F13 + index), RdpKeyCode(listOf(RemoteScan(scancodes[index]))))
    }
}
