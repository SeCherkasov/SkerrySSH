package app.skerry.ui.files

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.skerry.shared.files.FileItem
import app.skerry.shared.files.FileItemType
import app.skerry.shared.sftp.SftpClient
import app.skerry.shared.sftp.SftpProgress
import app.skerry.ui.sftp.DownloadTarget
import app.skerry.ui.sftp.TransferDirection
import app.skerry.ui.sftp.UploadSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/** Состояние пакетной передачи между панелями для нижней полосы переноса. */
sealed interface TransferState {
    /** Передачи нет. */
    data object Idle : TransferState

    /**
     * Идёт передача файла [name] ([fileIndex] из [fileCount] в пакете), [transferred] из [total]
     * байт ([total] = 0, если размер неизвестен).
     */
    data class Active(
        val name: String,
        val direction: TransferDirection,
        val fileIndex: Int,
        val fileCount: Int,
        val transferred: Long,
        val total: Long,
    ) : TransferState

    /** Передача [name] не удалась; [message] для показа пользователю. */
    data class Failed(val name: String, val message: String) : TransferState
}

/**
 * Координатор передачи файлов между [local]- и [remote]-панелями поверх одного удалённого
 * [SftpClient]. В двухпанельном режиме передача всегда идёт между локальной ФС и SFTP, что ложится
 * на готовые `SftpClient.download`/`upload` — отдельный транспорт не нужен. Координатор берёт
 * выделение панели-источника, гонит файлы по очереди в текущий каталог панели-приёмника, обновляет
 * [transfer] для прогресс-полосы, по завершении перечитывает приёмник и снимает выделение источника.
 * Каталоги в выделении пропускаются (рекурсивная передача — позже). Одновременно идёт не более одной
 * передачи (сериализация флагом [busy]).
 */
@Stable
class TransferCoordinator(
    private val sftp: SftpClient,
    val local: FilePaneController,
    val remote: FilePaneController,
    private val scope: CoroutineScope,
) {
    var transfer: TransferState by mutableStateOf(TransferState.Idle)
        private set

    /**
     * Сериализует передачи: проверка-и-взведение [busy] не атомарны, но безопасны — `uploadSelection`/
     * `downloadSelection` зовутся из UI-обработчиков на главном потоке, а `scope` панели наследует тот
     * же главный диспетчер, так что повторный тап в том же фрейме увидит уже взведённый флаг (как в
     * [FilePaneController]).
     */
    private var busy = false

    /** Загрузить выделенные локальные файлы в текущий каталог удалённой панели. */
    fun uploadSelection() = transferAll(
        files = local.selectedItems().filter { it.type == FileItemType.File },
        direction = TransferDirection.Upload,
        targetDir = remote.path,
        receiver = remote,
        source = local,
    ) { item, target, onProgress -> sftp.upload(item.path, target, onProgress) }

    /** Скачать выделенные удалённые файлы в текущий каталог локальной панели. */
    fun downloadSelection() = transferAll(
        files = remote.selectedItems().filter { it.type == FileItemType.File },
        direction = TransferDirection.Download,
        targetDir = local.path,
        receiver = local,
        source = remote,
    ) { item, target, onProgress -> sftp.download(item.path, target, onProgress) }

    /**
     * Скачать удалённый файл [item] в выбранную нативным пикером цель [target] (на Android — SAF-документ
     * «Save to…», на desktop — выбранный путь). SFTP пишет байты в `target.stagingPath`; по успеху —
     * `target.finalize()` (копирование staging→Uri), при ошибке/отмене — `target.discard()`. В отличие
     * от [downloadSelection] цель не привязана к локальной панели — это путь скачивания мобильного экрана
     * Files наружу из песочницы. Прогресс/ошибка идут в [transfer]; сериализуется тем же [busy]. Каталоги
     * игнорируются (рекурсивная передача — позже). `discard()` под [runCatching], чтобы сбой очистки не
     * подменил исходную ошибку.
     */
    fun downloadToTarget(item: FileItem, target: DownloadTarget) {
        if (busy || item.type != FileItemType.File) return
        busy = true
        scope.launch {
            try {
                transfer = TransferState.Active(target.displayName, TransferDirection.Download, 1, 1, 0, item.size)
                sftp.download(item.path, target.stagingPath) { transferred, total ->
                    transfer = TransferState.Active(target.displayName, TransferDirection.Download, 1, 1, transferred, total)
                }
                target.finalize()
                transfer = TransferState.Idle
            } catch (e: CancellationException) {
                runCatching { target.discard() }
                throw e
            } catch (e: Exception) {
                runCatching { target.discard() }
                transfer = TransferState.Failed(target.displayName, e.message ?: "Ошибка передачи")
            } finally {
                busy = false
            }
        }
    }

    /**
     * Fallback-загрузка: залить произвольный локальный [source] (из нативного пикера) в текущий каталог
     * remote-панели — на случай, когда в локальной панели нечего выделить. Имя на сервере — `source.name`.
     * Прогресс/ошибка идут в [transfer]; по завершении (успех/ошибка) вызывается `source.cleanup()` и
     * remote-панель перечитывается. Сериализуется тем же [busy], что и передачи по выделению.
     */
    fun uploadSource(source: UploadSource) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val target = childPath(remote.path, source.name)
                transfer = TransferState.Active(source.name, TransferDirection.Upload, 1, 1, 0, 0)
                sftp.upload(source.stagingPath, target) { transferred, total ->
                    transfer = TransferState.Active(source.name, TransferDirection.Upload, 1, 1, transferred, total)
                }
                transfer = TransferState.Idle
                remote.refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                transfer = TransferState.Failed(source.name, e.message ?: "Ошибка передачи")
            } finally {
                runCatching { source.cleanup() }
                busy = false
            }
        }
    }

    /** Закрыть полосу передачи (сбросить в [TransferState.Idle]); идущую передачу не трогает. */
    fun clearTransfer() {
        if (transfer !is TransferState.Active) transfer = TransferState.Idle
    }

    /**
     * Передать [files] по очереди в [targetDir], обновляя [transfer] на каждом файле и его прогрессе.
     * По успеху — перечитать [receiver] (показать новые файлы) и снять выделение [source]. Ошибка
     * любого файла останавливает пакет и переводит в [TransferState.Failed]. [CancellationException]
     * пробрасывается. Колбэк прогресса приходит синхронно изнутри [transferOne]; запись snapshot-стейта
     * потокобезопасна.
     */
    private fun transferAll(
        files: List<FileItem>,
        direction: TransferDirection,
        targetDir: String,
        receiver: FilePaneController,
        source: FilePaneController,
        transferOne: suspend (item: FileItem, target: String, onProgress: SftpProgress) -> Unit,
    ) {
        if (busy || files.isEmpty()) return
        busy = true
        scope.launch {
            try {
                files.forEachIndexed { index, item ->
                    val target = childPath(targetDir, item.name)
                    transfer = TransferState.Active(item.name, direction, index + 1, files.size, 0, item.size)
                    transferOne(item, target) { transferred, total ->
                        transfer = TransferState.Active(item.name, direction, index + 1, files.size, transferred, total)
                    }
                }
                transfer = TransferState.Idle
                receiver.refresh()
                source.clearSelection()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val name = (transfer as? TransferState.Active)?.name ?: "файл"
                transfer = TransferState.Failed(name, e.message ?: "Ошибка передачи")
            } finally {
                busy = false
            }
        }
    }

    /** Путь дочернего объекта [name] в каталоге [dir] (без двойного `/` в корне). */
    private fun childPath(dir: String, name: String): String = if (dir == "/") "/$name" else "$dir/$name"
}
