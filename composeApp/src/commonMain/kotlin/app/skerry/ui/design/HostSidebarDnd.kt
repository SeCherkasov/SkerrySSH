package app.skerry.ui.design

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import app.skerry.ui.host.FolderBounds
import app.skerry.ui.host.HostDrop
import app.skerry.ui.host.HostFolder
import app.skerry.ui.host.UNGROUPED_LABEL
import app.skerry.ui.host.folderDropTarget
import app.skerry.ui.host.hostDropTarget

/**
 * Состояние ручной сортировки сайдбара (drag-and-drop). Геометрию каждой строки/папки в координатах
 * окна собирают якорные модификаторы ([hostBoundsAnchor]/[folderRangeAnchor]/[folderHeaderAnchor]);
 * жесты ([draggableHostRow]/[draggableFolderHeader]) ведут указатель и на отпускании считают цель
 * чистыми [hostDropTarget]/[folderDropTarget]. Что куда переставить — решает [HostManagerController]
 * через колбэк, переданный модификатору.
 */
@Stable
class HostDragState {
    /** id перетаскиваемого хоста (или null). Перетаскивание хоста и папки взаимоисключающи. */
    var draggingHostId by mutableStateOf<String?>(null)
        private set

    /** Имя папки (HostFolder.name), которую сейчас тащат за заголовок (или null). */
    var draggingFolderName by mutableStateOf<String?>(null)
        private set

    /** Текущая цель сброса хоста — для подсветки целевой папки и линии вставки. */
    var activeHostDrop by mutableStateOf<HostDrop?>(null)
        private set

    /** Текущий индекс вставки папки (среди прочих папок) — для линии между папками. */
    var activeFolderDropIndex by mutableStateOf<Int?>(null)
        private set

    /** Вертикальная позиция указателя в координатах окна, ведётся по ходу жеста. */
    private var pointerY = 0f

    // Bounds в координатах окна — пишутся при layout, читаются только из жестов. Обычные HashMap, а не
    // Compose-map: composition их не читает, реактивность дала бы снапшот-запись на каждый layout-проход
    // (в т.ч. при скролле) впустую. Все обращения — на Main-потоке (layout + колбэки жестов).
    private val hostBounds = HashMap<String, Rect>()
    private val folderRange = HashMap<String, Rect>()
    private val folderHeader = HashMap<String, Rect>()

    val isDragging: Boolean get() = draggingHostId != null || draggingFolderName != null

    fun setHostBounds(id: String, rect: Rect) { hostBounds[id] = rect }
    fun setFolderRange(name: String, rect: Rect) { folderRange[name] = rect }
    fun setFolderHeader(name: String, rect: Rect) { folderHeader[name] = rect }

    /** Забыть геометрию строки удалённого хоста — иначе bounds накапливались бы по выбывшим id. */
    fun clearHostBounds(id: String) { hostBounds.remove(id) }

    fun startHostDrag(id: String, localOffsetY: Float) {
        draggingHostId = id
        pointerY = (hostBounds[id]?.top ?: 0f) + localOffsetY
    }

    fun startFolderDrag(name: String, localOffsetY: Float) {
        draggingFolderName = name
        pointerY = (folderHeader[name]?.top ?: 0f) + localOffsetY
    }

    fun dragBy(deltaY: Float) {
        pointerY += deltaY
    }

    /**
     * FolderBounds для расчёта сброса хоста: центры — без перетаскиваемого (как ждёт moveHostToGroup).
     * Папка без зафиксированной геометрии (ещё не прошла layout — например, отскроллена за экран)
     * пропускается; [hostDropTarget] тогда зажмёт сброс к ближайшей видимой папке.
     */
    fun hostFolderBounds(folders: List<HostFolder>): List<FolderBounds> = folders.mapNotNull { folder ->
        val range = folderRange[folder.name] ?: return@mapNotNull null
        FolderBounds(
            // Пустая папка хостов не имеет (выводимую из них) группу — берём её имя как ключ группы,
            // чтобы сброс хоста в свежесозданную пустую группу проставлял Host.group=её имя, а не null
            // (иначе хост уехал бы в Ungrouped). Синтетический «Ungrouped» как группа остаётся null.
            group = folder.hosts.firstOrNull()?.group ?: folder.name.takeIf { it != UNGROUPED_LABEL },
            top = range.top,
            bottom = range.bottom,
            otherHostCentersY = folder.hosts
                .filter { it.id != draggingHostId }
                .mapNotNull { hostBounds[it.id]?.let { b -> (b.top + b.bottom) / 2f } },
        )
    }

    fun currentHostDrop(folders: List<HostFolder>): HostDrop? = hostDropTarget(hostFolderBounds(folders), pointerY)

    /** Центры заголовков прочих папок (без перетаскиваемой) в порядке списка — для folderDropTarget. */
    fun currentFolderDropIndex(folders: List<HostFolder>): Int {
        val centers = folders
            .filter { it.name != draggingFolderName }
            .mapNotNull { folderHeader[it.name]?.let { b -> (b.top + b.bottom) / 2f } }
        return folderDropTarget(centers, pointerY)
    }

    fun refreshHostDrop(folders: List<HostFolder>) {
        // Только при смене цели — иначе каждое движение указателя перерисовывало бы все папки (подсветка).
        val next = currentHostDrop(folders)
        if (next != activeHostDrop) activeHostDrop = next
    }

    fun refreshFolderDrop(folders: List<HostFolder>) {
        val next = currentFolderDropIndex(folders)
        if (next != activeFolderDropIndex) activeFolderDropIndex = next
    }

    fun endDrag() {
        draggingHostId = null
        draggingFolderName = null
        activeHostDrop = null
        activeFolderDropIndex = null
    }
}

/** Записывает bounds строки хоста в окно — drag-цели читают его на отпускании. */
fun Modifier.hostBoundsAnchor(state: HostDragState, hostId: String): Modifier =
    onGloballyPositioned { state.setHostBounds(hostId, it.boundsInWindow()) }

/** Записывает bounds всего блока папки — определяет, над какой папкой находится указатель. */
fun Modifier.folderRangeAnchor(state: HostDragState, name: String): Modifier =
    onGloballyPositioned { state.setFolderRange(name, it.boundsInWindow()) }

/** Записывает bounds заголовка папки — задаёт центры для переупорядочивания папок. */
fun Modifier.folderHeaderAnchor(state: HostDragState, name: String): Modifier =
    onGloballyPositioned { state.setFolderHeader(name, it.boundsInWindow()) }

/**
 * Делает строку хоста перетаскиваемой. [folders] читается лениво (на момент жеста — свежий список),
 * [onDrop] получает целевую папку и индекс и переставляет хост через контроллер. [longPress] выбирает
 * старт жеста: на desktop — сразу по drag (мышь), на тач (mobile) — после долгого нажатия, иначе drag
 * перехватывал бы вертикальный скролл списка вместо его прокрутки.
 */
fun Modifier.draggableHostRow(
    state: HostDragState,
    hostId: String,
    folders: () -> List<HostFolder>,
    longPress: Boolean = false,
    onDrop: (HostDrop) -> Unit,
): Modifier = pointerInput(hostId, longPress) {
    var moved = false
    val onStart = { offset: Offset ->
        moved = false
        state.startHostDrag(hostId, offset.y)
        state.refreshHostDrop(folders())
    }
    val onMove = { change: PointerInputChange, amount: Offset ->
        change.consume()
        moved = true
        state.dragBy(amount.y)
        state.refreshHostDrop(folders())
    }
    val onEnd = {
        // Без реального перемещения (микро-жест на тапе) не трогаем каталог и диск.
        if (moved) state.currentHostDrop(folders())?.let(onDrop)
        state.endDrag()
    }
    val onCancel = { state.endDrag() }
    if (longPress) {
        detectDragGesturesAfterLongPress(onDragStart = onStart, onDrag = onMove, onDragEnd = onEnd, onDragCancel = onCancel)
    } else {
        detectDragGestures(onDragStart = onStart, onDrag = onMove, onDragEnd = onEnd, onDragCancel = onCancel)
    }
}

/**
 * Делает заголовок папки перетаскиваемым. [onDrop] получает целевой индекс среди папок и переставляет
 * блок через контроллер. [longPress] — см. [draggableHostRow]: тач-старт по долгому нажатию, чтобы не
 * мешать скроллу.
 */
fun Modifier.draggableFolderHeader(
    state: HostDragState,
    name: String,
    folders: () -> List<HostFolder>,
    longPress: Boolean = false,
    onDrop: (Int) -> Unit,
): Modifier = pointerInput(name, longPress) {
    var moved = false
    val onStart = { offset: Offset ->
        moved = false
        state.startFolderDrag(name, offset.y)
        state.refreshFolderDrop(folders())
    }
    val onMove = { change: PointerInputChange, amount: Offset ->
        change.consume()
        moved = true
        state.dragBy(amount.y)
        state.refreshFolderDrop(folders())
    }
    val onEnd = {
        if (moved) onDrop(state.currentFolderDropIndex(folders()))
        state.endDrag()
    }
    val onCancel = { state.endDrag() }
    if (longPress) {
        detectDragGesturesAfterLongPress(onDragStart = onStart, onDrag = onMove, onDragEnd = onEnd, onDragCancel = onCancel)
    } else {
        detectDragGestures(onDragStart = onStart, onDrag = onMove, onDragEnd = onEnd, onDragCancel = onCancel)
    }
}
