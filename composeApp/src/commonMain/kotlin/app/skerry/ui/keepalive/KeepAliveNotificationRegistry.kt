package app.skerry.ui.keepalive

/**
 * Pure session->notification-id registry used by the Android keep-alive service. Kept free of any
 * Android dependency so the id-allocation and lifecycle rules are unit-testable on all targets.
 *
 * Rules:
 * - A session id maps to one stable notification id for as long as it is registered ([register]
 *   twice keeps the first id — the per-session notification is updated in place, never replaced
 *   by a second one).
 * - Ids are allocated sequentially from [idBase] ([register] on a new session).
 * - [remove] returns the id that was in use (or null for an unknown id — idempotent), so the
 *   caller can cancel exactly that notification.
 * - [isEmpty] is true exactly when no session is registered — the service's foreground state.
 */
class KeepAliveNotificationRegistry(private val idBase: Int) {

    private val ids = LinkedHashMap<String, Int>()
    private var nextId = idBase

    /** The stable id for [sessionId], allocating a new one on first registration. */
    fun register(sessionId: String): Int = ids.getOrPut(sessionId) { nextId++ }

    /** The id previously held by [sessionId], or null when unknown (no-op). */
    fun remove(sessionId: String): Int? = ids.remove(sessionId)

    /** True when no session is registered. */
    val isEmpty: Boolean get() = ids.isEmpty()

    /** Number of registered sessions. */
    val size: Int get() = ids.size

    /** Registered session ids, in registration order. */
    fun idsInOrder(): List<String> = ids.keys.toList()
}
