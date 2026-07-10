package app.skerry.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.ai.CommandRisk
import app.skerry.ui.ai.TerminalAiController
import app.skerry.ui.ai.aiBlockedMessage
import app.skerry.ui.app.LocalAi
import app.skerry.ui.app.LocalFeatures
import app.skerry.ui.design.ChipButton
import app.skerry.ui.design.D
import app.skerry.ui.design.HLine
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.term_ai_ask_placeholder
import app.skerry.ui.generated.resources.term_ai_confirm_run
import app.skerry.ui.generated.resources.term_ai_dismiss
import app.skerry.ui.generated.resources.term_ai_not_a_command
import app.skerry.ui.generated.resources.term_ai_run
import app.skerry.ui.generated.resources.term_ai_run_anyway
import app.skerry.ui.generated.resources.term_ai_thinking
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.jetbrains.compose.resources.stringResource

// Terminal AI bar: live input under per-host policy, or a decorative mock preview.

/**
 * Decides whether to show the terminal AI bar and in what mode. With a live controller ([LocalAi]),
 * the bar runs under the per-host policy ([Host.aiPolicy]); [app.skerry.shared.ai.AiPolicy.Off] hides
 * it entirely. Without a controller but with the feature flag, falls back to the decorative mock.
 */
@Composable
internal fun TerminalAiBarSlot() {
    // Reached only outside the live path (no controller): decorative AI bar for design preview.
    if (LocalAi.current == null && LocalFeatures.current.ai) AiBarMock()
}

/**
 * Single fixed-height AI bar: input, "Thinking…", blocked/error, or — for a suggestion — the command
 * plus an inline explanation (what it does, or the risk reason colored by [CommandRisk]) plus action
 * buttons. A destructive command is red with a block icon. No auto-run (output is untrusted): "Run"
 * sends the command plus CR to the shell; [CommandRisk.Danger] requires a second tap ("Run anyway" ->
 * "Confirm run").
 */
@Composable
internal fun AiBarInput(
    controller: TerminalAiController,
    terminal: TerminalScreenState?,
    focusRequests: SharedFlow<Unit> = MutableSharedFlow(),
) {
    val mono = LocalFonts.current.mono
    var prompt by remember { mutableStateOf("") }
    val submit = {
        val text = prompt.trim()
        if (text.isNotEmpty()) { controller.ask(text); prompt = "" }
    }
    // Cmd+/ or Ctrl+Shift+/ focuses the input. requestFocus is wrapped in runCatching: while the bar
    // shows pending/thinking (no text field in composition) the FocusRequester isn't attached, so the
    // request is ignored rather than crashing. SharedFlow doesn't replay, so remounting the bar can't
    // steal focus.
    val promptFocus = remember { FocusRequester() }
    LaunchedEffect(focusRequests) {
        focusRequests.collect {
            // The "/" from the chord leaks a KEY_TYPED into the freshly focused field (typed events
            // bypass onPreviewKeyEvent, which is only suppressed on KeyDown). Clear before and after a
            // short window to erase the leaked character before the user starts typing.
            prompt = ""
            runCatching { promptFocus.requestFocus() }
            delay(50)
            prompt = ""
        }
    }
    val pending = controller.pending
    val risk = controller.pendingRisk?.risk ?: CommandRisk.None
    val danger = risk == CommandRisk.Danger
    // Any destructive command (delete/overwrite) is colored red, even at Warn level.
    val severe = danger || controller.pendingRisk?.destructive == true
    val accent = if (severe) D.sunset else D.moss
    var armed by remember(pending) { mutableStateOf(false) }
    Column {
        HLine()
        Row(
            Modifier.fillMaxWidth()
                .background(if (pending != null) accent.copy(alpha = 0.08f) else D.surface2)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val leadColor = if (pending != null) accent else D.amber
            // A destructive command shows a block icon instead of the terminal icon.
            val leadIcon = if (pending != null) (if (severe) "block" else "terminal") else "auto_awesome"
            Box(Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)).background(leadColor.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                Sym(leadIcon, size = 16.sp, color = leadColor)
            }
            Box(Modifier.weight(1f)) {
                when {
                    pending != null -> {
                        // Single line: command plus inline explanation or risk reason, no separate panel.
                        val infoColor = if (severe) D.sunset else if (risk == CommandRisk.Warn) D.amber else D.dim
                        val info = when (risk) {
                            CommandRisk.None -> controller.pendingInfo
                            else -> controller.pendingRisk?.reason
                        }
                        // Command and explanation share a baseline so differing font sizes don't drift.
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // Destructive commands are highlighted red. The command wraps (up to 6 lines)
                            // instead of being ellipsized, so the user sees the full command before
                            // confirming and running it.
                            Txt(pending, color = if (severe) D.sunset else D.text, size = 13.sp, font = mono, maxLines = 6, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).alignByBaseline())
                            if (info != null) Txt(info, color = infoColor, size = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).alignByBaseline())
                        }
                    }
                    controller.busy -> Txt(stringResource(Res.string.term_ai_thinking), color = D.dim, size = 13.sp)
                    controller.blocked != null -> Txt(aiBlockedMessage(controller.blocked!!), color = D.amber, size = 12.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    controller.rejected -> Txt(stringResource(Res.string.term_ai_not_a_command), color = D.amber, size = 12.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    controller.error != null -> Txt(controller.error!!, color = D.sunset, size = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    else -> {
                        if (prompt.isEmpty()) Txt(stringResource(Res.string.term_ai_ask_placeholder), color = D.dim, size = 13.sp)
                        BasicTextField(
                            value = prompt,
                            onValueChange = { prompt = it },
                            singleLine = true,
                            textStyle = TextStyle(color = D.text, fontSize = 13.sp),
                            cursorBrush = SolidColor(D.cyan),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { submit() }),
                            modifier = Modifier.fillMaxWidth().focusRequester(promptFocus),
                        )
                    }
                }
            }
            when {
                pending != null -> {
                    AiActionChip(
                        label = when { !danger -> stringResource(Res.string.term_ai_run); !armed -> stringResource(Res.string.term_ai_run_anyway); else -> stringResource(Res.string.term_ai_confirm_run) },
                        color = accent,
                        enabled = terminal != null,
                        onClick = {
                            if (danger && !armed) armed = true
                            else controller.confirm()?.let { terminal?.send(it + "\r") }
                        },
                    )
                    AiActionChip(stringResource(Res.string.term_ai_dismiss), D.faint, onClick = { controller.dismiss() })
                }
                controller.blocked != null || controller.error != null || controller.rejected ->
                    AiActionChip(stringResource(Res.string.term_ai_dismiss), D.faint, onClick = { controller.dismiss() })
                else -> {
                    AiBarTag("verified_user", controller.policy.name.uppercase(), mono)
                    Box(
                        Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)).background(D.cyan)
                            .clickable(enabled = !controller.busy) { submit() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Sym("arrow_upward", size = 16.sp, color = D.ink)
                    }
                }
            }
        }
    }
}

/** Compact filled chip button (Run/Dismiss) in the suggestion card, a filled variant of [ChipButton]. */
@Composable
private fun AiActionChip(label: String, color: Color, enabled: Boolean = true, onClick: () -> Unit) {
    ChipButton(label, color = color, onClick = onClick, enabled = enabled, filled = true, size = 12.sp, weight = FontWeight.Medium, verticalPadding = 5.dp)
}

/** Decorative AI bar for design preview only (no live controller). */
@Composable
private fun AiBarMock() {
    val mono = LocalFonts.current.mono
    Column {
        HLine()
        Row(
            Modifier.fillMaxWidth().background(D.surface2).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)).background(D.amber.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                Sym("auto_awesome", size = 16.sp, color = D.amber)
            }
            Txt("Ask Skerry: 'find files larger than 100MB'   ·   Ctrl / to focus", color = D.dim, size = 13.sp, modifier = Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AiBarTag("lock", "Local · Qwen 2.5", mono)
                AiBarTag("verified_user", "STRICT", mono)
            }
            Box(Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)).background(D.cyan), contentAlignment = Alignment.Center) {
                Sym("arrow_upward", size = 16.sp, color = D.ink)
            }
        }
    }
}

@Composable
private fun AiBarTag(icon: String, text: String, mono: FontFamily) {
    Row(
        Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0x0AFFFFFF))
            .border(1.dp, D.line, RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Sym(icon, size = 12.sp, color = D.faint)
        Txt(text, color = D.faint, size = 10.5.sp, font = mono)
    }
}
