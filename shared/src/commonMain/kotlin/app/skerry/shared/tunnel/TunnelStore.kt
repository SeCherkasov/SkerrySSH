package app.skerry.shared.tunnel

/**
 * Persistent store for saved tunnels (port forwarding). The platform implementation is
 * file-backed (jvmShared), like [app.skerry.shared.host.HostStore]. The contract is synchronous:
 * mutations are rare and UI-initiated. Implementations must be thread-safe.
 */
interface TunnelStore {
    /** All tunnels in insertion/update order. */
    fun all(): List<Tunnel>

    /**
     * The ids the store still holds a live record for, INCLUDING ones [all] cannot return because
     * their payload does not decrypt — adopting an account dataKey leaves every not-yet-pushed
     * record sealed under the previous one, and they read as absent until sync brings them back.
     *
     * A caller that tears something down on disappearance has to key off this rather than off
     * [all]: "the record is gone" and "the record cannot be read right now" mean opposite things,
     * and confusing them drops a live tunnel the user never asked to stop.
     */
    fun liveIds(): Set<String> = all().map { it.id }.toSet()

    /** Create a new record or replace an existing one with the same [Tunnel.id] (upsert). */
    fun put(tunnel: Tunnel)

    /** Remove the record by id; missing id is a no-op. */
    fun remove(id: String)
}
