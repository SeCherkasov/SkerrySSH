package app.skerry.ui.ai

import androidx.compose.runtime.mutableStateMapOf
import app.skerry.shared.ai.local.LocalModel
import app.skerry.shared.ai.local.LocalModelCatalog
import app.skerry.shared.ai.local.ModelDownloadEvent
import app.skerry.shared.ai.local.ModelDownloadException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** UI-facing state of a local model (provider card / catalog list). */
sealed interface LocalModelStatus {
    data object NotInstalled : LocalModelStatus
    data class Downloading(val downloadedBytes: Long, val totalBytes: Long) : LocalModelStatus
    data object Verifying : LocalModelStatus
    data object Installed : LocalModelStatus
    data class Failed(val message: String) : LocalModelStatus
}

/**
 * UI controller for local model lifecycle: download with progress/cancel, delete, and current
 * [LocalModelStatus] per catalog entry. Dependencies are lambdas ([installed]/[fetch]/[remove]),
 * so it's testable without network or disk.
 *
 * Cancelling a download keeps the part-file (resume is `ModelDownloader`'s job), so cancel is safe
 * and the status returns to [LocalModelStatus.NotInstalled].
 */
class LocalModelController(
    private val installed: (LocalModel) -> Boolean,
    private val fetch: (LocalModel) -> Flow<ModelDownloadEvent>,
    private val remove: (LocalModel) -> Unit,
    private val scope: CoroutineScope,
) {
    private val statuses = mutableStateMapOf<String, LocalModelStatus>()
    private val jobs = mutableMapOf<String, Job>()

    init {
        refresh()
    }

    fun status(model: LocalModel): LocalModelStatus = statuses[model.id] ?: LocalModelStatus.NotInstalled

    /** Recomputes statuses from disk (after unlock/returning to the screen); active downloads are untouched. */
    fun refresh() {
        LocalModelCatalog.models.forEach { m ->
            if (jobs[m.id]?.isActive != true) {
                statuses[m.id] = if (installed(m)) LocalModelStatus.Installed else LocalModelStatus.NotInstalled
            }
        }
    }

    /** Starts (or resumes) downloading a model. No-op if already downloading or installed. */
    fun download(model: LocalModel) {
        if (jobs[model.id]?.isActive == true || installed(model)) return
        statuses[model.id] = LocalModelStatus.Downloading(0, model.sizeBytes)
        jobs[model.id] = scope.launch {
            try {
                fetch(model).collect { event ->
                    statuses[model.id] = when (event) {
                        is ModelDownloadEvent.Progress -> LocalModelStatus.Downloading(event.downloadedBytes, event.totalBytes)
                        ModelDownloadEvent.Verifying -> LocalModelStatus.Verifying
                        is ModelDownloadEvent.Completed -> LocalModelStatus.Installed
                    }
                }
            } catch (e: CancellationException) {
                statuses[model.id] = if (installed(model)) LocalModelStatus.Installed else LocalModelStatus.NotInstalled
                throw e
            } catch (e: ModelDownloadException) {
                statuses[model.id] = LocalModelStatus.Failed(friendlyMessage(e))
            } catch (e: Exception) {
                statuses[model.id] = LocalModelStatus.Failed("Model download failed: ${e.message}")
            }
        }
    }

    /** Cancels an active download (the part-file remains, resuming from the same point). */
    fun cancel(model: LocalModel) {
        jobs[model.id]?.cancel()
    }

    /** Deletes a model from disk (cancels an active download first). */
    fun delete(model: LocalModel) {
        cancel(model)
        remove(model)
        statuses[model.id] = LocalModelStatus.NotInstalled
    }

    private fun friendlyMessage(e: ModelDownloadException): String = when (e.kind) {
        ModelDownloadException.Kind.NETWORK -> "Network error — the download will resume from where it stopped."
        ModelDownloadException.Kind.INTEGRITY -> "The downloaded file failed verification. Try again."
    }
}
