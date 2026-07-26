package app.skerry.ui.host

import app.skerry.shared.host.Host
import app.skerry.shared.ssh.ConnectionType
import app.skerry.shared.ssh.isRemoteDesktop

/**
 * Which part of the shell a saved profile belongs to. The catalog is one store (a profile is a
 * [Host] either way, with the same groups/tags/sync), but the UI is split: terminal-style
 * connections and remote desktops have separate lists, separate creation forms and separate
 * navigation entries (desktop rail item / mobile bottom tab).
 *
 * The split is derived from [Host.connectionType] rather than stored, so an existing VNC profile
 * moves to its new home with no migration, and changing a profile's transport re-files it.
 */
enum class HostSection { Terminal, RemoteDesktops }

/** Section this profile is filed under (see [HostSection]). */
val Host.section: HostSection
    get() = if (connectionType.isRemoteDesktop) HostSection.RemoteDesktops else HostSection.Terminal

/** The profiles of [section], in catalog order (the order the lists and manual reordering rely on). */
fun List<Host>.inSection(section: HostSection): List<Host> = filter { it.section == section }

/**
 * Secondary caption for a profile in the host lists: `user@address` where there is a user, and
 * `address:port` where there isn't — a remote desktop authenticates with a password and has no
 * username, so the usual form would render as a bare "@10.0.0.5". A port of 0 (local shell) drops
 * the suffix, leaving just the shell path (blank for the system default).
 */
fun Host.rowSubtitle(): String = when {
    username.isNotBlank() -> "$username@$address"
    port > 0 -> "$address:$port"
    else -> address
}

/** Section a transport is filed under — the profile-level [Host.section] follows it. */
val ConnectionType.section: HostSection
    get() = if (isRemoteDesktop) HostSection.RemoteDesktops else HostSection.Terminal

/**
 * Transports the "New connection" form of [section] offers, in [ConnectionType] order.
 *
 * [ConnectionType.LOCAL] is in neither: a local shell isn't a saved profile — it's launched from the
 * empty-tab placeholder and configured in Settings → Terminal → Local shell.
 */
fun connectionTypesIn(section: HostSection): List<ConnectionType> =
    ConnectionType.entries.filter { it != ConnectionType.LOCAL && it.section == section }

/** The transport a new profile of [section] starts on (first one the form offers). */
fun defaultConnectionTypeIn(section: HostSection): ConnectionType =
    connectionTypesIn(section).first()
