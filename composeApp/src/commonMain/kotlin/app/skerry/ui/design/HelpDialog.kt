package app.skerry.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.app.UiTags
import app.skerry.ui.theme.Skerry

/**
 * Help modal for a library screen (snippets, runbooks, vault): a titled card with scrollable
 * guidance content and a single Close control. Content rows come from the callers —
 * [HelpCodeRow] for a syntax entry, [HelpExampleRow] for a template offered with a one-click
 * create. Built the way [app.skerry.ui.runbook.RunbookStartDialog] is (scrim + card), because
 * [ConfirmActionDialog]/[NoticeDialog] are title+message shapes without room for a list.
 */
@Composable
fun HelpDialog(title: String, closeLabel: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalScrim(onDismiss = onDismiss) {
        Column(
            Modifier
                .widthIn(max = 720.dp)
                .fillMaxWidth()
                .padding(20.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Skerry.colors.surfaceDeep)
                .border(1.dp, Skerry.colors.cyan14, RoundedCornerShape(12.dp))
                .consumeClicks()
                .padding(26.dp)
                .testTag(UiTags.HELP_DIALOG),
        ) {
            Txt(title, color = Skerry.colors.text, size = 16.sp, weight = FontWeight.SemiBold, letterSpacing = (-0.2).sp)
            // Sized against the viewport, not a fixed cap: on a short screen (landscape phone) a
            // fixed content height would push the Close row past the edge with no way to reach it.
            // weight(fill = false) lets short content stay short and long content stop where the
            // title and the button row still fit.
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) { content() }
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.End) {
                GhostButton(closeLabel, onClick = onDismiss)
            }
        }
    }
}

/** One syntax entry: the placeholder itself in mono, what it does beside it. */
@Composable
fun HelpCodeRow(code: String, description: String) {
    Row(
        // One entry, one accessibility node: read as two, the placeholder announces as
        // "$ { { date } }" and what it does arrives a swipe later with nothing tying them together.
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .semantics(mergeDescendants = true) {}
            .testTag(UiTags.HELP_ROW),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Txt(
            code, color = Skerry.colors.cyanBright, size = 11.5.sp, font = LocalFonts.current.mono,
            modifier = Modifier
                .testTag(UiTags.HELP_CODE)
                .clip(RoundedCornerShape(5.dp))
                .background(Skerry.colors.terminalBg)
                .padding(horizontal = 7.dp, vertical = 3.dp),
        )
        Txt(description, color = Skerry.colors.dim, size = 11.5.sp, lineHeight = 15.sp, modifier = Modifier.weight(1f))
    }
}

/**
 * One offered template: name, its command line (or step summary) and a create button. [added]
 * flips the button into an inert "done" state — the dialog stays open so the rest can be added,
 * and a double click must not save the same template twice.
 */
@Composable
fun HelpExampleRow(
    label: String,
    detail: String,
    addLabel: String,
    addedLabel: String,
    added: Boolean,
    onAdd: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Skerry.colors.card)
            .border(1.dp, Skerry.colors.line, RoundedCornerShape(8.dp))
            .padding(horizontal = 11.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Txt(label, color = Skerry.colors.text, size = 12.sp, weight = FontWeight.Medium)
            Txt(
                detail, color = Skerry.colors.faint, size = 10.5.sp, font = LocalFonts.current.mono,
                lineHeight = 14.sp, modifier = Modifier.padding(top = 2.dp),
            )
        }
        Box(Modifier.align(Alignment.CenterVertically)) {
            ChipButton(
                label = if (added) addedLabel else addLabel,
                color = if (added) Skerry.colors.moss else Skerry.colors.cyan,
                onClick = onAdd,
                enabled = !added,
                icon = if (added) "check" else "add",
            )
        }
    }
}
