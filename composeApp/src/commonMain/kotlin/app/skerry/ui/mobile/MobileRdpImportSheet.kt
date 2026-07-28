package app.skerry.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.rdp.RdpFileImportResult
import app.skerry.ui.app.LocalHosts
import app.skerry.ui.app.MobileDesignState
import app.skerry.ui.app.MobileTab
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.host.rdpImportSummary
import app.skerry.ui.theme.Skerry
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.conn_rdp_import_button
import app.skerry.ui.generated.resources.conn_rdp_import_empty
import app.skerry.ui.generated.resources.conn_rdp_import_farm
import app.skerry.ui.generated.resources.conn_rdp_import_gateway
import app.skerry.ui.generated.resources.conn_rdp_import_subtitle
import app.skerry.ui.generated.resources.conn_rdp_import_title
import org.jetbrains.compose.resources.stringResource

/**
 * Mobile counterpart of [app.skerry.ui.host.RdpFileImportModal]: a bottom sheet previewing the
 * profile read from a picked `.rdp` file and creating it via
 * [app.skerry.ui.host.HostManagerController.importRdpFile]. Shown while [MobileDesignState.rdpImport]
 * is non-null (opened from the More tab).
 */
@Composable
fun MobileRdpImportSheet(state: MobileDesignState, result: RdpFileImportResult) {
    val hosts = LocalHosts.current
    val entry = result.host

    MobileBottomSheet(
        onDismiss = state::closeRdpImport,
        panelModifier = Modifier.padding(start = 22.dp, end = 22.dp, bottom = 30.dp),
    ) {
        Txt(stringResource(Res.string.conn_rdp_import_title), color = Skerry.colors.text, size = 20.sp, weight = FontWeight.Bold)
        Txt(stringResource(Res.string.conn_rdp_import_subtitle), color = Skerry.colors.dim, size = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(14.dp))

        if (entry == null) {
            Txt(stringResource(Res.string.conn_rdp_import_empty), color = Skerry.colors.dim, size = 14.sp)
        } else {
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Txt(entry.label, color = Skerry.colors.text, size = 15.sp, weight = FontWeight.Medium)
                Txt(
                    rdpImportSummary(entry),
                    color = Skerry.colors.faint,
                    size = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp),
                )
                if (entry.loadBalanceInfo.isNotBlank()) {
                    Txt(
                        stringResource(Res.string.conn_rdp_import_farm, entry.loadBalanceInfo),
                        color = Skerry.colors.faint,
                        size = 11.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
            }
            result.warnings.forEach { warning ->
                Row(
                    Modifier.fillMaxWidth().padding(top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Sym("warning", size = 15.sp, color = Skerry.colors.sunset)
                    Txt(
                        app.skerry.ui.host.rdpImportWarningText(warning),
                        color = Skerry.colors.sunset,
                        size = 12.sp,
                        lineHeight = 16.sp,
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        val enabled = entry != null && hosts != null
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(if (enabled) Skerry.colors.cyan else Skerry.colors.cyan.copy(alpha = 0.4f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, enabled = enabled) {
                    if (entry != null) {
                        hosts?.importRdpFile(entry)
                        // The new profile lives in the desktops tab; landing there is what shows it.
                        state.select(MobileTab.Desktops)
                    }
                    state.closeRdpImport()
                }
                .padding(15.dp),
            contentAlignment = Alignment.Center,
        ) {
            Txt(stringResource(Res.string.conn_rdp_import_button), color = Skerry.colors.ink, size = 16.sp, weight = FontWeight.Bold)
        }
    }
}
