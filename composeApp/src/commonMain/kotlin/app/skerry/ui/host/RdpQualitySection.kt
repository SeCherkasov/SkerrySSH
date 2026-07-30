package app.skerry.ui.host

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.rdp.RdpImageQuality
import app.skerry.ui.design.DropdownField
import app.skerry.ui.design.Txt
import app.skerry.ui.theme.Skerry
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.conn_image_quality_high_desc
import app.skerry.ui.generated.resources.conn_image_quality_low_desc
import app.skerry.ui.generated.resources.conn_image_quality_medium_desc
import app.skerry.ui.generated.resources.conn_image_quality_note
import app.skerry.ui.generated.resources.vnc_quality_high
import app.skerry.ui.generated.resources.vnc_quality_low
import app.skerry.ui.generated.resources.vnc_quality_medium

/**
 * Image quality of an RDP profile: how much of the remote desktop the server is asked to draw.
 *
 * A profile setting rather than a control beside the live picture — RDP settles this in the Client
 * Info PDU and holds it for the session, so the note under the picker says when the choice lands
 * rather than letting the user wonder why the desktop did not change.
 *
 * Shared by the desktop modal and the mobile sheet, like [RdpAudioSection].
 */
@Composable
fun RdpQualitySection(form: NewConnectionFormState) {
    Column(Modifier.fillMaxWidth()) {
        DropdownField(
            value = form.rdpQuality,
            options = RdpImageQuality.entries,
            label = { it.qualityLabel() },
            onPick = { form.rdpQuality = it },
        )
        Txt(
            form.rdpQuality.qualityDescription(),
            color = Skerry.colors.dim,
            size = 11.sp,
            lineHeight = 15.sp,
            modifier = Modifier.padding(top = 6.dp),
        )
        Txt(
            stringResource(Res.string.conn_image_quality_note),
            color = Skerry.colors.faint,
            size = 11.sp,
            lineHeight = 15.sp,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

/** The level names are the ones the VNC quality menu already uses — same three words. */
@Composable
private fun RdpImageQuality.qualityLabel(): String = stringResource(
    when (this) {
        RdpImageQuality.Low -> Res.string.vnc_quality_low
        RdpImageQuality.Medium -> Res.string.vnc_quality_medium
        RdpImageQuality.High -> Res.string.vnc_quality_high
    },
)

@Composable
private fun RdpImageQuality.qualityDescription(): String = stringResource(
    when (this) {
        RdpImageQuality.Low -> Res.string.conn_image_quality_low_desc
        RdpImageQuality.Medium -> Res.string.conn_image_quality_medium_desc
        RdpImageQuality.High -> Res.string.conn_image_quality_high_desc
    },
)
