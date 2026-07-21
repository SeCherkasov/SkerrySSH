package app.skerry.ui.files

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.design.D
import app.skerry.ui.design.HLine
import app.skerry.ui.design.IconBtn
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.ftail_fkey_edit
import app.skerry.ui.generated.resources.ftail_fkey_quit
import app.skerry.ui.generated.resources.ftail_fkey_save
import app.skerry.ui.generated.resources.ftail_fkey_search
import app.skerry.ui.generated.resources.sftp_edit_conflict_body
import app.skerry.ui.generated.resources.sftp_edit_conflict_q
import app.skerry.ui.generated.resources.sftp_edit_discard
import app.skerry.ui.generated.resources.sftp_edit_discard_body
import app.skerry.ui.generated.resources.sftp_edit_discard_q
import app.skerry.ui.generated.resources.sftp_edit_find
import app.skerry.ui.generated.resources.sftp_edit_modified
import app.skerry.ui.generated.resources.sftp_edit_not_found
import app.skerry.ui.generated.resources.sftp_edit_readonly
import app.skerry.ui.generated.resources.sftp_edit_saving
import app.skerry.ui.generated.resources.sftp_edit_title
import app.skerry.ui.generated.resources.sftp_edit_title_view
import app.skerry.ui.generated.resources.sftp_loading
import app.skerry.ui.generated.resources.sftp_overwrite
import app.skerry.ui.nav.PlatformBackHandler
import app.skerry.ui.sftp.ConfirmDangerDialog
import org.jetbrains.compose.resources.stringResource

/**
 * Built-in file viewer/editor (F3/F4). It takes over the file panel's own screen rather than opening
 * a window of its own: same chrome, same bottom key bar, only the keys change to the editor's
 * (F2 Save, F4 Edit, F7 Search, F10 Quit) — mc's arrangement, and the one the panel already teaches.
 *
 * Closing with unsaved changes asks first, as does an overwrite conflict raised by the controller;
 * those two stay modal dialogs (the app's convention for a yes/no that must not be missed).
 * [showKeyBar] is off on touch, where the header's buttons stand in for the function keys.
 * [onClose] is called once the editor is actually free to go.
 */
@Composable
fun FileEditorScreen(
    controller: FileEditController,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    showKeyBar: Boolean = true,
) {
    val mono = LocalFonts.current.mono
    var confirmDiscard by remember(controller) { mutableStateOf(false) }
    var searching by remember(controller) { mutableStateOf(false) }
    var query by remember(controller) { mutableStateOf("") }
    var notFound by remember(controller) { mutableStateOf(false) }
    // Caret and selection live here, not in the controller (which owns only the content): Tab needs
    // the caret to insert a tab, and search needs it to know where to continue from.
    var value by remember(controller) { mutableStateOf(TextFieldValue()) }

    val requestClose = { if (controller.dirty) confirmDiscard = true else onClose() }
    val findNext = {
        val hit = findNextMatch(value.text, query, value.selection.max)
        notFound = hit == null
        if (hit != null) value = value.copy(selection = TextRange(hit.first, hit.last + 1))
    }
    val onKey: (Int) -> Unit = { n ->
        when (n) {
            2 -> controller.save()
            4 -> controller.enableEditing()
            7 -> if (searching) findNext() else { searching = true; notFound = false }
            10 -> requestClose()
        }
    }

    // Android's system back closes the editor, not the whole file screen behind it — and goes
    // through the same unsaved-changes confirmation F10/Esc do. Disabled while a dialog of ours is
    // up: back should dismiss that first (handlers are LIFO, the dialog registers its own).
    PlatformBackHandler(enabled = !confirmDiscard && !controller.conflict) {
        if (searching) searching = false else requestClose()
    }

    val focus = remember(controller) { FocusRequester() }
    val overlaid = searching || confirmDiscard || controller.conflict
    val ready = controller.state is FileEditState.Ready
    // The editor owns the keyboard from the moment it opens, and takes it back when a dialog or the
    // search bar above it closes — otherwise F2/F10 would fall through to the panel underneath.
    LaunchedEffect(controller, ready, overlaid) {
        if (!overlaid && !ready) runCatching { focus.requestFocus() }
    }

    Column(
        modifier
            .background(D.bg)
            .imePadding()
            .focusRequester(focus)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when {
                    event.key == Key.F2 -> { onKey(2); true }
                    event.key == Key.F4 -> { onKey(4); true }
                    event.key == Key.F7 -> { onKey(7); true }
                    event.key == Key.F10 -> { onKey(10); true }
                    event.key == Key.Escape -> { requestClose(); true }
                    // Ctrl/⌘+S next to mc's F2: the chord every other editor has trained people on.
                    (event.isCtrlPressed || event.isMetaPressed) && event.key == Key.S -> {
                        controller.save()
                        true
                    }
                    else -> false
                }
            }
            .focusable(),
    ) {
        EditorHeader(controller, mono, showButtons = !showKeyBar, onSave = { controller.save() }, onClose = requestClose)
        HLine()
        controller.saveFailure?.let { failure ->
            EditorStrip("error", fileEditFailureText(failure), D.sunset)
            HLine()
        }
        if (searching) {
            EditorSearchBar(
                query = query,
                notFound = notFound,
                mono = mono,
                onQueryChange = { query = it; notFound = false },
                onSubmit = findNext,
                onDismiss = { searching = false; notFound = false },
            )
            HLine()
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (val st = controller.state) {
                FileEditState.Loading -> EditorNotice("sync", stringResource(Res.string.sftp_loading), D.faint)
                is FileEditState.Failed -> EditorNotice("error", fileEditFailureText(st.failure), D.sunset)
                is FileEditState.Ready -> EditorBuffer(
                    controller = controller,
                    state = st,
                    mono = mono,
                    value = value,
                    onValueChange = { value = it },
                    focusEditor = !overlaid,
                )
            }
        }
        if (showKeyBar) {
            HLine()
            FKeyBar(editorKeys(controller), onKey, mono)
        }
    }

    if (confirmDiscard) {
        ConfirmDangerDialog(
            title = stringResource(Res.string.sftp_edit_discard_q),
            body = stringResource(Res.string.sftp_edit_discard_body, controller.name),
            confirmLabel = stringResource(Res.string.sftp_edit_discard),
            onConfirm = { confirmDiscard = false; onClose() },
            onDismiss = { confirmDiscard = false },
        )
    }

    if (controller.conflict) {
        ConfirmDangerDialog(
            title = stringResource(Res.string.sftp_edit_conflict_q),
            body = stringResource(Res.string.sftp_edit_conflict_body, controller.name),
            confirmLabel = stringResource(Res.string.sftp_overwrite),
            onConfirm = controller::confirmOverwrite,
            onDismiss = controller::dismissConflict,
        )
    }
}

/**
 * The editor's own key legend, replacing the panel's while it is open. Save is greyed out with
 * nothing to write; in view mode F4 turns the buffer editable (mc's F3 → F4 path) and F2 stays dead
 * until it does.
 */
private fun editorKeys(controller: FileEditController): List<FKeyDef> = listOf(
    FKeyDef(2, Res.string.ftail_fkey_save, enabled = !controller.readOnly && controller.dirty && !controller.saving),
    FKeyDef(4, Res.string.ftail_fkey_edit, enabled = controller.readOnly),
    FKeyDef(7, Res.string.ftail_fkey_search, enabled = controller.state is FileEditState.Ready),
    FKeyDef(10, Res.string.ftail_fkey_quit),
)

/**
 * Editor header: mode icon, file name, path, and the read-only/modified/saving state. On touch
 * ([showButtons]) it also carries Save and Close, which the function keys cover elsewhere.
 */
@Composable
private fun EditorHeader(
    controller: FileEditController,
    mono: FontFamily,
    showButtons: Boolean,
    onSave: () -> Unit,
    onClose: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().background(D.surface2).padding(start = 16.dp, end = 6.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Sym(if (controller.readOnly) "visibility" else "edit_note", size = 18.sp, color = D.cyanBright)
        Txt(
            if (controller.readOnly) stringResource(Res.string.sftp_edit_title_view) else stringResource(Res.string.sftp_edit_title),
            color = D.text,
            size = 13.sp,
            weight = FontWeight.SemiBold,
        )
        Txt(controller.name, color = D.textBright, size = 12.sp, font = mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Txt(
            controller.path,
            color = D.faint,
            size = 11.sp,
            font = mono,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        when {
            controller.saving -> Txt(stringResource(Res.string.sftp_edit_saving), color = D.cyan, size = 11.sp)
            controller.readOnly -> Txt(stringResource(Res.string.sftp_edit_readonly), color = D.faint, size = 11.sp)
            controller.dirty -> Txt(stringResource(Res.string.sftp_edit_modified), color = D.sunset, size = 11.sp)
        }
        if (showButtons) {
            if (!controller.readOnly) {
                IconBtn(
                    "save",
                    onClick = onSave,
                    box = 26,
                    icon = 16.sp,
                    tint = if (controller.dirty && !controller.saving) D.cyanBright else D.faint,
                )
            }
            IconBtn("close", onClick = onClose, box = 26, icon = 16.sp)
        }
    }
}

/**
 * The buffer: a monospace field that scrolls itself, so the caret stays in view while typing.
 * [focusEditor] hands the keyboard over while nothing is layered on top (search bar, a dialog).
 */
@Composable
private fun EditorBuffer(
    controller: FileEditController,
    state: FileEditState.Ready,
    mono: FontFamily,
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    focusEditor: Boolean,
) {
    val focus = remember(controller) { FocusRequester() }
    // The controller can replace the content on its own (initial load, an edit rejected while a
    // conflict is pending): adopt it, but never on the echo of our own keystroke.
    LaunchedEffect(state.text) {
        // Caret at the top, not at the end: a file opens at its beginning, the way every editor and
        // pager on the platform opens one.
        if (value.text != state.text) onValueChange(TextFieldValue(state.text, TextRange.Zero))
    }
    LaunchedEffect(controller, focusEditor) { if (focusEditor) runCatching { focus.requestFocus() } }
    val editable = !controller.readOnly && !controller.saving
    BasicTextField(
        value = value,
        onValueChange = { next ->
            onValueChange(next)
            controller.edit(next.text)
        },
        readOnly = !editable,
        textStyle = TextStyle(color = D.textBright, fontSize = 12.5.sp, fontFamily = mono),
        cursorBrush = SolidColor(D.cyan),
        modifier = Modifier
            .fillMaxSize()
            .padding(14.dp)
            .focusRequester(focus)
            .onPreviewKeyEvent { event ->
                // Tab types a tab (this is a file editor, and config files are full of them);
                // without this Compose would move focus out of the buffer instead.
                if (event.type != KeyEventType.KeyDown || event.key != Key.Tab || !editable) {
                    return@onPreviewKeyEvent false
                }
                val selection = value.selection
                val next = value.text.replaceRange(selection.min, selection.max, "\t")
                onValueChange(TextFieldValue(next, TextRange(selection.min + 1)))
                controller.edit(next)
                true
            },
    )
}

/**
 * F7 search bar: type and press Enter (or F7 again) to jump to the next match, Esc to close. The hit
 * is selected in the buffer rather than highlighted separately — the field scrolls to its own
 * selection, so the match lands in view.
 */
@Composable
private fun EditorSearchBar(
    query: String,
    notFound: Boolean,
    mono: FontFamily,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Row(
        Modifier.fillMaxWidth().background(D.panel).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Sym("search", size = 15.sp, color = D.cyanBright)
        Txt(stringResource(Res.string.sftp_edit_find), color = D.faint, size = 11.5.sp)
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = TextStyle(color = D.textBright, fontSize = 12.sp, fontFamily = mono),
            cursorBrush = SolidColor(D.cyan),
            modifier = Modifier
                .weight(1f)
                .focusRequester(focus)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.Enter, Key.NumPadEnter, Key.F7 -> { onSubmit(); true }
                        Key.Escape -> { onDismiss(); true }
                        else -> false
                    }
                },
        )
        if (notFound) Txt(stringResource(Res.string.sftp_edit_not_found), color = D.sunset, size = 11.sp)
    }
}

/** One-line notice strip under the header (a failed save keeps the buffer, so it isn't a full screen). */
@Composable
private fun EditorStrip(icon: String, text: String, color: Color) {
    Row(
        Modifier.fillMaxWidth().background(D.surface2).padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Sym(icon, size = 15.sp, color = color)
        Txt(text, color = color, size = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Centered notice in the buffer area (loading / failed to open). */
@Composable
private fun EditorNotice(icon: String, text: String, color: Color) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Sym(icon, size = 26.sp, color = color)
            Txt(text, color = D.text, size = 12.5.sp)
        }
    }
}

/**
 * Next occurrence of [query] in [text] at or after [from], wrapping around to the start (so repeated
 * F7 cycles through the file). Case-insensitive; a blank query never matches.
 */
internal fun findNextMatch(text: String, query: String, from: Int): IntRange? {
    if (query.isEmpty()) return null
    val start = from.coerceIn(0, text.length)
    val ahead = text.indexOf(query, start, ignoreCase = true)
    val at = if (ahead >= 0) ahead else text.indexOf(query, 0, ignoreCase = true)
    return if (at < 0) null else at until (at + query.length)
}
