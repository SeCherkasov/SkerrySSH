package app.skerry.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.shell_cancel
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.theme.Skerry
import androidx.compose.ui.platform.testTag
import app.skerry.ui.app.UiTags

/**
 * Confirmation dialog for a destructive action (disconnect session, close split panel, delete
 * tunnel): scrim + card, title + message + Cancel/[confirmLabel]. Same visual language as
 * [DesktopDeleteHostDialog]/[DesktopPasswordDialog]; confirm button defaults to [Skerry.colors.sunset].
 * [onDismiss] fires on Cancel or Esc (not on a click outside the card).
 *
 * Rendered in a [Popup] over the whole window, the way the group dialog inside the connection modal
 * is: a caller deep in a scrolled column (a settings card) would otherwise get a scrim the size of
 * whatever box it sits in, dimming a strip of the screen and centering the card in it. That makes it
 * its own focusable layer — [ModalPresence] keeps the Esc order straight, but a call site that opens
 * this over another Popup-based modal stacks two of them and is worth checking by hand.
 */
@Composable
fun ConfirmActionDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmColor: Color = Skerry.colors.sunset,
) {
    Popup(alignment = Alignment.Center, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        ModalScrim(onDismiss = onDismiss) {
            Column(
                Modifier
                    .widthIn(max = 420.dp)
                    .fillMaxWidth()
                    .padding(20.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Skerry.colors.surfaceDeep)
                    .border(1.dp, Skerry.colors.cyan14, RoundedCornerShape(12.dp))
                    .consumeClicks()
                    .padding(26.dp),
            ) {
                Txt(title, color = Skerry.colors.text, size = 16.sp, weight = FontWeight.SemiBold, letterSpacing = (-0.2).sp)
                Txt(message, color = Skerry.colors.dim, size = 12.5.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 10.dp))
                Row(
                    Modifier.fillMaxWidth().padding(top = 18.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CancelButton(stringResource(Res.string.shell_cancel), onClick = onDismiss, modifier = Modifier.testTag(UiTags.FORM_CANCEL))
                    PrimaryButton(confirmLabel, onClick = onConfirm, bg = confirmColor, fg = Skerry.colors.sunsetInk, modifier = Modifier.testTag(UiTags.FORM_SAVE))
                }
            }
        }
    }
}
