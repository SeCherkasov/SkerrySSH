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
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.sftp_no_session
import app.skerry.ui.generated.resources.sftp_no_session_hint
import app.skerry.ui.generated.resources.sftp_pane_local
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.design.HLine
import app.skerry.ui.design.MeterBar
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.design.VLine
import app.skerry.ui.theme.Skerry
import app.skerry.ui.design.Badge
import app.skerry.ui.session.SessionStatus
import app.skerry.ui.terminal.WorkBarLabel
import app.skerry.ui.generated.resources.sftp_wbar_subtitle

private data class FileEntry(val icon: String, val name: String, val meta: String, val selected: Boolean = false)

/** Host the static preview pretends to be connected to (same one the design mockups use). */
private const val MOCK_HOST = "prod-web-01"

private const val MOCK_REMOTE_PATH = "/var/www"

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

/** Mock row icon tint by icon kind, from the active theme (folders — cyan, files — faint). */
@Composable
private fun mockFileIconTint(icon: String): Color = when (icon) {
    "folder" -> Skerry.colors.cyanBright
    else -> Skerry.colors.faint
}

/** A live session exists but isn't connected: bar + notice. */
@Composable
internal fun NoSessionSftpView(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Skerry.colors.bg)) {
        // No connection, hence no title and no actions: the bar carries the way back only.
        SftpWorkBar(label = null, onBack = onBack) {}
        Box(Modifier.weight(1f).fillMaxWidth()) {
            PaneNotice("cloud_off", stringResource(Res.string.sftp_no_session), stringResource(Res.string.sftp_no_session_hint), Skerry.colors.faint)
        }
    }
}

/** Static mock (offscreen render/preview without a session backend). */
@Composable
internal fun MockSftpView(mono: FontFamily) {
    Column(Modifier.fillMaxSize().background(Skerry.colors.bg)) {
        SftpWorkBar(
            label = WorkBarLabel.Solo(MOCK_HOST, stringResource(Res.string.sftp_wbar_subtitle, MOCK_REMOTE_PATH), SessionStatus.Live),
            onBack = {},
        ) {
            SftpWorkBarActions(
                localActive = true,
                enabled = false,
                onRefresh = {},
                onNewFolder = {},
                onFilter = {},
                onTransfer = {},
            )
        }
        Row(Modifier.weight(1f).fillMaxWidth()) {
            MockPane("computer", Skerry.colors.dim, stringResource(Res.string.sftp_pane_local), false, "~/projects", LOCAL_FILES, mono, Modifier.weight(1f))
            VLine(Skerry.colors.line)
            MockPane("dns", Skerry.colors.moss, MOCK_HOST, true, MOCK_REMOTE_PATH, REMOTE_FILES, mono, Modifier.weight(1f))
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
    badge: String,
    badgeAccent: Boolean,
    path: String,
    files: List<FileEntry>,
    mono: FontFamily,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxHeight()) {
        Row(
            Modifier.fillMaxWidth().background(Skerry.colors.surface).padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Sym(icon, size = 16.sp, color = iconColor)
            Txt(path, color = Skerry.colors.textBright, size = 11.5.sp, font = mono, modifier = Modifier.weight(1f))
            Badge(
                badge,
                bg = if (badgeAccent) Skerry.colors.cyan14 else Skerry.colors.overlayMed,
                fg = if (badgeAccent) Skerry.colors.cyanBright else Skerry.colors.dim,
                radius = 6,
                size = 10.sp,
            )
        }
        HLine()
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            files.forEach { MockRow(it, mono) }
        }
    }
}

@Composable
private fun MockRow(entry: FileEntry, mono: FontFamily) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (entry.selected) Skerry.colors.cyan06 else Color.Transparent)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Sym(entry.icon, size = 16.sp, color = mockFileIconTint(entry.icon))
        Txt(
            entry.name,
            color = when {
                entry.name == ".." -> Skerry.colors.dim
                entry.icon == "folder" -> Skerry.colors.cyanBright
                else -> Skerry.colors.text
            },
            size = 12.sp,
            font = mono,
            modifier = Modifier.weight(1f),
        )
        if (entry.meta.isNotEmpty()) Txt(entry.meta, color = Skerry.colors.faint, size = 11.sp, font = mono)
    }
}
