package app.skerry.ui.terminal

import androidx.compose.foundation.lazy.LazyListState
import app.skerry.shared.host.Host
import app.skerry.ui.design.FolderDrop
import app.skerry.ui.host.HostFolder
import app.skerry.ui.host.UNGROUPED_LABEL
import app.skerry.ui.host.groupHostsByConnectionType

/** Logical positions survive disposal of offscreen rows; layout is only queried for visible pixels. */
internal sealed interface SidebarCatalogRow {
    val key: String
    val folderIndex: Int
    data class Header(val folder: HostFolder, override val folderIndex: Int) : SidebarCatalogRow {
        override val key = "folder:${folder.name}"
    }
    data class Entry(val host: Host, val index: Int, override val folderIndex: Int) : SidebarCatalogRow {
        override val key = "host:${host.id}"
    }
    data class Transport(val type: app.skerry.shared.ssh.ConnectionType, override val folderIndex: Int) : SidebarCatalogRow {
        override val key = "transport:$folderIndex:$type"
    }
}

internal fun sidebarCatalogRows(folders: List<HostFolder>, collapsed: (String) -> Boolean, folderDragging: Boolean): List<SidebarCatalogRow> =
    buildList {
        folders.forEachIndexed { folderIndex, folder ->
            add(SidebarCatalogRow.Header(folder, folderIndex))
            if (!collapsed(folder.name) && !folderDragging) {
                if (folder.name == UNGROUPED_LABEL) {
                    var index = 0
                    groupHostsByConnectionType(folder.hosts).forEach { (type, hosts) ->
                        add(SidebarCatalogRow.Transport(type, folderIndex))
                        hosts.forEach { add(SidebarCatalogRow.Entry(it, index++, folderIndex)) }
                    }
                } else folder.hosts.forEachIndexed { index, host -> add(SidebarCatalogRow.Entry(host, index, folderIndex)) }
            }
        }
    }

/** Maps a visible item back to the complete filtered catalog, never counts only measured rows. */
internal class SidebarDropGeometry(
    private val folders: List<HostFolder>,
    rows: List<SidebarCatalogRow>,
    private val listState: LazyListState,
) {
    private val rowsByKey = rows.associateBy { it.key }
    private val draggedPositions = rows.filterIsInstance<SidebarCatalogRow.Entry>().associate { it.host.id to it }
    private val folderPositions = folders.mapIndexed { index, folder -> folder.name to index }.toMap()
    var windowTop: Float = 0f

    private fun nearest(pointerY: Float, headersOnly: Boolean = false): Pair<SidebarCatalogRow, Float>? {
        val y = pointerY - windowTop
        val visible = listState.layoutInfo.visibleItemsInfo
        var nearest: Pair<SidebarCatalogRow, Float>? = null
        var distance = Float.POSITIVE_INFINITY
        visible.forEach { item ->
            val row = rowsByKey[item.key] ?: return@forEach
            if (headersOnly && row !is SidebarCatalogRow.Header) return@forEach
            val gap = when {
                y < item.offset -> item.offset - y
                y > item.offset + item.size -> y - item.offset - item.size
                else -> 0f
            }
            if (gap < distance) {
                distance = gap
                nearest = row to (item.offset + item.size / 2f)
            }
        }
        return nearest
    }

    fun draggedIndex(id: String?, folderIndex: Int): Int =
        draggedPositions[id]?.takeIf { it.folderIndex == folderIndex }?.index ?: -1

    fun itemDrop(pointerY: Float, draggedId: String?): FolderDrop? {
        val (row, center) = nearest(pointerY) ?: return null
        val folder = folders[row.folderIndex]
        var index = when (row) {
            is SidebarCatalogRow.Entry -> row.index + if (pointerY - windowTop > center) 1 else 0
            else -> 0
        }
        val dragged = draggedPositions[draggedId]
        if (dragged != null && dragged.folderIndex == row.folderIndex && dragged.index < index) index--
        return FolderDrop(folder.hosts.firstOrNull()?.group ?: folder.name.takeIf { it != UNGROUPED_LABEL }, index)
    }

    fun folderDrop(pointerY: Float, draggedName: String?): Int {
        val (row, center) = nearest(pointerY, headersOnly = true) ?: return 0
        var index = row.folderIndex + if (pointerY - windowTop > center) 1 else 0
        val dragged = folderPositions[draggedName]
        if (dragged != null && dragged < index) index--
        return index
    }
}
