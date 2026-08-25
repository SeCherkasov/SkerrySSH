package app.skerry.ui.sftp

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.files.FKeyDef
import app.skerry.ui.files.FilePaneController
import app.skerry.ui.files.FilePaneState
import app.skerry.ui.files.PathJumpField
import app.skerry.ui.files.fileBrowserFailureText
import app.skerry.ui.files.fileDisplayPath
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.shell_tip_close
import app.skerry.ui.generated.resources.ftail_fkey_copy
import app.skerry.ui.generated.resources.ftail_fkey_delete
import app.skerry.ui.generated.resources.ftail_fkey_edit
import app.skerry.ui.generated.resources.ftail_fkey_mkdir
import app.skerry.ui.generated.resources.ftail_fkey_move
import app.skerry.ui.generated.resources.ftail_fkey_quit
import app.skerry.ui.generated.resources.ftail_fkey_refresh
import app.skerry.ui.generated.resources.ftail_fkey_rename
import app.skerry.ui.generated.resources.ftail_fkey_view
import app.skerry.ui.generated.resources.sftp_filter_hint
import app.skerry.ui.generated.resources.sftp_error
import app.skerry.ui.generated.resources.sftp_loading
import org.jetbrains.compose.resources.stringResource
import androidx.compose.runtime.DisposableEffect
import app.skerry.ui.design.HLine
import app.skerry.ui.design.IconBtn
import app.skerry.ui.design.StatusAnnouncer
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.design.fieldFocus
import app.skerry.ui.design.fieldName
import app.skerry.ui.design.rememberSeededDraft
import app.skerry.ui.theme.Skerry
import app.skerry.ui.design.Badge

/**
 * Target of the active pane's batch F-operations: if nothing is marked — select the cursored row (mc
 * copies/moves/deletes the cursored item when there's no selection). On ".."/empty — no-op.
 */
internal fun ensureOperandSelection(pane: FilePaneController) {
    if (pane.selection.isEmpty()) pane.cursoredItem()?.let { pane.selectOnly(it) }
}

/** Nothing for a batch F-operation to act on: no marks and no cursored row to fall back on. */
internal fun FilePaneController.hasNoOperand(): Boolean = selection.isEmpty() && cursoredItem() == null

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
 * One live pane over [FilePaneController]: header (side icon + path + [badge] naming the side) and
 * the listing. No toolbar — up-navigation is the ".." row, file operations go through the bottom
 * F-key bar; selection is left-click (toggle) and rubber-band with held right-click. [badgeAccent]
 * marks the remote side, which carries the host's name rather than a fixed word.
 */
@Composable
internal fun LivePane(
    pane: FilePaneController,
    icon: String,
    iconColor: Color,
    badge: String,
    badgeAccent: Boolean,
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
            Modifier.fillMaxWidth().background(Skerry.colors.surface).padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Sym(icon, size = 16.sp, color = if (active) iconColor else Skerry.colors.faint)
            PathField(
                pane = pane,
                mono = mono,
                onActivate = onActivate,
                onEditingPath = onEditingPath,
                restoreFocus = restoreFocus,
                modifier = Modifier.weight(1f),
            )
            Badge(
                badge,
                bg = if (badgeAccent) Skerry.colors.cyan14 else Skerry.colors.overlayMed,
                fg = if (badgeAccent) Skerry.colors.cyanBright else Skerry.colors.dim,
                radius = 6,
                size = 10.sp,
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
        // The listing and the failure notice are two branches of the same `when`, so the switch
        // between them is an insertion, not a change a screen reader is told about (WCAG 4.1.3).
        // The announcer sits above it, outlives the switch and carries the reason itself; every
        // other state says nothing, so navigation churn stays silent.
        StatusAnnouncer((pane.state as? FilePaneState.Error)?.let { fileBrowserFailureText(it.failure) } ?: "")
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
    val focus = remember { FocusRequester() }
    val draft = rememberSeededDraft(text, pane, pane.path)
    val clearAndClose = {
        pane.setNameFilter("")
        onClose()
        restoreFocus()
    }
    // Each Ctrl+F press (tick increment) refocuses the field, including when the row is already open.
    LaunchedEffect(focusTick) { if (focusTick > 0) focus.requestFocus() }
    // The row can leave composition while focused (Esc path, listing reload) — release the standdown.
    DisposableEffect(Unit) { onDispose { onEditing(false) } }
    Row(
        Modifier.fillMaxWidth().background(Skerry.colors.panel).padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Sym("filter_alt", size = 14.sp, color = Skerry.colors.faint)
        BasicTextField(
            value = draft.textFieldValue(text),
            onValueChange = {
                draft.accept(it, text) { changed ->
                    text = changed
                    pane.setNameFilter(changed)
                }
            },
            singleLine = true,
            textStyle = TextStyle(color = Skerry.colors.textBright, fontSize = 11.5.sp, fontFamily = mono),
            cursorBrush = SolidColor(Skerry.colors.cyan),
            modifier = Modifier
                .weight(1f)
                .focusRequester(focus)
                .fieldFocus(draft)
                // The placeholder is the only caption this field ever shows, and it goes on the
                // first keystroke — same treatment as the phone's filter row.
                .fieldName(fallback = stringResource(Res.string.sftp_filter_hint))
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
        IconBtn("close", label = stringResource(Res.string.shell_tip_close), onClick = clearAndClose, box = 20, icon = 13.sp)
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
            // Drawn only — the editing branch below keeps the real path, which is what is submitted.
            fileDisplayPath(pane.path),
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
