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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.sftp_no_session
import app.skerry.ui.generated.resources.sftp_no_session_hint
import app.skerry.ui.generated.resources.sftp_pane_local
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.design.HLine
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.design.VLine
import app.skerry.ui.theme.Skerry
import app.skerry.ui.design.Badge
import app.skerry.ui.files.TransferStatus
import app.skerry.ui.files.TransferEntry
import app.skerry.ui.session.SessionStatus
import app.skerry.ui.terminal.WorkBarLabel
import app.skerry.ui.generated.resources.sftp_wbar_subtitle

private data class FileEntry(
    val icon: String,
    val name: String,
    val size: String = NO_SIZE,
    val modified: String = "",
    val permissions: String = "",
    val selected: Boolean = false,
)

/** Host the static preview pretends to be connected to (same one the design mockups use). */
private const val MOCK_HOST = "prod-web-01"

private const val MOCK_REMOTE_PATH = "/var/www"

private val MOCK_TRANSFERS = listOf(
    TransferEntry(
        id = 1,
        direction = TransferDirection.Upload,
        name = "release-0.2.1.tar.gz",
        fileIndex = 1,
        fileCount = 1,
        transferred = 7_444_889,
        total = 11_744_051,
        bytesDone = 7_444_889,
        elapsedMillis = 1_460,
        status = TransferStatus.Active,
    ),
    TransferEntry(
        id = 2,
        direction = TransferDirection.Download,
        name = "nginx.conf",
        fileIndex = 1,
        fileCount = 1,
        transferred = 2_867,
        total = 2_867,
        bytesDone = 2_867,
        elapsedMillis = 400,
        status = TransferStatus.Done,
    ),
)

private val LOCAL_FILES = listOf(
    FileEntry("folder", ".."),
    FileEntry("folder", "skerry-app", modified = "Jun 21 09:14", permissions = "drwxr-xr-x"),
    FileEntry("folder", "deploy-scripts", modified = "Jun 18 22:40", permissions = "drwxr-xr-x"),
    FileEntry("description", "docker-compose.yml", size = "2.4 KB", modified = "Jun 18 22:40", permissions = "-rw-r--r--"),
    FileEntry("key", "id_ed25519.pub", size = "96 B", modified = "May 04 11:02", permissions = "-rw-r--r--"),
    FileEntry("archive", "backup.tar.gz", size = "418 MB", modified = "Jun 20 03:15", permissions = "-rw-r--r--"),
)

private val REMOTE_FILES = listOf(
    FileEntry("folder", ".."),
    FileEntry("folder", "html", modified = "Jun 21 08:40", permissions = "drwxr-xr-x"),
    FileEntry("folder", "releases", modified = "Jun 20 19:12", permissions = "drwxr-xr-x"),
    FileEntry("description", "nginx.conf", size = "3.1 KB", modified = "Jun 18 08:41", permissions = "-rw-r--r--", selected = true),
    FileEntry("description", "robots.txt", size = "112 B", modified = "Jun 02 10:00", permissions = "-rw-r--r--"),
    FileEntry("terminal", "deploy.sh", size = "1.8 KB", modified = "Jun 18 08:41", permissions = "-rwxr-xr-x"),
)

/** Mock row icon tint, matching the live listing: folders cyan, the parent row and files faint. */
@Composable
private fun mockFileIconTint(entry: FileEntry): Color =
    if (entry.icon == "folder" && entry.name != "..") Skerry.colors.cyanBright else Skerry.colors.faint

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
            MockPane(
                "computer",
                Skerry.colors.dim,
                stringResource(Res.string.sftp_pane_local),
                false,
                "~/projects",
                LOCAL_FILES,
                mono,
                active = true,
                modifier = Modifier.weight(1f),
            )
            VLine(Skerry.colors.line)
            MockPane(
                "dns",
                Skerry.colors.moss,
                MOCK_HOST,
                true,
                MOCK_REMOTE_PATH,
                REMOTE_FILES,
                mono,
                active = false,
                modifier = Modifier.weight(1f),
            )
        }
        // The same strip the live screen uses, over static entries: the design render must show
        // what ships, not a second hand-drawn progress bar.
        TransferQueueStrip(MOCK_TRANSFERS, mono, onDismiss = {})
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
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxHeight()) {
        Row(
            Modifier.fillMaxWidth().background(Skerry.colors.surface).padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Only one pane is ever focused; the live header greys the other one's glyph out.
            Sym(icon, size = 16.sp, color = if (active) iconColor else Skerry.colors.faint)
            Txt(
                path,
                color = Skerry.colors.textBright,
                size = 11.5.sp,
                font = mono,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
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
        ColumnHeaderRow(showModified = true, showPermissions = true)
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
            .listingRowHairline()
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        FileRowIcon(entry.icon, mockFileIconTint(entry))
        FileRowName(entry.name, entry.selected, entry.icon == "folder", mono, Modifier.weight(1f))
        FileRowColumnCells(
            FileRowColumns(permissions = entry.permissions, modified = entry.modified, size = entry.size),
            mono,
        )
    }
}
