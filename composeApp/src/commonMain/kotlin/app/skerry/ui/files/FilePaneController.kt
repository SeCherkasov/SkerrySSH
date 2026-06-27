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

    /**
     * Путь строки под курсором — текущая позиция клавиатурной навигации (mc-режим), отдельная от
     * [selection]: курсор есть всегда (если каталог непустой), выделение может быть пустым. Стрелки
     * двигают курсор, Insert/Space помечают строку под курсором, Enter входит в каталог под курсором.
     */
    var cursor: String? by mutableStateOf(null)
        private set

    /** Курсор стоит на синтетической строке «..» (переход в родитель). Взаимоисключающе с [cursor]. */
    var cursorOnParent: Boolean by mutableStateOf(false)
        private set

    /** Доступна ли строка «..» (мы не в корне) — она открывает пространство навигации сверху. */
    val hasParent: Boolean get() = path != "/"

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
        op { navigateTo(item.path) }
    }

    /** Подняться в родительский каталог. Сбрасывает выделение. */
    fun goUp() = op { navigateTo(parentPath(path)) }

    /** Перечитать текущий каталог. */
    fun refresh() = op { reload() }

    /** Создать подкаталог [name] в текущем каталоге и навести курсор на него (как в mc). */
    fun mkdir(name: String) = op {
        val created = childPath(name)
        browser.mkdir(created)
        reload()
        loadedEntries().firstOrNull { it.path == created }?.let { setCursor(it) }
    }

    /** Удалить [item] (файл или каталог рекурсивно — со всем содержимым). */
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

    /** Сдвинуть курсор на [delta] строк (с зажимом по краям). Пространство навигации включает «..». */
    fun moveCursor(delta: Int) {
        if (combinedCount() == 0) {
            cursor = null
            cursorOnParent = false
            return
        }
        val current = cursorCombinedIndex().let { if (it < 0) 0 else it }
        setCombined(current + delta)
    }

    /** Курсор на первую строку (Home) — это «..», если мы не в корне. */
    fun cursorToFirst() = setCombined(0)

    /** Курсор на последнюю строку (End). */
    fun cursorToLast() = setCombined(combinedCount() - 1)

    /** Явно поставить курсор на [item] (клик мышью наводит курсор на строку). */
    fun setCursor(item: FileItem) {
        cursor = item.path
        cursorOnParent = false
    }

    /** Поставить курсор на строку «..» (родитель), если она доступна (мы не в корне). */
    fun setCursorOnParent() {
        if (hasParent) {
            cursorOnParent = true
            cursor = null
        }
    }

    /** Элемент под курсором, либо null (курсор на «..»/пустой каталог/курсор исчез). */
    fun cursoredItem(): FileItem? =
        if (cursorOnParent) null else loadedEntries().firstOrNull { it.path == cursor }

    /** Enter: «..» — наверх; каталог — войти; файл — no-op (просмотр появится с F3). */
    fun enterCursored() {
        if (cursorOnParent) goUp() else cursoredItem()?.let(::open)
    }

    /** Space: пометить/снять строку под курсором, не двигая курсор. «..» не помечается. */
    fun markCursored() {
        if (cursorOnParent) return
        cursoredItem()?.let(::toggle)
    }

    /** Insert: пометить/снять строку под курсором и сдвинуть курсор вниз. На «..» только сдвиг. */
    fun markCursoredAndAdvance() {
        if (cursorOnParent) {
            moveCursor(1)
            return
        }
        val item = cursoredItem() ?: return
        toggle(item)
        moveCursor(1)
    }

    /** Выделенные элементы текущего листинга в порядке отображения (для пакетных операций/передачи). */
    fun selectedItems(): List<FileItem> =
        (state as? FilePaneState.Loaded)?.entries?.filter { it.path in selection } ?: emptyList()

    /**
     * Перейти в каталог [target] АТОМАРНО: сперва загружаем его листинг, и только потом одним снимком
     * меняем path+state+выделение. Иначе path менялся бы раньше entries — старый список «висел» бы под
     * новым путём, а строка «..» (зависит от path) мигала бы сразу, давая видимый подскок/«пересортировку
     * в момент отрисовки» при каждом переходе (баг проявлялся и на desktop, и на mobile — общий слой).
     * До прихода листинга панель показывает прежний каталог без изменений. Ошибку листинга показываем
     * уже на новом пути (пользователь видит, куда пытался зайти).
     */
    private suspend fun navigateTo(target: String) {
        val next = loadState(target)
        path = target
        state = next
        resetSelection() // новый каталог — выделение пустое; pruneSelection не нужен (нечего чистить)
        // Курсор на первый реальный файл (не на «..»), как при открытии каталога в mc.
        cursor = (next as? FilePaneState.Loaded)?.entries?.firstOrNull()?.path
        cursorOnParent = false
    }

    /** Перечитать текущий [path] на месте (refresh/после mkdir/rename/delete — путь не меняется). */
    private suspend fun reload() {
        state = loadState(path)
        // На Error выделение сохраняется намеренно (no-op): пользователь видит ошибку и прежнюю
        // подсветку, может повторить. Чистим только при успешном листинге.
        pruneSelection()
        clampCursor()
    }

    /** Список загруженного каталога либо пусто (Loading/Error/пустой каталог). */
    private fun loadedEntries(): List<FileItem> = (state as? FilePaneState.Loaded)?.entries ?: emptyList()

    /** Сдвиг entries в объединённом пространстве: 0 в корне, 1 когда сверху есть «..». */
    private fun parentOffset(): Int = if (hasParent) 1 else 0

    /** Размер пространства навигации: entries плюс строка «..» (если доступна). */
    private fun combinedCount(): Int = loadedEntries().size + parentOffset()

    /** Индекс курсора в объединённом пространстве [.. , entries…], либо -1. */
    private fun cursorCombinedIndex(): Int {
        if (cursorOnParent && hasParent) return 0
        val c = cursor ?: return -1
        val idx = loadedEntries().indexOfFirst { it.path == c }
        return if (idx < 0) -1 else idx + parentOffset()
    }

    /** Поставить курсор по индексу объединённого пространства (с зажимом по краям). */
    private fun setCombined(index: Int) {
        val total = combinedCount()
        if (total == 0) {
            cursor = null
            cursorOnParent = false
            return
        }
        val i = index.coerceIn(0, total - 1)
        if (hasParent && i == 0) {
            cursorOnParent = true
            cursor = null
        } else {
            cursorOnParent = false
            cursor = loadedEntries()[i - parentOffset()].path
        }
    }

    /** После перечитывания: если курсорная строка исчезла (удаление/переименование) — на первую. */
    private fun clampCursor() {
        if (cursorOnParent) {
            if (!hasParent) setCombined(0) // оказались в корне — «..» исчезла, перевели на первую
            return
        }
        val entries = loadedEntries()
        if (entries.isEmpty()) {
            cursor = null
            return
        }
        if (cursor == null || entries.none { it.path == cursor }) cursor = entries.first().path
    }

    /** Загрузить и отсортировать листинг [target] в [FilePaneState] (без записи в поля стора). */
    private suspend fun loadState(target: String): FilePaneState =
        try {
            FilePaneState.Loaded(browser.list(target).sortedForPane())
        } catch (e: FileBrowserException) {
            FilePaneState.Error(e.message ?: "Ошибка файловой панели")
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
     *
     * [busy] — обычный (не `@Volatile`) флаг: рассчитываем, что [scope] однопоточный/Main-confined
     * (вызовы идут из UI-обработчиков, продолжение после suspend возвращается туда же). При
     * многопоточном диспетчере read/write [busy] стали бы гонкой — тогда сериализацию надо усилить.
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

    /**
     * Лексический родитель абсолютного пути для кнопки «вверх». НЕ просим сервер канонизировать
     * `"$path/.."` через [FileBrowser.realpath]: часть SFTP-серверов не разрешает REALPATH для путей
     * с `..` (наблюдалось «Не удалось разрешить путь /root/..»). Для навигации вверх лексический
     * родитель — и есть ожидаемое поведение (вернуться в каталог, откуда пришли), а локальная ФС всё
     * равно нормализует путь сама. Корень и пути без разделителя сводятся к `/`.
     */
    private fun parentPath(path: String): String {
        val trimmed = path.trimEnd('/')
        val cut = trimmed.lastIndexOf('/')
        return if (cut <= 0) "/" else trimmed.substring(0, cut)
    }
}

/** Каталоги первыми, затем по имени без учёта регистра — привычный порядок файлового менеджера. */
private fun List<FileItem>.sortedForPane(): List<FileItem> =
    sortedWith(
        compareBy(
            { if (it.type == FileItemType.Directory) 0 else 1 },
            { it.name.lowercase() },
        ),
    )
