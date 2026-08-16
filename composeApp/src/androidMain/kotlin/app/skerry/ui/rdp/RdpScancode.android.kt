package app.skerry.ui.rdp

import androidx.compose.ui.input.key.Key

// Android keycodes stop at F12, so there is nothing to add here.
internal actual val platformScancodeExtras: Map<Key, RdpKeyCode> = emptyMap()
