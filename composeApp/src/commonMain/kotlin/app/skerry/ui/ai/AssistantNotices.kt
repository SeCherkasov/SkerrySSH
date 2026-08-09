package app.skerry.ui.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.design.labelUppercase
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.assistant_no_answer
import app.skerry.ui.generated.resources.assistant_policy_note
import app.skerry.ui.generated.resources.term_ai_not_a_command
import app.skerry.ui.theme.Skerry
import org.jetbrains.compose.resources.stringResource

// The feed items that are not turns: an outcome that has no reply, and the standing policy card.

/** Blocked/failed/unusable outcome, in the feed rather than a popup: it belongs to that question. */
@Composable
internal fun AssistantNotice(notice: AiNotice) {
    val text = when (notice) {
        is AiNotice.Blocked -> aiBlockedMessage(notice.reason)
        is AiNotice.Ask -> notice.question
        AiNotice.Rejected -> stringResource(Res.string.term_ai_not_a_command)
        AiNotice.NoAnswer -> stringResource(Res.string.assistant_no_answer)
        is AiNotice.Error -> aiFailureMessage(notice.failure)
    }
    val color = if (notice is AiNotice.Error) Skerry.colors.storm else Skerry.colors.amber
    // Selectable like a turn: a failure that cannot be pasted into a report is a failure reported
    // from memory.
    SelectionContainer {
        Txt(text, color = color, size = 12.sp, lineHeight = 17.sp)
    }
}

/** Policy badge plus the one line of what is sent — the STRICT card from the mock. */
@Composable
internal fun AssistantPolicyNote(controller: SessionAssistantController) {
    val mono = LocalFonts.current.mono
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Skerry.colors.overlaySoft)
            .border(1.dp, Skerry.colors.lineStrong, RoundedCornerShape(12.dp))
            .padding(horizontal = 11.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.clip(RoundedCornerShape(6.dp)).background(Skerry.colors.amberSoft).padding(horizontal = 7.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Sym("shield", size = 12.sp, color = Skerry.colors.amber)
            Txt(labelUppercase(controller.policy.shortLabel()), color = Skerry.colors.amber, size = 10.sp, font = mono, maxLines = 1)
        }
        Txt(stringResource(Res.string.assistant_policy_note), color = Skerry.colors.dim, size = 11.5.sp, lineHeight = 16.sp)
    }
}
