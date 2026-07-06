package app.skerry.shared.host

/**
 * Persistent storage for host manager profiles. Platform implementation is file-based (desktop),
 * like [app.skerry.shared.ssh.KnownHostsStore]. The contract is synchronous: mutations are rare and
 * UI-initiated, no encryption yet.
 *
 * Implementations must be thread-safe: there are no background callers like the key verifier, but
 * the contract doesn't forbid multi-threaded access.
 */
interface HostStore {
    /** All profiles in insertion/update order. */
    fun all(): List<Host>

    /** Create a new record or replace an existing one with the same [Host.id] (upsert). */
    fun put(host: Host)

    /** Remove the record by id; missing id is a no-op. */
    fun remove(id: String)

    /**
     * Atomic reorder: [transform] receives the current profile list and returns the new order
     * (with updated [Host.group] when moving between folders). Read, compute, and write happen
     * under one lock — otherwise a reorder computed from a stale snapshot could clobber a concurrent
     * write (e.g. a vault migration redirecting [Host.credentialId]). The id set must not change:
     * implementations must reject a result with a different id set (a lost/duplicated profile means
     * a lost secret reference). The error message must not include the [Host] values themselves.
     */
    fun reorder(transform: (List<Host>) -> List<Host>)
}
