package app.skerry.ui.host

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.rdp.RdpClientSettings
import app.skerry.ui.design.DropdownField
import app.skerry.ui.design.FormField
import app.skerry.ui.design.ModalTextField
import app.skerry.ui.design.Txt
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.conn_rdp_color_depth
import app.skerry.ui.generated.resources.conn_rdp_color_depth_auto
import app.skerry.ui.generated.resources.conn_rdp_color_depth_bits
import app.skerry.ui.generated.resources.conn_rdp_color_depth_note
import app.skerry.ui.generated.resources.conn_rdp_background
import app.skerry.ui.generated.resources.conn_rdp_background_hint
import app.skerry.ui.generated.resources.conn_rdp_background_note
import app.skerry.ui.generated.resources.conn_rdp_background_invalid
import app.skerry.ui.theme.Skerry
import org.jetbrains.compose.resources.stringResource

/** Profile color choices shared by the desktop modal and Android sheet. */
@Composable
internal fun RdpColorSection(form: NewConnectionFormState) {
    FormField(stringResource(Res.string.conn_rdp_color_depth), top = 0.dp) {
        DropdownField(
            value = form.rdpColorDepth,
            options = listOf(null) + RdpClientSettings.COLOR_DEPTHS,
            label = { bits ->
                if (bits == null) stringResource(Res.string.conn_rdp_color_depth_auto)
                else stringResource(Res.string.conn_rdp_color_depth_bits, bits)
            },
            onPick = { form.rdpColorDepth = it },
        )
    }
    Txt(stringResource(Res.string.conn_rdp_color_depth_note), color = Skerry.colors.dim, size = 11.sp)
    FormField(stringResource(Res.string.conn_rdp_background), top = 0.dp) {
        ModalTextField(
            value = form.rdpBackgroundHex,
            onValueChange = { form.rdpBackgroundHex = it },
            placeholder = stringResource(Res.string.conn_rdp_background_hint),
            trailing = {
                val color = form.rdpBackgroundRgb?.let { Color(it or (255 shl 24)) } ?: Color.Black
                Box(Modifier.size(20.dp).background(color).border(1.dp, Skerry.colors.dim))
            },
        )
    }
    Txt(
        stringResource(if (form.rdpBackgroundInvalid) Res.string.conn_rdp_background_invalid
            else Res.string.conn_rdp_background_note),
        color = if (form.rdpBackgroundInvalid) Skerry.colors.sunset else Skerry.colors.dim,
        size = 11.sp,
    )
}
