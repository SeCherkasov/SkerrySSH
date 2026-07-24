package app.skerry.ui.host

import app.skerry.shared.host.Host
import app.skerry.shared.ssh.ConnectionType

/**
 * Stable id of the local-terminal session. It isn't a stored profile: the host is synthesized on
 * demand from the device-local shell path (configured in Settings → Terminal → Local shell) and
 * launched from the empty-tab placeholder, so it can't be created, duplicated or deleted like a
 * normal host.
 */
const val LOCAL_TERMINAL_HOST_ID = "__local_terminal__"

/**
 * Synthetic [Host] for the local shell, built from the device-local [shellPath] (blank → the system
 * default shell) and a localized [label] used as the session/tab title. Not persisted in the host
 * store; it only carries what the connect path needs ([Host.address] = the shell binary path,
 * [ConnectionType.LOCAL]). Auth resolves to an empty password (LOCAL has no SSH auth), so opening it
 * prompts for nothing.
 */
fun localTerminalHost(shellPath: String, label: String): Host = Host(
    id = LOCAL_TERMINAL_HOST_ID,
    label = label,
    address = shellPath.trim(),
    username = "",
    connectionType = ConnectionType.LOCAL,
    keepAliveSeconds = 0,
)
