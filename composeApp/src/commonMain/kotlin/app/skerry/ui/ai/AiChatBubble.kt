package app.skerry.ui.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import app.skerry.shared.ai.AiRole
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.theme.Skerry

/**
 * Clickable header for the collapsible quick-chat (AI settings, desktop and mobile): title,
 * subtitle, and chevron. Collapsed by default.
 */
@Composable
internal fun AiQuickChatHeader(title: String, desc: String, open: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable(onClick = onToggle).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Txt(title, color = Skerry.colors.text, size = 13.sp, weight = FontWeight.Medium)
            Txt(desc, color = Skerry.colors.dim, size = 11.5.sp, modifier = Modifier.padding(top = 2.dp))
        }
        Sym(if (open) "expand_less" else "expand_more", size = 16.sp, color = Skerry.colors.faint)
    }
}

/** Chat bubble for a quick-chat turn: user aligned right in cyan, assistant aligned left, muted. */
@Composable
internal fun AiChatBubble(role: AiRole, text: String) {
    val mine = role == AiRole.USER
    // The quick chat does no fence parsing — a reply arrives as one string, commands and all — so
    // this is the only place its text can be filtered before it is shown and, now, copied. The
    // user's own turn goes through the same call for one code path; it costs nothing.
    val shown = remember(text) { AssistantAnswer.safeText(text) }
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        // One scope per bubble, as in the desktop feed ([AssistantMessage]): a reply arriving in the
        // next bubble must not collapse a selection being made in this one.
        SelectionContainer {
            Box(
                Modifier.clip(RoundedCornerShape(8.dp))
                    .background(if (mine) Skerry.colors.cyan10 else Skerry.colors.overlayMed)
                    .border(1.dp, if (mine) Skerry.colors.cyan14 else Skerry.colors.line, RoundedCornerShape(8.dp))
                    .padding(horizontal = 11.dp, vertical = 8.dp),
            ) {
                Txt(shown, color = if (mine) Skerry.colors.text else Skerry.colors.dim, size = 12.5.sp, lineHeight = 18.sp)
            }
        }
    }
}

/**
 * A failed AI request under the control that triggered it — the quick chat's own reply, or the model
 * list's refresh. Selectable: the error token ([app.skerry.ui.theme.SkerryColors.storm]) marks
 * exactly the text worth pasting into a report; `sunset` stays reserved for warnings.
 *
 * [compact] is the form used under a field (smaller, padded above only), as against the chat feed's.
 */
@Composable
internal fun AiChatError(failure: AiFailure, compact: Boolean = false) {
    SelectionContainer {
        Txt(
            aiFailureMessage(failure),
            color = Skerry.colors.storm,
            size = if (compact) 11.sp else 12.sp,
            lineHeight = if (compact) 15.sp else TextUnit.Unspecified,
            modifier = if (compact) Modifier.padding(top = 6.dp) else Modifier.padding(vertical = 6.dp),
        )
    }
}
