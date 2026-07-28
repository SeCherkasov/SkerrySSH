package app.skerry.ui.host

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.rdp.RdpFileImportResult
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
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.conn_cancel
import app.skerry.ui.generated.resources.conn_rdp_import_button
import app.skerry.ui.generated.resources.conn_rdp_import_empty
import app.skerry.ui.generated.resources.conn_rdp_import_farm
import app.skerry.ui.generated.resources.conn_rdp_import_gateway
import app.skerry.ui.generated.resources.conn_rdp_import_subtitle
import app.skerry.ui.generated.resources.conn_rdp_import_title
import app.skerry.ui.theme.Skerry
import org.jetbrains.compose.resources.stringResource

/**
 * Preview-and-confirm modal for importing a `.rdp` file. One file describes one connection, so
 * unlike the ssh_config import there is nothing to select — the modal shows what the file will
 * create and what it dropped, then persists it via [HostManagerController.importRdpFile]. Rendered
 * at the desktop app root while [DesktopDesignState.rdpImport] is non-null; picking and parsing
 * happen beforehand (see [pickAndParseRdpFile]).
 */
@Composable
fun RdpFileImportModal(state: DesktopDesignState, result: RdpFileImportResult) {
    val hosts = LocalHosts.current
    val entry = result.host

    ModalScrim(onDismiss = state::closeRdpImport) {
        Column(
            Modifier
                .widthIn(max = 460.dp)
                .fillMaxWidth()
                .padding(20.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Skerry.colors.surfaceDeep)
                .border(1.dp, Skerry.colors.cyan14, RoundedCornerShape(12.dp))
                .consumeClicks(),
        ) {
            Box(Modifier.fillMaxWidth().padding(start = 26.dp, end = 26.dp, top = 22.dp, bottom = 14.dp)) {
                Column {
                    Txt(stringResource(Res.string.conn_rdp_import_title), color = Skerry.colors.text, size = 18.sp, weight = FontWeight.SemiBold, letterSpacing = (-0.2).sp)
                    Txt(stringResource(Res.string.conn_rdp_import_subtitle), color = Skerry.colors.dim, size = 12.5.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 6.dp))
                }
                IconBtn("close", onClick = state::closeRdpImport, modifier = Modifier.align(Alignment.TopEnd))
            }
            HLine()

            if (entry == null) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 26.dp, vertical = 32.dp), contentAlignment = Alignment.Center) {
                    Txt(stringResource(Res.string.conn_rdp_import_empty), color = Skerry.colors.dim, size = 13.sp)
                }
            } else {
                Column(Modifier.fillMaxWidth().padding(horizontal = 26.dp, vertical = 14.dp)) {
                    Txt(entry.label, color = Skerry.colors.text, size = 13.5.sp, weight = FontWeight.Medium)
                    Txt(
                        rdpImportSummary(entry),
                        color = Skerry.colors.faint,
                        size = 11.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                    // A farm token decides which host of a collection answers, so it is worth
                    // showing: the profile connects somewhere else without it.
                    if (entry.loadBalanceInfo.isNotBlank()) {
                        Txt(
                            stringResource(Res.string.conn_rdp_import_farm, entry.loadBalanceInfo),
                            color = Skerry.colors.faint,
                            size = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                }
                // Everything the import could not carry over, not only the gateway: a port that was
                // silently replaced is exactly the kind of change worth saying out loud.
                if (result.warnings.isNotEmpty()) {
                    HLine()
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 26.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        result.warnings.forEach { warning ->
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Sym("warning", size = 14.sp, color = Skerry.colors.sunset)
                                Txt(rdpImportWarningText(warning), color = Skerry.colors.sunset, size = 11.5.sp, lineHeight = 16.sp)
                            }
                        }
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
                CancelButton(stringResource(Res.string.conn_cancel), onClick = state::closeRdpImport)
                PrimaryButton(
                    stringResource(Res.string.conn_rdp_import_button),
                    onClick = {
                        if (entry != null) {
                            hosts?.importRdpFile(entry)
                            state.showSection(HostSection.RemoteDesktops)
                        }
                        state.closeRdpImport()
                    },
                    icon = "download",
                    enabled = entry != null && hosts != null,
                )
            }
        }
    }
}
