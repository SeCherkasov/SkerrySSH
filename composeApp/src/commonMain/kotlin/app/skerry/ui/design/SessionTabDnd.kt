package app.skerry.ui.design

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned

/**
 * Индекс вставки перетаскиваемой вкладки среди ОСТАЛЬНЫХ вкладок (центры по X в порядке списка) для
 * позиции указателя [pointerX]: число вкладок, чей центр левее указателя. Результат — в координатах
 * списка БЕЗ перетаскиваемой, что и есть целевой `toIndex` для [app.skerry.ui.session.SessionsController.moveTab]
 * (он переставляет removeAt→add в той же модели). Чистая и тестируемая отдельно от Compose.
 */
fun tabInsertIndex(otherCentersX: List<Float>, pointerX: Float): Int = otherCentersX.count { it < pointerX }

/**
 * Состояние drag-reorder вкладок titlebar. Геометрию чипов в
 * координатах окна собирает [tabBoundsAnchor]; жест [draggableTab] ведёт указатель и на отпускании
 * считает целевой индекс чистой [tabInsertIndex]. Что переставить — решает вызывающий через onDrop
 * (обычно [app.skerry.ui.session.SessionsController.moveTab]).
 */
@Stable
class TabDragState {
    /** id перетаскиваемой вкладки (или null). */
    var draggingTabId by mutableStateOf<String?>(null)
        private set

    /**
     * Позиция линии вставки в координатах ПОЛНОГО списка (0..size): перед вкладкой с этим индексом
     * (size — линия в самом конце). null — drag не идёт. Учитывает, что перетаскиваемый чип всё ещё
     * отрисован в ряду (см. [refresh]).
     */
    var insertLineIndex by mutableStateOf<Int?>(null)
        private set

    // Горизонтальная позиция указателя в координатах окна, ведётся по ходу жеста.
    private var pointerX = 0f

    // Bounds чипов в координатах окна — пишутся при layout, читаются из жестов. Обычная HashMap (не
    // snapshot): composition их не читает. Все обращения на Main-потоке (layout + колбэки жестов).
    private val bounds = HashMap<String, Rect>()

    val isDragging: Boolean get() = draggingTabId != null

    fun setBounds(id: String, rect: Rect) { bounds[id] = rect }

    /** Забыть геометрию закрытой вкладки — иначе bounds копились бы по выбывшим id. */
    fun clearBounds(id: String) { bounds.remove(id) }

    fun start(id: String, localOffsetX: Float) {
        draggingTabId = id
        pointerX = (bounds[id]?.left ?: 0f) + localOffsetX
    }

    fun dragBy(deltaX: Float) { pointerX += deltaX }

    /** Целевой `toIndex` (в списке без перетаскиваемой) для текущей позиции указателя. */
    fun currentDropIndex(ids: List<String>): Int =
        tabInsertIndex(otherCenters(ids), pointerX)

    private fun otherCenters(ids: List<String>): List<Float> = ids
        .filter { it != draggingTabId }
        .mapNotNull { bounds[it]?.let { b -> (b.left + b.right) / 2f } }

    /**
     * Пересчитать линию вставки. [insertLineIndex] — в координатах полного ряда: drop-индекс `d`
     * (без перетаскиваемой) маппится с поправкой на позицию перетаскиваемого `p` (он всё ещё в ряду):
     * линия перед `d`, либо перед `d+1`, если цель правее исходной позиции. Пишем только при смене —
     * иначе каждое движение указателя перерисовывало бы titlebar.
     */
    fun refresh(ids: List<String>) {
        val dragId = draggingTabId ?: return
        val p = ids.indexOf(dragId)
        val d = currentDropIndex(ids)
        val line = if (d <= p) d else d + 1
        if (line != insertLineIndex) insertLineIndex = line
    }

    fun end() {
        draggingTabId = null
        insertLineIndex = null
    }
}

/** Записывает bounds чипа вкладки в координатах окна — drag-жест читает их на отпускании. */
fun Modifier.tabBoundsAnchor(state: TabDragState, tabId: String): Modifier =
    onGloballyPositioned { state.setBounds(tabId, it.boundsInWindow()) }

/**
 * Делает чип вкладки перетаскиваемым. [ids] читается лениво (на момент жеста — свежий порядок),
 * [onDrop] получает (fromIndex, toIndex) и переставляет вкладку через контроллер. Тапы проходят мимо
 * (detectDragGestures срабатывает только на реальном перетаскивании) к [clickable] чипа.
 */
fun Modifier.draggableTab(
    state: TabDragState,
    tabId: String,
    ids: () -> List<String>,
    onDrop: (fromIndex: Int, toIndex: Int) -> Unit,
): Modifier = pointerInput(tabId) {
    var moved = false
    detectDragGestures(
        onDragStart = { offset ->
            moved = false
            state.start(tabId, offset.x)
            state.refresh(ids())
        },
        onDrag = { change, amount ->
            change.consume()
            moved = true
            state.dragBy(amount.x)
            state.refresh(ids())
        },
        onDragEnd = {
            if (moved) {
                val list = ids()
                val from = list.indexOf(tabId)
                val to = state.currentDropIndex(list)
                if (from >= 0) onDrop(from, to)
            }
            state.end()
        },
        onDragCancel = { state.end() },
    )
}
