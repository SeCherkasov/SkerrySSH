package app.skerry.ui.host

/**
 * Чистая геометрия сброса для drag-and-drop сайдбара: по вертикальной позиции указателя считает,
 * в какую папку и на какой индекс уронить хост ([hostDropTarget]) или куда переставить папку
 * ([folderDropTarget]). Вынесено из жестов Compose, чтобы покрыть тестами без UI
 * ([app.skerry.ui.host.HostDropTargetingTest]); жесты лишь поставляют координаты в пикселях окна.
 */

/**
 * Геометрия одной папки сайдбара в координатах окна. [top]/[bottom] — вертикальный диапазон блока
 * папки (для выбора целевой папки). [otherHostCentersY] — центры строк хостов этой папки, БЕЗ
 * перетаскиваемого: индекс среди них совпадает с контрактом [moveHostToGroup], который удаляет
 * перетаскиваемый хост перед вставкой.
 */
data class FolderBounds(
    val group: String?,
    val top: Float,
    val bottom: Float,
    val otherHostCentersY: List<Float>,
)

/** Куда уронить хост: группа целевой папки + индекс среди её хостов (без перетаскиваемого). */
data class HostDrop(val group: String?, val index: Int)

/**
 * Целевая папка — та, чей диапазон содержит [pointerY]; выше первой/ниже последней — зажимается к
 * краю. Индекс внутри — число хостов папки, чей центр выше указателя. `null`, если папок нет.
 */
fun hostDropTarget(folders: List<FolderBounds>, pointerY: Float): HostDrop? {
    if (folders.isEmpty()) return null
    val folder = folders.firstOrNull { pointerY >= it.top && pointerY <= it.bottom }
        ?: if (pointerY < folders.first().top) folders.first() else folders.last()
    return HostDrop(folder.group, folder.otherHostCentersY.count { it < pointerY })
}

/**
 * Индекс вставки папки среди прочих папок — число их заголовков выше [pointerY]. [headerCentersY]
 * передаётся БЕЗ перетаскиваемой папки, чтобы индекс совпал с контрактом [moveGroup].
 */
fun folderDropTarget(headerCentersY: List<Float>, pointerY: Float): Int =
    headerCentersY.count { it < pointerY }
