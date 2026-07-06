package app.skerry.shared.tunnel

/**
 * Persistent store for saved tunnels (port forwarding). The platform implementation is
 * file-backed (jvmShared), like [app.skerry.shared.host.HostStore]. The contract is synchronous:
 * mutations are rare and UI-initiated. Implementations must be thread-safe.
 */
interface TunnelStore {
    /** All tunnels in insertion/update order. */
    fun all(): List<Tunnel>

    /** Create a new record or replace an existing one with the same [Tunnel.id] (upsert). */
    fun put(tunnel: Tunnel)

    /** Remove the record by id; missing id is a no-op. */
    fun remove(id: String)
}
