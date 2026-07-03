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

/** Состояние локальной модели для UI (карточка провайдера / список каталога). */
sealed interface LocalModelStatus {
    data object NotInstalled : LocalModelStatus
    data class Downloading(val downloadedBytes: Long, val totalBytes: Long) : LocalModelStatus
    data object Verifying : LocalModelStatus
    data object Installed : LocalModelStatus
    data class Failed(val message: String) : LocalModelStatus
}

/**
 * UI-контроллер жизненного цикла локальных моделей: закачка с прогрессом/отменой, удаление,
 * актуальный [LocalModelStatus] по каждой записи каталога. Зависимости — лямбды ([installed]/
 * [fetch]/[remove]), как у остальных AI-контроллеров: тестируется без сети и диска.
 *
 * Отмена закачки сохраняет part-файл (докачка при следующем старте — забота `ModelDownloader`),
 * поэтому Cancel безопасен и статус честно возвращается в [LocalModelStatus.NotInstalled].
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

    /** Пересчитать статусы с диска (после разблокировки/возврата на экран); активные закачки не трогаем. */
    fun refresh() {
        LocalModelCatalog.models.forEach { m ->
            if (jobs[m.id]?.isActive != true) {
                statuses[m.id] = if (installed(m)) LocalModelStatus.Installed else LocalModelStatus.NotInstalled
            }
        }
    }

    /** Начать (или докачать) модель. No-op, если уже качается или установлена. */
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

    /** Отменить активную закачку (part-файл остаётся — продолжится с того же места). */
    fun cancel(model: LocalModel) {
        jobs[model.id]?.cancel()
    }

    /** Удалить модель с диска (активную закачку — отменить). */
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
