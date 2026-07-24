package app.skerry.ui.host

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.ssh.SshConfigParseResult
import app.skerry.ui.app.DesktopDesignState
import app.skerry.ui.app.LocalHosts
import app.skerry.ui.design.CancelButton
import app.skerry.ui.design.HLine
import app.skerry.ui.design.IconBtn
import app.skerry.ui.design.ModalScrim
import app.skerry.ui.design.PrimaryButton
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.design.consumeClicks
import app.skerry.ui.theme.Skerry
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.conn_cancel
import app.skerry.ui.generated.resources.conn_import_button
import app.skerry.ui.generated.resources.conn_import_empty
import app.skerry.ui.generated.resources.conn_import_select_all
import app.skerry.ui.generated.resources.conn_import_skipped
import app.skerry.ui.generated.resources.conn_import_subtitle
import app.skerry.ui.generated.resources.conn_import_title
import org.jetbrains.compose.resources.stringResource

/**
 * Preview-and-select modal for importing hosts parsed from an `ssh_config` file. Shows every parsed
 * host with a checkbox (all selected by default), a select-all toggle, and any parser warnings, then
 * creates the chosen profiles via [HostManagerController.importSshConfig]. Rendered at the desktop
 * app root while [DesktopDesignState.sshImport] is non-null; the picking/parsing happens beforehand
 * (see [pickAndParseSshConfig]) so this modal only handles selection.
 */
@Composable
fun SshConfigImportModal(state: DesktopDesignState, result: SshConfigParseResult) {
    val hosts = LocalHosts.current
    val defaultUser = remember { localOsUserName() }
    var selected by remember(result) { mutableStateOf(result.hosts.map { it.alias }.toSet()) }
    val allSelected = result.hosts.isNotEmpty() && selected.size == result.hosts.size

    ModalScrim(onDismiss = state::closeSshImport) {
        Column(
            Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .padding(20.dp)
                .heightIn(max = 680.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Skerry.colors.surfaceDeep)
                .border(1.dp, Skerry.colors.cyan14, RoundedCornerShape(12.dp))
                .consumeClicks(),
        ) {
            Box(Modifier.fillMaxWidth().padding(start = 26.dp, end = 26.dp, top = 22.dp, bottom = 14.dp)) {
                Column {
                    Txt(stringResource(Res.string.conn_import_title), color = Skerry.colors.text, size = 18.sp, weight = FontWeight.SemiBold, letterSpacing = (-0.2).sp)
                    Txt(stringResource(Res.string.conn_import_subtitle), color = Skerry.colors.dim, size = 12.5.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 6.dp))
                }
                IconBtn("close", onClick = state::closeSshImport, modifier = Modifier.align(Alignment.TopEnd))
            }
            HLine()

            if (result.hosts.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 26.dp, vertical = 40.dp), contentAlignment = Alignment.Center) {
                    Txt(stringResource(Res.string.conn_import_empty), color = Skerry.colors.dim, size = 13.sp)
                }
            } else {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { selected = if (allSelected) emptySet() else result.hosts.map { it.alias }.toSet() }
                        .padding(horizontal = 26.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SshImportCheck(allSelected)
                    Txt(stringResource(Res.string.conn_import_select_all), color = Skerry.colors.dim, size = 12.sp, weight = FontWeight.Medium)
                }
                HLine()
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    result.hosts.forEach { host ->
                        val isSelected = host.alias in selected
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { selected = if (isSelected) selected - host.alias else selected + host.alias }
                                .background(if (isSelected) Skerry.colors.cyan10 else androidx.compose.ui.graphics.Color.Transparent)
                                .padding(horizontal = 26.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            SshImportCheck(isSelected)
                            Column(Modifier.weight(1f)) {
                                Txt(host.alias, color = Skerry.colors.text, size = 13.sp, weight = FontWeight.Medium)
                                Txt(sshImportHostSummary(host), color = Skerry.colors.faint, size = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                if (result.warnings.isNotEmpty()) {
                    HLine()
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 26.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Sym("info", size = 14.sp, color = Skerry.colors.faint)
                        Txt(stringResource(Res.string.conn_import_skipped), color = Skerry.colors.faint, size = 11.5.sp)
                    }
                }
            }

            HLine()
            Row(
                Modifier.fillMaxWidth().background(Skerry.colors.shade15).padding(horizontal = 26.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Spacer(Modifier.weight(1f))
                CancelButton(stringResource(Res.string.conn_cancel), onClick = state::closeSshImport)
                PrimaryButton(
                    stringResource(Res.string.conn_import_button, selected.size),
                    onClick = {
                        hosts?.importSshConfig(result.hosts, selected, defaultUser)
                        state.closeSshImport()
                    },
                    icon = "download",
                    enabled = selected.isNotEmpty(),
                )
            }
        }
    }
}
