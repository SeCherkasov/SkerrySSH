package app.skerry.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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

/** SFTP view: заголовок + две панели (Local/Remote) + очередь передачи. */
@Composable
fun SftpView() {
    val mono = LocalFonts.current.mono
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
            FilePane("computer", D.dim, "Local", "~/projects", LOCAL_FILES, mono, Modifier.weight(1f))
            VLine(D.line)
            FilePane("dns", D.moss, "Remote", "/var/www", REMOTE_FILES, mono, Modifier.weight(1f))
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
