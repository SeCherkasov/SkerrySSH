package app.skerry.shared.tunnel

import kotlinx.serialization.Serializable

/** Direction of a saved tunnel forward: `-L` / `-R` / `-D` (see specs in the ssh core). */
@Serializable
enum class TunnelDirection { Local, Remote, Dynamic }

/**
 * A saved tunnel (port forwarding): a standalone object, not an ephemeral part of an open
 * terminal session. Identity is the stable [id] (assigned at creation, unchanged by edits).
 * [label] is the display name; [hostId] references the [app.skerry.shared.host.Host] used to
 * open the forward (the secret is loaded from the vault via [Host.credentialId] on activation).
 *
 * [bindHost]/[bindPort] is the listener on this machine (for `-L`/`-D`) or on the server (for
 * `-R`); `0` lets the OS/server pick the port. [destHost]/[destPort] is the destination for
 * `-L`/`-R`; `-D` (SOCKS5) has no destination, so both are `null`.
 *
 * The secret itself is not stored here — the tunnel references a host, and the host references
 * a vault keychain record.
 */
@Serializable
data class Tunnel(
    val id: String,
    val label: String,
    val hostId: String,
    val direction: TunnelDirection,
    val bindHost: String = "127.0.0.1",
    val bindPort: Int,
    val destHost: String? = null,
    val destPort: Int? = null,
)
