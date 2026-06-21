package app.skerry.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.sftp.SftpEntry
import app.skerry.shared.sftp.SftpEntryType
import app.skerry.ui.connection.ConnectionController
import app.skerry.ui.connection.ConnectionUiState
import app.skerry.ui.sftp.SftpController
import app.skerry.ui.sftp.SftpPaneState
import app.skerry.ui.sftp.sftpEntryIcon
import app.skerry.ui.sftp.sftpEntryMeta
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
 * SFTP view: заголовок + две панели (Local/Remote) + очередь передачи. Когда есть живая сессия
 * ([LocalSessions]), правая панель (Remote) рендерится поверх живого [SftpController] активной
 * сессии — листинг, навигация по каталогам, путь — реальные. Без сессии (офскрин-рендер дизайна)
 * показывается мок. Local-панель, upload/download и очередь передачи пока остаются заглушками
 * (локальная ФС и file picker — отдельный слайс).
 */
@Composable
fun SftpView() {
    val mono = LocalFonts.current.mono
    val sessions = LocalSessions.current
    val active = sessions?.active
    val controller = active?.controller
    val connected = controller?.uiState is ConnectionUiState.Connected
    val live = sessions != null

    Column(Modifier.fillMaxSize().background(D.bg)) {
        Row(
            Modifier.fillMaxWidth().background(D.surface2).padding(horizontal = 18.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Sym("drive_file_move", size = 18.sp, color = D.cyanBright)
                Txt("File transfer", color = D.text, size = 13.sp, weight = FontWeight.SemiBold)
                val subtitle = if (live) (active?.subtitle?.let { "$it · SFTP" } ?: "No active session") else "root@prod-web-01 · SFTP"
                Txt(subtitle, color = D.faint, size = 11.5.sp, font = mono)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Upload / New folder — отдельный слайс (write-операции + file picker); кнопки макета
                // сохранены визуально.
                GhostButton("Upload", onClick = {}, icon = "upload")
                GhostButton("New folder", onClick = {}, icon = "create_new_folder")
            }
        }
        HLine()
        Row(Modifier.weight(1f).fillMaxWidth()) {
            FilePane("computer", D.dim, "Local", "~/projects", LOCAL_FILES, mono, Modifier.weight(1f))
            VLine(D.line)
            when {
                !live -> FilePane("dns", D.moss, "Remote", "/var/www", REMOTE_FILES, mono, Modifier.weight(1f))
                connected && controller != null -> RemoteFilePaneLive(controller, mono, Modifier.weight(1f))
                else -> RemotePaneNotice(Modifier.weight(1f))
            }
        }
        HLine()
        if (!live) {
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
}

/** Правая панель поверх живого [SftpController] активной сессии: путь + листинг + навигация. */
@Composable
private fun RemoteFilePaneLive(controller: ConnectionController, mono: FontFamily, modifier: Modifier) {
    var sftp by remember(controller) { mutableStateOf<SftpController?>(null) }
    var openError by remember(controller) { mutableStateOf<String?>(null) }
    // openSftpController кэшируется на соединении и живёт на scope сессии — открываем один раз,
    // переключение вкладок/панелей листинг не сбрасывает. Канал закрывает disconnect().
    LaunchedEffect(controller) {
        openError = null
        try {
            sftp = controller.openSftpController()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            openError = e.message ?: "Не удалось открыть SFTP"
        }
    }
    Column(modifier.fillMaxHeight()) {
        PaneHeader("dns", D.moss, "Remote", sftp?.path ?: "…", mono)
        HLine()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val current = sftp
            when {
                openError != null -> PaneNotice("error", "SFTP unavailable", openError, D.sunset)
                current == null -> PaneNotice("sync", "Opening SFTP…", null, D.faint)
                else -> when (val st = current.state) {
                    SftpPaneState.Loading -> PaneNotice("sync", "Loading…", null, D.faint)
                    is SftpPaneState.Error -> PaneNotice("error", "SFTP error", st.message, D.sunset)
                    is SftpPaneState.Loaded -> RemoteListing(current, st.entries, mono)
                }
            }
        }
    }
}

@Composable
private fun RemoteListing(controller: SftpController, entries: List<SftpEntry>, mono: FontFamily) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(6.dp)) {
        if (controller.path != "/") {
            val onUp = remember(controller) { controller::goUp }
            LiveFileRow("arrow_upward", D.faint, "..", "", mono, onClick = onUp)
        }
        entries.forEach { entry ->
            val isDir = entry.type == SftpEntryType.Directory || entry.type == SftpEntryType.Symlink
            // Открываем только каталоги/ссылки; скачивание файлов — отдельный слайс. Лямбда
            // стабилизирована по (controller, path), чтобы не пересоздаваться на каждой рекомпозиции.
            val onClick = remember(controller, entry.path, isDir) {
                if (isDir) ({ controller.open(entry) }) else null
            }
            LiveFileRow(
                icon = sftpEntryIcon(entry.type),
                iconColor = if (entry.type == SftpEntryType.Directory) D.cyanBright else D.dim,
                name = entry.name,
                meta = sftpEntryMeta(entry),
                mono = mono,
                onClick = onClick,
            )
        }
    }
}

/** Шапка панели (иконка + метка + путь) — общая для мок- и live-панелей. */
@Composable
private fun PaneHeader(icon: String, iconColor: Color, label: String, path: String, mono: FontFamily) {
    Row(
        Modifier.fillMaxWidth().background(D.panel).padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Sym(icon, size = 16.sp, color = iconColor)
        Txt(label.uppercase(), color = D.faint, size = 11.sp, weight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
        Txt(path, color = D.textBright, size = 11.5.sp, font = mono)
    }
}

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

/** Правая панель без активной подключённой сессии. */
@Composable
private fun RemotePaneNotice(modifier: Modifier) {
    Column(modifier.fillMaxHeight()) {
        PaneHeader("dns", D.faint, "Remote", "—", LocalFonts.current.mono)
        HLine()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            PaneNotice("cloud_off", "No active session", "Connect a host to browse files", D.faint)
        }
    }
}

@Composable
private fun FilePane(
    icon: String,
    iconColor: Color,
    label: String,
    path: String,
    files: List<FileEntry>,
    mono: FontFamily,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxHeight()) {
        PaneHeader(icon, iconColor, label, path, mono)
        HLine()
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(6.dp)) {
            files.forEach { FileRow(it, mono) }
        }
    }
}

@Composable
private fun FileRow(entry: FileEntry, mono: FontFamily) {
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

/** Строка живого листинга: как [FileRow], но кликабельна для каталогов/«..». */
@Composable
private fun LiveFileRow(
    icon: String,
    iconColor: Color,
    name: String,
    meta: String,
    mono: FontFamily,
    onClick: (() -> Unit)?,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(5.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Sym(icon, size = 17.sp, color = iconColor)
        Txt(name, color = if (name == "..") D.dim else D.textBright, size = 12.sp, font = mono, modifier = Modifier.weight(1f))
        if (meta.isNotEmpty()) Txt(meta, color = D.faint, size = 11.sp)
    }
}
