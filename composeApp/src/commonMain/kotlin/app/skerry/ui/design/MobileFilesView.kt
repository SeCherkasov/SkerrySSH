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

/** Фон карточки передачи (`#0B1A26` = [D.surface2]); трек прогресс-полосы — белый 7%. */
private val TransferTrack = Color(0x12FFFFFF)

/** Трек сегмент-переключателя Remote/Local (белый 5%, как в макете). */
private val SegmentTrack = Color(0x0DFFFFFF)

/**
 * Корневой таб Files мобильного макета `docs/new/Skerry Mobile.html` (слайс 4): single-pane браузер
 * с переключателем Remote/Local поверх живого SFTP активной сессии. В отличие от двухпанельного
 * desktop-[SftpView], телефон показывает одну панель за раз (как в макете), но опирается на тот же
 * кэшированный [TransferCoordinator] сессии — обе панели и передача реальны.
 *
 * Режим выбирается [mobileFilesMode]: без менеджера сессий — статичный мок ([MockMobileFilesView]),
 * с подключённой активной сессией — живой листинг, иначе — уведомление «нет сессии». Видимые действия
 * макета: тап по папке — вход, тап по файлу (иконка `ios_share`) — передача (скачать с Remote / залить
 * с Local), FAB `upload` — выбор файла с устройства и заливка в текущий каталог Remote. Переименование/
 * удаление спрятаны в контекстное меню (long-press), как и на desktop — в макете их нет.
 */
@Composable
fun MobileFilesScreen() {
    val mono = LocalFonts.current.mono
    val sessions = LocalSessions.current
    val active = sessions?.active
    val connected = active?.controller?.uiState is ConnectionUiState.Connected
    when (mobileFilesMode(hasSessions = sessions != null, connected = connected)) {
        MobileFilesMode.Preview -> MockMobileFilesView(mono)
        // active?.let вместо !! : sessions.active — производный геттер над двумя snapshot-полями,
        // и при гонке закрытия сессии мог бы оказаться null даже при connected — раннее «ничего».
        MobileFilesMode.Live -> active?.let { LiveMobileFilesView(it.controller, it.subtitle, mono) }
        MobileFilesMode.NoSession -> NoSessionMobileFilesView(mono)
    }
}

// ──────────────────────────────────────── Live ────────────────────────────────────────

/**
 * Живой Files-экран поверх кэшированного [TransferCoordinator] сессии (открывается один раз и живёт
 * на scope сессии — переключение таба путь/выделение не сбрасывает). [showRemote] выбирает активную
 * панель: Remote (хост) по умолчанию, как в макете; Local — локальная ФС.
 */
@Composable
private fun LiveMobileFilesView(controller: ConnectionController, subtitle: String, mono: FontFamily) {
    var coord by remember(controller) { mutableStateOf<TransferCoordinator?>(null) }
    var openError by remember(controller) { mutableStateOf<String?>(null) }
    var showRemote by remember(controller) { mutableStateOf(true) }
    // UI-scope только для нативного пикера файла (FAB Upload); сама передача живёт на scope сессии
    // внутри координатора и переживёт уход вью из композиции.
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
    Box(Modifier.fillMaxSize().background(D.bg)) {
        Column(Modifier.fillMaxSize()) {
            MobileFilesTitle()
            MobileFilesToggle(showRemote, onSelect = { showRemote = it })
            when {
                openError != null -> MobileFilesNotice("error", "SFTP unavailable", openError, D.sunset)
                c == null -> MobileFilesNotice("sync", "Opening SFTP…", null, D.faint)
                else -> {
                    val pane = if (showRemote) c.remote else c.local
                    // Видимое действие строки-файла (ios_share): передать между панелями. Стабилизируем
                    // лямбду по (c, showRemote) — иначе она пересоздавалась бы на каждой рекомпозиции
                    // (напр. при обновлении карточки передачи) и зря инвалидировала MobileLivePane.
                    val onTransfer = remember(c, showRemote) {
                        { item: FileItem ->
                            if (showRemote) {
                                c.remote.selectOnly(item)
                                c.downloadSelection()
                            } else {
                                c.local.selectOnly(item)
                                c.uploadSelection()
                            }
                        }
                    }
                    MobileFilesBreadcrumbRow(showRemote, pane.label, pane.path, mono)
                    MobileLivePane(
                        pane = pane,
                        mono = mono,
                        onTransfer = onTransfer,
                        modifier = Modifier.weight(1f),
                    )
                    MobileTransferCard(c.transfer, mono, onDismiss = c::clearTransfer)
                    Spacer(Modifier.height(96.dp)) // место под таб-бар (FAB поверх него)
                }
            }
        }
        if (c != null && openError == null && showRemote) {
            // FAB upload показан только на Remote-панели (как в макете): нативный пикер → заливка в
            // текущий каталог Remote (uploadSource всегда целит в remote.path — на Local он не к месту).
            MobileUploadFab(
                onClick = { uiScope.launch { pickUploadSource()?.let { c.uploadSource(it) } } },
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 22.dp, bottom = 104.dp),
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
    modifier: Modifier,
) {
    var renaming by remember(pane) { mutableStateOf<FileItem?>(null) }
    var deleting by remember(pane) { mutableStateOf<FileItem?>(null) }

    Box(modifier.fillMaxWidth()) {
        when (val st = pane.state) {
            FilePaneState.Loading -> MobileFilesNoticeBox("sync", "Loading…", null, D.faint)
            is FilePaneState.Error -> MobileFilesNoticeBox("error", "Error", st.message, D.sunset)
            is FilePaneState.Loaded -> Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 12.dp, start = 12.dp, end = 12.dp),
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

/**
 * Строка списка файлов мобильного макета: ведущая иконка ([mobileFileIcon]) + имя (mono) + мета +
 * завершающая иконка ([mobileFileTrailingIcon]). Тап — [onClick] (войти/передать); long-press —
 * контекстное меню Rename/Delete (в макете их нет — прячем, как на desktop).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MobileFileRow(
    entry: FileItem,
    selected: Boolean,
    mono: FontFamily,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val isDir = entry.type == FileItemType.Directory
    Box {
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
            Popup(alignment = Alignment.TopEnd, onDismissRequest = { menuOpen = false }) {
                Column(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(D.surface2)
                        .border(1.dp, D.cyan14, RoundedCornerShape(8.dp))
                        .padding(4.dp),
                ) {
                    MobileMenuItem("Rename", D.text) { menuOpen = false; onRename() }
                    MobileMenuItem("Delete", D.sunset) { menuOpen = false; onDelete() }
                }
            }
        }
    }
}

@Composable
private fun MobileMenuItem(label: String, color: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Txt(label, color = color, size = 13.sp)
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
                Txt("${transfer.name}: ${transfer.message}", color = D.sunset, size = 11.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                IconBtn("close", onClick = onDismiss, box = 26, icon = 16.sp)
            }
        }
    }
}

// ──────────────────────────────────────── Shared chrome ────────────────────────────────────────

/** Заголовок «Files» (28sp, как в макете). */
@Composable
private fun MobileFilesTitle() {
    Box(Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 6.dp, bottom = 10.dp)) {
        Txt("Files", color = D.text, size = 28.sp, weight = FontWeight.Bold, letterSpacing = (-0.5).sp)
    }
}

/** Сегмент-переключатель Remote/Local макета (трек белый 5%, активный — заливка cyan 16%). */
@Composable
private fun MobileFilesToggle(showRemote: Boolean, onSelect: (Boolean) -> Unit) {
    Row(
        Modifier
            .padding(horizontal = 22.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(SegmentTrack)
            .padding(3.dp),
    ) {
        MobileFilesSegment("Remote", active = showRemote, onClick = { onSelect(true) }, modifier = Modifier.weight(1f))
        MobileFilesSegment("Local", active = !showRemote, onClick = { onSelect(false) }, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun MobileFilesSegment(label: String, active: Boolean, onClick: () -> Unit, modifier: Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) D.cyan.copy(alpha = 0.16f) else Color.Transparent)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Txt(
            label,
            color = if (active) D.cyanBright else D.dim,
            size = 13.sp,
            weight = if (active) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

/** Строка-крошка под переключателем: иконка источника (dns/computer) + «label : path». */
@Composable
private fun MobileFilesBreadcrumbRow(remote: Boolean, label: String, path: String, mono: FontFamily) {
    Row(
        Modifier.padding(start = 22.dp, end = 22.dp, top = 13.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Sym(if (remote) "dns" else "computer", size = 16.sp, color = if (remote) D.moss else D.dim)
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

/** Круглая FAB-кнопка upload (cyan, тёмная иконка) — нижний правый угол макета. */
@Composable
private fun MobileUploadFab(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(56.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(D.cyan)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Sym("upload", size = 26.sp, color = Color(0xFF0A1A26))
    }
}

/** Уведомление под заголовком+переключателем (открытие/ошибка). */
@Composable
private fun MobileFilesNotice(icon: String, title: String, subtitle: String?, color: Color) {
    Box(Modifier.fillMaxWidth().padding(top = 60.dp), contentAlignment = Alignment.Center) {
        MobileFilesNoticeContent(icon, title, subtitle, color)
    }
}

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
private fun NoSessionMobileFilesView(mono: FontFamily) {
    Column(Modifier.fillMaxSize().background(D.bg)) {
        MobileFilesTitle()
        MobileFilesNoticeBox("cloud_off", "No active session", "Connect a host to browse files", D.faint)
    }
}

// ──────────────────────────────────────── Mock (preview/офскрин) ────────────────────────────────────────

private data class MockFileEntry(val icon: String, val iconColor: Color, val name: String, val meta: String, val trailing: String, val selected: Boolean = false)

/** Статичные данные Remote-панели ровно из макета (FILES) — для офскрин-сверки 1:1. */
private val MOCK_REMOTE_FILES = listOf(
    MockFileEntry("folder", D.cyanBright, "html", "drwxr-xr-x · 4 items", "chevron_right"),
    MockFileEntry("folder", D.cyanBright, "releases", "drwxr-xr-x · 12 items", "chevron_right"),
    MockFileEntry("description", D.dim, "nginx.conf", "3.1 KB · Jun 20", "ios_share", selected = true),
    MockFileEntry("terminal", D.dim, "deploy.sh", "1.8 KB · Jun 18", "ios_share"),
    MockFileEntry("description", D.dim, "robots.txt", "112 B · May 30", "ios_share"),
)

/** Статичный мок секции FILES макета (превью/офскрин без бэкенда) — 1:1 с `docs/new/Skerry Mobile.html`. */
@Composable
private fun MockMobileFilesView(mono: FontFamily) {
    Box(Modifier.fillMaxSize().background(D.bg)) {
        Column(Modifier.fillMaxSize()) {
            MobileFilesTitle()
            MobileFilesToggle(showRemote = true, onSelect = {})
            MobileFilesBreadcrumbRow(remote = true, label = "prod-web-01", path = "/var/www", mono = mono)
            Column(Modifier.fillMaxWidth().padding(top = 12.dp, start = 12.dp, end = 12.dp)) {
                MOCK_REMOTE_FILES.forEach { MockFileRow(it, mono) }
            }
            MockTransferCard(mono)
            Spacer(Modifier.height(96.dp))
        }
        MobileUploadFab(onClick = {}, modifier = Modifier.align(Alignment.BottomEnd).padding(end = 22.dp, bottom = 104.dp))
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
