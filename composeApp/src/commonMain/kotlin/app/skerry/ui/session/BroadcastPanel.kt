package app.skerry.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.connection.ConnectionUiState
import app.skerry.ui.design.ChipButton
import app.skerry.ui.design.D
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.ModalScrim
import app.skerry.ui.design.PrimaryButton
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.design.consumeClicks
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
import org.jetbrains.compose.resources.stringResource

/**
 * Every connected terminal a broadcast can reach: top-level tabs and their split panes, VNC tabs
 * excluded (no shell to type into). Commands go through
 * [app.skerry.ui.terminal.TerminalScreenState.typeInput] rather than a raw send, so a broadcast
 * lands in each host's own command history like anything else the user typed.
 */
internal fun broadcastTargets(sessions: SessionsController?): List<BroadcastTarget> =
    sessions?.sessions.orEmpty().flatMap { session ->
        listOfNotNull(session, session.splitSession).mapNotNull { candidate ->
            val terminal = (candidate.controller.uiState as? ConnectionUiState.Connected)?.terminal
            terminal?.let {
                BroadcastTarget(
                    id = candidate.id,
                    label = candidate.displayTitle.ifBlank { candidate.subtitle },
                    send = { text -> it.typeInput(text) },
                )
            }
        }
    }

/**
 * Broadcast panel (⌘B / Ctrl+Shift+B): pick sessions, type once, run everywhere — the csshX /
 * tmux `synchronize-panes` case. Unlike the command palette this *does* execute: broadcasting is
 * the whole point, and the picker above the field is the confirmation step.
 */
@Composable
internal fun BroadcastPanel(
    controller: BroadcastController,
    targets: List<BroadcastTarget>,
    onDismiss: () -> Unit,
) {
    val mono = LocalFonts.current.mono
    var command by remember { mutableStateOf("") }
    var lastSentTo by remember { mutableStateOf<Int?>(null) }
    val inputFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { inputFocus.requestFocus() }
    val selected = controller.selectedCount(targets)

    val submit = {
        val delivered = controller.send(command, targets)
        if (delivered > 0) {
            lastSentTo = delivered
            command = ""
        }
    }

    ModalScrim(onDismiss = onDismiss, contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .padding(top = 90.dp)
                .width(520.dp)
                .consumeClicks()
                .clip(RoundedCornerShape(10.dp))
                .background(D.surface2)
                .border(1.dp, D.lineStrong, RoundedCornerShape(10.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column {
                Txt(stringResource(Res.string.term_broadcast_title), color = D.textBright, size = 15.sp, weight = FontWeight.SemiBold)
                Txt(stringResource(Res.string.term_broadcast_subtitle), color = D.faint, size = 11.sp)
            }

            if (targets.isEmpty()) {
                Txt(stringResource(Res.string.term_broadcast_none), color = D.faint, size = 12.sp, font = mono)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    ChipButton(stringResource(Res.string.term_broadcast_select_all), color = D.cyan, size = 11.sp, verticalPadding = 4.dp, onClick = { controller.selectAll(targets) })
                    ChipButton(stringResource(Res.string.term_broadcast_clear), color = D.dim, size = 11.sp, verticalPadding = 4.dp, onClick = controller::clear)
                    Txt(
                        stringResource(Res.string.term_broadcast_selected, selected.toString(), targets.size.toString()),
                        color = D.faint, size = 11.sp, modifier = Modifier.padding(start = 4.dp),
                    )
                }
                Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    targets.forEach { target ->
                        key(target.id) {
                            TargetRow(target.label, controller.isSelected(target.id), mono) { controller.toggle(target.id) }
                        }
                    }
                }
                CommandField(command, { command = it }, mono, inputFocus, submit)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    lastSentTo?.let {
                        Txt(stringResource(Res.string.term_broadcast_sent, it.toString()), color = D.cyanBright, size = 11.sp, modifier = Modifier.weight(1f))
                    } ?: Box(Modifier.weight(1f))
                    PrimaryButton(stringResource(Res.string.term_broadcast_send), onClick = submit, icon = "send")
                }
            }
        }
    }
}

@Composable
private fun TargetRow(label: String, selected: Boolean, mono: FontFamily, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) D.cyan10 else Color.Transparent)
            .clickable(onClick = onToggle)
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
            Sym(if (selected) "check_box" else "check_box_outline_blank", size = 16.sp, color = if (selected) D.cyanBright else D.faint)
        }
        Txt(label, color = if (selected) D.textBright else D.dim, size = 12.5.sp, font = mono)
    }
}

@Composable
private fun CommandField(
    value: String,
    onValueChange: (String) -> Unit,
    mono: FontFamily,
    focus: FocusRequester,
    onSubmit: () -> Unit,
) {
    val style = remember(mono) { TextStyle(color = D.textBright, fontSize = 13.sp, fontFamily = mono) }
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(7.dp))
            .background(D.terminalBg)
            .border(1.dp, D.cyan14, RoundedCornerShape(7.dp))
            .padding(horizontal = 11.dp, vertical = 10.dp),
    ) {
        if (value.isEmpty()) Txt(stringResource(Res.string.term_broadcast_placeholder), color = D.faint, size = 13.sp, font = mono)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = style,
            cursorBrush = SolidColor(D.cyan),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focus)
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Enter) {
                        onSubmit(); true
                    } else {
                        false
                    }
                },
        )
    }
}
