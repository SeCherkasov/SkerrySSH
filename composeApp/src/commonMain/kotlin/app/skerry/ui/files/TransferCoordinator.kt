package app.skerry.ui.files

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.skerry.shared.files.FileBrowser
import app.skerry.shared.files.FileBrowserException
import app.skerry.shared.files.FileItem
import app.skerry.shared.files.FileItemType
import app.skerry.shared.sftp.SftpClient
import app.skerry.shared.sftp.SftpEntryType
import app.skerry.shared.sftp.SftpException
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
 * При загрузке (upload) каталоги в выделении пропускаются; при скачивании (download) каталог берётся
 * рекурсивно — дерево обходится через [sftp], локальные подкаталоги воссоздаются через [localBrowser]
 * (паритет с поведением «скачать папку» в популярных SSH-клиентах). Одновременно идёт не более одной передачи
 * (сериализация флагом [busy]).
 */
@Stable
class TransferCoordinator(
    private val sftp: SftpClient,
    val local: FilePaneController,
    private val localBrowser: FileBrowser,
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

    /**
     * Скачать выделенные удалённые объекты в текущий каталог локальной панели. Файлы качаются как есть,
     * каталоги — рекурсивно: сначала строится план обхода дерева ([buildDownloadPlan]), затем
     * воссоздаются локальные подкаталоги ([ensureLocalDir]) и по очереди качаются файлы дерева с общим
     * счётчиком прогресса. Симлинки/прочее пропускаются (за линком в цель не идём). Прогресс/ошибка
     * идут в [transfer]; сериализуется [busy].
     */
    fun downloadSelection() {
        val items = remote.selectedItems()
        if (busy || items.isEmpty()) return
        busy = true
        scope.launch {
            try {
                val plan = buildDownloadPlan(items, local.path)
                // Каталоги создаём в порядке обхода (pre-order): родитель всегда раньше детей.
                plan.dirs.forEach { ensureLocalDir(it) }
                plan.files.forEachIndexed { index, task ->
                    transfer = TransferState.Active(task.name, TransferDirection.Download, index + 1, plan.files.size, 0, task.size)
                    sftp.download(task.remotePath, task.localPath) { transferred, total ->
                        transfer = TransferState.Active(task.name, TransferDirection.Download, index + 1, plan.files.size, transferred, total)
                    }
                }
                transfer = TransferState.Idle
                local.refresh()
                remote.clearSelection()
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

    /** Один файл к скачиванию: [name] для прогресс-полосы, удалённый [remotePath] → локальный [localPath]. */
    private data class DownloadTask(val name: String, val remotePath: String, val localPath: String, val size: Long)

    /** План рекурсивного скачивания: [dirs] — локальные каталоги в порядке создания, [files] — файлы. */
    private class DownloadPlan {
        val dirs = mutableListOf<String>()
        val files = mutableListOf<DownloadTask>()
    }

    /** Построить план скачивания [items] (верхнего уровня) в локальный каталог [localDir]. */
    private suspend fun buildDownloadPlan(items: List<FileItem>, localDir: String): DownloadPlan {
        val plan = DownloadPlan()
        items.forEach { walkDownload(it.name, it.path, it.type, it.size, localDir, plan) }
        return plan
    }

    /**
     * Обойти объект удалённого дерева, наполняя [plan]. Файл — задача скачивания; каталог — локальный
     * подкаталог + рекурсия по содержимому; симлинк/прочее — пропуск (за линком в цель не идём).
     *
     * Защита от выхода за пределы цели (path traversal от недоверенного сервера): [name] обязан быть
     * простым именем — без разделителей пути `/` и `\` (последний — сепаратор на Windows-таргете), не
     * `.`/`..` и не пустым. Удалённые пути детей мы строим сами от родителя + проверенного имени
     * ([childPath]) и НЕ доверяем `child.path` из листинга — иначе сервер увёл бы обход (и запись) в
     * чужой каталог. Так оба пути — локальный и удалённый — структурно остаются внутри своих корней.
     */
    private suspend fun walkDownload(
        name: String,
        remotePath: String,
        type: FileItemType,
        size: Long,
        localDir: String,
        plan: DownloadPlan,
    ) {
        if (name.isEmpty() || "/" in name || "\\" in name || name == "." || name == "..") {
            throw SftpException("Недопустимое имя в листинге: $name")
        }
        val localPath = childPath(localDir, name)
        when (type) {
            FileItemType.File -> plan.files += DownloadTask(name, remotePath, localPath, size)
            FileItemType.Directory -> {
                plan.dirs += localPath
                sftp.list(remotePath).forEach { child ->
                    walkDownload(child.name, childPath(remotePath, child.name), child.type.toItemType(), child.size, localPath, plan)
                }
            }
            FileItemType.Symlink, FileItemType.Other -> Unit
        }
    }

    /**
     * Создать локальный каталог [path], если его ещё нет. `mkdir` без `-p` бросает на уже существующем
     * каталоге — это нормально при повторном скачивании: проверяем листингом, что каталог реально есть,
     * и только тогда игнорируем ошибку; иначе (нет прав/это файл) пробрасываем исходную ошибку mkdir.
     */
    private suspend fun ensureLocalDir(path: String) {
        try {
            localBrowser.mkdir(path)
        } catch (e: FileBrowserException) {
            try {
                localBrowser.list(path)
            } catch (_: FileBrowserException) {
                throw e
            }
        }
    }

    /** Путь дочернего объекта [name] в каталоге [dir] (без двойного `/` в корне). */
    private fun childPath(dir: String, name: String): String = if (dir == "/") "/$name" else "$dir/$name"
}

/** Маппинг типа SFTP-записи в нейтральный [FileItemType] (для обхода дерева при скачивании). */
private fun SftpEntryType.toItemType(): FileItemType = when (this) {
    SftpEntryType.File -> FileItemType.File
    SftpEntryType.Directory -> FileItemType.Directory
    SftpEntryType.Symlink -> FileItemType.Symlink
    SftpEntryType.Other -> FileItemType.Other
}
