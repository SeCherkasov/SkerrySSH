package app.skerry.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.ai.CommandRisk
import app.skerry.ui.ai.AiNotice
import app.skerry.ui.ai.AssistantAnswer
import app.skerry.ui.ai.TerminalAiController
import app.skerry.ui.ai.aiBlockedMessage
import app.skerry.ui.ai.aiFailureMessage
import app.skerry.ui.ai.shortLabel
import app.skerry.ui.design.labelUppercase
import app.skerry.ui.terminal.TerminalScreenState
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.term_open_path_in_files
import app.skerry.ui.generated.resources.term_ai_thinking
import app.skerry.ui.generated.resources.term_ai_ask_short
import app.skerry.ui.generated.resources.term_ai_run
import app.skerry.ui.generated.resources.term_ai_run_anyway
import app.skerry.ui.generated.resources.term_ai_confirm
import app.skerry.ui.generated.resources.term_ai_dismiss
import app.skerry.ui.generated.resources.term_ai_explain_reading
import app.skerry.ui.generated.resources.term_ai_not_a_command
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.theme.Skerry
import app.skerry.ui.ai.commandRiskReasonText

/**
 * The single form of the mobile AI bar (desktop parity) — constant height, the terminal isn't resized,
 * nothing is overlapped. One row: input, "Thinking…", blocked/error, and for a suggestion — the command
 * + inline note (None: what it does; Warn/Danger: risk reason in color) + buttons. Destructive is red
 * with a "block" icon. No auto-run: Run = confirmation; [CommandRisk.Danger] needs a second tap.
 */
@Composable
internal fun MobileAiBarInput(controller: TerminalAiController, terminal: TerminalScreenState) {
    val mono = LocalFonts.current.mono
    var prompt by remember { mutableStateOf("") }
    val submit = {
        val text = prompt.trim()
        if (text.isNotEmpty()) { controller.ask(text); prompt = "" }
    }
    val pending = controller.pending
    val explanation = controller.explanation
    val risk = controller.pendingRisk?.risk ?: CommandRisk.None
    val danger = risk == CommandRisk.Danger
    // Red for any destructive command (delete/overwrite), even Warn.
    val severe = danger || controller.pendingRisk?.destructive == true
    val accent = if (severe) Skerry.colors.sunset else Skerry.colors.moss
    var armed by remember(pending) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(if (pending != null) accent.copy(alpha = 0.08f) else Skerry.colors.surface2)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Sym(
                when {
                    pending != null -> if (severe) "block" else "terminal"
                    explanation != null -> "summarize"
                    else -> "auto_awesome"
                },
                size = 16.sp, color = if (pending != null) accent else Skerry.colors.amber,
            )
            Box(Modifier.weight(1f)) {
                when {
                    pending != null -> {
                        val infoColor = if (severe) Skerry.colors.sunset else if (risk == CommandRisk.Warn) Skerry.colors.amber else Skerry.colors.dim
                        val info = when (risk) {
                            CommandRisk.None -> controller.pendingInfo
                            else -> controller.pendingRisk?.reason?.let { commandRiskReasonText(it) }
                        }
                        // Long-press selects, as in the terminal underneath: the bar has no Copy
                        // button, so selection is the only way the command leaves the screen.
                        SelectionContainer {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                // The command wraps (up to 6 lines), not truncated: the user sees in full what
                                // they confirm and run (see TerminalView — the same safety invariant).
                                // Direction pinned, as on the desktop card: a shell line is LTR whatever the UI language.
                                Txt(pending, color = if (severe) Skerry.colors.sunset else Skerry.colors.text, size = 12.sp, font = mono, maxLines = 6, overflow = TextOverflow.Ellipsis, textDirection = TextDirection.Ltr, modifier = Modifier.weight(1f, fill = false).alignByBaseline())
                                if (info != null) Txt(info, color = infoColor, size = 10.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).alignByBaseline())
                            }
                        }
                    }
                    explanation != null ->
                        if (explanation.isEmpty()) {
                            Txt(stringResource(Res.string.term_ai_explain_reading), color = Skerry.colors.dim, size = 13.sp)
                        } else {
                            Column(Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState())) {
                                // The one place a model's prose appears on mobile outside the AI
                                // screen, and there is no Copy for it — selection is the way out.
                                // Filtered like the panel's: it is raw model text, and what a
                                // selection copies can be pasted straight back into the shell.
                                SelectionContainer {
                                    Txt(
                                        remember(explanation) { AssistantAnswer.safeText(explanation) },
                                        color = Skerry.colors.text, size = 12.sp, lineHeight = 17.sp,
                                    )
                                }
                            }
                        }
                    controller.busy -> Txt(stringResource(Res.string.term_ai_thinking), color = Skerry.colors.dim, size = 13.sp)
                    controller.notice != null -> SelectionContainer {
                        when (val notice = controller.notice!!) {
                            is AiNotice.Blocked -> Txt(aiBlockedMessage(notice.reason), color = Skerry.colors.amber, size = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            is AiNotice.Ask -> Txt(notice.question, color = Skerry.colors.amber, size = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            // The bar only ever asks for a command, so an empty reply is the same
                            // "nothing usable" outcome as prose — one message covers both.
                            AiNotice.Rejected, AiNotice.NoAnswer ->
                                Txt(stringResource(Res.string.term_ai_not_a_command), color = Skerry.colors.amber, size = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            is AiNotice.Error -> Txt(aiFailureMessage(notice.failure), color = Skerry.colors.sunset, size = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    else -> {
                        if (prompt.isEmpty()) Txt(stringResource(Res.string.term_ai_ask_short), color = Skerry.colors.dim, size = 13.sp)
                        BasicTextField(
                            value = prompt,
                            onValueChange = { prompt = it },
                            singleLine = true,
                            textStyle = TextStyle(color = Skerry.colors.text, fontSize = 13.sp),
                            cursorBrush = SolidColor(Skerry.colors.cyan),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { submit() }),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            when {
                pending != null -> {
                    MobileAiChip(when { !danger -> stringResource(Res.string.term_ai_run); !armed -> stringResource(Res.string.term_ai_run_anyway); else -> stringResource(Res.string.term_ai_confirm) }, accent) {
                        if (danger && !armed) armed = true
                        else controller.confirm()?.let { terminal.sendUserInputGuarded(it + "\r") }
                    }
                    MobileAiChip(stringResource(Res.string.term_ai_dismiss), Skerry.colors.faint) { controller.dismiss() }
                }
                explanation != null ->
                    MobileAiChip(stringResource(Res.string.term_ai_dismiss), Skerry.colors.faint) { controller.dismiss() }
                controller.notice != null ->
                    MobileAiChip(stringResource(Res.string.term_ai_dismiss), Skerry.colors.faint) { controller.dismiss() }
                else -> {
                    // Explain the current selection, or the visible screen when nothing is selected.
                    Box(
                        Modifier.size(30.dp).clip(RoundedCornerShape(7.dp))
                            .background(Skerry.colors.overlaySoft)
                            .border(1.dp, Skerry.colors.line, RoundedCornerShape(7.dp))
                            .clickable(enabled = !controller.busy) {
                                // Selection wins; else the last command's output; else the whole screen.
                                controller.explain(terminal.selectedText() ?: terminal.lastOutput() ?: terminal.output)
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Sym("summarize", size = 16.sp, color = Skerry.colors.amber)
                    }
                    Txt(labelUppercase(controller.policy.shortLabel()), color = Skerry.colors.faint, size = 10.sp, font = mono)
                    Box(
                        Modifier.size(30.dp).clip(RoundedCornerShape(7.dp)).background(Skerry.colors.cyan)
                            .clickable(enabled = !controller.busy) { submit() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Sym("arrow_upward", size = 16.sp, color = Skerry.colors.ink)
                    }
                }
            }
        }
    }
}

/**
 * "Open in Files" row over the key panel, shown while the selection is exactly one file path. Full
 * width and tappable as a whole (a phone-sized target), with the path itself in mono so it reads as
 * the thing being opened; a long path ellipsizes at the start, keeping the file name visible.
 */
@Composable
internal fun MobileOpenPathBar(path: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Skerry.colors.surface2).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Sym("drive_file_move", size = 16.sp, color = Skerry.colors.cyanBright)
        Txt(stringResource(Res.string.term_open_path_in_files), color = Skerry.colors.text, size = 12.sp, weight = FontWeight.Medium)
        Txt(
            path,
            color = Skerry.colors.dim,
            size = 11.5.sp,
            font = LocalFonts.current.mono,
            maxLines = 1,
            overflow = TextOverflow.StartEllipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun MobileAiChip(label: String, color: Color, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = 0.16f))
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(6.dp)).clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 5.dp),
    ) {
        Txt(label, color = color, size = 11.5.sp, weight = FontWeight.Medium)
    }
}
