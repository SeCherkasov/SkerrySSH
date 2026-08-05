package app.skerry.ui.host

import app.skerry.shared.ssh.ConnectionType

/**
 * Material Symbols name marking a profile's protocol ([app.skerry.ui.design.Sym]). One mapping for
 * the whole UI: the connection form's protocol picker, the desktop sidebar rows (catalog, recent,
 * team) and the mobile host list — so a host wears the same symbol wherever it's listed.
 */
val ConnectionType.icon: String
    get() = when (this) {
        // Server rack, the same mark the mobile Hosts tab carries: "lan" drew a network topology,
        // which named the wire rather than the machine at the end of it.
        ConnectionType.SSH -> "dns"
        ConnectionType.MOSH -> "bolt"
        ConnectionType.TELNET -> "terminal"
        ConnectionType.SERIAL -> "cable"
        ConnectionType.VNC -> "desktop_windows"
        ConnectionType.RDP -> "computer"
        ConnectionType.LOCAL -> "keyboard_command_key"
        ConnectionType.CONTAINER -> "deployed_code"
    }
