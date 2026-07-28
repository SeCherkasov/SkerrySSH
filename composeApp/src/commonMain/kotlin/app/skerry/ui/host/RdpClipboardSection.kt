package app.skerry.ui.host

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.design.Toggle
import app.skerry.ui.design.Txt
import app.skerry.ui.theme.Skerry
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.conn_clipboard_share
import app.skerry.ui.generated.resources.conn_clipboard_share_desc

/**
 * Clipboard redirection of an RDP profile (MS-RDPECLIP).
 *
 * On because that is what every RDP client does and what a session is expected to feel like, but
 * stated and switchable: the channel carries text both ways for as long as the session is open, so
 * a host that is not trusted with what the user last copied has a way to be told so.
 *
 * Shared by the desktop modal and the mobile sheet, like [RdpAudioSection].
 */
@Composable
fun RdpClipboardSection(form: NewConnectionFormState) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f)) {
            Txt(
                stringResource(Res.string.conn_clipboard_share),
                color = Skerry.colors.text,
                size = 12.5.sp,
                weight = FontWeight.Medium,
            )
            Txt(
                stringResource(Res.string.conn_clipboard_share_desc),
                color = Skerry.colors.dim,
                size = 11.sp,
                lineHeight = 15.sp,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
        Toggle(
            on = form.rdpClipboard,
            onToggle = { form.rdpClipboard = !form.rdpClipboard },
            modifier = Modifier.align(Alignment.CenterVertically),
        )
    }
}
