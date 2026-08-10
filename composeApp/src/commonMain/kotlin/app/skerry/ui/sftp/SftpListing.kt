package app.skerry.ui.sftp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.files.FileItem
import app.skerry.shared.files.FileItemType
import app.skerry.ui.app.LocalSftpPrefs
import app.skerry.ui.design.HLine
import app.skerry.ui.design.NO_PRESS
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.design.labelUppercase
import app.skerry.ui.files.FilePaneController
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.sftp_col_modified
import app.skerry.ui.generated.resources.sftp_col_name
import app.skerry.ui.generated.resources.sftp_col_permissions_short
import app.skerry.ui.generated.resources.sftp_col_size
import app.skerry.ui.theme.Skerry
import org.jetbrains.compose.resources.stringResource

/** Double-click threshold for a row (ms between two LMB presses → enter directory). */
private const val DOUBLE_CLICK_MS = 350L

@Composable
internal fun LivePaneList(
    pane: FilePaneController,
    entries: List<FileItem>,
    mono: FontFamily,
    listState: LazyListState,
    active: Boolean,
    onActivate: () -> Unit,
) {
    val prefs = LocalSftpPrefs.current
    // The optional columns are fixed-width and the row has no horizontal scroll: on a narrow pane
    // (small window, 50/50 split) they'd push past the pane edge while the weighted name shrinks
    // to nothing. Below these widths the optional columns yield — permissions first, then the date.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // The permissions slot also needs data to exist for this pane at all (the local okio
        // source reports no mode bits) — otherwise it would be a dead 76dp on every local row.
        val showPermissions = prefs.showPermissions && maxWidth >= 460.dp && entries.any { it.permissions != null }
        val showModified = prefs.showModified && maxWidth >= 360.dp
        Column(Modifier.fillMaxSize()) {
            ColumnHeaderRow(showModified = showModified, showPermissions = showPermissions)
            LazyColumn(Modifier.fillMaxSize(), state = listState) {
                if (pane.path != "/") {
                    item(key = "..") {
                        LiveFileRow(
                            // Folder icon, like the mockup: the row is the parent directory, and the
                        // way up is what double-clicking it does, not what it is.
                        "folder", Skerry.colors.faint, "..",
                            // The parent row has nothing to report in any column, but takes the
                            // same slots the entries take — otherwise its dash drifts right,
                            // under whichever column happens to be last.
                            columns = FileRowColumns(
                                permissions = if (showPermissions) "" else null,
                                modified = if (showModified) "" else null,
                                size = NO_SIZE,
                            ),
                            isSelected = false, cursored = pane.cursorOnParent, active = active, mono = mono,
                            // A single click only puts the cursor on ".."; going up is a double click (like entering a directory).
                            onPress = { onActivate(); pane.setCursorOnParent() },
                            onDoubleClick = { onActivate(); pane.goUp() },
                            rubberBand = null, // the ".." row can't be marked — no rubber-band needed on it
                        )
                    }
                }
                items(entries, key = { it.path }) { entry ->
                    // Single click (on press): activate the pane and place the cursor — doesn't mark or enter.
                    // Entering a directory is a double click (open; no-op for a file). Selection — RMB/Space/Insert.
                    val onPress = {
                        onActivate()
                        pane.setCursor(entry)
                    }
                    val onDoubleClick = {
                        onActivate()
                        pane.setCursor(entry)
                        pane.open(entry)
                    }
                    LiveFileRow(
                        icon = sftpFileIcon(entry.name, entry.type),
                        iconColor = if (entry.type == FileItemType.Directory) Skerry.colors.cyanBright else Skerry.colors.faint,
                        name = entry.name,
                        directory = entry.type == FileItemType.Directory,
                        // An enabled column always occupies its fixed-width slot — an empty value
                        // renders as a blank slot, otherwise rows with a missing value (a directory's
                        // size, an unreported mtime) would let the remaining columns drift right and
                        // break the vertical alignment.
                        columns = FileRowColumns(
                            permissions = if (showPermissions) permissionsText(entry.type, entry.permissions).orEmpty() else null,
                            modified = if (showModified) fileDateText(entry.modifiedEpochSeconds) else null,
                            size = if (entry.type == FileItemType.File) humanSize(entry.size) else NO_SIZE,
                        ),
                        isSelected = entry.path in pane.selection,
                        cursored = entry.path == pane.cursor,
                        active = active,
                        mono = mono,
                        onPress = onPress,
                        onDoubleClick = onDoubleClick,
                        rubberBand = RowRubberBand(entry, pane, listState, entries, onActivate),
                    )
                }
            }
        }
    }
}

/**
 * Column captions above a pane's listing (NAME / SIZE / MODIFIED / PERMS). Fixed, not part of the
 * scrolled list: the listing is read by column, and a header that scrolls away leaves the numbers
 * unnamed. Slot widths mirror [LiveFileRow]'s, so captions stay over their values.
 */
@Composable
internal fun ColumnHeaderRow(showModified: Boolean, showPermissions: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Skerry.colors.overlayFaint)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ColumnCaption(stringResource(Res.string.sftp_col_name), Modifier.padding(start = ICON_SLOT_WIDTH).weight(1f))
        ColumnCaption(stringResource(Res.string.sftp_col_size), Modifier.width(SIZE_COLUMN_WIDTH), TextAlign.End)
        if (showModified) ColumnCaption(stringResource(Res.string.sftp_col_modified), Modifier.width(MODIFIED_COLUMN_WIDTH))
        if (showPermissions) ColumnCaption(stringResource(Res.string.sftp_col_permissions_short), Modifier.width(PERMISSIONS_COLUMN_WIDTH))
    }
    HLine()
}

@Composable
private fun ColumnCaption(text: String, modifier: Modifier, align: TextAlign = TextAlign.Start) {
    Txt(
        labelUppercase(text),
        color = Skerry.colors.faint,
        size = 9.5.sp,
        weight = FontWeight.SemiBold,
        letterSpacing = 0.9.sp,
        maxLines = 1,
        align = align,
        modifier = modifier,
    )
}

/**
 * Row rubber-band gesture (mc-style selection with held RMB). A press paints [entry] (toggle by its
 * current state), dragging down/up paints the whole range with the same sign. The mouse cursor captured
 * by the pressed row is translated into list coordinates via the row offset in [listState], then the row
 * under it is found in [listState] and painted up to it. No scroll on edge drag (the right button doesn't
 * scroll the list) — offsets are stable for the whole gesture.
 */
internal class RowRubberBand(
    val entry: FileItem,
    val pane: FilePaneController,
    val listState: LazyListState,
    val entries: List<FileItem>,
    val onActivate: () -> Unit,
) {
    // Member-extension on the restricted-scope AwaitPointerEventScope — else awaitPointerEvent() can't be called.
    suspend fun AwaitPointerEventScope.dragSelect(press: PointerEvent) {
        onActivate() // painting in this pane — make it active (F-keys go here)
        // Fix the sign by the pressed row: not marked → paint, marked → clear.
        val select = entry.path !in pane.selection
        pane.rubberBandTo(entry, entry, select)
        press.changes.forEach { it.consume() }
        val anchorOffset = listState.layoutInfo.visibleItemsInfo
            .firstOrNull { it.key == entry.path }?.offset ?: 0
        while (true) {
            val drag = awaitPointerEvent()
            if (!drag.buttons.isSecondaryPressed) break // RMB released — end of gesture
            val listY = anchorOffset + drag.changes.first().position.y
            val key = listState.layoutInfo.visibleItemsInfo
                .firstOrNull { listY >= it.offset && listY < it.offset + it.size }?.key as? String
            key?.let { k -> entries.firstOrNull { it.path == k } }
                ?.let { target -> pane.rubberBandTo(entry, target, select) }
            drag.changes.forEach { it.consume() }
        }
    }
}

/**
 * Right-side row columns, each `null` when hidden/empty for this row: [size] (files carry a size,
 * anything else the [NO_SIZE] dash), [modified] (mtime), [permissions] (`ls -l` style, remote pane
 * only — the local okio source doesn't report mode bits). Fixed widths keep the columns aligned
 * across rows; the order matches the captions in [ColumnHeaderRow].
 */
internal data class FileRowColumns(
    val permissions: String? = null,
    val modified: String? = null,
    val size: String? = null,
)

/**
 * Size cell of a row that has no size of its own (directory, symlink). Not translatable: an em
 * dash is typography, not copy.
 */
internal const val NO_SIZE = "—"

private val PERMISSIONS_COLUMN_WIDTH = 76.dp

private val MODIFIED_COLUMN_WIDTH = 96.dp

private val SIZE_COLUMN_WIDTH = 62.dp

/** Width of the row's leading icon slot; the column captions indent by it to clear the icons. */
private val ICON_SLOT_WIDTH = 27.dp

/** Hairline between listing rows. */
private val ROW_SEPARATOR_WIDTH = 1.dp

@Composable
internal fun LiveFileRow(
    icon: String,
    iconColor: Color,
    name: String,
    columns: FileRowColumns,
    // Named apart from the semantics property it feeds: `selected` inside a SemanticsPropertyReceiver
    // resolves to the property, not to this.
    isSelected: Boolean,
    cursored: Boolean,
    active: Boolean,
    mono: FontFamily,
    onPress: () -> Unit,
    onDoubleClick: () -> Unit,
    // A directory reads by its name colour, the way a file manager's listing does — the icon alone
    // is too small to sort a long listing by eye.
    directory: Boolean = false,
    // Data for held-RMB rubber-band (mc): the anchor row, controller, list state for translating the
    // cursor position to a row, and the current listing. null for the synthetic ".." row.
    rubberBand: RowRubberBand?,
) {
    // Latest callbacks without restarting the gesture (pointerInput is keyed on Unit — it lives the row's whole life).
    val currentPress by rememberUpdatedState(onPress)
    val currentDouble by rememberUpdatedState(onDoubleClick)
    // Cursor (navigation position) and selection (marked files) are distinct in mc: the active pane's
    // cursor is a bright bar, the inactive one's a neutral one; selection is a highlight + bold name.
    val rowBg = when {
        cursored && active -> Skerry.colors.cyan20
        cursored -> Skerry.colors.overlayMed
        isSelected -> Skerry.colors.cyan06
        else -> Color.Transparent
    }
    Row(
        Modifier
            .fillMaxWidth()
            // One node per row. The pane around the listing is clickable and therefore merges
            // everything under it, which without this leaves the whole directory as a single node —
            // one unreadable run of names, sizes and dates with no row boundaries in it.
            //
            // No contentDescription on purpose: the merged text is name · size · date · mode in the
            // order the columns are drawn, which is what a listing should read like. A description
            // would replace all of it with the name alone.
            //
            // The click action is the row's own: opening is a double click parsed by hand below, and
            // a hand-rolled gesture publishes nothing, so without this the row is inert to anything
            // that is not a mouse. Marking is what `selected` reports.
            .semantics(mergeDescendants = true) {
                selected = isSelected
                onClick { currentDouble(); true }
            }
            .background(rowBg)
            .listingRowHairline()
            // LMB: our own tap parsing in one loop — more reliable than detectTapGestures (which lost
            // double clicks to slop/timeouts). Each LMB press instantly places the cursor (currentPress);
            // two presses closer than DOUBLE_CLICK_MS are a double click (enter directory). Time comes
            // from the event itself (uptimeMillis) — deterministic. RMB is skipped (rubber-band below handles it).
            .pointerInput(Unit) {
                var lastDownMs = NO_PRESS
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent()
                        if (e.type != PointerEventType.Press || e.buttons.isSecondaryPressed) continue
                        val t = e.changes.first().uptimeMillis
                        currentPress()
                        if (t - lastDownMs <= DOUBLE_CLICK_MS) {
                            currentDouble()
                            lastDownMs = NO_PRESS // reset so a triple click doesn't give a second enter
                        } else {
                            lastDownMs = t
                        }
                    }
                }
            }
            // RMB (mc): a press paints the row (toggle by sign), dragging down/up paints the range with
            // the same sign — rubber-band. It runs after the tap detector (inner) and consumes the right
            // button in the Main pass earlier — so detectTapGestures (requireUnconsumed) ignores it, while
            // LMB is untouched (no consume) and reaches the tap detector.
            .then(
                if (rubberBand != null) {
                    // Key — anchor+listing (stable during a drag: a selection change doesn't touch them,
                    // so the gesture isn't restarted mid rubber-band).
                    Modifier.pointerInput(rubberBand.entry, rubberBand.entries) {
                        awaitPointerEventScope {
                            while (true) {
                                val press = awaitPointerEvent()
                                if (press.type != PointerEventType.Press) continue
                                if (!press.buttons.isSecondaryPressed) continue
                                with(rubberBand) { dragSelect(press) }
                            }
                        }
                    }
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        FileRowIcon(icon, iconColor)
        FileRowName(name, isSelected, directory, mono, Modifier.weight(1f))
        FileRowColumnCells(columns, mono)
    }
}

/**
 * Name cell of a listing row. A marked row and a directory both read by colour — the 16sp icon is
 * too small to sort a long listing by eye — and a marked one is bold on top of that. One line
 * always: a wrapped name would grow the row and drag its own columns off the captions.
 */
@Composable
internal fun FileRowName(
    name: String,
    selected: Boolean,
    directory: Boolean,
    mono: FontFamily,
    modifier: Modifier = Modifier,
) {
    Txt(
        name,
        color = when {
            name == ".." -> Skerry.colors.dim
            selected || directory -> Skerry.colors.cyanBright
            else -> Skerry.colors.text
        },
        size = 12.sp,
        font = mono,
        weight = if (selected) FontWeight.Bold else FontWeight.Normal,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/**
 * Leading icon slot of a listing row. [ColumnHeaderRow] indents its NAME caption by the same width,
 * so the caption stands over the names rather than over the icons.
 */
@Composable
internal fun FileRowIcon(icon: String, tint: Color) {
    Box(Modifier.width(ICON_SLOT_WIDTH - 10.dp), contentAlignment = Alignment.Center) {
        Sym(icon, size = 16.sp, color = tint)
    }
}

/**
 * Full-bleed hairline under a listing row: the listing reads as a table, and rounded rows over a
 * table grid read as cards instead.
 */
@Composable
internal fun Modifier.listingRowHairline(): Modifier {
    val separator = ROW_SEPARATOR_WIDTH
    val separatorColor = Skerry.colors.line
    return drawBehind {
        val y = size.height - separator.toPx() / 2
        drawLine(separatorColor, Offset(0f, y), Offset(size.width, y), separator.toPx())
    }
}

/**
 * The right-side cells of a listing row, in the same fixed slots as [ColumnHeaderRow]'s captions.
 * Shared so the static design render lines up under the same captions as the live listing.
 */
@Composable
internal fun FileRowColumnCells(columns: FileRowColumns, mono: FontFamily) {
    columns.size?.let { Txt(it, color = Skerry.colors.faint, size = 11.sp, font = mono, maxLines = 1, align = TextAlign.End, modifier = Modifier.width(SIZE_COLUMN_WIDTH)) }
    columns.modified?.let { Txt(it, color = Skerry.colors.faint, size = 11.sp, font = mono, maxLines = 1, modifier = Modifier.width(MODIFIED_COLUMN_WIDTH)) }
    columns.permissions?.let { Txt(it, color = Skerry.colors.faint, size = 11.sp, font = mono, maxLines = 1, modifier = Modifier.width(PERMISSIONS_COLUMN_WIDTH)) }
}
