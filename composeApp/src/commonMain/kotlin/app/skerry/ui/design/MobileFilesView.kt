package app.skerry.ui.design

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.files.FileItem
import app.skerry.shared.files.FileItemType
import app.skerry.ui.connection.ConnectionController
import app.skerry.ui.connection.ConnectionUiState
import app.skerry.ui.files.FilePaneController
import app.skerry.ui.files.FilePaneState
import app.skerry.ui.files.TransferCoordinator
import app.skerry.ui.files.TransferState
import app.skerry.ui.files.platformLocalBrowser
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.ftail_open_failed
import app.skerry.ui.generated.resources.sftp_connecting
import app.skerry.ui.generated.resources.sftp_create
import app.skerry.ui.generated.resources.sftp_create_directory
import app.skerry.ui.generated.resources.sftp_delete
import app.skerry.ui.generated.resources.sftp_download_to_device
import app.skerry.ui.generated.resources.sftp_error
import app.skerry.ui.generated.resources.sftp_files_title
import app.skerry.ui.generated.resources.sftp_loading
import app.skerry.ui.generated.resources.sftp_new_folder
import app.skerry.ui.generated.resources.sftp_no_session
import app.skerry.ui.generated.resources.sftp_no_session_hint
import app.skerry.ui.generated.resources.sftp_rename
import app.skerry.ui.generated.resources.sftp_transfer_error
import app.skerry.ui.generated.resources.sftp_unavailable
import app.skerry.ui.generated.resources.sftp_upload_file
import app.skerry.ui.sftp.TransferDirection
import app.skerry.ui.sftp.pickDownloadTarget
import app.skerry.ui.sftp.pickUploadSource
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/** Фон карточки передачи (`#0B1A26` = [D.surface2]); трек прогресс-полосы — белый 7%. */
private val TransferTrack = Color(0x12FFFFFF)

/**
 * Корневой таб Files: single-pane браузер Remote-SFTP активной сессии поверх кэшированного
 * [TransferCoordinator]. Локальная панель устройства убрана (scoped-storage Android делает её
 * бесполезной), переключатель Remote/Local тоже — экран всегда показывает каталог хоста.
 *
 * Режим выбирается [mobileFilesMode]: без менеджера сессий — статичный мок ([MockMobileFilesView]),
 * с подключённой активной сессией — живой листинг, иначе — уведомление «нет сессии». Видимые действия:
 * тап по папке — вход, тап по файлу (иконка `ios_share`) — скачать через системный «Save to…», FAB —
 * создать каталог / залить файл с устройства. Переименование/удаление и «Download to device» спрятаны
 * в контекстное меню (long-press).
 */
@Composable
fun MobileFilesScreen(onBack: (() -> Unit)? = null) {
    val mono = LocalFonts.current.mono
    val sessions = LocalSessions.current
    val active = sessions?.active
    val uiState = active?.controller?.uiState
    val connected = uiState is ConnectionUiState.Connected
    val connecting = uiState is ConnectionUiState.Connecting
    when (mobileFilesMode(hasSessions = sessions != null, connected = connected, connecting = connecting)) {
        MobileFilesMode.Preview -> MockMobileFilesView(mono)
        // active?.let вместо !! : sessions.active — производный геттер над двумя snapshot-полями,
        // и при гонке закрытия сессии мог бы оказаться null даже при connected — раннее «ничего».
        MobileFilesMode.Live -> active?.let { LiveMobileFilesView(it.controller, it.subtitle, mono, onBack) }
        // «Connecting…» с подписью хоста: после тапа SFTP/Connect сессия ещё в хендшейке — не мигаем
        // «No active session». active?.let на случай гонки закрытия (как в Live).
        MobileFilesMode.Connecting -> active?.let { ConnectingMobileFilesView(it.subtitle, onBack) }
        MobileFilesMode.NoSession -> NoSessionMobileFilesView(mono, onBack)
    }
}

// Живой путь.

/**
 * Живой Files-экран поверх кэшированного [TransferCoordinator] сессии (открывается один раз и живёт
 * на scope сессии — переключение таба путь/выделение не сбрасывает). Показывает только Remote-панель
 * (каталог хоста); Local-панель координатора используется лишь как приёмник «Download to device».
 */
@Composable
private fun LiveMobileFilesView(controller: ConnectionController, subtitle: String, mono: FontFamily, onBack: (() -> Unit)? = null) {
    var coord by remember(controller) { mutableStateOf<TransferCoordinator?>(null) }
    var openError by remember(controller) { mutableStateOf<String?>(null) }
    var creatingFolder by remember(controller) { mutableStateOf(false) }
    var fabOpen by remember(controller) { mutableStateOf(false) }
    // UI-scope только для нативного пикера файла (FAB Upload); сама передача живёт на scope сессии
    // внутри координатора и переживёт уход вью из композиции.
    val uiScope = rememberCoroutineScope()
    // stringResource нельзя звать внутри LaunchedEffect — поднимаем значение заранее.
    val openFailedMsg = stringResource(Res.string.ftail_open_failed)
    LaunchedEffect(controller) {
        openError = null
        try {
            coord = controller.openTransferCoordinator(platformLocalBrowser(), subtitle)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            openError = e.message ?: openFailedMsg
        }
    }

    val c = coord
    Box(Modifier.fillMaxSize().background(D.bg)) {
        Column(Modifier.fillMaxSize()) {
            MobileFilesTitle(onBack)
            when {
                // Один нейтральный центрированный статус на всю фазу ожидания: хендшейк сессии →
                // открытие SFTP-канала → первый листинг каталога. Текст и позиция не меняются (как у
                // терминала — «какая разница terminal это или sftp»), поэтому экран не мигает и не
                // «прыгает» вертикально. Хлебную крошку + список показываем только когда листинг готов.
                openError != null -> MobileFilesNoticeBox("error", stringResource(Res.string.sftp_unavailable), openError, D.sunset)
                c == null || c.remote.state is FilePaneState.Loading ->
                    MobileFilesNoticeBox("sync", stringResource(Res.string.sftp_connecting), subtitle, D.cyanBright)
                else -> {
                    val pane = c.remote
                    // Видимое действие строки-файла (ios_share): скачать НАРУЖУ из песочницы через
                    // системный «Save to…» ([pickDownloadTarget] → SAF на Android, нативный диалог на
                    // desktop) — пикер suspend, поэтому через uiScope. Стабилизируем по (c, uiScope),
                    // чтобы лямбда не пересоздавалась на каждой рекомпозиции (напр. при обновлении
                    // карточки передачи) и зря не инвалидировала список.
                    val onTransfer = remember(c, uiScope) {
                        { item: FileItem ->
                            uiScope.launch { pickDownloadTarget(item.name)?.let { c.downloadToTarget(item, it) } }
                            Unit
                        }
                    }
                    // «Download to device» (long-press на файле): скачать БЕЗ диалога в каталог
                    // приложения (Local-панель координатора), чтобы файл сразу лежал на устройстве.
                    val downloadHere = remember(c) {
                        { item: FileItem -> c.remote.selectOnly(item); c.downloadSelection() }
                    }
                    MobileFilesBreadcrumbRow(pane.label, pane.path, mono)
                    MobileLivePane(
                        pane = pane,
                        mono = mono,
                        onTransfer = onTransfer,
                        onDownloadHere = downloadHere,
                        modifier = Modifier.weight(1f),
                    )
                    MobileTransferCard(c.transfer, mono, onDismiss = c::clearTransfer)
                    Spacer(Modifier.height(88.dp)) // место под плавающую FAB (push-экран без таб-бара)
                }
            }
        }
        // Скрим-подложка под раскрытым меню: тап мимо гасит FAB (мобильная идиома speed-dial).
        if (fabOpen) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                        fabOpen = false
                    },
            )
        }
        // Единый «+»-FAB: раскрывает действия над Remote-панелью — «Создать директорию» и «Загрузить
        // файл» (uploadSource целит в remote.path). Действия всплывают НАД кнопкой стопкой с подписями.
        if (c != null && openError == null && c.remote.state !is FilePaneState.Loading) {
            Column(
                Modifier.align(Alignment.BottomEnd).padding(end = 22.dp, bottom = 24.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (fabOpen) {
                    MobileFabAction("create_new_folder", stringResource(Res.string.sftp_create_directory)) {
                        fabOpen = false
                        creatingFolder = true
                    }
                    MobileFabAction("upload", stringResource(Res.string.sftp_upload_file)) {
                        fabOpen = false
                        uiScope.launch { pickUploadSource()?.let { c.uploadSource(it) } }
                    }
                }
                MobileFabButton(open = fabOpen, onClick = { fabOpen = !fabOpen })
            }
        }
        if (creatingFolder && c != null) {
            // Создание каталога в Remote-панели. Переиспользуем общий NameDialog
            // (валидирует пустоту/«/»/«.»/«..»/управляющие), как desktop «New folder».
            val pane = c.remote
            NameDialog(
                title = stringResource(Res.string.sftp_new_folder),
                confirmLabel = stringResource(Res.string.sftp_create),
                initial = "",
                onConfirm = { pane.mkdir(it); creatingFolder = false },
                onDismiss = { creatingFolder = false },
            )
        }
    }
}

/**
 * Живая панель (Remote или Local) поверх [FilePaneController]: листинг + строка-вверх «..» +
 * контекстное меню переименования/удаления (long-press). [onTransfer] — передача файла (видимое
 * действие `ios_share` строки-файла).
 */
@Composable
private fun MobileLivePane(
    pane: FilePaneController,
    mono: FontFamily,
    onTransfer: (FileItem) -> Unit,
    onDownloadHere: ((FileItem) -> Unit)?,
    modifier: Modifier,
) {
    var renaming by remember(pane) { mutableStateOf<FileItem?>(null) }
    var deleting by remember(pane) { mutableStateOf<FileItem?>(null) }

    // Новый каталог всегда показываем с начала. Панель НЕ перезагружается через Loading
    // (reload() сразу ставит Loaded), поэтому скролл-колонка переживает смену каталога, и без
    // явного сброса verticalScroll унаследовал бы прокрутку предыдущего каталога — остаточный
    // офсет (после флинга/оверскролла) переносился бы в новый листинг, и список «прыгал» бы на
    // пару пикселей при каждом переходе. Сбрасываем в начало при смене пути (scrollTo — мгновенно).
    val scroll = rememberScrollState()
    LaunchedEffect(pane.path) { scroll.scrollTo(0) }

    Box(modifier.fillMaxWidth()) {
        when (val st = pane.state) {
            FilePaneState.Loading -> MobileFilesNoticeBox("sync", stringResource(Res.string.sftp_loading), null, D.faint)
            is FilePaneState.Error -> MobileFilesNoticeBox("error", stringResource(Res.string.sftp_error), st.message, D.sunset)
            is FilePaneState.Loaded -> Column(
                Modifier.fillMaxSize().verticalScroll(scroll).padding(top = 12.dp, start = 12.dp, end = 12.dp),
            ) {
                if (pane.path != "/") {
                    MobileFileUpRow(mono, onClick = pane::goUp)
                }
                st.entries.forEach { entry ->
                    // key по пути: forEach в Column переиспользует слоты позиционно, и без ключа
                    // открытое контекстное меню «переехало» бы на новую строку после refresh/rename.
                    key(entry.path) {
                        val isDir = entry.type == FileItemType.Directory
                        MobileFileRow(
                            entry = entry,
                            selected = entry.path in pane.selection,
                            mono = mono,
                            onClick = { if (isDir) pane.open(entry) else onTransfer(entry) },
                            onDownloadHere = if (!isDir && onDownloadHere != null) ({ onDownloadHere(entry) }) else null,
                            onRename = { renaming = entry },
                            onDelete = { deleting = entry },
                        )
                    }
                }
            }
        }
    }

    renaming?.let { entry ->
        NameDialog(
            title = stringResource(Res.string.sftp_rename),
            confirmLabel = stringResource(Res.string.sftp_rename),
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

/**
 * Строка списка файлов: ведущая иконка ([mobileFileIcon]) + имя (mono) + мета +
 * завершающая иконка ([mobileFileTrailingIcon]). Тап — [onClick] (войти/передать); long-press —
 * контекстное меню Rename/Delete.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MobileFileRow(
    entry: FileItem,
    selected: Boolean,
    mono: FontFamily,
    onClick: () -> Unit,
    onDownloadHere: (() -> Unit)?,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val isDir = entry.type == FileItemType.Directory
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.dp))
            .background(if (selected) D.cyan06 else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = { menuOpen = true })
            .padding(horizontal = 12.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        Sym(mobileFileIcon(entry), size = 23.sp, color = if (isDir) D.cyanBright else D.dim)
        Column(Modifier.weight(1f)) {
            Txt(entry.name, color = D.text, size = 14.5.sp, font = mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val meta = mobileFileRowMeta(entry)
            if (meta.isNotEmpty()) Txt(meta, color = D.faint, size = 11.sp)
        }
        Sym(mobileFileTrailingIcon(entry.type), size = 20.sp, color = D.faint)
    }
    if (menuOpen) {
        MobileActionSheet(
            title = entry.name,
            actions = buildList {
                onDownloadHere?.let { dl ->
                    add(MobileSheetAction(stringResource(Res.string.sftp_download_to_device), onClick = dl, icon = "download"))
                }
                add(MobileSheetAction(stringResource(Res.string.sftp_rename), onClick = onRename, icon = "edit"))
                add(MobileSheetAction(stringResource(Res.string.sftp_delete), onClick = onDelete, icon = "delete", danger = true))
            },
            onDismiss = { menuOpen = false },
        )
    }
}

/** Строка возврата в родительский каталог («..»). */
@Composable
private fun MobileFileUpRow(mono: FontFamily, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        Sym("arrow_upward", size = 23.sp, color = D.faint)
        Txt("..", color = D.dim, size = 14.5.sp, font = mono)
    }
}

/**
 * Карточка передачи мобильного макета (под списком): иконка направления + имя + процент + полоса.
 * Active — живой прогресс; Failed — ошибка с закрытием; Idle — ничего.
 */
@Composable
private fun MobileTransferCard(transfer: TransferState, mono: FontFamily, onDismiss: () -> Unit) {
    when (transfer) {
        TransferState.Idle -> Unit

        is TransferState.Active -> {
            val up = transfer.direction == TransferDirection.Upload
            val fraction = if (transfer.total > 0) transfer.transferred.toFloat() / transfer.total else 0f
            val percent = (fraction * 100).toInt()
            Column(
                Modifier
                    .padding(horizontal = 22.dp, vertical = 14.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(D.surface2)
                    .border(1.dp, D.cyan08, RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    Sym(if (up) "upload" else "download", size = 17.sp, color = D.cyan)
                    Txt(transfer.name, color = D.textBright, size = 12.5.sp, font = mono, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Txt("$percent%", color = D.dim, size = 11.sp)
                }
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(TransferTrack)) {
                    MeterBar(fraction, D.cyan, Modifier.fillMaxWidth())
                }
            }
        }

        is TransferState.Failed -> {
            Row(
                Modifier
                    .padding(horizontal = 22.dp, vertical = 14.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(D.surface2)
                    .border(1.dp, D.sunset.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                    .padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Sym("error", size = 17.sp, color = D.sunset)
                Txt(stringResource(Res.string.sftp_transfer_error, transfer.name, transfer.message), color = D.sunset, size = 11.5.sp, maxLines = 6, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                IconBtn("close", onClick = onDismiss, box = 26, icon = 16.sp)
            }
        }
    }
}

// Общий chrome.

/**
 * Заголовок «Files» (28sp, как в макете). Действия (создать каталог/загрузить) — в общем «+»-FAB.
 * [onBack] (push-режим SFTP с карточки хоста) добавляет слева back-стрелку, как у терминала; в превью
 * (`null`) её нет.
 */
@Composable
private fun MobileFilesTitle(onBack: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(start = if (onBack != null) 14.dp else 22.dp, end = 22.dp, top = 6.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (onBack != null) {
            Sym(
                "chevron_left",
                size = 27.sp,
                color = D.cyanBright,
                modifier = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onBack),
            )
        }
        Txt(stringResource(Res.string.sftp_files_title), color = D.text, size = 28.sp, weight = FontWeight.Bold, letterSpacing = (-0.5).sp)
    }
}

/** Строка-крошка под заголовком: иконка хоста (dns) + «label : path» активной Remote-сессии. */
@Composable
private fun MobileFilesBreadcrumbRow(label: String, path: String, mono: FontFamily) {
    Row(
        Modifier.padding(start = 22.dp, end = 22.dp, top = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Sym("dns", size = 16.sp, color = D.moss)
        Txt(
            mobileFilesBreadcrumb(label, path),
            color = D.dim,
            size = 12.sp,
            font = mono,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Круглая «+»-FAB (cyan, тёмная иконка); в раскрытом состоянии [open] показывает «×» для сворачивания. */
@Composable
private fun MobileFabButton(open: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(56.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(D.cyan)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Sym(if (open) "close" else "add", size = 26.sp, color = Color(0xFF0A1A26))
    }
}

/** Пункт меню «+»-FAB: пилюля с иконкой и подписью (всплывает над кнопкой). */
@Composable
private fun MobileFabAction(icon: String, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(D.surface2)
            .border(1.dp, D.cyan14, RoundedCornerShape(14.dp))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Sym(icon, size = 20.sp, color = D.cyanBright)
        Txt(label, color = D.text, size = 13.5.sp, weight = FontWeight.Medium)
    }
}

/** Центрированное уведомление в свободной области под заголовком (подключение/открытие/загрузка/ошибка). */
@Composable
private fun MobileFilesNoticeBox(icon: String, title: String, subtitle: String?, color: Color) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        MobileFilesNoticeContent(icon, title, subtitle, color)
    }
}

@Composable
private fun MobileFilesNoticeContent(icon: String, title: String, subtitle: String?, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Sym(icon, size = 30.sp, color = color)
        Txt(title, color = D.text, size = 14.sp, weight = FontWeight.Medium)
        if (subtitle != null) Txt(subtitle, color = D.faint, size = 12.sp)
    }
}

/** Менеджер сессий есть, но активная не подключена: заголовок + уведомление. */
@Composable
private fun NoSessionMobileFilesView(mono: FontFamily, onBack: (() -> Unit)? = null) {
    Column(Modifier.fillMaxSize().background(D.bg)) {
        MobileFilesTitle(onBack)
        MobileFilesNoticeBox("cloud_off", stringResource(Res.string.sftp_no_session), stringResource(Res.string.sftp_no_session_hint), D.faint)
    }
}

/** Активная сессия ещё подключается (тап SFTP/Connect): заголовок + «Connecting…» с подписью хоста. */
@Composable
private fun ConnectingMobileFilesView(subtitle: String, onBack: (() -> Unit)? = null) {
    Column(Modifier.fillMaxSize().background(D.bg)) {
        MobileFilesTitle(onBack)
        MobileFilesNoticeBox("sync", stringResource(Res.string.sftp_connecting), subtitle, D.cyanBright)
    }
}

// Мок (превью/офскрин).

private data class MockFileEntry(val icon: String, val iconColor: Color, val name: String, val meta: String, val trailing: String, val selected: Boolean = false)

/** Статичные данные Remote-панели (превью/офскрин). */
private val MOCK_REMOTE_FILES = listOf(
    MockFileEntry("folder", D.cyanBright, "html", "drwxr-xr-x · 4 items", "chevron_right"),
    MockFileEntry("folder", D.cyanBright, "releases", "drwxr-xr-x · 12 items", "chevron_right"),
    MockFileEntry("description", D.dim, "nginx.conf", "3.1 KB · Jun 20", "ios_share", selected = true),
    MockFileEntry("terminal", D.dim, "deploy.sh", "1.8 KB · Jun 18", "ios_share"),
    MockFileEntry("description", D.dim, "robots.txt", "112 B · May 30", "ios_share"),
)

/** Статичный мок секции Files (превью/офскрин без бэкенда). */
@Composable
private fun MockMobileFilesView(mono: FontFamily) {
    Box(Modifier.fillMaxSize().background(D.bg)) {
        Column(Modifier.fillMaxSize()) {
            MobileFilesTitle()
            MobileFilesBreadcrumbRow(label = "prod-web-01", path = "/var/www", mono = mono)
            Column(Modifier.fillMaxWidth().padding(top = 12.dp, start = 12.dp, end = 12.dp)) {
                MOCK_REMOTE_FILES.forEach { MockFileRow(it, mono) }
            }
            MockTransferCard(mono)
            Spacer(Modifier.height(96.dp))
        }
        MobileFabButton(open = false, onClick = {}, modifier = Modifier.align(Alignment.BottomEnd).padding(end = 22.dp, bottom = 104.dp))
    }
}

@Composable
private fun MockFileRow(entry: MockFileEntry, mono: FontFamily) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.dp))
            .background(if (entry.selected) D.cyan06 else Color.Transparent)
            .padding(horizontal = 12.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        Sym(entry.icon, size = 23.sp, color = entry.iconColor)
        Column(Modifier.weight(1f)) {
            Txt(entry.name, color = D.text, size = 14.5.sp, font = mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Txt(entry.meta, color = D.faint, size = 11.sp)
        }
        Sym(entry.trailing, size = 20.sp, color = D.faint)
    }
}

/** Карточка передачи макета (статичная, 64%). */
@Composable
private fun MockTransferCard(mono: FontFamily) {
    Column(
        Modifier
            .padding(horizontal = 22.dp, vertical = 14.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(D.surface2)
            .border(1.dp, D.cyan08, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Sym("upload", size = 17.sp, color = D.cyan)
            Txt("backup.tar.gz", color = D.textBright, size = 12.5.sp, font = mono, modifier = Modifier.weight(1f))
            Txt("64%", color = D.dim, size = 11.sp)
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(TransferTrack)) {
            MeterBar(0.64f, D.cyan, Modifier.fillMaxWidth())
        }
    }
}
