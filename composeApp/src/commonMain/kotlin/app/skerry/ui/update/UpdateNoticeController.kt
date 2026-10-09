package app.skerry.ui.update

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.skerry.shared.update.AvailableUpdate
import app.skerry.shared.update.UpdateSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * UI controller for the update notice: owns the [UpdateSettings] toggle and, while it is on, a
 * periodic check loop ([check] once per [recheckIntervalMs]). Does not depend on Vault directly;
 * settings come via [persist]/[reload] lambdas (see [app.skerry.ui.ai.AiAssistantController]).
 * A check failure never surfaces: an earlier notice is kept, errors stay silent.
 */
class UpdateNoticeController(
    initialSettings: UpdateSettings,
    private val persist: (UpdateSettings) -> Unit,
    private val check: suspend () -> AvailableUpdate?,
    private val scope: CoroutineScope,
    private val reload: () -> UpdateSettings = { initialSettings },
    private val recheckIntervalMs: Long = DEFAULT_RECHECK_MS,
) {
    var settings by mutableStateOf(initialSettings); private set

    /** Latest known newer release (independent of dismissal, for the About section). */
    var available by mutableStateOf<AvailableUpdate?>(null); private set

    // Dismissal is per version: "later" on 0.2.0 must not swallow the eventual 0.3.0 notice.
    private var dismissedVersion by mutableStateOf<String?>(null)

    private var job: Job? = null

    // The loop runs on a multi-threaded [scope] while the toggle flips on the UI thread, and
    // Job.cancel() does not wait: a check result could land after reconcile() cleared [available]
    // and stick forever. Storing a result is therefore gated by a generation stamp under a lock.
    private val lock = Any()
    private var generation = 0

    /** What the passive notice (status bar / banner) should show; null when dismissed or none. */
    val notice: AvailableUpdate? get() = available?.takeIf { it.versionLabel != dismissedVersion }

    /**
     * Reloads settings from storage (after vault unlock or live sync) and reconciles the loop.
     * Deliberately does NOT restart an already-running loop: reloadManagers fires on every synced
     * change, and each restart would re-check immediately.
     */
    fun refresh() { prepareRefresh().invoke() }

    fun prepareRefresh(): () -> Unit {
        val incoming = reload()
        return { settings = incoming; reconcile() }
    }

    /** Persists the toggle; turning it off also stops the loop and hides any current notice. */
    fun setCheckForUpdates(on: Boolean) {
        val next = settings.copy(checkForUpdates = on)
        persist(next)
        settings = next
        reconcile()
    }

    /** Hides the notice for the currently known version (a newer one shows again). */
    fun dismiss() {
        dismissedVersion = available?.versionLabel
    }

    /** Stops the check loop (e.g. when the owning composition leaves); settings stay untouched. */
    fun stop() {
        synchronized(lock) {
            generation++
            job?.cancel()
            job = null
        }
    }

    private fun reconcile() {
        if (!settings.checkForUpdates) {
            stop()
            available = null
            return
        }
        synchronized(lock) {
            if (job != null) return
            val gen = generation
            job = scope.launch {
                while (isActive) {
                    val found = check()
                    // Discard a result from a loop that was stopped while check() was in flight.
                    if (found != null) synchronized(lock) { if (gen == generation) available = found }
                    delay(recheckIntervalMs)
                }
            }
        }
    }

    companion object {
        /** Long-running desktop sessions re-check daily; each check is one small anonymous GET. */
        const val DEFAULT_RECHECK_MS: Long = 24 * 60 * 60 * 1000
    }
}
