package app.skerry.ui.sftp

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.files.FileItem
import app.skerry.shared.files.FileItemType
import app.skerry.ui.files.FKeyDef
import app.skerry.ui.files.FilePaneController
import app.skerry.ui.files.FilePaneState
import app.skerry.ui.files.PathJumpField
import app.skerry.ui.files.fileBrowserFailureText
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.ftail_fkey_copy
import app.skerry.ui.generated.resources.ftail_fkey_delete
import app.skerry.ui.generated.resources.ftail_fkey_edit
import app.skerry.ui.generated.resources.ftail_fkey_mkdir
import app.skerry.ui.generated.resources.ftail_fkey_move
import app.skerry.ui.generated.resources.ftail_fkey_quit
import app.skerry.ui.generated.resources.ftail_fkey_refresh
import app.skerry.ui.generated.resources.ftail_fkey_rename
import app.skerry.ui.generated.resources.ftail_fkey_view
import app.skerry.ui.generated.resources.sftp_col_modified
import app.skerry.ui.generated.resources.sftp_col_permissions
import app.skerry.ui.generated.resources.sftp_columns
import app.skerry.ui.generated.resources.sftp_filter_hint
import app.skerry.ui.generated.resources.sftp_error
import app.skerry.ui.generated.resources.sftp_loading
import app.skerry.ui.generated.resources.sftp_new_folder
import app.skerry.ui.generated.resources.sftp_subtitle_host
import app.skerry.ui.generated.resources.sftp_title
import app.skerry.ui.generated.resources.sftp_upload
import app.skerry.ui.sftp.fileDateText
import app.skerry.ui.sftp.humanSize
import app.skerry.ui.sftp.permissionsText
import org.jetbrains.compose.resources.stringResource
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.text.style.TextAlign
import app.skerry.ui.design.AnchoredDropdown
import app.skerry.ui.design.GhostButton
import app.skerry.ui.design.HLine
import app.skerry.ui.design.IconBtn
import app.skerry.ui.design.labelUppercase
import app.skerry.ui.app.LocalSftpPrefs
import app.skerry.ui.app.SftpPrefs
import app.skerry.ui.design.Sym
import app.skerry.ui.design.ToggleRow
import app.skerry.ui.design.Txt
import app.skerry.ui.theme.Skerry

/** Double-click threshold for a row (ms between two LMB presses → enter directory). */
private const val DOUBLE_CLICK_MS = 350L

/**
 * The two-pane screen's header: "File transfer" + the session's subtitle on the left, Upload and
 * New folder on the right. [onUpload] transfers the local pane's selection, or falls back to the
 * native picker; [onNewFolder] creates a directory in the active pane.
 */
@Composable
internal fun LivePaneHeader(
    subtitle: String,
    mono: FontFamily,
    onUpload: () -> Unit,
    onNewFolder: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().background(Skerry.colors.surface2).padding(horizontal = 18.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Sym("drive_file_move", size = 18.sp, color = Skerry.colors.cyanBright)
            Txt(stringResource(Res.string.sftp_title), color = Skerry.colors.text, size = 13.sp, weight = FontWeight.SemiBold)
            Txt(stringResource(Res.string.sftp_subtitle_host, subtitle), color = Skerry.colors.faint, size = 11.5.sp, font = mono)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            GhostButton(stringResource(Res.string.sftp_upload), onClick = onUpload, icon = "upload")
            GhostButton(stringResource(Res.string.sftp_new_folder), onClick = onNewFolder, icon = "create_new_folder")
            ColumnsMenu(LocalSftpPrefs.current)
        }
    }
}

/**
 * Listing column visibility popup ("view_column" in the header): toggles for the modified-date and
 * permissions columns. One setting for both panes (like show-hidden), persisted via [SftpPrefs].
 */
@Composable
private fun ColumnsMenu(prefs: SftpPrefs) {
    var open by remember { mutableStateOf(false) }
    AnchoredDropdown(
        expanded = open,
        onDismiss = { open = false },
        trigger = { IconBtn("view_column", onClick = { open = !open }, box = 26, icon = 16.sp) },
    ) { _ ->
        Column(
            Modifier
                .width(220.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Skerry.colors.surface2)
                .border(1.dp, Skerry.colors.lineStrong, RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Txt(
                labelUppercase(stringResource(Res.string.sftp_columns)),
                color = Skerry.colors.faint,
                size = 10.sp,
                weight = FontWeight.SemiBold,
                letterSpacing = 0.5.sp,
            )
            ToggleRow(stringResource(Res.string.sftp_col_modified), prefs.showModified, onToggle = { prefs.setShowModified(!prefs.showModified) })
            ToggleRow(stringResource(Res.string.sftp_col_permissions), prefs.showPermissions, onToggle = { prefs.setShowPermissions(!prefs.showPermissions) })
        }
    }
}

/**
 * Target of the active pane's batch F-operations: if nothing is marked — select the cursored row (mc
 * copies/moves/deletes the cursored item when there's no selection). On ".."/empty — no-op.
 */
internal fun ensureOperandSelection(pane: FilePaneController) {
    if (pane.selection.isEmpty()) pane.cursoredItem()?.let { pane.selectOnly(it) }
}

/** The file panel's own key legend (mc/Total Commander order, adapted for Skerry). */
internal val PANEL_FKEYS = listOf(
    FKeyDef(2, Res.string.ftail_fkey_rename),
    FKeyDef(3, Res.string.ftail_fkey_view),
    FKeyDef(4, Res.string.ftail_fkey_edit),
    FKeyDef(5, Res.string.ftail_fkey_copy),
    FKeyDef(6, Res.string.ftail_fkey_move),
    FKeyDef(7, Res.string.ftail_fkey_mkdir),
    FKeyDef(8, Res.string.ftail_fkey_delete),
    FKeyDef(9, Res.string.ftail_fkey_refresh),
    FKeyDef(10, Res.string.ftail_fkey_quit),
)

/**
 * One live pane over [FilePaneController]: header [label] + path (no toolbar — up-navigation via the
 * ".." row) and the listing. File operations go through the bottom F-key bar; selection is left-click
 * (toggle) and rubber-band with held right-click.
 */
@Composable
internal fun LivePane(
    pane: FilePaneController,
    icon: String,
    iconColor: Color,
    label: String,
    mono: FontFamily,
    listState: LazyListState,
    active: Boolean,
    onActivate: () -> Unit,
    onEditingPath: (Boolean) -> Unit,
    onEditingFilter: (Boolean) -> Unit,
    filterTick: Int,
    onFilterClose: () -> Unit,
    restoreFocus: () -> Unit,
    modifier: Modifier,
) {
    // Keep the cursored row in view during keyboard navigation. The LazyColumn index is offset by the
    // synthetic ".." row (it precedes entries when we're not at root).
    LaunchedEffect(pane.cursor, pane.cursorOnParent, pane.state) {
        val st = pane.state as? FilePaneState.Loaded ?: return@LaunchedEffect
        val target = if (pane.cursorOnParent) {
            0 // the ".." row is always on top
        } else {
            val idx = st.entries.indexOfFirst { it.path == pane.cursor }
            if (idx < 0) return@LaunchedEffect
            idx + if (pane.path != "/") 1 else 0
        }
        val visible = listState.layoutInfo.visibleItemsInfo
        val first = visible.firstOrNull()?.index ?: 0
        val last = visible.lastOrNull()?.index ?: 0
        if (visible.isEmpty() || target < first || target > last) listState.scrollToItem(target)
    }

    // Activating a pane by clicking it must not flash a ripple over the whole pane — bare click, no
    // indication (the highlight is the header/cursor recolor, not a Material overlay).
    Column(
        modifier
            .fillMaxHeight()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onActivate),
    ) {
        Row(
            Modifier.fillMaxWidth().background(Skerry.colors.panel).padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Sym(icon, size = 16.sp, color = if (active) iconColor else Skerry.colors.faint)
            Txt(
                labelUppercase(label),
                color = if (active) Skerry.colors.cyanBright else Skerry.colors.faint,
                size = 11.sp,
                weight = FontWeight.SemiBold,
                letterSpacing = 0.5.sp,
            )
            PathField(
                pane = pane,
                mono = mono,
                onActivate = onActivate,
                onEditingPath = onEditingPath,
                restoreFocus = restoreFocus,
                modifier = Modifier.weight(1f),
            )
        }
        HLine()
        // Quick filter row (Ctrl+F): visible while opened or while a filter is applied, so the
        // active narrowing is never invisible state.
        if (filterTick > 0 || pane.nameFilter.isNotEmpty()) {
            PaneFilterField(
                pane = pane,
                mono = mono,
                focusTick = filterTick,
                onClose = onFilterClose,
                onEditing = onEditingFilter,
                restoreFocus = restoreFocus,
            )
            HLine()
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (val st = pane.state) {
                FilePaneState.Loading -> PaneNotice("sync", stringResource(Res.string.sftp_loading), null, Skerry.colors.faint)
                is FilePaneState.Error ->
                    PaneNotice("error", stringResource(Res.string.sftp_error), fileBrowserFailureText(st.failure), Skerry.colors.sunset)
                is FilePaneState.Loaded -> LivePaneList(
                    pane = pane,
                    entries = st.entries,
                    mono = mono,
                    listState = listState,
                    active = active,
                    onActivate = onActivate,
                )
            }
        }
    }
}

/**
 * Quick name filter row below a pane's header (Ctrl+F): live filtering as you type
 * ([FilePaneController.setNameFilter] — substring or `*`/`?` glob). Esc clears the filter and
 * closes the row; Enter keeps the filter and returns the keyboard to the listing. While the field
 * is focused, [onEditing] tells the screen to stand its key handler down. The local text state is
 * re-keyed on the pane's path: navigation clears the controller's filter, so the field follows.
 */
@Composable
private fun PaneFilterField(
    pane: FilePaneController,
    mono: FontFamily,
    focusTick: Int,
    onClose: () -> Unit,
    onEditing: (Boolean) -> Unit,
    restoreFocus: () -> Unit,
) {
    var text by remember(pane, pane.path) { mutableStateOf(pane.nameFilter) }
    val fieldFocus = remember { FocusRequester() }
    val clearAndClose = {
        pane.setNameFilter("")
        onClose()
        restoreFocus()
    }
    // Each Ctrl+F press (tick increment) refocuses the field, including when the row is already open.
    LaunchedEffect(focusTick) { if (focusTick > 0) fieldFocus.requestFocus() }
    // The row can leave composition while focused (Esc path, listing reload) — release the standdown.
    DisposableEffect(Unit) { onDispose { onEditing(false) } }
    Row(
        Modifier.fillMaxWidth().background(Skerry.colors.panel).padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Sym("filter_alt", size = 14.sp, color = Skerry.colors.faint)
        BasicTextField(
            value = text,
            onValueChange = {
                text = it
                pane.setNameFilter(it)
            },
            singleLine = true,
            textStyle = TextStyle(color = Skerry.colors.textBright, fontSize = 11.5.sp, fontFamily = mono),
            cursorBrush = SolidColor(Skerry.colors.cyan),
            modifier = Modifier
                .weight(1f)
                .focusRequester(fieldFocus)
                .onFocusChanged { onEditing(it.isFocused) }
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.Escape -> {
                            clearAndClose()
                            true
                        }
                        Key.Enter, Key.NumPadEnter -> {
                            restoreFocus()
                            true
                        }
                        else -> false
                    }
                },
            decorationBox = { inner ->
                Box {
                    if (text.isEmpty()) {
                        Txt(stringResource(Res.string.sftp_filter_hint), color = Skerry.colors.dim, size = 11.5.sp, font = mono)
                    }
                    inner()
                }
            },
        )
        IconBtn("close", onClick = clearAndClose, box = 20, icon = 13.sp)
    }
}

/**
 * Editable path bar in a pane header. Shows [pane].path as text; a click turns it into an input so a
 * known destination can be typed and jumped to (Enter → [FilePaneController.goToPath], Esc → cancel;
 * blurring the field also cancels). While editing, [onEditingPath] tells the screen to stand its key
 * handler down so arrows/Enter reach this field, not the listing; on close [restoreFocus] hands the
 * keyboard back to the panes.
 */
@Composable
private fun PathField(
    pane: FilePaneController,
    mono: FontFamily,
    onActivate: () -> Unit,
    onEditingPath: (Boolean) -> Unit,
    restoreFocus: () -> Unit,
    modifier: Modifier,
) {
    var editing by remember(pane) { mutableStateOf(false) }
    if (!editing) {
        Txt(
            pane.path,
            color = Skerry.colors.textBright,
            size = 11.5.sp,
            font = mono,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { onActivate(); editing = true },
            ),
        )
        return
    }
    // The editor itself (prefill/selection/focus/blur-cancel) is the shared [PathJumpField]; this
    // wrapper owns the display↔edit toggle and the screen's key-handler standdown protocol.
    val close = {
        editing = false
        onEditingPath(false)
        restoreFocus()
    }
    LaunchedEffect(Unit) { onEditingPath(true) }
    PathJumpField(
        path = pane.path,
        mono = mono,
        textSize = 11.5.sp,
        onCommit = { pane.goToPath(it); close() },
        onCancel = close,
        modifier = modifier,
    ) { inner ->
        Box(
            Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(Skerry.colors.bg)
                .border(1.dp, Skerry.colors.lineStrong, RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) { inner() }
    }
}

@Composable
private fun LivePaneList(
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
        LazyColumn(Modifier.fillMaxSize().padding(6.dp), state = listState) {
            if (pane.path != "/") {
                item(key = "..") {
                    LiveFileRow(
                        "arrow_upward", Skerry.colors.faint, "..",
                        columns = FileRowColumns(),
                        selected = false, cursored = pane.cursorOnParent, active = active, mono = mono,
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
                    icon = fileItemIcon(entry.type),
                    iconColor = if (entry.type == FileItemType.Directory) Skerry.colors.cyanBright else Skerry.colors.dim,
                    name = entry.name,
                    // An enabled column always occupies its fixed-width slot — an empty value
                    // renders as a blank slot, otherwise rows with a missing value (a directory's
                    // size, an unreported mtime) would let the remaining columns drift right and
                    // break the vertical alignment.
                    columns = FileRowColumns(
                        permissions = if (showPermissions) permissionsText(entry.type, entry.permissions).orEmpty() else null,
                        modified = if (showModified) fileDateText(entry.modifiedEpochSeconds) else null,
                        size = when {
                            entry.type == FileItemType.File -> humanSize(entry.size)
                            showPermissions || showModified -> ""
                            else -> null
                        },
                    ),
                    selected = entry.path in pane.selection,
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
 * Right-side row columns, each `null` when hidden/empty for this row: [permissions] (`ls -l`
 * style, remote pane only — the local okio source doesn't report mode bits), [modified] (mtime),
 * [size] (files only). Fixed widths keep the columns aligned across rows.
 */
internal data class FileRowColumns(
    val permissions: String? = null,
    val modified: String? = null,
    val size: String? = null,
)

private val PERMISSIONS_COLUMN_WIDTH = 76.dp

private val MODIFIED_COLUMN_WIDTH = 96.dp

private val SIZE_COLUMN_WIDTH = 62.dp

@Composable
internal fun LiveFileRow(
    icon: String,
    iconColor: Color,
    name: String,
    columns: FileRowColumns,
    selected: Boolean,
    cursored: Boolean,
    active: Boolean,
    mono: FontFamily,
    onPress: () -> Unit,
    onDoubleClick: () -> Unit,
    // Data for held-RMB rubber-band (mc): the anchor row, controller, list state for translating the
    // cursor position to a row, and the current listing. null for the synthetic ".." row.
    rubberBand: RowRubberBand?,
) {
    // Latest callbacks without restarting the gesture (pointerInput is keyed on Unit — it lives the row's whole life).
    val currentPress by rememberUpdatedState(onPress)
    val currentDouble by rememberUpdatedState(onDoubleClick)
    // Cursor (navigation position) and selection (marked files) are distinct in mc: the active pane's
    // cursor is a bright bar, the inactive one's a border; selection is a highlight + bold name.
    val rowBg = when {
        cursored && active -> Skerry.colors.cyan.copy(alpha = 0.22f)
        selected -> Skerry.colors.cyan06
        else -> Color.Transparent
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(5.dp))
            .background(rowBg)
            .then(if (cursored && !active) Modifier.border(1.dp, Skerry.colors.lineStrong, RoundedCornerShape(5.dp)) else Modifier)
            // LMB: our own tap parsing in one loop — more reliable than detectTapGestures (which lost
            // double clicks to slop/timeouts). Each LMB press instantly places the cursor (currentPress);
            // two presses closer than DOUBLE_CLICK_MS are a double click (enter directory). Time comes
            // from the event itself (uptimeMillis) — deterministic. RMB is skipped (rubber-band below handles it).
            .pointerInput(Unit) {
                var lastDownMs = 0L
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent()
                        if (e.type != PointerEventType.Press || e.buttons.isSecondaryPressed) continue
                        val t = e.changes.first().uptimeMillis
                        currentPress()
                        if (t - lastDownMs <= DOUBLE_CLICK_MS) {
                            currentDouble()
                            lastDownMs = 0L // reset so a triple click doesn't give a second enter
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
        Sym(icon, size = 17.sp, color = iconColor)
        Txt(
            name,
            color = when {
                name == ".." -> Skerry.colors.dim
                selected -> Skerry.colors.cyanBright
                else -> Skerry.colors.textBright
            },
            size = 12.sp,
            font = mono,
            weight = if (selected) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        columns.permissions?.let { Txt(it, color = Skerry.colors.faint, size = 11.sp, font = mono, maxLines = 1, modifier = Modifier.width(PERMISSIONS_COLUMN_WIDTH)) }
        columns.modified?.let { Txt(it, color = Skerry.colors.faint, size = 11.sp, font = mono, maxLines = 1, align = TextAlign.End, modifier = Modifier.width(MODIFIED_COLUMN_WIDTH)) }
        columns.size?.let { Txt(it, color = Skerry.colors.faint, size = 11.sp, maxLines = 1, align = TextAlign.End, modifier = Modifier.width(SIZE_COLUMN_WIDTH)) }
    }
}
