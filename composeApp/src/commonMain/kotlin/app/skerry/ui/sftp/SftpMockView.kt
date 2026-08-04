package app.skerry.ui.sftp

import androidx.compose.foundation.background
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.files.FileItemType
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.sftp_new_folder
import app.skerry.ui.generated.resources.sftp_no_session
import app.skerry.ui.generated.resources.sftp_no_session_hint
import app.skerry.ui.generated.resources.sftp_pane_local
import app.skerry.ui.generated.resources.sftp_pane_remote
import app.skerry.ui.generated.resources.sftp_title
import app.skerry.ui.generated.resources.sftp_upload
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.design.GhostButton
import app.skerry.ui.design.HLine
import app.skerry.ui.design.MeterBar
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.design.VLine
import app.skerry.ui.theme.Skerry

private data class FileEntry(val icon: String, val name: String, val meta: String, val selected: Boolean = false)

private val LOCAL_FILES = listOf(
    FileEntry("arrow_upward", "..", ""),
    FileEntry("folder", "skerry-app", "Jun 21 09:14"),
    FileEntry("folder", "deploy-scripts", "Jun 18 22:40"),
    FileEntry("description", "docker-compose.yml", "2.4 KB"),
    FileEntry("key", "id_ed25519.pub", "96 B"),
    FileEntry("description", "backup.tar.gz", "418 MB"),
)

private val REMOTE_FILES = listOf(
    FileEntry("arrow_upward", "..", ""),
    FileEntry("folder", "html", "drwxr-xr-x"),
    FileEntry("folder", "releases", "drwxr-xr-x"),
    FileEntry("description", "nginx.conf", "3.1 KB", selected = true),
    FileEntry("description", "robots.txt", "112 B"),
    FileEntry("terminal", "deploy.sh", "1.8 KB"),
)

/** Mock row icon tint by icon kind, from the active theme (folders — cyan, files — dim). */
@Composable
private fun mockFileIconTint(icon: String): Color = when (icon) {
    "folder" -> Skerry.colors.cyanBright
    "arrow_upward" -> Skerry.colors.faint
    else -> Skerry.colors.dim
}

/** Material icon name ([Sym] ligature) for a file-pane item type. */
internal fun fileItemIcon(type: FileItemType): String = when (type) {
    FileItemType.Directory -> "folder"
    FileItemType.Symlink -> "link"
    FileItemType.File, FileItemType.Other -> "description"
}

/** A live session exists but isn't connected: header + notice. */
@Composable
internal fun NoSessionSftpView(mono: FontFamily) {
    Column(Modifier.fillMaxSize().background(Skerry.colors.bg)) {
        SftpTopBar(stringResource(Res.string.sftp_no_session), mono)
        HLine()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            PaneNotice("cloud_off", stringResource(Res.string.sftp_no_session), stringResource(Res.string.sftp_no_session_hint), Skerry.colors.faint)
        }
    }
}

/** Static mock (offscreen render/preview without a session backend). */
@Composable
internal fun MockSftpView(mono: FontFamily) {
    Column(Modifier.fillMaxSize().background(Skerry.colors.bg)) {
        Row(
            Modifier.fillMaxWidth().background(Skerry.colors.surface2).padding(horizontal = 18.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Sym("drive_file_move", size = 18.sp, color = Skerry.colors.cyanBright)
                Txt(stringResource(Res.string.sftp_title), color = Skerry.colors.text, size = 13.sp, weight = FontWeight.SemiBold)
                Txt("root@prod-web-01 · SFTP", color = Skerry.colors.faint, size = 11.5.sp, font = mono)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostButton(stringResource(Res.string.sftp_upload), onClick = {}, icon = "upload")
                GhostButton(stringResource(Res.string.sftp_new_folder), onClick = {}, icon = "create_new_folder")
            }
        }
        HLine()
        Row(Modifier.weight(1f).fillMaxWidth()) {
            MockPane("computer", Skerry.colors.dim, stringResource(Res.string.sftp_pane_local), "~/projects", LOCAL_FILES, mono, Modifier.weight(1f))
            VLine(Skerry.colors.line)
            MockPane("dns", Skerry.colors.moss, stringResource(Res.string.sftp_pane_remote), "/var/www", REMOTE_FILES, mono, Modifier.weight(1f))
        }
        HLine()
        Row(
            Modifier.fillMaxWidth().background(Skerry.colors.surface2).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Sym("upload", size = 16.sp, color = Skerry.colors.cyan)
            Txt("backup.tar.gz", color = Skerry.colors.textBright, size = 11.5.sp, font = mono)
            MeterBar(0.64f, Skerry.colors.cyan, Modifier.weight(1f))
            Txt("64% · 12.4 MB/s · 02:18 left", color = Skerry.colors.dim, size = 11.sp, font = mono)
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
            Modifier.fillMaxWidth().background(Skerry.colors.panel).padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Sym(icon, size = 16.sp, color = iconColor)
            Txt(label.uppercase(), color = Skerry.colors.faint, size = 11.sp, weight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
            Txt(path, color = Skerry.colors.textBright, size = 11.5.sp, font = mono)
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
            .background(if (entry.selected) Skerry.colors.cyan06 else Color.Transparent)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Sym(entry.icon, size = 17.sp, color = mockFileIconTint(entry.icon))
        Txt(entry.name, color = if (entry.name == "..") Skerry.colors.dim else Skerry.colors.textBright, size = 12.sp, font = mono, modifier = Modifier.weight(1f))
        if (entry.meta.isNotEmpty()) Txt(entry.meta, color = Skerry.colors.faint, size = 11.sp)
    }
}
