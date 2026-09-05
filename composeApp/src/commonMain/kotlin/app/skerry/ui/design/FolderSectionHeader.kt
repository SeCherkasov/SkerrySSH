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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
 * Folder header of a list section: chevron + folder icon + name + count, the whole row folding the
 * section on a click.
 *
 * The host sidebar's own header ([app.skerry.ui.terminal] `FolderHeader`) stays separate because
 * there the row carries a second control (the empty-folder pencil) in a layout of its own. Here the
 * full-width row is the fold target — which is what makes it usable on a phone, where a 22dp chevron
 * is not — and the drag, when the list has one, is a modifier the caller wraps around it: it claims
 * the pointer only after a dead zone or a long press, so a tap still reaches this row.
 *
 * The row names itself after what a click does and to which folder: a screen of headers all saying
 * "Collapse" says nothing about which one is which.
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
    dragging: Boolean = false,
    dropTarget: Boolean = false,
) {
    val label = folderLabel(name)
    val action = stringResource(
        if (collapsed) Res.string.shtail_group_expand else Res.string.shtail_group_collapse,
        label,
    )
    // The click label says what a click would do; the state says where the folder stands now. A
    // reader with action hints turned down hears only the latter, and without it hears neither.
    val state = stringResource(
        if (collapsed) Res.string.shtail_group_state_collapsed else Res.string.shtail_group_state_expanded,
    )
    val accent = dragging || dropTarget
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(
                when {
                    dragging -> Skerry.colors.card
                    dropTarget -> Skerry.colors.cyan.copy(alpha = DROP_TARGET_TINT)
                    else -> Color.Transparent
                },
            )
            .border(
                1.dp,
                when {
                    dragging -> Skerry.colors.cyan
                    dropTarget -> Skerry.colors.cyanBright
                    else -> Color.Transparent
                },
                RoundedCornerShape(6.dp),
            )
            .clickable(onClickLabel = action, role = Role.Button, onClick = onToggle)
            .semantics { stateDescription = state }
            .padding(padding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Sym(if (collapsed) "chevron_right" else "expand_more", size = 16.sp, color = Skerry.colors.faint)
        Sym("folder_open", size = 15.sp, color = Skerry.colors.cyanBright)
        Txt(
            label,
            color = if (accent) Skerry.colors.cyanBright else Skerry.colors.dim,
            size = 12.5.sp,
            weight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
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

/** Tint of a folder the pointer is dragging a row over — a wash, not a fill: the rows stay readable. */
private const val DROP_TARGET_TINT = 0.12f

/**
 * Header padding on a phone. The whole row is the fold target and it gets tapped over and over, so
 * it stays clear of the floor a finger needs; the horizontal inset is the list's own, because the
 * header lines up with the cards under it and each list insets those differently.
 */
fun mobileFolderHeaderPadding(horizontal: Dp = 0.dp): PaddingValues =
    PaddingValues(horizontal = horizontal, vertical = 8.dp)

/**
 * A list rendered as folder sections: a [FolderSectionHeader] per folder, then that folder's rows,
 * with a collapsed folder drawing its header alone. Emits into the caller's layout, so the list's
 * own container (scroll, spacing, padding) stays where it was.
 *
 * With nothing filed anywhere the rows are emitted flat: folders are opt-in, and a library that has
 * never used one must not sprout an "Ungrouped" header on upgrade ([hasFolders]). A draggable list
 * keeps its drop targets in that state too — the flat list is one unnamed folder as far as a drag
 * is concerned, so rows can still be reordered before the first folder exists.
 *
 * [scope] namespaces the fold state ([folderCollapseKey]) — pass a constant per list, not a value
 * derived from what is on screen. [itemKey] is the stable identity of a row, so folding a folder
 * does not make Compose rebuild the rows of the ones below it.
 *
 * Both drop callbacks are handed the ids the sections had on screen at the moment of the drop: the
 * caller may be showing a filtered slice of a list whose order lives in the whole of it, and an
 * index counted here means nothing without them ([FilteredFolderList]).
 *
 * Passing [onMoveItem]/[onMoveGroup] turns the sections into a drag surface, and is also what says
 * the list carries a manual order: its folders then come in the order the list itself has
 * ([foldersOf] with `ordered`) rather than alphabetically, because that order is what a folder drag
 * writes. [longPress] starts the drag on a long press instead of a mouse dead zone — the phones'
 * setting, where an immediate drag would hijack the scroll.
 */
@Composable
fun <T> FolderSections(
    items: List<T>,
    scope: String,
    collapse: FolderCollapse,
    group: (T) -> String?,
    itemKey: (T) -> String,
    headerPadding: PaddingValues = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
    longPress: Boolean = false,
    onEditGroup: ((String) -> Unit)? = null,
    onMoveItem: ((id: String, targetGroup: String?, indexInGroup: Int, visibleIds: Set<String>) -> Unit)? = null,
    onMoveGroup: ((group: String?, targetGroupIndex: Int, visibleIds: Set<String>) -> Unit)? = null,
    item: @Composable (T) -> Unit,
) {
    val draggable = onMoveItem != null
    // Keyed by the groups as well as the list: an entry is a mutable holder, so moving a row to
    // another folder changes what foldersOf answers without the list itself changing identity.
    val groups = items.map(group)
    val folders = remember(items, groups) { foldersOf(items, ordered = draggable || onMoveGroup != null, group) }
    val dragState = remember { FolderDragState() }
    val dragFolders = remember(folders) {
        folders.map { folder ->
            DragFolder(folder.name, folder.name.takeIf { it != UNGROUPED_FOLDER }, folder.items.map(itemKey))
        }
    }
    // Read through a state holder rather than captured directly: Modifier.pointerInput keeps the
    // coroutine it launched on the row's first pointer event, and a closure over the list itself
    // would keep answering with the folders that existed then — a folder created or renamed since
    // would be invisible to every later drag of that row.
    val latestFolders = rememberUpdatedState(dragFolders)
    val foldersProvider = remember { { latestFolders.value } }
    // The drop callbacks are held the same way and for the same reason: they are what the row's
    // frozen gesture coroutine calls, so a caller capturing anything of its own in them would keep
    // committing against the state that existed when the row was first touched.
    val moveItem = rememberUpdatedState(onMoveItem)
    val moveGroup = rememberUpdatedState(onMoveGroup)

    val onDropItem: ((String, String?, Int, Set<String>) -> Unit)? = onMoveItem?.let {
        { id, targetGroup, index, ids -> moveItem.value?.invoke(id, targetGroup, index, ids) }
    }

    if (!hasFolders(items, group)) {
        FlatRows(items, itemKey, dragState, longPress, onDropItem, item)
        return
    }

    val folderLine = dragState.folderLinePlacement(folders.map { it.name })

    folders.forEach { folder ->
        key(folder.name) {
            if (folder.name == folderLine.before) FolderDropLine()
            val collapseKey = folderCollapseKey(scope, folder.name)
            val collapsed = collapse.isGroupCollapsed(collapseKey)
            val targetGroup = folder.name.takeIf { it != UNGROUPED_FOLDER }
            val dragged = folder.name == dragState.draggingFolderName
            // `activeDrop?.group == targetGroup` alone would light the no-folder bucket up on every
            // drag that has not resolved a target yet: null == null.
            val rowDrop = dragState.activeDrop?.takeIf { dragState.draggingItemId != null }
            val dropTarget = rowDrop != null && rowDrop.group == targetGroup
            // The bucket is not a folder anyone named, so it is neither renameable nor draggable.
            val named = folder.name != UNGROUPED_FOLDER
            DisposableEffect(folder.name) { onDispose { dragState.clearFolderBounds(folder.name) } }

            // The range anchor measures on every layout pass, scroll frames included, and only a
            // drag ever reads it back — the keychain has no drag at all, so it does not pay for one.
            val rangeAnchor =
                if (draggable || onMoveGroup != null) Modifier.folderRangeAnchor(dragState, folder.name) else Modifier
            Column(
                Modifier.fillMaxWidth()
                    .alpha(if (dragged) DRAGGED_ALPHA else 1f)
                    .then(rangeAnchor),
            ) {
                val headerDrag = if (onMoveGroup != null && named) {
                    Modifier.folderHeaderAnchor(dragState, folder.name)
                        .draggableFolderHeader(dragState, folder.name, foldersProvider, longPress) { index ->
                            moveGroup.value?.invoke(targetGroup, index, foldersProvider().visibleItemIds())
                        }
                } else {
                    Modifier
                }
                FolderSectionHeader(
                    name = folder.name,
                    count = folder.items.size,
                    collapsed = collapsed,
                    onToggle = { collapse.toggleGroupCollapsed(collapseKey) },
                    modifier = headerDrag,
                    padding = headerPadding,
                    onEdit = if (named && onEditGroup != null) ({ onEditGroup(folder.name) }) else null,
                    dragging = dragged,
                    dropTarget = dropTarget,
                )
                // While a folder is being dragged every list shows headers only: the blocks between
                // them are what makes a header travel half a screen to move one place, and the drop
                // index is counted from headers regardless.
                if (!collapsed && dragState.draggingFolderName == null) {
                    DraggableRows(
                        rows = folder.items,
                        itemKey = itemKey,
                        state = dragState,
                        folders = foldersProvider,
                        longPress = longPress,
                        dropIndex = if (dropTarget) rowDrop?.index else null,
                        onMoveItem = onDropItem,
                        item = item,
                    )
                }
            }
        }
    }
    if (folderLine.atEnd) FolderDropLine()
}

/** How far a dragged folder fades while it travels — enough to read as lifted, not as gone. */
private const val DRAGGED_ALPHA = 0.6f

/** A list with nothing filed anywhere: no headers, but still one folder as far as a drag is concerned. */
@Composable
private fun <T> FlatRows(
    items: List<T>,
    itemKey: (T) -> String,
    state: FolderDragState,
    longPress: Boolean,
    onMoveItem: ((String, String?, Int, Set<String>) -> Unit)?,
    item: @Composable (T) -> Unit,
) {
    if (onMoveItem == null) {
        items.forEach { row -> key(itemKey(row)) { item(row) } }
        return
    }
    val flat = remember(items) { listOf(DragFolder(UNGROUPED_FOLDER, null, items.map(itemKey))) }
    val latestFlat = rememberUpdatedState(flat)
    val flatProvider = remember { { latestFlat.value } }
    Column(Modifier.fillMaxWidth().folderRangeAnchor(state, UNGROUPED_FOLDER)) {
        DraggableRows(
            rows = items,
            itemKey = itemKey,
            state = state,
            folders = flatProvider,
            longPress = longPress,
            dropIndex = if (state.draggingItemId != null) state.activeDrop?.index else null,
            onMoveItem = onMoveItem,
            item = item,
        )
    }
}

/**
 * The rows of one folder, with the insertion line where the drop would land. [dropIndex] is counted
 * over the rows that are *not* being dragged, which is the contract [moveIntoFolder] reads it under.
 */
@Composable
private fun <T> DraggableRows(
    rows: List<T>,
    itemKey: (T) -> String,
    state: FolderDragState,
    folders: () -> List<DragFolder>,
    longPress: Boolean,
    dropIndex: Int?,
    onMoveItem: ((String, String?, Int, Set<String>) -> Unit)?,
    item: @Composable (T) -> Unit,
) {
    val others = rows.filter { itemKey(it) != state.draggingItemId }
    val line = dropIndex?.coerceIn(0, others.size)
    val lineBeforeId = line?.takeIf { it < others.size }?.let { itemKey(others[it]) }
    rows.forEach { row ->
        val id = itemKey(row)
        key(id) {
            if (id == lineBeforeId) FolderDropLine()
            if (onMoveItem == null) {
                item(row)
            } else {
                DisposableEffect(id) { onDispose { state.clearItemBounds(id) } }
                Box(
                    Modifier.fillMaxWidth()
                        .itemBoundsAnchor(state, id)
                        .alpha(if (id == state.draggingItemId) DRAGGED_ROW_ALPHA else 1f)
                        .draggableItemRow(state, id, folders, longPress) { drop ->
                            onMoveItem(id, drop.group, drop.index, folders().visibleItemIds())
                        },
                ) {
                    item(row)
                }
            }
        }
    }
    if (line != null && line == others.size) FolderDropLine()
}

/** The dragged row stays visible in its old place, faded, so the list keeps its shape under the pointer. */
private const val DRAGGED_ROW_ALPHA = 0.4f

/**
 * Folder caption of a palette section: the folder's name alone, with no chevron and no count.
 *
 * A palette is opened, typed into and dismissed; there is nowhere to keep a fold state that would
 * still be there next time, so a header that folds would reset on every open. The caption only says
 * where the rows below it belong, which is what the split is for.
 */
@Composable
fun FolderCaption(name: String) {
    Txt(
        folderLabel(name),
        color = Skerry.colors.faint,
        size = 10.sp,
        weight = FontWeight.SemiBold,
        letterSpacing = 0.5.sp,
        modifier = Modifier.padding(start = 9.dp, top = 7.dp, bottom = 2.dp),
    )
}
