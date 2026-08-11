package app.skerry.ui.host

import app.skerry.shared.host.Host
import app.skerry.shared.ssh.ConnectionType
import app.skerry.shared.ssh.isRemoteDesktop
import app.skerry.ui.design.SHORT_ID_CHARS
import app.skerry.ui.design.untrustedLabel

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
 * The name a profile is drawn under.
 *
 * A profile shared through a team was named by whoever shared it, and the name travels inside the
 * sealed envelope — the server never sees it and could not validate it. So the catalog draws it
 * through [untrustedLabel] rather than raw: a bidi override in a name makes one host row draw as
 * another.
 *
 * A name that is nothing but the characters filtering drops leaves nothing to draw, and a blank row
 * is one the user cannot tell from any other: the address stands in, and the id after it. The id is
 * a peer's text as well for a shared record, so it is filtered like the rest. Same ladder as a team
 * space's label.
 */
fun Host.rowLabel(): String =
    untrustedLabel(label).ifBlank { untrustedLabel(address) }.ifBlank { untrustedLabel(id.take(SHORT_ID_CHARS)) }

/**
 * Secondary caption for a profile in the host lists: `user@address` where there is a user, and
 * `address:port` where there isn't — a remote desktop authenticates with a password and has no
 * username, so the usual form would render as a bare "@10.0.0.5". A port of 0 (local shell) drops
 * the suffix, leaving just the shell path (blank for the system default).
 *
 * Sanitized for the same reason as [rowLabel]: the username and the address of a shared profile are
 * a peer's text too, and this line is what tells two rows with the same name apart.
 */
fun Host.rowSubtitle(): String = untrustedLabel(
    when {
        // The port belongs to the address a row identifies a profile by: two profiles on the same
        // machine (a jump host and a container gateway, 22 and 2222) are otherwise the same line.
        username.isNotBlank() && port > 0 -> "$username@$address:$port"
        username.isNotBlank() -> "$username@$address"
        port > 0 -> "$address:$port"
        else -> address
    },
)

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
