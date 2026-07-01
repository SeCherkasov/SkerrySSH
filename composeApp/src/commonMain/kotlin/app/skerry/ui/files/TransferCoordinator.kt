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
import app.skerry.ui.sftp.DownloadTarget
import app.skerry.ui.sftp.TransferDirection
import app.skerry.ui.sftp.UploadSource
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.ftail_delete_source_failed
import app.skerry.ui.generated.resources.ftail_file_fallback
import app.skerry.ui.generated.resources.ftail_transfer_error
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
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
    private val remoteBrowser: FileBrowser,
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

    /**
     * Залить выделенные локальные объекты в текущий каталог удалённой панели. Файлы заливаются как есть,
     * каталоги — рекурсивно (поддерево воссоздаётся на хосте), симметрично скачиванию. Симлинки/прочее
     * пропускаются. Прогресс/ошибка идут в [transfer]; сериализуется [busy].
     */
    fun uploadSelection() = launchExclusive {
        val items = local.selectedItems()
        if (items.isEmpty()) return@launchExclusive
        runUpload(items, remote.path)
        remote.refresh()
        local.clearSelection()
    }

    /**
     * Скачать выделенные удалённые объекты в текущий каталог локальной панели. Файлы качаются как есть,
     * каталоги — рекурсивно: сначала строится план обхода дерева ([buildDownloadPlan]), затем
     * воссоздаются локальные подкаталоги ([ensureLocalDir]) и по очереди качаются файлы дерева с общим
     * счётчиком прогресса. Симлинки/прочее пропускаются (за линком в цель не идём). Прогресс/ошибка
     * идут в [transfer]; сериализуется [busy].
     */
    fun downloadSelection() = launchExclusive {
        val items = remote.selectedItems()
        if (items.isEmpty()) return@launchExclusive
        runDownload(items, local.path, remote.path)
        local.refresh()
        remote.clearSelection()
    }

    /**
     * F6 Move: скопировать выделение активной панели в каталог другой и удалить источники ПОСЛЕ
     * успешной передачи (между разными ФС перемещение = copy + delete, как в mc). [fromLocal] —
     * активна локальная панель (заливаем на хост) или удалённая (качаем на локаль). Удаление идёт
     * только при успехе передачи: ошибка оставляет источники нетронутыми (catch до delete). Источники
     * снимаются рекурсивно (каталог — со всем содержимым). Подтверждение — на стороне UI.
     */
    fun moveSelection(fromLocal: Boolean) = launchExclusive {
        if (fromLocal) {
            val items = local.selectedItems()
            if (items.isEmpty()) return@launchExclusive
            runUpload(items, remote.path)
            val failed = deleteSources(items) { localBrowser.delete(it) }
            remote.refresh()
            local.refresh()
            if (failed == null) local.clearSelection() else transfer = failed
        } else {
            // Снимок каталога ДО любых suspend: навигация панели (open/goUp) идёт на том же scope и
            // могла бы сменить remote.path между скачиванием и удалением — тогда удалили бы из чужого
            // каталога. Один и тот же снимок идёт и в runDownload, и в пересборку пути удаления.
            val remoteDir = remote.path
            val items = remote.selectedItems()
            if (items.isEmpty()) return@launchExclusive
            runDownload(items, local.path, remoteDir)
            // Удаляем источник по пути, пересобранному из снимка каталога + проверенного имени, а не из
            // server-controlled item.path — иначе вредоносный листинг увёл бы rmdir/remove из каталога.
            val failed = deleteSources(items) { remoteBrowser.delete(it.copy(path = safeRemoteChild(it.name, remoteDir))) }
            local.refresh()
            remote.refresh()
            if (failed == null) remote.clearSelection() else transfer = failed
        }
    }

    /**
     * Удалить источники [items] после успешного переноса. Передача уже прошла (файлы на приёмнике), так
     * что сбой удаления не теряет данные, но оставляет частично перенесённое состояние — возвращаем
     * [TransferState.Failed] с именем КОНКРЕТНОГО сбойного объекта (а не дефолтным «файл», т.к. к этому
     * моменту [transfer] уже Idle). null — все источники удалены. [CancellationException] пробрасывается.
     */
    private suspend fun deleteSources(items: List<FileItem>, delete: suspend (FileItem) -> Unit): TransferState.Failed? {
        for (item in items) {
            try {
                delete(item)
            } catch (e: FileBrowserException) {
                return TransferState.Failed(item.name, e.message ?: getString(Res.string.ftail_delete_source_failed))
            }
        }
        return null
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
                transfer = TransferState.Failed(target.displayName, e.message ?: getString(Res.string.ftail_transfer_error))
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
                transfer = TransferState.Failed(source.name, e.message ?: getString(Res.string.ftail_transfer_error))
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
     * Запустить передачу, сериализуя её флагом [busy]: пока идёт одна, новые игнорируются. Любая
     * ошибка переводит полосу в [TransferState.Failed] (имя берём из текущего активного шага),
     * [CancellationException] пробрасывается. Пустую/no-op работу [block] отсеивает сам (ранний
     * return внутри). Колбэки прогресса приходят синхронно из передачи; запись snapshot-стейта
     * потокобезопасна.
     */
    private fun launchExclusive(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val name = (transfer as? TransferState.Active)?.name ?: getString(Res.string.ftail_file_fallback)
                transfer = TransferState.Failed(name, e.message ?: getString(Res.string.ftail_transfer_error))
            } finally {
                busy = false
            }
        }
    }

    /**
     * Залить [items] (файлы как есть, каталоги рекурсивно — [buildUploadPlan]) в удалённый [remoteDir],
     * воссоздавая поддерево на хосте ([ensureRemoteDir]); по завершении — [TransferState.Idle]. Без
     * сериализации/пост-действий: вызывается внутри уже взведённого [launchExclusive]-блока.
     */
    private suspend fun runUpload(items: List<FileItem>, remoteDir: String) {
        val plan = buildUploadPlan(items, remoteDir)
        // Каталоги создаём в порядке обхода (pre-order): родитель всегда раньше детей.
        plan.dirs.forEach { ensureRemoteDir(it) }
        plan.files.forEachIndexed { index, task ->
            transfer = TransferState.Active(task.name, TransferDirection.Upload, index + 1, plan.files.size, 0, task.size)
            sftp.upload(task.localPath, task.remotePath) { transferred, total ->
                transfer = TransferState.Active(task.name, TransferDirection.Upload, index + 1, plan.files.size, transferred, total)
            }
        }
        transfer = TransferState.Idle
    }

    /**
     * Скачать [items] (файлы как есть, каталоги рекурсивно — [buildDownloadPlan]) из удалённого [remoteDir]
     * в локальный [localDir], воссоздавая поддерево ([ensureLocalDir]); по завершении — [TransferState.Idle].
     * Без сериализации/пост-действий: вызывается внутри уже взведённого [launchExclusive]-блока.
     */
    private suspend fun runDownload(items: List<FileItem>, localDir: String, remoteDir: String) {
        val plan = buildDownloadPlan(items, localDir, remoteDir)
        // Каталоги создаём в порядке обхода (pre-order): родитель всегда раньше детей.
        plan.dirs.forEach { ensureLocalDir(it) }
        plan.files.forEachIndexed { index, task ->
            transfer = TransferState.Active(task.name, TransferDirection.Download, index + 1, plan.files.size, 0, task.size)
            sftp.download(task.remotePath, task.localPath) { transferred, total ->
                transfer = TransferState.Active(task.name, TransferDirection.Download, index + 1, plan.files.size, transferred, total)
            }
        }
        transfer = TransferState.Idle
    }

    /** Один файл к скачиванию: [name] для прогресс-полосы, удалённый [remotePath] → локальный [localPath]. */
    private data class DownloadTask(val name: String, val remotePath: String, val localPath: String, val size: Long)

    /** План рекурсивного скачивания: [dirs] — локальные каталоги в порядке создания, [files] — файлы. */
    private class DownloadPlan {
        val dirs = mutableListOf<String>()
        val files = mutableListOf<DownloadTask>()
    }

    /**
     * Построить план скачивания [items] (верхнего уровня) из удалённого [remoteDir] в локальный
     * [localDir]. Удалённый путь верхнего уровня собираем сами ([childPath] от [remoteDir] + имени), а
     * НЕ доверяем `item.path` из листинга — так же, как для детей в [walkDownload] (имя проверяется там).
     */
    private suspend fun buildDownloadPlan(items: List<FileItem>, localDir: String, remoteDir: String): DownloadPlan {
        val plan = DownloadPlan()
        items.forEach { walkDownload(it.name, childPath(remoteDir, it.name), it.type, it.size, localDir, plan) }
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

    /** Один файл к заливке: [name] для прогресс-полосы, локальный [localPath] → удалённый [remotePath]. */
    private data class UploadTask(val name: String, val localPath: String, val remotePath: String, val size: Long)

    /** План рекурсивной заливки: [dirs] — удалённые каталоги в порядке создания, [files] — файлы. */
    private class UploadPlan {
        val dirs = mutableListOf<String>()
        val files = mutableListOf<UploadTask>()
    }

    /** Построить план заливки [items] (верхнего уровня) в удалённый каталог [remoteDir]. */
    private suspend fun buildUploadPlan(items: List<FileItem>, remoteDir: String): UploadPlan {
        val plan = UploadPlan()
        items.forEach { walkUpload(it.name, it.path, it.type, it.size, remoteDir, plan) }
        return plan
    }

    /**
     * Обойти объект локального дерева, наполняя [plan] (зеркало [walkDownload]). Файл — задача заливки;
     * каталог — удалённый подкаталог + рекурсия по содержимому ([localBrowser] листит локальную ФС);
     * симлинк/прочее — пропуск. Удалённые пути строим сами от [remoteDir] + проверенного имени (без
     * разделителей/`.`/`..`), а локальные пути берём из доверенного локального листинга.
     */
    private suspend fun walkUpload(
        name: String,
        localPath: String,
        type: FileItemType,
        size: Long,
        remoteDir: String,
        plan: UploadPlan,
    ) {
        if (name.isEmpty() || "/" in name || "\\" in name || name == "." || name == "..") {
            throw SftpException("Недопустимое имя в листинге: $name")
        }
        val remotePath = childPath(remoteDir, name)
        when (type) {
            FileItemType.File -> plan.files += UploadTask(name, localPath, remotePath, size)
            FileItemType.Directory -> {
                plan.dirs += remotePath
                localBrowser.list(localPath).forEach { child ->
                    walkUpload(child.name, child.path, child.type, child.size, remotePath, plan)
                }
            }
            FileItemType.Symlink, FileItemType.Other -> Unit
        }
    }

    /** Создать удалённый каталог [path], если его ещё нет (зеркало [ensureLocalDir] для заливки). */
    private suspend fun ensureRemoteDir(path: String) {
        try {
            remoteBrowser.mkdir(path)
        } catch (e: FileBrowserException) {
            try {
                remoteBrowser.list(path)
            } catch (_: FileBrowserException) {
                throw e
            }
        }
    }

    /**
     * Безопасный путь удалённого объекта [name] в каталоге [remoteDir] для операций удаления: проверяем,
     * что [name] — простое имя (без разделителей/`.`/`..`), и строим путь сами от [remoteDir] (снимок
     * каталога панели), не доверяя server-controlled `item.path`.
     */
    private fun safeRemoteChild(name: String, remoteDir: String): String {
        if (name.isEmpty() || "/" in name || "\\" in name || name == "." || name == "..") {
            throw SftpException("Недопустимое имя в листинге: $name")
        }
        return childPath(remoteDir, name)
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
