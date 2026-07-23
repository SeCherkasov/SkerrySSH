package app.skerry.ui.theme

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Refcounted OS dark-mode watcher shared by every desktop [systemInDarkTheme] call site (app
 * theme, terminal twin, theme-picker preview): the first subscriber starts the single OS watcher
 * (gdbus monitor / poll), the last one stops it, and every value it pushes is fanned out to all
 * listeners and cached for [current]. Without sharing, each call site would spawn its own watcher
 * subprocess and its own synchronous initial detect.
 *
 * [detect] and [watch] are injectable for tests; production wiring lives in [INSTANCE].
 */
internal class SystemDarkMonitor(
    private val detect: () -> Boolean?,
    private val watch: suspend (onDark: (Boolean) -> Unit) -> Unit,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val lock = Any()
    private val listeners = LinkedHashSet<(Boolean) -> Unit>()
    private var last: Boolean? = null
    private var job: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    /**
     * Current OS dark-mode state. While a watcher is live this serves its cache (no subprocess);
     * while idle it re-detects, so a consumer that re-enables after a pause starts fresh. An
     * unknown OS answer keeps the last known value, defaulting to dark before any observation.
     */
    fun current(): Boolean {
        synchronized(lock) { if (job != null) last?.let { return it } }
        val fresh = detect()
        return synchronized(lock) {
            if (fresh != null) last = fresh
            last ?: true
        }
    }

    /**
     * Registers [listener] for pushed dark-mode changes, starting the shared watcher if this is
     * the first subscriber. Closing the handle unregisters; the last close stops the watcher
     * (cancelling its job, which tears down the underlying monitor process).
     */
    fun subscribe(listener: (Boolean) -> Unit): AutoCloseable {
        synchronized(lock) {
            listeners += listener
            if (job == null) job = scope.launch { watch(::publish) }
        }
        return AutoCloseable { unsubscribe(listener) }
    }

    private fun unsubscribe(listener: (Boolean) -> Unit) {
        val toCancel = synchronized(lock) {
            listeners -= listener
            if (listeners.isEmpty()) job.also { job = null } else null
        }
        toCancel?.cancel()
    }

    private fun publish(dark: Boolean) {
        // Snapshot under the lock, notify outside it: a listener may unsubscribe from its callback.
        val snapshot = synchronized(lock) {
            last = dark
            listeners.toList()
        }
        snapshot.forEach { it(dark) }
    }

    companion object {
        /** Process-wide monitor driving [systemInDarkTheme] on desktop. */
        val INSTANCE = SystemDarkMonitor(::detectSystemDark, ::watchSystemColorScheme)
    }
}
