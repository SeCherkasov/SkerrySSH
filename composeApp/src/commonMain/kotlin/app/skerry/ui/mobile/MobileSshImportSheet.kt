package app.skerry.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.ssh.SshConfigParseResult
import app.skerry.ui.app.LocalHosts
import app.skerry.ui.app.MobileDesignState
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.host.SshImportCheck
import app.skerry.ui.host.localOsUserName
import app.skerry.ui.host.sshImportHostSummary
import app.skerry.ui.theme.Skerry
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.conn_import_button
import app.skerry.ui.generated.resources.conn_import_empty
import app.skerry.ui.generated.resources.conn_import_select_all
import app.skerry.ui.generated.resources.conn_import_skipped
import app.skerry.ui.generated.resources.conn_import_subtitle
import app.skerry.ui.generated.resources.conn_import_title
import org.jetbrains.compose.resources.stringResource

/**
 * Mobile counterpart of the desktop [app.skerry.ui.host.SshConfigImportModal]: a bottom sheet that
 * previews the hosts parsed from a picked `ssh_config`, lets the user pick which to add, and creates
 * them via [app.skerry.ui.host.HostManagerController.importSshConfig]. Shown while
 * [MobileDesignState.sshImport] is non-null (opened from the More tab).
 */
@Composable
fun MobileSshImportSheet(state: MobileDesignState, result: SshConfigParseResult) {
    val hosts = LocalHosts.current
    val defaultUser = remember { localOsUserName() }
    var selected by remember(result) { mutableStateOf(result.hosts.map { it.alias }.toSet()) }
    val allSelected = result.hosts.isNotEmpty() && selected.size == result.hosts.size

    MobileBottomSheet(
        onDismiss = state::closeSshImport,
        panelModifier = Modifier.fillMaxHeight(0.85f).padding(start = 22.dp, end = 22.dp, bottom = 30.dp),
    ) {
        Txt(stringResource(Res.string.conn_import_title), color = Skerry.colors.text, size = 20.sp, weight = FontWeight.Bold)
        Txt(stringResource(Res.string.conn_import_subtitle), color = Skerry.colors.dim, size = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(14.dp))

        if (result.hosts.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Txt(stringResource(Res.string.conn_import_empty), color = Skerry.colors.dim, size = 14.sp)
            }
        } else {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                        selected = if (allSelected) emptySet() else result.hosts.map { it.alias }.toSet()
                    }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SshImportCheck(allSelected, size = 20.sp)
                Txt(stringResource(Res.string.conn_import_select_all), color = Skerry.colors.dim, size = 13.sp, weight = FontWeight.Medium)
            }
            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
                result.hosts.forEach { host ->
                    val isSelected = host.alias in selected
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isSelected) Skerry.colors.cyan10 else Color.Transparent)
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                                selected = if (isSelected) selected - host.alias else selected + host.alias
                            }
                            .padding(horizontal = 4.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        SshImportCheck(isSelected, size = 20.sp)
                        Column(Modifier.weight(1f)) {
                            Txt(host.alias, color = Skerry.colors.text, size = 15.sp, weight = FontWeight.Medium)
                            Txt(sshImportHostSummary(host), color = Skerry.colors.faint, size = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            if (result.warnings.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Sym("info", size = 15.sp, color = Skerry.colors.faint)
                    Txt(stringResource(Res.string.conn_import_skipped), color = Skerry.colors.faint, size = 12.sp)
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        val enabled = selected.isNotEmpty()
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(if (enabled) Skerry.colors.cyan else Skerry.colors.cyan.copy(alpha = 0.4f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, enabled = enabled) {
                    hosts?.importSshConfig(result.hosts, selected, defaultUser)
                    state.closeSshImport()
                }
                .padding(15.dp),
            contentAlignment = Alignment.Center,
        ) {
            Txt(stringResource(Res.string.conn_import_button, selected.size), color = Skerry.colors.ink, size = 16.sp, weight = FontWeight.Bold)
        }
    }
}
