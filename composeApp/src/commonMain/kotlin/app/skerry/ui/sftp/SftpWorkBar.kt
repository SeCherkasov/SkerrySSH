package app.skerry.ui.sftp

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.app.LocalSftpPrefs
import app.skerry.ui.app.SftpPrefs
import app.skerry.ui.design.AnchoredDropdown
import app.skerry.ui.design.IconBtn
import app.skerry.ui.design.ToggleRow
import app.skerry.ui.design.Txt
import app.skerry.ui.design.labelUppercase
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.sftp_col_modified
import app.skerry.ui.generated.resources.sftp_col_permissions
import app.skerry.ui.generated.resources.sftp_columns
import app.skerry.ui.generated.resources.sftp_new_folder
import app.skerry.ui.generated.resources.sftp_tip_back
import app.skerry.ui.generated.resources.sftp_tip_download
import app.skerry.ui.generated.resources.sftp_tip_filter
import app.skerry.ui.generated.resources.sftp_tip_refresh
import app.skerry.ui.generated.resources.sftp_tip_upload
import app.skerry.ui.terminal.WorkBar
import app.skerry.ui.terminal.WorkBarLabel
import app.skerry.ui.terminal.WorkBarLeading
import app.skerry.ui.theme.Skerry
import org.jetbrains.compose.resources.stringResource

/**
 * The bar above the SFTP work area — the same strip the terminal uses, so the two views read as one
 * window: the tab on the left ([label]), what can be done to it on the right ([actions]). The host
 * is never picked from here (the pane already holds a live connection), hence no picker.
 *
 * The leading chevron goes back to the terminal ([onBack], the same thing F10 does) rather than
 * toggling the hosts sidebar: this screen fills the whole work area and shows no sidebar, so the
 * toggle would move nothing on screen.
 */
@Composable
internal fun SftpWorkBar(
    label: WorkBarLabel?,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit,
) {
    WorkBar(
        label = label,
        tabKey = null,
        leading = WorkBarLeading.back(Res.string.sftp_tip_back, onBack),
        onPickHost = null,
        actions = actions,
    )
}

/**
 * The bar's action row: refresh both panes, create a directory, open the quick filter, choose the
 * listing columns, transfer the active pane's selection. Each one is an F-key of the bottom bar —
 * the bar names them, the icons are the reachable-by-mouse half of the same commands. The transfer
 * icon follows the active pane's direction: local active means the bytes go up, remote — down.
 */
@Composable
internal fun SftpWorkBarActions(
    localActive: Boolean,
    enabled: Boolean,
    onRefresh: () -> Unit,
    onNewFolder: () -> Unit,
    onFilter: () -> Unit,
    onTransfer: () -> Unit,
) {
    IconBtn("refresh", onClick = onRefresh, box = 26, tooltip = stringResource(Res.string.sftp_tip_refresh), enabled = enabled)
    IconBtn("create_new_folder", onClick = onNewFolder, box = 26, tooltip = stringResource(Res.string.sftp_new_folder), enabled = enabled)
    IconBtn("filter_alt", onClick = onFilter, box = 26, tooltip = stringResource(Res.string.sftp_tip_filter), enabled = enabled)
    ColumnsMenu(LocalSftpPrefs.current)
    IconBtn(
        if (localActive) "upload" else "download",
        onClick = onTransfer,
        box = 26,
        tooltip = stringResource(if (localActive) Res.string.sftp_tip_upload else Res.string.sftp_tip_download),
        enabled = enabled,
    )
}

/**
 * Listing column visibility popup ("view_column" in the header): toggles for the modified-date and
 * permissions columns. One setting for both panes (like show-hidden), persisted via [SftpPrefs].
 */
@Composable
private fun ColumnsMenu(prefs: SftpPrefs) {
    var open by remember { mutableStateOf(false) }
    AnchoredDropdown(
        expanded = open,
        onDismiss = { open = false },
        trigger = {
            IconBtn(
                "view_column",
                onClick = { open = !open },
                box = 26,
                icon = 16.sp,
                tooltip = stringResource(Res.string.sftp_columns),
            )
        },
    ) { _ ->
        Column(
            Modifier
                .width(220.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Skerry.colors.surface2)
                .border(1.dp, Skerry.colors.lineStrong, RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Txt(
                labelUppercase(stringResource(Res.string.sftp_columns)),
                color = Skerry.colors.faint,
                size = 10.sp,
                weight = FontWeight.SemiBold,
                letterSpacing = 0.5.sp,
            )
            ToggleRow(stringResource(Res.string.sftp_col_modified), prefs.showModified, onToggle = { prefs.setShowModified(!prefs.showModified) })
            ToggleRow(stringResource(Res.string.sftp_col_permissions), prefs.showPermissions, onToggle = { prefs.setShowPermissions(!prefs.showPermissions) })
        }
    }
}
