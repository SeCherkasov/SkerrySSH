package app.skerry.ui.host

import app.skerry.shared.ssh.ConnectionType

/**
 * Material Symbols name marking a profile's protocol ([app.skerry.ui.design.Sym]). One mapping for
 * the whole UI: the connection form's protocol picker, the desktop sidebar rows (catalog, recent,
 * team) and the mobile host list — so a host wears the same symbol wherever it's listed.
 */
val ConnectionType.icon: String
    get() = when (this) {
        ConnectionType.SSH -> "lan"
        ConnectionType.MOSH -> "bolt"
        ConnectionType.TELNET -> "terminal"
        ConnectionType.SERIAL -> "cable"
        ConnectionType.VNC -> "desktop_windows"
        ConnectionType.LOCAL -> "keyboard_command_key"
    }
