package app.skerry.ui.design

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.Popup
import app.skerry.shared.files.FileItem
import app.skerry.shared.files.FileItemType
import app.skerry.ui.connection.ConnectionController
import app.skerry.ui.connection.ConnectionUiState
import app.skerry.ui.files.FilePaneController
import app.skerry.ui.files.FilePaneState
import app.skerry.ui.files.TransferCoordinator
import app.skerry.ui.files.TransferState
import app.skerry.ui.files.platformLocalBrowser
import app.skerry.ui.sftp.TransferDirection
import app.skerry.ui.sftp.humanSize
import app.skerry.ui.sftp.pickUploadSource
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt

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
            LiveSftpView(active.controller, active.subtitle, mono)
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
private fun LiveSftpView(controller: ConnectionController, subtitle: String, mono: FontFamily) {
    var coord by remember(controller) { mutableStateOf<TransferCoordinator?>(null) }
    var openError by remember(controller) { mutableStateOf<String?>(null) }
    var creatingFolder by remember(controller) { mutableStateOf(false) }
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
    Column(Modifier.fillMaxSize().background(D.bg)) {
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
                    LivePane(c.local, "computer", D.dim, "Local", mono, onDownload = null, modifier = Modifier.weight(1f))
                    VLine(D.line)
                    LivePane(
                        c.remote, "dns", D.moss, "Remote", mono,
                        onDownload = { item -> c.remote.selectOnly(item); c.downloadSelection() },
                        modifier = Modifier.weight(1f),
                    )
                }
                LiveTransferStrip(c.transfer, mono, onDismiss = c::clearTransfer)
            }
        }
    }

    if (creatingFolder && c != null) {
        NameDialog(
            title = "New folder",
            confirmLabel = "Create",
            initial = "",
            onConfirm = { c.remote.mkdir(it); creatingFolder = false },
            onDismiss = { creatingFolder = false },
        )
    }
}

/**
 * Одна живая панель поверх [FilePaneController]: шапка [label] + путь (ровно как в шаблоне, без
 * тулбара — навигация вверх строкой «..»), листинг и диалоги переименования/удаления. [onDownload]
 * задаётся только для remote-панели (скачать файл из контекстного меню строки).
 */
@Composable
private fun LivePane(
    pane: FilePaneController,
    icon: String,
    iconColor: Color,
    label: String,
    mono: FontFamily,
    onDownload: ((FileItem) -> Unit)?,
    modifier: Modifier,
) {
    var renaming by remember(pane) { mutableStateOf<FileItem?>(null) }
    var deleting by remember(pane) { mutableStateOf<FileItem?>(null) }

    Column(modifier.fillMaxHeight()) {
        Row(
            Modifier.fillMaxWidth().background(D.panel).padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Sym(icon, size = 16.sp, color = iconColor)
            Txt(label.uppercase(), color = D.faint, size = 11.sp, weight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
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
                    onDownload = onDownload,
                    onRename = { renaming = it },
                    onDelete = { deleting = it },
                )
            }
        }
    }

    renaming?.let { entry ->
        NameDialog(
            title = "Rename",
            confirmLabel = "Rename",
            initial = entry.name,
            onConfirm = { pane.rename(entry, it); renaming = null },
            onDismiss = { renaming = null },
        )
    }
    deleting?.let { entry ->
        ConfirmDeleteDialog(
            entry = entry,
            onConfirm = { pane.delete(entry); deleting = null },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun LivePaneList(
    pane: FilePaneController,
    entries: List<FileItem>,
    mono: FontFamily,
    onDownload: ((FileItem) -> Unit)?,
    onRename: (FileItem) -> Unit,
    onDelete: (FileItem) -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(6.dp)) {
        if (pane.path != "/") {
            val onUp = remember(pane) { pane::goUp }
            LiveFileRow("arrow_upward", D.faint, "..", "", false, mono, onClick = onUp, menuItems = null, onMenuOpen = null)
        }
        entries.forEach { entry ->
            val isDir = entry.type == FileItemType.Directory
            // Каталог открываем, файл — добавляем/убираем из выделения (Upload берёт выделение).
            val onClick = remember(pane, entry.path, isDir) {
                { if (isDir) pane.open(entry) else pane.toggle(entry) }
            }
            // Действия строки — в контекстном меню (long-press/right-click), как в шаблоне без ⋮.
            val menuItems = buildList {
                // Download — для файлов и каталогов (каталог скачивается рекурсивно), но не для симлинков/прочего.
                if (onDownload != null && (entry.type == FileItemType.File || entry.type == FileItemType.Directory)) {
                    add(MenuAction("Download", D.text) { onDownload(entry) })
                }
                add(MenuAction("Rename", D.text) { onRename(entry) })
                add(MenuAction("Delete", D.sunset) { onDelete(entry) })
            }
            LiveFileRow(
                icon = fileItemIcon(entry.type),
                iconColor = if (entry.type == FileItemType.Directory) D.cyanBright else D.dim,
                name = entry.name,
                meta = if (entry.type == FileItemType.File) humanSize(entry.size) else "",
                selected = entry.path in pane.selection,
                mono = mono,
                onClick = onClick,
                menuItems = menuItems,
                // При открытии меню выделяем строку — видно, к какому файлу относятся действия.
                onMenuOpen = remember(pane, entry.path) { { pane.selectOnly(entry) } },
            )
        }
    }
}

/** Действие контекстного меню строки. */
private data class MenuAction(val label: String, val color: Color, val onClick: () -> Unit)

/**
 * Строка живого листинга — визуально идентична шаблону (иконка + имя + размер, без ⋮). Клик:
 * open(каталог)/select(файл); long-press/right-click открывает контекстное меню [menuItems]
 * (Download/Rename/Delete) — действия есть, но в обычном состоянии строка чистая, как в макете.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LiveFileRow(
    icon: String,
    iconColor: Color,
    name: String,
    meta: String,
    selected: Boolean,
    mono: FontFamily,
    onClick: () -> Unit,
    menuItems: List<MenuAction>?,
    onMenuOpen: (() -> Unit)?,
) {
    var menuOpen by remember { mutableStateOf(false) }
    // Позиция курсора в момент нажатия (локально к строке) — чтобы меню открылось под курсором, а не
    // в углу строки. Обновляется на каждом Press, так что и right-click, и long-press берут точку нажатия.
    var pressOffset by remember { mutableStateOf(Offset.Zero) }
    val hasMenu = !menuItems.isNullOrEmpty()
    // Открыть меню: сначала выделить строку (подсветка показывает цель действий), затем показать меню.
    val openMenu = { onMenuOpen?.invoke(); menuOpen = true }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(5.dp))
                .background(if (selected) D.cyan06 else Color.Transparent)
                // Right-click (desktop) открывает контекстное меню; long-press — для тача.
                .then(
                    if (hasMenu) {
                        Modifier.pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    if (event.type == PointerEventType.Press) {
                                        pressOffset = event.changes.first().position
                                        if (event.buttons.isSecondaryPressed) {
                                            event.changes.forEach { it.consume() }
                                            openMenu()
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        Modifier
                    },
                )
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = if (hasMenu) openMenu else null,
                )
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Sym(icon, size = 17.sp, color = iconColor)
            Txt(
                name,
                color = if (name == "..") D.dim else D.textBright,
                size = 12.sp,
                font = mono,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (meta.isNotEmpty()) Txt(meta, color = D.faint, size = 11.sp)
        }
        if (menuOpen && !menuItems.isNullOrEmpty()) {
            Popup(
                alignment = Alignment.TopStart,
                offset = IntOffset(pressOffset.x.roundToInt(), pressOffset.y.roundToInt()),
                onDismissRequest = { menuOpen = false },
            ) {
                Column(
                    Modifier
                        .clip(RoundedCornerShape(7.dp))
                        .background(D.surface2)
                        .border(1.dp, D.lineStrong, RoundedCornerShape(7.dp))
                        .padding(4.dp),
                ) {
                    menuItems.forEach { action ->
                        MenuItem(action.label, action.color) { menuOpen = false; action.onClick() }
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuItem(label: String, color: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(5.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Txt(label, color = color, size = 12.sp)
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
) {
    // Ключ initial: при повторном показе под другой entry (rename без выхода из композиции) поле
    // должно сброситься на новое имя, а не сохранить прежнее.
    var name by remember(initial) { mutableStateOf(initial) }
    val trimmed = name.trim()
    // Отвергаем пустое имя, разделитель пути, «.»/«..» и управляющие символы (null-байт/перевод
    // строки) — последние ломают пути на POSIX-ФС/SFTP-сервере и вёрстку строки.
    val valid = trimmed.isNotEmpty() &&
        "/" !in trimmed &&
        trimmed != "." &&
        trimmed != ".." &&
        trimmed.none { it == '\u0000' || it == '\n' || it == '\r' }
    val mono = LocalFonts.current.mono
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
                modifier = Modifier.fillMaxWidth(),
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
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GhostButton("Cancel", onClick = onDismiss)
                PrimaryButton(
                    confirmLabel,
                    onClick = { if (valid) onConfirm(trimmed) },
                    bg = if (valid) D.cyan else D.whiteFaint,
                )
            }
        }
    }
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
