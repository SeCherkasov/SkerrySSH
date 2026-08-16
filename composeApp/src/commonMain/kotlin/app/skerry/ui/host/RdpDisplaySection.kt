package app.skerry.ui.host

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.rdp.RdpH264Mode
import app.skerry.ui.design.DropdownField
import app.skerry.ui.design.ToggleRow
import app.skerry.ui.design.Txt
import app.skerry.ui.theme.Skerry
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.conn_rdp_gfx
import app.skerry.ui.generated.resources.conn_rdp_gfx_desc
import app.skerry.ui.generated.resources.conn_rdp_h264
import app.skerry.ui.generated.resources.conn_rdp_h264_auto
import app.skerry.ui.generated.resources.conn_rdp_h264_desc
import app.skerry.ui.generated.resources.conn_rdp_h264_off
import app.skerry.ui.generated.resources.conn_rdp_remotefx
import app.skerry.ui.generated.resources.conn_rdp_remotefx_desc

/**
 * Graphics-path settings of an RDP profile (F-28/F-32): the MS-RDPEGFX pipeline, RemoteFX on the
 * legacy path, and the H.264 ladder the pipeline advertises. Every default is today's behaviour;
 * the switches exist as the escape hatch when one host misbehaves on one path.
 *
 * Shared by the desktop modal and the mobile sheet, like [RdpAudioSection].
 */
@Composable
fun RdpDisplaySection(form: NewConnectionFormState) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ToggleRow(
            label = stringResource(Res.string.conn_rdp_gfx),
            on = form.rdpGraphicsPipeline,
            onToggle = { form.rdpGraphicsPipeline = !form.rdpGraphicsPipeline },
            subtitle = stringResource(Res.string.conn_rdp_gfx_desc),
            subtitleColor = Skerry.colors.dim,
        )
        ToggleRow(
            label = stringResource(Res.string.conn_rdp_remotefx),
            on = form.rdpRemoteFx,
            onToggle = { form.rdpRemoteFx = !form.rdpRemoteFx },
            subtitle = stringResource(Res.string.conn_rdp_remotefx_desc),
            subtitleColor = Skerry.colors.dim,
        )
        Column(Modifier.fillMaxWidth()) {
            Txt(
                stringResource(Res.string.conn_rdp_h264),
                color = Skerry.colors.text,
                size = 12.5.sp,
                weight = FontWeight.Medium,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            DropdownField(
                value = form.rdpH264,
                options = RdpH264Mode.entries,
                label = { it.h264Label() },
                onPick = { form.rdpH264 = it },
            )
            Txt(
                stringResource(Res.string.conn_rdp_h264_desc),
                color = Skerry.colors.dim,
                size = 11.sp,
                lineHeight = 15.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/** AVC profile names are wire vocabulary, not copy; only Auto and Off are words to translate. */
@Composable
private fun RdpH264Mode.h264Label(): String = when (this) {
    RdpH264Mode.Auto -> stringResource(Res.string.conn_rdp_h264_auto)
    RdpH264Mode.Off -> stringResource(Res.string.conn_rdp_h264_off)
    RdpH264Mode.Avc420 -> "AVC 4:2:0"
    RdpH264Mode.Avc444 -> "AVC 4:4:4"
}
