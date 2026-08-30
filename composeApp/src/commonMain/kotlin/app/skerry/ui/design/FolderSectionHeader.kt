package app.skerry.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.shtail_group_collapse
import app.skerry.ui.generated.resources.shtail_group_expand
import app.skerry.ui.generated.resources.shtail_group_rename
import app.skerry.ui.generated.resources.shtail_group_state_collapsed
import app.skerry.ui.generated.resources.shtail_group_state_expanded
import app.skerry.ui.theme.Skerry
import org.jetbrains.compose.resources.stringResource

/**
 * Folder header of a list section: chevron + folder icon + name + count.
 * The chevron toggles collapsed state; the header body acts as a drag surface.
 */
@Composable
fun FolderSectionHeader(
    name: String,
    count: Int,
    collapsed: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
    onEdit: (() -> Unit)? = null,
    isDragging: Boolean = false,
    isDropTarget: Boolean = false,
) {
    val label = folderLabel(name)
    val action = stringResource(
        if (collapsed) Res.string.shtail_group_expand else Res.string.shtail_group_collapse,
        label,
    )
    val state = stringResource(
        if (collapsed) Res.string.shtail_group_state_collapsed else Res.string.shtail_group_state_expanded,
    )

    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(
                when {
                    isDragging -> Skerry.colors.card
                    isDropTarget -> Skerry.colors.cyan.copy(alpha = 0.12f)
                    else -> Color.Transparent
                }
            )
            .border(
                1.dp,
                when {
                    isDragging -> Skerry.colors.cyan
                    isDropTarget -> Skerry.colors.cyanBright
                    else -> Color.Transparent
                },
                RoundedCornerShape(6.dp)
            )
            .padding(padding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(
            Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(4.dp))
                .clickable(onClickLabel = action, role = Role.Button, onClick = onToggle)
                .semantics { stateDescription = state },
            contentAlignment = Alignment.Center,
        ) {
            Sym(if (collapsed) "chevron_right" else "expand_more", size = 16.sp, color = Skerry.colors.faint)
        }
        Sym("folder_open", size = 15.sp, color = if (isDragging || isDropTarget) Skerry.colors.cyanBright else Skerry.colors.cyanBright)
        Txt(
            label,
            color = if (isDragging || isDropTarget) Skerry.colors.cyanBright else Skerry.colors.dim,
            size = 12.5.sp,
            weight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .clickable(onClickLabel = action, role = Role.Button, onClick = onToggle),
        )
        if (onEdit != null) {
            IconBtn(
                "edit",
                onClick = onEdit,
                box = 20,
                icon = 13.sp,
                tint = Skerry.colors.faint,
                tooltip = stringResource(Res.string.shtail_group_rename, label),
            )
        }
        Box(
            Modifier.clip(RoundedCornerShape(8.dp)).background(Skerry.colors.card)
                .padding(horizontal = 6.dp, vertical = 1.dp),
        ) {
            Txt(count.toString(), color = Skerry.colors.faint, size = 10.sp)
        }
    }
}

/**
 * Header padding on a phone.
 */
fun mobileFolderHeaderPadding(horizontal: Dp = 0.dp): PaddingValues =
    PaddingValues(horizontal = horizontal, vertical = 8.dp)

/**
 * A list rendered as folder sections with optional drag-and-drop reordering and group management.
 */
@Composable
fun <T> FolderSections(
    items: List<T>,
    scope: String,
    collapse: FolderCollapse,
    group: (T) -> String?,
    itemKey: (T) -> String,
    selectedIds: Set<String> = emptySet(),
    headerPadding: PaddingValues = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
    longPress: Boolean = false,
    onEditGroup: ((String) -> Unit)? = null,
    onMoveItem: ((itemId: String, targetGroup: String?, targetIndexInGroup: Int) -> Unit)? = null,
    onMoveItems: ((itemIds: Set<String>, targetGroup: String?, targetIndexInGroup: Int) -> Unit)? = null,
    onMoveGroup: ((group: String?, targetGroupIndex: Int) -> Unit)? = null,
    item: @Composable (T) -> Unit,
) {
    val groupList = items.map { group(it) }
    val folders = remember(items, groupList) { foldersOf(items, group) }
    val hasAnyFolders = hasFolders(items, group)

    val canMove = onMoveItems != null || onMoveItem != null
    val canMoveGroup = onMoveGroup != null
    if (!hasAnyFolders && !canMove && !canMoveGroup) {
        items.forEach { row -> key(itemKey(row)) { item(row) } }
        return
    }

    val performMove: (Set<String>, String?, Int) -> Unit = { ids, targetGroup, targetIndex ->
        if (onMoveItems != null) {
            onMoveItems(ids, targetGroup, targetIndex)
        } else if (onMoveItem != null) {
            ids.forEachIndexed { i, id ->
                onMoveItem(id, targetGroup, targetIndex + i)
            }
        }
    }

    val dragState = remember { ListDragState() }

    if (!hasAnyFolders) {
        val singleFolder = Folder(UNGROUPED_FOLDER, items)
        val others = items.filter { !dragState.isItemDragging(itemKey(it)) }
        val isDropTarget = dragState.isDragging && dragState.activeDrop?.group == null
        val dropIndex = if (isDropTarget) dragState.activeDrop?.index?.coerceIn(0, others.size) else null
        val lineBeforeId = dropIndex?.takeIf { it < others.size }?.let { itemKey(others[it]) }

        Column(
            Modifier
                .fillMaxWidth()
                .folderRangeAnchor(dragState, UNGROUPED_FOLDER)
        ) {
            items.forEach { row ->
                val key = itemKey(row)
                key(key) {
                    if (key == lineBeforeId) ListDropLine()
                    val isRowDragging = dragState.isItemDragging(key)
                    val isAnchor = dragState.anchorId == key
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .itemBoundsAnchor(dragState, key)
                            .alpha(if (isRowDragging) 0.4f else 1f)
                            .then(
                                if (canMove) {
                                    Modifier.draggableItemRow(
                                        state = dragState,
                                        id = key,
                                        folders = { listOf(singleFolder) },
                                        keyOf = itemKey,
                                        selectedIds = { selectedIds },
                                        longPress = longPress,
                                        onDrop = { drop, movingIds -> performMove(movingIds, drop.group, drop.index) },
                                    )
                                } else Modifier
                            )
                    ) {
                        item(row)
                        if (isAnchor && dragState.draggingIds.size > 1) {
                            Box(
                                Modifier
                                    .align(Alignment.CenterEnd)
                                    .padding(end = 24.dp)
                            ) {
                                DragBatchBadge(dragState.draggingIds.size)
                            }
                        }
                    }
                }
            }
            if (dropIndex != null && dropIndex == others.size) ListDropLine()
        }
        return
    }

    val otherFolders = folders.filter { it.name != dragState.draggingFolderName }
    val folderLineIndex = dragState.draggingFolderName?.let { dragState.activeFolderDropIndex }
    val folderLineBefore = folderLineIndex?.takeIf { it < otherFolders.size }?.let { otherFolders[it].name }

    folders.forEach { folder ->
        key(folder.name) {
            if (folder.name == folderLineBefore) ListDropLine()
            val collapseKey = folderCollapseKey(scope, folder.name)
            val collapsed = collapse.isGroupCollapsed(collapseKey)
            val targetGroup = if (folder.name == UNGROUPED_FOLDER) null else folder.name
            val isDropTarget = dragState.isDragging && dragState.activeDrop?.group == targetGroup
            val onEdit = if (folder.name != UNGROUPED_FOLDER && onEditGroup != null) {
                { onEditGroup(folder.name) }
            } else null

            val others = folder.items.filter { !dragState.isItemDragging(itemKey(it)) }
            val dropIndex = if (isDropTarget) dragState.activeDrop?.index?.coerceIn(0, others.size) else null
            val lineBeforeId = dropIndex?.takeIf { it < others.size }?.let { itemKey(others[it]) }

            val isAnyFolderDragging = dragState.draggingFolderName != null
            val isThisFolderDragging = dragState.isFolderDragging(folder.name)
            val folderAlpha = if (isThisFolderDragging) 0.6f else 1f

            Column(
                Modifier
                    .fillMaxWidth()
                    .alpha(folderAlpha)
                    .folderRangeAnchor(dragState, folder.name)
            ) {
                val headerModifier = if (canMoveGroup && folder.name != UNGROUPED_FOLDER) {
                    Modifier
                        .fillMaxWidth()
                        .folderHeaderAnchor(dragState, folder.name)
                        .draggableFolderHeader(
                            state = dragState,
                            name = folder.name,
                            folders = { folders },
                            longPress = longPress,
                            onDrop = { targetIndex ->
                                onMoveGroup?.invoke(targetGroup, targetIndex)
                            },
                        )
                } else Modifier.fillMaxWidth()

                Box(headerModifier) {
                    FolderSectionHeader(
                        name = folder.name,
                        count = folder.items.size,
                        collapsed = collapsed,
                        onToggle = { collapse.toggleGroupCollapsed(collapseKey) },
                        padding = headerPadding,
                        onEdit = onEdit,
                        isDragging = isThisFolderDragging,
                        isDropTarget = isDropTarget,
                    )
                }
                // When any folder is being dragged, temporarily collapse all folders into compact headers
                // so the user can easily reorder between groups without list jumping.
                if (!collapsed && !isAnyFolderDragging) {
                    folder.items.forEach { row ->
                        val key = itemKey(row)
                        key(key) {
                            if (key == lineBeforeId) ListDropLine()
                            val isRowDragging = dragState.isItemDragging(key)
                            val isAnchor = dragState.anchorId == key
                            val rowModifier = if (canMove) {
                                Modifier
                                    .fillMaxWidth()
                                    .itemBoundsAnchor(dragState, key)
                                    .alpha(if (isRowDragging) 0.4f else 1f)
                                    .draggableItemRow(
                                        state = dragState,
                                        id = key,
                                        folders = { folders },
                                        keyOf = itemKey,
                                        selectedIds = { selectedIds },
                                        longPress = longPress,
                                        onDrop = { drop, movingIds -> performMove(movingIds, drop.group, drop.index) },
                                    )
                            } else Modifier.fillMaxWidth()

                            Box(rowModifier) {
                                item(row)
                                if (isAnchor && dragState.draggingIds.size > 1) {
                                    Box(
                                        Modifier
                                            .align(Alignment.CenterEnd)
                                            .padding(end = 24.dp)
                                    ) {
                                        DragBatchBadge(dragState.draggingIds.size)
                                    }
                                }
                            }
                        }
                    }
                    if (dropIndex != null && dropIndex == others.size) ListDropLine()
                }
            }
        }
    }
    if (folderLineIndex != null && folderLineIndex == otherFolders.size) ListDropLine()
}
