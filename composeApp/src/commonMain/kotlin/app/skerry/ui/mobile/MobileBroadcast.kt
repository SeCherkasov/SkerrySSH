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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.design.D
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.term_broadcast_clear
import app.skerry.ui.generated.resources.term_broadcast_none
import app.skerry.ui.generated.resources.term_broadcast_placeholder
import app.skerry.ui.generated.resources.term_broadcast_select_all
import app.skerry.ui.generated.resources.term_broadcast_selected
import app.skerry.ui.generated.resources.term_broadcast_send
import app.skerry.ui.generated.resources.term_broadcast_sent
import app.skerry.ui.generated.resources.term_broadcast_subtitle
import app.skerry.ui.generated.resources.term_broadcast_title
import app.skerry.ui.session.BroadcastController
import app.skerry.ui.session.BroadcastTarget
import org.jetbrains.compose.resources.stringResource

/**
 * Broadcast on mobile: the desktop panel ([app.skerry.ui.session.BroadcastPanel]) as a bottom sheet
 * over the same [BroadcastController]. Sending executes the command on every picked session — the
 * checklist above the field is the confirmation step.
 */
@Composable
internal fun MobileBroadcastSheet(
    controller: BroadcastController,
    targets: List<BroadcastTarget>,
    onDismiss: () -> Unit,
) {
    val mono = LocalFonts.current.mono
    var command by remember { mutableStateOf("") }
    var lastSentTo by remember { mutableStateOf<Int?>(null) }
    val selected = controller.selectedCount(targets)

    MobileBottomSheet(onDismiss = onDismiss, maxHeightFraction = 0.8f) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Txt(stringResource(Res.string.term_broadcast_title), color = D.text, size = 18.sp, weight = FontWeight.Bold)
            Txt(stringResource(Res.string.term_broadcast_subtitle), color = D.faint, size = 12.sp)

            if (targets.isEmpty()) {
                Txt(stringResource(Res.string.term_broadcast_none), color = D.faint, size = 13.sp)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    MobileSheetButton(
                        label = stringResource(Res.string.term_broadcast_select_all),
                        onClick = { controller.selectAll(targets) },
                        icon = "select_all",
                        filled = false,
                    )
                    MobileSheetButton(
                        label = stringResource(Res.string.term_broadcast_clear),
                        onClick = controller::clear,
                        icon = "backspace",
                        filled = false,
                    )
                }
                Txt(
                    stringResource(Res.string.term_broadcast_selected, selected.toString(), targets.size.toString()),
                    color = D.faint, size = 11.5.sp,
                )
                targets.forEach { target ->
                    key(target.id) {
                        val on = controller.isSelected(target.id)
                        val onClick = remember(target.id) { { controller.toggle(target.id) } }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (on) D.cyan.copy(alpha = 0.10f) else Color.Transparent)
                                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
                                .padding(horizontal = 10.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                                Sym(if (on) "check_box" else "check_box_outline_blank", size = 18.sp, color = if (on) D.cyanBright else D.faint)
                            }
                            Txt(target.label, color = if (on) D.text else D.dim, size = 13.sp, font = mono)
                        }
                    }
                }
                MobileFormInput(command, { command = it }, stringResource(Res.string.term_broadcast_placeholder))
                lastSentTo?.let { Txt(stringResource(Res.string.term_broadcast_sent, it.toString()), color = D.cyanBright, size = 11.5.sp) }
                MobileSheetButton(
                    label = stringResource(Res.string.term_broadcast_send),
                    onClick = {
                        val delivered = controller.send(command, targets)
                        if (delivered > 0) {
                            lastSentTo = delivered
                            command = ""
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    icon = "send",
                    filled = true,
                )
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}
