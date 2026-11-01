package app.skerry.ui.terminal

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import app.skerry.shared.ai.AiEndpoint
import app.skerry.ui.ai.AiNotice
import app.skerry.ui.ai.AssistantMessage
import app.skerry.ui.ai.FailedCommandExplanationController
import app.skerry.ui.ai.aiBlockedMessage
import app.skerry.ui.ai.aiFailureMessage
import app.skerry.ui.ai.rememberAssistantCommandActions
import app.skerry.ui.design.CancelButton
import app.skerry.ui.design.CommandQuote
import app.skerry.ui.design.ModalScrim
import app.skerry.ui.design.PrimaryButton
import app.skerry.ui.design.Txt
import app.skerry.ui.design.consumeClicks
import app.skerry.ui.design.modalBody
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.assistant_no_answer
import app.skerry.ui.generated.resources.failed_command_action
import app.skerry.ui.generated.resources.failed_command_device
import app.skerry.ui.generated.resources.failed_command_preview
import app.skerry.ui.generated.resources.failed_command_send
import app.skerry.ui.generated.resources.failed_command_system
import app.skerry.ui.generated.resources.failed_command_unavailable
import app.skerry.ui.generated.resources.failed_command_user
import app.skerry.ui.generated.resources.shell_cancel
import app.skerry.ui.generated.resources.term_ai_dismiss
import app.skerry.ui.generated.resources.term_ai_thinking
import app.skerry.ui.theme.Skerry
import org.jetbrains.compose.resources.stringResource

/** Shared desktop/phone preview and answer. Nothing is sent by opening this dialog. */
@Composable
internal fun ExplainFailedCommandDialog(
    controller: FailedCommandExplanationController,
    terminal: TerminalScreenState?,
    onDismiss: () -> Unit,
    canRun: () -> Boolean = { terminal != null },
    onBeforeRun: () -> Unit = {},
) {
    val title = stringResource(Res.string.failed_command_action)
    val baseActions = rememberAssistantCommandActions(terminal)
    val actions = baseActions.copy(
        // Return to the originating pane so its production-guard dialog cannot sit behind this Popup.
        run = { if (canRun()) { onBeforeRun(); baseActions.run(it); onDismiss() } },
        edit = { if (canRun()) { onBeforeRun(); baseActions.edit(it); onDismiss() } },
        runnable = baseActions.runnable && canRun(),
    )
    Popup(alignment = Alignment.Center, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        ModalScrim(onDismiss = onDismiss, label = title) {
            Column(
                Modifier.widthIn(max = 620.dp).fillMaxWidth().padding(16.dp)
                    .clip(RoundedCornerShape(12.dp)).background(Skerry.colors.surfaceDeep)
                    .border(1.dp, Skerry.colors.cyan14, RoundedCornerShape(12.dp))
                    .consumeClicks().padding(20.dp),
            ) {
                Txt(title, color = Skerry.colors.text, size = 16.sp, weight = FontWeight.SemiBold)
                val preview = controller.preview
                Column(modalBody(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (preview != null) {
                        Txt(stringResource(Res.string.failed_command_preview), color = Skerry.colors.dim, size = 12.sp)
                        val destination = when (val endpoint = preview.endpoint) {
                            is AiEndpoint.Cloud -> endpoint.config.baseUrl + " · " + endpoint.requestModel
                            is AiEndpoint.Device -> stringResource(Res.string.failed_command_device, endpoint.requestModel)
                        }
                        CommandQuote(destination, visibleLines = 3)
                        preview.messages.forEachIndexed { index, message ->
                            Txt(stringResource(if (index == 0) Res.string.failed_command_system else Res.string.failed_command_user), color = Skerry.colors.faint, size = 11.sp)
                            CommandQuote(message.content, visibleLines = if (index == 0) 5 else 12, modifier = Modifier.fillMaxWidth())
                        }
                    } else if (controller.unavailable) {
                        Txt(stringResource(Res.string.failed_command_unavailable), color = Skerry.colors.amber, size = 12.sp)
                    } else {
                        when (val notice = controller.notice) {
                            is AiNotice.Blocked -> Txt(aiBlockedMessage(notice.reason), color = Skerry.colors.amber, size = 12.sp)
                            is AiNotice.Error -> Txt(aiFailureMessage(notice.failure), color = Skerry.colors.sunset, size = 12.sp)
                            null -> Unit
                            else -> Txt(stringResource(Res.string.assistant_no_answer), color = Skerry.colors.amber, size = 12.sp)
                        }
                        if (controller.busy && controller.answer.isNullOrEmpty()) {
                            Txt(stringResource(Res.string.term_ai_thinking), color = Skerry.colors.dim, size = 12.sp)
                        }
                        controller.answer?.takeIf { it.isNotEmpty() }?.let { answer ->
                            AssistantMessage(answer, fromUser = false, actions = actions.copy(runnable = actions.runnable && !controller.busy), streaming = controller.busy)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                    CancelButton(stringResource(if (preview != null) Res.string.shell_cancel else Res.string.term_ai_dismiss), onClick = onDismiss)
                    if (preview != null) PrimaryButton(stringResource(Res.string.failed_command_send), onClick = controller::confirm)
                }
            }
        }
    }
}
