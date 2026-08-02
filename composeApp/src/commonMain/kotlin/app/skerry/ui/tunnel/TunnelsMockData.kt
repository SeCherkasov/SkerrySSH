package app.skerry.ui.tunnel

import app.skerry.shared.tunnel.Tunnel
import app.skerry.shared.tunnel.TunnelDirection

/**
 * Sample rows for the offscreen/preview render of the section, where there is no [TunnelManager]
 * to list. Built as real [TunnelEntry] values so the preview goes through the same table as the
 * live path — a separate mock table would drift the moment a column changes.
 *
 * `-R` listens on the server, hence the wildcard bind address on the webhook row.
 */
internal fun mockTunnelEntries(): List<Pair<TunnelEntry, String>> = listOf(
    mockEntry(
        Tunnel("m1", "Postgres", "mock", TunnelDirection.Local, "127.0.0.1", 5432, "10.0.2.11", 5432),
        TunnelStatus.Active(5432), bytes = 41_200_000,
    ) to "db-master",
    mockEntry(
        Tunnel("m2", "Browser proxy", "mock", TunnelDirection.Dynamic, "127.0.0.1", 1080),
        TunnelStatus.Active(1080), bytes = 318_000_000,
    ) to "vps-edge",
    mockEntry(
        Tunnel("m3", "Webhook callback", "mock", TunnelDirection.Remote, "0.0.0.0", 9000, "127.0.0.1", 8080),
        TunnelStatus.Active(9000), bytes = 1_100_000,
    ) to "vps-edge",
    mockEntry(
        Tunnel("m4", "Redis", "mock", TunnelDirection.Local, "127.0.0.1", 6379, "10.0.2.11", 6379),
        TunnelStatus.Inactive, bytes = 0,
    ) to "db-master",
)

private fun mockEntry(tunnel: Tunnel, status: TunnelStatus, bytes: Long): TunnelEntry {
    val entry = TunnelEntry(tunnel)
    entry.status = status
    entry.bytesUp = bytes / 2
    entry.bytesDown = bytes - bytes / 2
    return entry
}
