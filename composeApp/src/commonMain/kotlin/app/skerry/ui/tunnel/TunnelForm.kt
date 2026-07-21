package app.skerry.ui.tunnel

import app.skerry.shared.host.Host
import app.skerry.shared.tunnel.TunnelDirection
import app.skerry.ui.connection.JumpChainResolution
import app.skerry.ui.connection.resolveJumpChain
import app.skerry.ui.connection.toSshAuth
import app.skerry.ui.connection.toTarget
import app.skerry.shared.vault.Credential

// Pure tunnel editor helpers, kept out of Compose for UI-free testability.

private fun parsePort(value: String, min: Int): Int? =
    value.trim().toIntOrNull()?.takeIf { it in min..65535 }

/**
 * Builds a valid [TunnelDraft] from form fields, or `null` if input is incomplete/invalid. Name
 * and host are required; bind port `0..65535` (0 = OS-assigned); `-L`/`-R` require a destination
 * host and port (`1..65535`); `-D` (SOCKS) has no destination. Empty bind host defaults to loopback.
 */
fun buildTunnelDraft(
    id: String?,
    label: String,
    hostId: String?,
    direction: TunnelDirection,
    bindHost: String,
    bindPort: String,
    destHost: String,
    destPort: String,
): TunnelDraft? {
    val name = label.trim().ifEmpty { return null }
    val host = hostId?.takeIf { it.isNotBlank() } ?: return null
    val bind = parsePort(bindPort, min = 0) ?: return null
    val resolvedBindHost = bindHost.trim().ifEmpty { "127.0.0.1" }
    return when (direction) {
        TunnelDirection.Dynamic -> TunnelDraft(
            id = id, label = name, hostId = host, direction = direction,
            bindHost = resolvedBindHost, bindPort = bind, destHost = null, destPort = null,
        )
        TunnelDirection.Local, TunnelDirection.Remote -> {
            val dHost = destHost.trim().ifEmpty { return null }
            val dPort = parsePort(destPort, min = 1) ?: return null
            TunnelDraft(
                id = id, label = name, hostId = host, direction = direction,
                bindHost = resolvedBindHost, bindPort = bind, destHost = dHost, destPort = dPort,
            )
        }
    }
}

/**
 * Resolves a saved tunnel to connection parameters (for the [TunnelManager] production lambda).
 * Host is looked up by [Tunnel.hostId], credential by [Host.credentialId] in the unlocked vault;
 * the host's ProxyJump chain (if any) is resolved too, so tunnels ride the same route as sessions.
 * Failures are typed ([TunnelUnavailable]) — this runs outside the composition, the view localizes.
 */
fun resolveTunnel(
    tunnel: app.skerry.shared.tunnel.Tunnel,
    findHost: (String) -> Host?,
    findCredential: (String?) -> Credential?,
): TunnelResolution {
    val host = findHost(tunnel.hostId) ?: return TunnelResolution.Unavailable(TunnelUnavailable.HostNotFound)
    val credential = findCredential(host.credentialId)
        ?: return TunnelResolution.Unavailable(TunnelUnavailable.NoCredential)
    val jump = when (val chain = resolveJumpChain(host, findHost, findCredential)) {
        is JumpChainResolution.Unavailable ->
            return TunnelResolution.Unavailable(TunnelUnavailable.Jump(chain.problem))
        is JumpChainResolution.Resolved -> chain.jump
    }
    return TunnelResolution.Ready(host.toTarget(jump), credential.toSshAuth())
}
