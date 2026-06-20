package app.skerry.ui.files

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.skerry.shared.files.FileBrowser
import app.skerry.shared.files.FileBrowserException
import app.skerry.shared.files.FileItem
import app.skerry.shared.files.FileItemType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/** Состояние листинга одной файловой панели (локальной или удалённой). */
sealed interface FilePaneState {
    /** Идёт загрузка листинга. */
    data object Loading : FilePaneState

    /** Листинг загружен; [entries] отсортированы (каталоги первыми, затем по имени). */
    data class Loaded(val entries: List<FileItem>) : FilePaneState

    /** Последняя операция/загрузка не удалась; [message] для показа пользователю. */
    data class Error(val message: String) : FilePaneState
}

/**
 * Контроллер одной файловой панели поверх [FileBrowser] — общий для локальной ФС и удалённого SFTP
 * (Total-Commander-режим держит две такие панели). Навигация и операции над каталогами
 * (создать/удалить/переименовать) + мультивыделение строк (одиночное/Ctrl-тоггл/Shift-диапазон).
 * Передача файлов между панелями контроллеру НЕ принадлежит — она в координаторе экрана.
 *
 * Действия сериализуются флагом [busy]: пока идёт операция, новые игнорируются — иначе параллельные
 * list/мутации гонялись бы за один [state]/[path]. Ошибки операций переводят панель в
 * [FilePaneState.Error], не роняя контроллер. Владение источником ([FileBrowser]/каналом) — снаружи.
 */
@Stable
class FilePaneController(
    private val browser: FileBrowser,
    private val scope: CoroutineScope,
) {
    val label: String get() = browser.label

    var path: String by mutableStateOf("/")
        private set

    var state: FilePaneState by mutableStateOf(FilePaneState.Loading)
        private set

    /** Пути выделенных строк (для пакетных операций и подсветки). */
    var selection: Set<String> by mutableStateOf(emptySet())
        private set

    /** Якорь Shift-диапазона — путь последней одиночно выбранной/тогглнутой строки. Snapshot-стейт,
     *  чтобы чтение в [selectTo] было согласовано с записью [selection] в той же транзакции. */
    private var anchor: String? by mutableStateOf(null)
    private var busy = false

    /** Загрузить стартовый каталог источника. Вызывать один раз при открытии панели. */
    fun start() = op {
        path = browser.realpath(".")
        reload()
    }

    /** Войти в каталог [item]; для файла — no-op. Сбрасывает выделение (новый каталог). */
    fun open(item: FileItem) {
        if (item.type != FileItemType.Directory) return
        op {
            path = item.path
            resetSelection()
            reload()
        }
    }

    /** Подняться в родительский каталог. Сбрасывает выделение. */
    fun goUp() = op {
        path = browser.realpath("$path/..")
        resetSelection()
        reload()
    }

    /** Перечитать текущий каталог. */
    fun refresh() = op { reload() }

    /** Создать подкаталог [name] в текущем каталоге. */
    fun mkdir(name: String) = op {
        browser.mkdir(childPath(name))
        reload()
    }

    /** Удалить [item] (файл или пустой каталог). */
    fun delete(item: FileItem) = op {
        browser.delete(item)
        reload()
    }

    /** Переименовать [item] в [newName] (в пределах текущего каталога). */
    fun rename(item: FileItem, newName: String) = op {
        browser.rename(item.path, childPath(newName))
        reload()
    }

    /** Выделить только [item] (обычный клик), сделав его якорем для последующего Shift-диапазона. */
    fun selectOnly(item: FileItem) {
        selection = setOf(item.path)
        anchor = item.path
    }

    /** Ctrl-клик: добавить/убрать [item] из выделения, сделав его якорем. */
    fun toggle(item: FileItem) {
        selection = if (item.path in selection) selection - item.path else selection + item.path
        anchor = item.path
    }

    /**
     * Shift-клик: выделить диапазон от якоря до [item] по текущему отображаемому порядку; без якоря
     * (или если он исчез из листинга) — как [selectOnly]. Якорь сохраняется, чтобы Shift-клик по
     * другой строке переразмечал диапазон от той же точки. Выделение ЗАМЕНЯЕТСЯ диапазоном (MVP —
     * без аккумуляции к предыдущему Ctrl-выбору; добавится при необходимости).
     */
    fun selectTo(item: FileItem) {
        val a = anchor
        val entries = (state as? FilePaneState.Loaded)?.entries
        if (a == null || entries == null) {
            selectOnly(item)
            return
        }
        val from = entries.indexOfFirst { it.path == a }
        val to = entries.indexOfFirst { it.path == item.path }
        if (from < 0 || to < 0) {
            selectOnly(item)
            return
        }
        val range = if (from <= to) from..to else to..from
        selection = entries.slice(range).mapTo(mutableSetOf()) { it.path }
    }

    /** Снять всё выделение. */
    fun clearSelection() = resetSelection()

    /** Выделенные элементы текущего листинга в порядке отображения (для пакетных операций/передачи). */
    fun selectedItems(): List<FileItem> =
        (state as? FilePaneState.Loaded)?.entries?.filter { it.path in selection } ?: emptyList()

    /** Перечитать [path], отсортировать листинг и подчистить выделение от исчезнувших путей. */
    private suspend fun reload() {
        state = try {
            FilePaneState.Loaded(browser.list(path).sortedForPane())
        } catch (e: FileBrowserException) {
            FilePaneState.Error(e.message ?: "Ошибка файловой панели")
        }
        // На Error выделение сохраняется намеренно (no-op): пользователь видит ошибку и прежнюю
        // подсветку, может повторить. Чистим только при успешном листинге.
        pruneSelection()
    }

    /** Убрать из выделения/якоря пути, которых больше нет в текущем листинге (удалённые, перемещённые). */
    private fun pruneSelection() {
        val present = (state as? FilePaneState.Loaded)?.entries?.mapTo(mutableSetOf()) { it.path } ?: return
        if (!present.containsAll(selection)) selection = selection.intersect(present)
        if (anchor != null && anchor !in present) anchor = null
    }

    private fun resetSelection() {
        selection = emptySet()
        anchor = null
    }

    /**
     * Запустить операцию панели, сериализуя её флагом [busy]. Любая [FileBrowserException] из самой
     * операции (mkdir/rename/…) переводит панель в [FilePaneState.Error] — каждый путь к источнику
     * под защитой, не только [reload].
     */
    private fun op(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: FileBrowserException) {
                state = FilePaneState.Error(e.message ?: "Ошибка файловой панели")
            } finally {
                busy = false
            }
        }
    }

    /** Путь дочернего объекта [name] в текущем каталоге (без двойного `/` в корне). */
    private fun childPath(name: String): String = if (path == "/") "/$name" else "$path/$name"
}

/** Каталоги первыми, затем по имени без учёта регистра — привычный порядок файлового менеджера. */
private fun List<FileItem>.sortedForPane(): List<FileItem> =
    sortedWith(
        compareBy(
            { if (it.type == FileItemType.Directory) 0 else 1 },
            { it.name.lowercase() },
        ),
    )
