package app.skerry.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
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
import androidx.compose.ui.window.Dialog
import app.skerry.shared.files.FileItem
import app.skerry.shared.files.FileItemType
import app.skerry.ui.connection.ConnectionController
import app.skerry.ui.connection.ConnectionUiState
import app.skerry.ui.files.FilePaneController
import app.skerry.ui.files.FilePaneState
import app.skerry.ui.files.TransferCoordinator
import app.skerry.ui.files.TransferState
import app.skerry.ui.files.platformLocalBrowser
import app.skerry.ui.session.SessionView
import app.skerry.ui.sftp.TransferDirection
import app.skerry.ui.sftp.humanSize
import app.skerry.ui.sftp.pickUploadSource
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

private data class FileEntry(val icon: String, val iconColor: Color, val name: String, val meta: String, val selected: Boolean = false)

private val LOCAL_FILES = listOf(
    FileEntry("arrow_upward", D.faint, "..", ""),
    FileEntry("folder", D.cyanBright, "skerry-app", "Jun 21 09:14"),
    FileEntry("folder", D.cyanBright, "deploy-scripts", "Jun 18 22:40"),
    FileEntry("description", D.dim, "docker-compose.yml", "2.4 KB"),
    FileEntry("key", D.dim, "id_ed25519.pub", "96 B"),
    FileEntry("description", D.dim, "backup.tar.gz", "418 MB"),
)

private val REMOTE_FILES = listOf(
    FileEntry("arrow_upward", D.faint, "..", ""),
    FileEntry("folder", D.cyanBright, "html", "drwxr-xr-x"),
    FileEntry("folder", D.cyanBright, "releases", "drwxr-xr-x"),
    FileEntry("description", D.dim, "nginx.conf", "3.1 KB", selected = true),
    FileEntry("description", D.dim, "robots.txt", "112 B"),
    FileEntry("terminal", D.dim, "deploy.sh", "1.8 KB"),
)

/**
 * SFTP view (двухпанельный, Total-Commander): заголовок + Local-панель (локальная ФС) + Remote-панель
 * (хост) + панель действий передачи + полоса прогресса. При живой сессии ([LocalSessions]) обе панели
 * рендерятся поверх [TransferCoordinator] активной сессии — листинг/навигация/CRUD/передача реальны.
 * Без сессии (офскрин-рендер дизайна без бэкенда) показывается статичный мок макета.
 */
@Composable
fun SftpView() {
    val mono = LocalFonts.current.mono
    val sessions = LocalSessions.current
    val active = sessions?.active

    when {
        sessions == null -> MockSftpView(mono)
        active != null && active.controller.uiState is ConnectionUiState.Connected ->
            LiveSftpView(
                active.controller,
                active.subtitle,
                mono,
                onQuit = { sessions.setActiveView(SessionView.Terminal) },
            )
        else -> NoSessionSftpView(mono)
    }
}

/** Верхняя шапка view: иконка + «File transfer» + подзаголовок сессии. */
@Composable
private fun SftpTopBar(subtitle: String, mono: FontFamily) {
    Row(
        Modifier.fillMaxWidth().background(D.surface2).padding(horizontal = 18.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Sym("drive_file_move", size = 18.sp, color = D.cyanBright)
        Txt("File transfer", color = D.text, size = 13.sp, weight = FontWeight.SemiBold)
        Txt(subtitle, color = D.faint, size = 11.5.sp, font = mono)
    }
}

// Живой путь.

/**
 * Живой двухпанельный SFTP поверх кэшированного [TransferCoordinator] сессии. Координатор открывается
 * один раз ([ConnectionController.openTransferCoordinator]) и живёт на scope сессии — переключение
 * view путь/выделение панелей не сбрасывает, канал закрывает `disconnect()`. [subtitle] идёт меткой
 * удалённой панели.
 */
@Composable
private fun LiveSftpView(
    controller: ConnectionController,
    subtitle: String,
    mono: FontFamily,
    onQuit: () -> Unit,
) {
    var coord by remember(controller) { mutableStateOf<TransferCoordinator?>(null) }
    var openError by remember(controller) { mutableStateOf<String?>(null) }
    var creatingFolder by remember(controller) { mutableStateOf(false) }
    var active by remember(controller) { mutableStateOf(ActivePane.Local) }
    // Цели F8 Delete / F6 Move — активная панель в момент вызова (диалог читает её operands() для
    // текста/выполнения). null — диалог закрыт.
    var deleteTarget by remember(controller) { mutableStateOf<FilePaneController?>(null) }
    var moveTarget by remember(controller) { mutableStateOf<FilePaneController?>(null) }
    // Цель F5 Copy — активная панель в момент вызова (источник; назначение — противоположная панель).
    var copyTarget by remember(controller) { mutableStateOf<FilePaneController?>(null) }
    // Цель F2 Rename — пара (панель, строка под курсором) на момент нажатия. null — диалог закрыт.
    var renameTarget by remember(controller) { mutableStateOf<Pair<FilePaneController, FileItem>?>(null) }
    val localList = rememberLazyListState()
    val remoteList = rememberLazyListState()
    // Персистентная настройка показа скрытых (Ctrl+H) — единый источник правды для обеих панелей.
    val sftpPrefs = LocalSftpPrefs.current
    val focus = remember(controller) { FocusRequester() }
    // UI-scope только для показа нативного пикера файла (fallback Upload); сама передача живёт на
    // scope сессии внутри координатора и переживёт уход вью из композиции.
    val uiScope = rememberCoroutineScope()
    LaunchedEffect(controller) {
        openError = null
        try {
            coord = controller.openTransferCoordinator(platformLocalBrowser(), subtitle)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            openError = e.message ?: "Не удалось открыть SFTP"
        }
    }

    val c = coord
    // Как только координатор открыт — даём панелям фокус, чтобы стрелки/Tab работали без клика.
    LaunchedEffect(c) { if (c != null) focus.requestFocus() }
    // Применяем сохранённую настройку показа скрытых к обеим панелям: при открытии координатора и при
    // каждом переключении Ctrl+H (sftpPrefs.showHidden — ключ эффекта).
    LaunchedEffect(c, sftpPrefs.showHidden) {
        if (c != null) {
            c.local.setShowHidden(sftpPrefs.showHidden)
            c.remote.setShowHidden(sftpPrefs.showHidden)
        }
    }

    // Единая точка для F-клавиш: и нажатие клавиши, и клик по строке нижней панели идут сюда. Операции
    // работают над АКТИВНОЙ панелью, цель — её operands() (выделение либо строка под курсором, mc-стиль).
    // F3 View / F4 Edit пока заглушки (нужно чтение файла в контракте FileBrowser — следующий слайс).
    val fKey: (Int) -> Unit = remember(c) { fKey@{ n ->
        val coord = c ?: return@fKey
        val pane = if (active == ActivePane.Local) coord.local else coord.remote
        when (n) {
            2 -> pane.cursoredItem()?.let { renameTarget = pane to it } // Rename строки под курсором
            5 -> { // Copy: выделение/курсор активной панели в другую (upload/download), с подтверждением
                ensureOperandSelection(pane)
                if (pane.operands().isNotEmpty()) copyTarget = pane
            }
            6 -> { // Move: copy + delete источника, с подтверждением
                ensureOperandSelection(pane)
                if (pane.operands().isNotEmpty()) moveTarget = pane
            }
            7 -> creatingFolder = true // MkDir
            8 -> { // Delete активной панели, с подтверждением
                ensureOperandSelection(pane)
                if (pane.operands().isNotEmpty()) deleteTarget = pane
            }
            9 -> { coord.local.refresh(); coord.remote.refresh() } // Refresh обеих панелей
            10 -> onQuit() // Quit: назад в терминал этой вкладки
            else -> {} // F3 View / F4 Edit — следующий слайс
        }
    } }

    Column(
        Modifier
            .fillMaxSize()
            .background(D.bg)
            .focusRequester(focus)
            .onPreviewKeyEvent { event ->
                if (c == null || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                // Ctrl+H — показать/скрыть скрытые объекты (dotfiles); меняем персистентную настройку,
                // а её применение к обеим панелям делает LaunchedEffect ниже (единый источник правды).
                if (event.isCtrlPressed && event.key == Key.H) {
                    sftpPrefs.setShowHidden(!sftpPrefs.showHidden)
                    return@onPreviewKeyEvent true
                }
                val pane = if (active == ActivePane.Local) c.local else c.remote
                val listState = if (active == ActivePane.Local) localList else remoteList
                val page = (listState.layoutInfo.visibleItemsInfo.size - 1).coerceAtLeast(1)
                when (event.key) {
                    Key.DirectionUp -> pane.moveCursor(-1)
                    Key.DirectionDown -> pane.moveCursor(1)
                    Key.PageUp -> pane.moveCursor(-page)
                    Key.PageDown -> pane.moveCursor(page)
                    Key.MoveHome -> pane.cursorToFirst()
                    Key.MoveEnd -> pane.cursorToLast()
                    Key.Enter, Key.NumPadEnter -> pane.enterCursored()
                    Key.DirectionRight -> pane.cursoredItem()?.let(pane::open)
                    Key.DirectionLeft, Key.Backspace -> pane.goUp()
                    Key.Insert -> pane.markCursoredAndAdvance()
                    Key.Spacebar -> pane.markCursored()
                    Key.Escape -> pane.clearSelection()
                    Key.Tab -> active = if (active == ActivePane.Local) ActivePane.Remote else ActivePane.Local
                    Key.F2 -> fKey(2)
                    Key.F3 -> fKey(3)
                    Key.F4 -> fKey(4)
                    Key.F5 -> fKey(5)
                    Key.F6 -> fKey(6)
                    Key.F7 -> fKey(7)
                    Key.F8 -> fKey(8)
                    Key.F9 -> fKey(9)
                    Key.F10 -> fKey(10)
                    else -> return@onPreviewKeyEvent false
                }
                true
            }
            .focusable(),
    ) {
        // Шапка ровно по шаблону: слева «File transfer» + подзаголовок, справа Upload + New folder.
        Row(
            Modifier.fillMaxWidth().background(D.surface2).padding(horizontal = 18.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Sym("drive_file_move", size = 18.sp, color = D.cyanBright)
                Txt("File transfer", color = D.text, size = 13.sp, weight = FontWeight.SemiBold)
                Txt("$subtitle · SFTP", color = D.faint, size = 11.5.sp, font = mono)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Upload: есть выделение локальных файлов → передаём их; иначе fallback — нативный
                // диалог выбора файла → заливаем в текущий каталог remote-панели. New folder — в remote.
                GhostButton(
                    "Upload",
                    onClick = {
                        val coord = c
                        if (coord != null) {
                            if (coord.local.selection.isNotEmpty()) {
                                coord.uploadSelection()
                            } else {
                                uiScope.launch { pickUploadSource()?.let { coord.uploadSource(it) } }
                            }
                        }
                    },
                    icon = "upload",
                )
                GhostButton("New folder", onClick = { if (c != null) creatingFolder = true }, icon = "create_new_folder")
            }
        }
        HLine()
        when {
            openError != null -> Box(Modifier.weight(1f).fillMaxWidth()) {
                PaneNotice("error", "SFTP unavailable", openError, D.sunset)
            }
            c == null -> Box(Modifier.weight(1f).fillMaxWidth()) {
                PaneNotice("sync", "Opening SFTP…", null, D.faint)
            }
            else -> {
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    LivePane(
                        c.local, "computer", D.dim, "Local", mono,
                        listState = localList,
                        active = active == ActivePane.Local,
                        onActivate = { active = ActivePane.Local; focus.requestFocus() },
                        modifier = Modifier.weight(1f),
                    )
                    VLine(D.line)
                    LivePane(
                        c.remote, "dns", D.moss, "Remote", mono,
                        listState = remoteList,
                        active = active == ActivePane.Remote,
                        onActivate = { active = ActivePane.Remote; focus.requestFocus() },
                        modifier = Modifier.weight(1f),
                    )
                }
                LiveTransferStrip(c.transfer, mono, onDismiss = c::clearTransfer)
            }
        }
        HLine()
        FKeyBar(enabled = c != null, onKey = fKey, mono = mono)
    }

    if (creatingFolder && c != null) {
        // New folder создаётся в АКТИВНОЙ панели (F7/тулбар) — а не всегда в remote, иначе из локальной
        // панели папка «улетала» в remote и казалось, что мы провалились в чужой каталог.
        val target = if (active == ActivePane.Local) c.local else c.remote
        NameDialog(
            title = "New folder",
            confirmLabel = "Create",
            initial = "",
            existing = (target.state as? FilePaneState.Loaded)?.entries?.mapTo(mutableSetOf()) { it.name } ?: emptySet(),
            onConfirm = { target.mkdir(it); creatingFolder = false },
            onDismiss = { creatingFolder = false },
        )
    }

    // F8 Delete активной панели: подтверждение по operands() (выделение или строка под курсором).
    // Если цель внезапно опустела (фоновый refresh между нажатием и кадром) — закрываем через эффект,
    // а не записью стейта прямо в композиции.
    deleteTarget?.let { pane ->
        val items = pane.operands()
        if (items.isEmpty()) {
            LaunchedEffect(pane) { deleteTarget = null }
        } else {
            ConfirmDeleteItemsDialog(
                items = items,
                onConfirm = { pane.deleteSelected(); deleteTarget = null },
                onDismiss = { deleteTarget = null },
            )
        }
    }

    // F6 Move активной панели в другую: copy + delete источника, с подтверждением. Назначение —
    // текущий каталог противоположной панели.
    moveTarget?.let { pane ->
        val coord = c
        val items = pane.operands()
        if (coord == null || items.isEmpty()) {
            LaunchedEffect(pane) { moveTarget = null }
        } else {
            val fromLocal = pane === coord.local
            val destPath = if (fromLocal) coord.remote.path else coord.local.path
            ConfirmMoveDialog(
                items = items,
                destLabel = if (fromLocal) "Remote" else "Local",
                destPath = destPath,
                onConfirm = { coord.moveSelection(fromLocal); moveTarget = null },
                onDismiss = { moveTarget = null },
            )
        }
    }

    // F5 Copy активной панели в другую (upload/download), с подтверждением. Назначение — текущий
    // каталог противоположной панели.
    copyTarget?.let { pane ->
        val coord = c
        val items = pane.operands()
        if (coord == null || items.isEmpty()) {
            LaunchedEffect(pane) { copyTarget = null }
        } else {
            val fromLocal = pane === coord.local
            val destPath = if (fromLocal) coord.remote.path else coord.local.path
            ConfirmCopyDialog(
                items = items,
                destLabel = if (fromLocal) "Remote" else "Local",
                destPath = destPath,
                onConfirm = {
                    if (fromLocal) coord.uploadSelection() else coord.downloadSelection()
                    copyTarget = null
                },
                onDismiss = { copyTarget = null },
            )
        }
    }

    // F2 Rename строки под курсором активной панели (классика mc — клавиатурный путь, без меню).
    renameTarget?.let { (pane, item) ->
        NameDialog(
            title = "Rename",
            confirmLabel = "Rename",
            initial = item.name,
            existing = (pane.state as? FilePaneState.Loaded)?.entries?.mapTo(mutableSetOf()) { it.name } ?: emptySet(),
            onConfirm = { pane.rename(item, it); renameTarget = null },
            onDismiss = { renameTarget = null },
        )
    }
}

/**
 * Цель пакетных F-операций активной панели: если ничего не помечено — выделить строку под курсором
 * (mc копирует/двигает/удаляет объект под курсором, когда нет выделения). На «..»/пустом — no-op.
 */
private fun ensureOperandSelection(pane: FilePaneController) {
    if (pane.selection.isEmpty()) pane.cursoredItem()?.let { pane.selectOnly(it) }
}

/**
 * Раскладка нижней панели F-клавиш в стиле mc (классика, адаптированная под Skerry).
 * [done] = клавиша реально что-то делает; false — заглушка (слайс 3). Нерабочие помечены «*»
 * в панели, чтобы было видно, что подключено, а что ещё нет.
 */
private data class FKeyDef(val n: Int, val label: String, val done: Boolean)

private val FKEY_LABELS = listOf(
    FKeyDef(2, "Rename", done = true),
    FKeyDef(3, "View", done = false),
    FKeyDef(4, "Edit", done = false),
    FKeyDef(5, "Copy", done = true),
    FKeyDef(6, "Move", done = true),
    FKeyDef(7, "MkDir", done = true),
    FKeyDef(8, "Delete", done = true),
    FKeyDef(9, "Refresh", done = true),
    FKeyDef(10, "Quit", done = true),
)

/**
 * Нижняя панель горячих клавиш (mc/Total Commander): строка ячеек «F3 View … F10 Quit».
 * И клик по ячейке, и нажатие F-клавиши идут через общий [onKey]. [enabled] гасит панель,
 * пока SFTP-координатор не открыт.
 */
@Composable
private fun FKeyBar(enabled: Boolean, onKey: (Int) -> Unit, mono: FontFamily) {
    Row(
        Modifier.fillMaxWidth().background(D.surface2).padding(horizontal = 6.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        FKEY_LABELS.forEach { def ->
            FKeyCell(def, enabled, mono, onClick = { onKey(def.n) }, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun FKeyCell(
    def: FKeyDef,
    enabled: Boolean,
    mono: FontFamily,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    Row(
        modifier
            .clip(RoundedCornerShape(4.dp))
            .background(D.panel)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Txt("F${def.n}", color = if (enabled) D.cyanBright else D.faint, size = 10.5.sp, weight = FontWeight.SemiBold, font = mono)
        Txt(
            def.label,
            color = if (enabled) D.text else D.faint,
            size = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // «*» — пометка нерабочей заглушки (F3 View / F4 Edit — следующий слайс).
        if (!def.done) Txt("*", color = D.sunset, size = 11.sp, weight = FontWeight.SemiBold)
    }
}

/**
 * Одна живая панель поверх [FilePaneController]: шапка [label] + путь (ровно как в шаблоне, без
 * тулбара — навигация вверх строкой «..») и листинг. Файловые операции — через нижнюю панель F-клавиш,
 * выделение — ЛКМ (toggle) и rubber-band зажатой ПКМ.
 */
@Composable
private fun LivePane(
    pane: FilePaneController,
    icon: String,
    iconColor: Color,
    label: String,
    mono: FontFamily,
    listState: LazyListState,
    active: Boolean,
    onActivate: () -> Unit,
    modifier: Modifier,
) {
    // Держим курсорную строку в видимой области при навигации с клавиатуры. Индекс в LazyColumn
    // сдвинут на синтетическую строку «..» (она идёт перед entries, когда мы не в корне).
    LaunchedEffect(pane.cursor, pane.cursorOnParent, pane.state) {
        val st = pane.state as? FilePaneState.Loaded ?: return@LaunchedEffect
        val target = if (pane.cursorOnParent) {
            0 // строка «..» всегда сверху
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

    Column(modifier.fillMaxHeight().clickable(onClick = onActivate)) {
        Row(
            Modifier.fillMaxWidth().background(D.panel).padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Sym(icon, size = 16.sp, color = if (active) iconColor else D.faint)
            Txt(
                label.uppercase(),
                color = if (active) D.cyanBright else D.faint,
                size = 11.sp,
                weight = FontWeight.SemiBold,
                letterSpacing = 0.5.sp,
            )
            Txt(
                pane.path,
                color = D.textBright,
                size = 11.5.sp,
                font = mono,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        HLine()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (val st = pane.state) {
                FilePaneState.Loading -> PaneNotice("sync", "Loading…", null, D.faint)
                is FilePaneState.Error -> PaneNotice("error", "Error", st.message, D.sunset)
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

@Composable
private fun LivePaneList(
    pane: FilePaneController,
    entries: List<FileItem>,
    mono: FontFamily,
    listState: LazyListState,
    active: Boolean,
    onActivate: () -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize().padding(6.dp), state = listState) {
        if (pane.path != "/") {
            item(key = "..") {
                LiveFileRow(
                    "arrow_upward", D.faint, "..", "", selected = false, cursored = pane.cursorOnParent, active = active, mono,
                    // Одиночный клик только ставит курсор на «..»; вверх — двойным кликом (как вход в каталог).
                    onPress = { onActivate(); pane.setCursorOnParent() },
                    onDoubleClick = { onActivate(); pane.goUp() },
                    rubberBand = null, // строку «..» нельзя пометить — rubber-band на ней не нужен
                )
            }
        }
        items(entries, key = { it.path }) { entry ->
            // Одиночный клик (по нажатию): активировать панель и навести курсор — НЕ помечает и НЕ входит.
            // Вход в каталог — двойным кликом (open; для файла no-op). Выделение — ПКМ/Space/Insert.
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
                iconColor = if (entry.type == FileItemType.Directory) D.cyanBright else D.dim,
                name = entry.name,
                meta = if (entry.type == FileItemType.File) humanSize(entry.size) else "",
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

/**
 * Жест rubber-band строки (выделение зажатой ПКМ в стиле mc). Нажатие красит строку-[entry] (toggle
 * по её текущему состоянию), протяжка вниз/вверх красит весь диапазон в тот же знак. Курсор мыши,
 * захваченный строкой нажатия, переводим в координаты списка через смещение строки в [listState],
 * затем ищем строку под ним в [listState] и красим до неё. Скролл при протяжке у краёв не делаем
 * (правая кнопка список не прокручивает) — смещения стабильны на всё время жеста.
 */
private class RowRubberBand(
    val entry: FileItem,
    val pane: FilePaneController,
    val listState: LazyListState,
    val entries: List<FileItem>,
    val onActivate: () -> Unit,
) {
    // Member-extension на restricted-scope AwaitPointerEventScope — иначе awaitPointerEvent() звать нельзя.
    suspend fun AwaitPointerEventScope.dragSelect(press: PointerEvent) {
        onActivate() // красим в этой панели — делаем её активной (F-клавиши пойдут сюда)
        // Знак фиксируем по строке под нажатием: не помечена → красим, помечена → стираем.
        val select = entry.path !in pane.selection
        pane.rubberBandTo(entry, entry, select)
        press.changes.forEach { it.consume() }
        val anchorOffset = listState.layoutInfo.visibleItemsInfo
            .firstOrNull { it.key == entry.path }?.offset ?: 0
        while (true) {
            val drag = awaitPointerEvent()
            if (!drag.buttons.isSecondaryPressed) break // ПКМ отпущена — конец жеста
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
 * Строка живого листинга — визуально идентична шаблону (иконка + имя + размер, без ⋮). ЛКМ просто
 * ставит курсор (НЕ помечает и НЕ входит в каталог) — реагирует мгновенно по нажатию ([onPress]),
 * чтобы не было задержки распознавания двойного клика. Двойной клик — вход в каталог ([onDoubleClick]).
 * Выделение — ПКМ-нажатие/протяжка (rubber-band, [RowRubberBand]) либо Space/Insert. Контекстного
 * меню нет: действия идут через нижнюю панель F-клавиш.
 */
/** Какая из двух панелей активна (получает клавиатуру и курсорную подсветку). */
private enum class ActivePane { Local, Remote }

/** Порог двойного клика по строке (мс между двумя нажатиями ЛКМ → вход в каталог). */
private const val DOUBLE_CLICK_MS = 350L

@Composable
private fun LiveFileRow(
    icon: String,
    iconColor: Color,
    name: String,
    meta: String,
    selected: Boolean,
    cursored: Boolean,
    active: Boolean,
    mono: FontFamily,
    onPress: () -> Unit,
    onDoubleClick: () -> Unit,
    // Данные для rubber-band зажатой ПКМ (mc): строка-якорь, контроллер, состояние списка для
    // перевода позиции курсора в строку, и текущий листинг. null для синтетической строки «..».
    rubberBand: RowRubberBand?,
) {
    // Последние колбэки без перезапуска жеста (pointerInput ключуем на Unit — он живёт всю жизнь строки).
    val currentPress by rememberUpdatedState(onPress)
    val currentDouble by rememberUpdatedState(onDoubleClick)
    // Курсор (позиция навигации) и выделение (помеченные файлы) — разные сущности mc: курсор активной
    // панели — яркая полоса, неактивной — рамка; выделение — подсветка + жирное имя.
    val rowBg = when {
        cursored && active -> D.cyan.copy(alpha = 0.22f)
        selected -> D.cyan06
        else -> Color.Transparent
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(5.dp))
            .background(rowBg)
            .then(if (cursored && !active) Modifier.border(1.dp, D.lineStrong, RoundedCornerShape(5.dp)) else Modifier)
            // ЛКМ: свой разбор тапов в одном цикле — надёжнее detectTapGestures (тот терял двойной клик
            // из-за slop/тайм-аутов). Каждое нажатие ЛКМ мгновенно ставит курсор (currentPress); два
            // нажатия подряд ближе DOUBLE_CLICK_MS — это двойной клик (вход в каталог). Время берём из
            // самого события (uptimeMillis) — детерминированно. ПКМ пропускаем (её ведёт rubber-band ниже).
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
                            lastDownMs = 0L // сброс, чтобы тройной клик не дал второй вход
                        } else {
                            lastDownMs = t
                        }
                    }
                }
            }
            // ПКМ (mc): нажатие красит строку (toggle по знаку), протяжка вниз/вверх красит диапазон в
            // тот же знак — rubber-band. Идёт ПОСЛЕ детектора тапов (inner) и потребляет правую кнопку
            // в Main-проходе раньше — поэтому detectTapGestures (requireUnconsumed) её игнорирует, а ЛКМ
            // не трогает (без consume) и достаётся детектору тапов.
            .then(
                if (rubberBand != null) {
                    // Ключ — якорь+листинг (стабильны во время протяжки: смена selection их не трогает,
                    // поэтому жест не перезапускается прямо посреди rubber-band).
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
                name == ".." -> D.dim
                selected -> D.cyanBright
                else -> D.textBright
            },
            size = 12.sp,
            font = mono,
            weight = if (selected) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (meta.isNotEmpty()) Txt(meta, color = D.faint, size = 11.sp)
    }
}

/** Полоса прогресса передачи: активная (бар + счётчик), ошибка (с закрытием) или ничего при Idle. */
@Composable
private fun LiveTransferStrip(transfer: TransferState, mono: FontFamily, onDismiss: () -> Unit) {
    when (transfer) {
        TransferState.Idle -> Unit

        is TransferState.Active -> {
            HLine()
            Row(
                Modifier.fillMaxWidth().background(D.surface2).padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val up = transfer.direction == TransferDirection.Upload
                Sym(if (up) "upload" else "download", size = 16.sp, color = D.cyan)
                val counter = if (transfer.fileCount > 1) " (${transfer.fileIndex}/${transfer.fileCount})" else ""
                Txt(transfer.name + counter, color = D.textBright, size = 11.5.sp, font = mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val fraction = if (transfer.total > 0) transfer.transferred.toFloat() / transfer.total else 0f
                MeterBar(fraction, D.cyan, Modifier.weight(1f))
                val tail = if (transfer.total > 0) {
                    "${humanSize(transfer.transferred)} / ${humanSize(transfer.total)}"
                } else {
                    humanSize(transfer.transferred)
                }
                Txt(tail, color = D.dim, size = 11.sp, font = mono)
            }
        }

        is TransferState.Failed -> {
            HLine()
            Row(
                Modifier.fillMaxWidth().background(D.surface2).padding(start = 16.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Sym("error", size = 16.sp, color = D.sunset)
                Txt(
                    "${transfer.name}: ${transfer.message}",
                    color = D.sunset,
                    size = 11.5.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconBtn("close", onClick = onDismiss, box = 26, icon = 16.sp)
            }
        }
    }
}

// Диалоги.

/** Модальный ввод имени (New folder / Rename) в дизайн-стиле. Confirm активен только при валидном имени. */
@Composable
internal fun NameDialog(
    title: String,
    confirmLabel: String,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    existing: Set<String> = emptySet(),
) {
    // Ключ initial: при повторном показе под другой entry (rename без выхода из композиции) поле
    // должно сброситься на новое имя, а не сохранить прежнее.
    var name by remember(initial) { mutableStateOf(initial) }
    val trimmed = name.trim()
    // Конфликт имён ловим заранее (имя уже есть в каталоге) — иначе mkdir/rename упал бы в Error и
    // панель «прыгнула» бы; вместо этого показываем сообщение в диалоге и держим его открытым.
    // initial разрешён (rename в то же имя — no-op, не конфликт).
    val conflict = trimmed.isNotEmpty() && trimmed != initial && trimmed in existing
    // Отвергаем пустое имя, разделитель пути, «.»/«..» и управляющие символы (null-байт/перевод
    // строки) — последние ломают пути на POSIX-ФС/SFTP-сервере и вёрстку строки.
    val valid = trimmed.isNotEmpty() &&
        "/" !in trimmed &&
        trimmed != "." &&
        trimmed != ".." &&
        trimmed.none { it == '\u0000' || it == '\n' || it == '\r' }
    val mono = LocalFonts.current.mono
    val ok = valid && !conflict
    val submit = { if (ok) onConfirm(trimmed) }
    // Автофокус: поле должно быть готово к вводу сразу при открытии диалога, без клика.
    val fieldFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { fieldFocus.requestFocus() }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .width(340.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(D.surface)
                .border(1.dp, D.line, RoundedCornerShape(12.dp))
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Txt(title, color = D.text, size = 14.sp, weight = FontWeight.SemiBold)
            // Рамка — в decorationBox, чтобы клик по всей площади поля ставил каретку.
            BasicTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                textStyle = TextStyle(color = D.text, fontSize = 13.sp, fontFamily = mono),
                cursorBrush = SolidColor(D.cyan),
                // Enter подтверждает (если имя валидно), Esc закрывает — обработчик ДО focusable поля.
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(fieldFocus)
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.Enter, Key.NumPadEnter -> { submit(); true }
                            Key.Escape -> { onDismiss(); true }
                            else -> false
                        }
                    },
                decorationBox = { inner ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(7.dp))
                            .background(D.panel)
                            .border(1.dp, D.lineStrong, RoundedCornerShape(7.dp))
                            .padding(horizontal = 10.dp, vertical = 9.dp),
                    ) { inner() }
                },
            )
            if (conflict) Txt("«$trimmed» already exists", color = D.sunset, size = 11.5.sp)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GhostButton("Cancel", onClick = onDismiss)
                PrimaryButton(
                    confirmLabel,
                    onClick = submit,
                    bg = if (ok) D.cyan else D.whiteFaint,
                )
            }
        }
    }
}

/**
 * Подтверждение удаления пакета [items] (F8 над активной панелью): один объект — конкретное имя,
 * несколько — счётчик. Текст предупреждает о рекурсии, если в пакете есть каталог.
 */
@Composable
private fun ConfirmDeleteItemsDialog(items: List<FileItem>, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val single = items.singleOrNull()
    val hasDir = items.any { it.type == FileItemType.Directory }
    val title = when {
        single != null && single.type == FileItemType.Directory -> "Delete folder?"
        single != null -> "Delete file?"
        else -> "Delete ${items.size} items?"
    }
    val body = when {
        single != null && single.type == FileItemType.Directory ->
            "«${single.name}» and everything inside it will be removed permanently."
        single != null -> "«${single.name}» will be removed permanently."
        hasDir -> "${items.size} items (folders with their contents) will be removed permanently."
        else -> "${items.size} items will be removed permanently."
    }
    ConfirmDangerDialog(title, body, "Delete", onConfirm, onDismiss)
}

/**
 * Подтверждение копирования пакета [items] в каталог [destPath] панели [destLabel] (F5).
 */
@Composable
private fun ConfirmCopyDialog(
    items: List<FileItem>,
    destLabel: String,
    destPath: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val single = items.singleOrNull()
    val what = if (single != null) "«${single.name}»" else "${items.size} items"
    ConfirmDangerDialog(
        title = "Copy to $destLabel?",
        body = "$what → $destPath",
        confirmLabel = "Copy",
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        confirmBg = D.cyan,
        confirmFg = Color(0xFF0A1A26),
    )
}

/**
 * Подтверждение перемещения пакета [items] в каталог [destPath] панели [destLabel] (F6). Перемещение
 * между ФС = копирование + удаление источника, поэтому подтверждаем явно.
 */
@Composable
private fun ConfirmMoveDialog(
    items: List<FileItem>,
    destLabel: String,
    destPath: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val single = items.singleOrNull()
    val what = if (single != null) "«${single.name}»" else "${items.size} items"
    ConfirmDangerDialog(
        title = "Move to $destLabel?",
        body = "$what → $destPath",
        confirmLabel = "Move",
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        confirmBg = D.cyan,
        confirmFg = Color(0xFF0A1A26),
    )
}

/**
 * Общий каркас диалога подтверждения (заголовок + текст + Cancel/действие) в дизайн-стиле. Управляется
 * с клавиатуры (mc-стиль): по умолчанию фокус на действии — Enter сразу подтверждает (F8→Enter удаляет);
 * ←/→/Tab переключают между Cancel и действием, Esc отменяет. Фокусированная кнопка обведена рамкой.
 */
@Composable
private fun ConfirmDangerDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmBg: Color = D.sunset,
    confirmFg: Color = Color(0xFF0A1A26),
) {
    var focusConfirm by remember { mutableStateOf(true) }
    val dialogFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { dialogFocus.requestFocus() }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .width(340.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(D.surface)
                .border(1.dp, D.line, RoundedCornerShape(12.dp))
                .padding(18.dp)
                .focusRequester(dialogFocus)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.Enter, Key.NumPadEnter -> { if (focusConfirm) onConfirm() else onDismiss(); true }
                        Key.Escape -> { onDismiss(); true }
                        Key.DirectionLeft, Key.DirectionRight, Key.Tab -> { focusConfirm = !focusConfirm; true }
                        else -> false
                    }
                }
                .focusable(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Txt(title, color = D.text, size = 14.sp, weight = FontWeight.SemiBold)
            Txt(body, color = D.faint, size = 12.sp)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DialogButtonFocus(focused = !focusConfirm) { GhostButton("Cancel", onClick = onDismiss) }
                DialogButtonFocus(focused = focusConfirm) {
                    PrimaryButton(confirmLabel, onClick = onConfirm, bg = confirmBg, fg = confirmFg)
                }
            }
        }
    }
}

/** Обводка кнопки диалога рамкой, когда она в фокусе клавиатуры (←/→/Tab). */
@Composable
private fun DialogButtonFocus(focused: Boolean, content: @Composable () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(9.dp))
            .then(if (focused) Modifier.border(1.5.dp, D.cyanBright, RoundedCornerShape(9.dp)) else Modifier)
            .padding(1.5.dp),
    ) { content() }
}

/** Подтверждение удаления файла/каталога в дизайн-стиле. */
@Composable
internal fun ConfirmDeleteDialog(entry: FileItem, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val isDir = entry.type == FileItemType.Directory
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .width(340.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(D.surface)
                .border(1.dp, D.line, RoundedCornerShape(12.dp))
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Txt(if (isDir) "Delete folder?" else "Delete file?", color = D.text, size = 14.sp, weight = FontWeight.SemiBold)
            Txt(
                if (isDir) "«${entry.name}» and everything inside it will be removed permanently."
                else "«${entry.name}» will be removed permanently.",
                color = D.faint,
                size = 12.sp,
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GhostButton("Cancel", onClick = onDismiss)
                PrimaryButton("Delete", onClick = onConfirm, bg = D.sunset, fg = Color(0xFF0A1A26))
            }
        }
    }
}

// Общее и мок-путь.

/** Центрированное уведомление в области листинга (открытие/ошибка/нет сессии). */
@Composable
private fun PaneNotice(icon: String, title: String, subtitle: String?, color: Color) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Sym(icon, size = 26.sp, color = color)
            Txt(title, color = D.text, size = 13.sp, weight = FontWeight.SemiBold)
            if (subtitle != null) Txt(subtitle, color = D.faint, size = 11.5.sp)
        }
    }
}

/** Имя Material-иконки (лигатура [Sym]) по типу объекта файловой панели. */
private fun fileItemIcon(type: FileItemType): String = when (type) {
    FileItemType.Directory -> "folder"
    FileItemType.Symlink -> "link"
    FileItemType.File, FileItemType.Other -> "description"
}

/** Живая сессия есть, но не подключена: заголовок + уведомление. */
@Composable
private fun NoSessionSftpView(mono: FontFamily) {
    Column(Modifier.fillMaxSize().background(D.bg)) {
        SftpTopBar("No active session", mono)
        HLine()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            PaneNotice("cloud_off", "No active session", "Connect a host to browse files", D.faint)
        }
    }
}

/** Статичный мок макета (офскрин-рендер/превью без бэкенда сессий). */
@Composable
private fun MockSftpView(mono: FontFamily) {
    Column(Modifier.fillMaxSize().background(D.bg)) {
        Row(
            Modifier.fillMaxWidth().background(D.surface2).padding(horizontal = 18.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Sym("drive_file_move", size = 18.sp, color = D.cyanBright)
                Txt("File transfer", color = D.text, size = 13.sp, weight = FontWeight.SemiBold)
                Txt("root@prod-web-01 · SFTP", color = D.faint, size = 11.5.sp, font = mono)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostButton("Upload", onClick = {}, icon = "upload")
                GhostButton("New folder", onClick = {}, icon = "create_new_folder")
            }
        }
        HLine()
        Row(Modifier.weight(1f).fillMaxWidth()) {
            MockPane("computer", D.dim, "Local", "~/projects", LOCAL_FILES, mono, Modifier.weight(1f))
            VLine(D.line)
            MockPane("dns", D.moss, "Remote", "/var/www", REMOTE_FILES, mono, Modifier.weight(1f))
        }
        HLine()
        Row(
            Modifier.fillMaxWidth().background(D.surface2).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Sym("upload", size = 16.sp, color = D.cyan)
            Txt("backup.tar.gz", color = D.textBright, size = 11.5.sp, font = mono)
            MeterBar(0.64f, D.cyan, Modifier.weight(1f))
            Txt("64% · 12.4 MB/s · 02:18 left", color = D.dim, size = 11.sp, font = mono)
        }
    }
}

@Composable
private fun MockPane(
    icon: String,
    iconColor: Color,
    label: String,
    path: String,
    files: List<FileEntry>,
    mono: FontFamily,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxHeight()) {
        Row(
            Modifier.fillMaxWidth().background(D.panel).padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Sym(icon, size = 16.sp, color = iconColor)
            Txt(label.uppercase(), color = D.faint, size = 11.sp, weight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
            Txt(path, color = D.textBright, size = 11.5.sp, font = mono)
        }
        HLine()
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(6.dp)) {
            files.forEach { MockRow(it, mono) }
        }
    }
}

@Composable
private fun MockRow(entry: FileEntry, mono: FontFamily) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(5.dp))
            .background(if (entry.selected) D.cyan06 else Color.Transparent)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Sym(entry.icon, size = 17.sp, color = entry.iconColor)
        Txt(entry.name, color = if (entry.name == "..") D.dim else D.textBright, size = 12.sp, font = mono, modifier = Modifier.weight(1f))
        if (entry.meta.isNotEmpty()) Txt(entry.meta, color = D.faint, size = 11.sp)
    }
}
