package app.skerry.shared.agent

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * Bounded in-memory trace of what the agent has been asked to do — the "recent activity" list in
 * Settings → SSH agent. Deliberately NOT persisted: which key signed for which host and when is
 * exactly the metadata a zero-knowledge client should not leave on disk, and the list only has to
 * answer "is something using my keys right now?".
 *
 * Entries arrive from the agent's IO threads (forwarded channels, socket connections) and are read
 * from the UI thread, so the read-modify-write is synchronized (as in
 * [app.skerry.shared.vault.FileSecurityLog]).
 */
class SshAgentActivityLog(
    private val max: Int = DEFAULT_MAX,
    private val clock: () -> String = { "" },
) {
    private val lock = SynchronizedObject()
    private val entries = ArrayDeque<SshAgentActivity>()

    /**
     * Record one use and hand the resulting list (newest first) to [publish] — still under the
     * lock, so two concurrent requests cannot publish their snapshots out of order and leave the
     * UI showing the older one.
     */
    fun record(usage: SshAgentUsage, publish: (List<SshAgentActivity>) -> Unit): Unit = synchronized(lock) {
        entries.addFirst(SshAgentActivity(usage.origin, usage.action, usage.keyComment, clock()))
        while (entries.size > max) entries.removeLast()
        publish(entries.toList())
    }

    /** Current entries, newest first. */
    fun recent(): List<SshAgentActivity> = synchronized(lock) { entries.toList() }

    fun clear(): Unit = synchronized(lock) { entries.clear() }

    companion object {
        const val DEFAULT_MAX = 20
    }
}

/** One entry of the activity list: what the agent did, for whom, and when. */
data class SshAgentActivity(
    val origin: SshAgentOrigin,
    val action: SshAgentAction,
    /** Comment of the key involved, when the action names one. */
    val keyComment: String?,
    /** ISO-8601, from the same clock as the security log. */
    val at: String,
)
