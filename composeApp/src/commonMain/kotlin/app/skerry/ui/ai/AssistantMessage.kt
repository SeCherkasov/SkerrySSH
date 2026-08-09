package app.skerry.ui.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.ai.CommandRisk
import app.skerry.shared.ai.CommandRiskClassifier
import app.skerry.ui.design.ChipButton
import app.skerry.ui.design.HLine
import app.skerry.ui.design.IconBtn
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.assistant_attached
import app.skerry.ui.generated.resources.assistant_confirm_run
import app.skerry.ui.generated.resources.assistant_copy
import app.skerry.ui.generated.resources.assistant_edit
import app.skerry.ui.generated.resources.assistant_run
import app.skerry.ui.generated.resources.assistant_run_anyway
import app.skerry.ui.theme.Skerry
import org.jetbrains.compose.resources.stringResource

/** What a code block's buttons can do; the panel owns the session, the message only reports intent. */
internal data class AssistantCommandActions(
    val run: (String) -> Unit,
    val copy: (String) -> Unit,
    /** Put the command on the shell's input line without executing it. */
    val edit: (String) -> Unit,
    /** False while nothing is connected, or while the reply is still streaming: Run/Edit dim, Copy stays. */
    val runnable: Boolean,
)

/**
 * One turn in the feed. A user turn is a right-aligned bubble; an assistant turn is a left-aligned
 * card whose fenced blocks ([AssistantAnswer]) carry the Run/Copy/Edit row.
 */
@Composable
internal fun AssistantMessage(
    text: String,
    fromUser: Boolean,
    actions: AssistantCommandActions,
    /** How many command outputs travelled with this question; 0 draws nothing. */
    attached: Int = 0,
) {
    val alignment = if (fromUser) Alignment.End else Alignment.Start
    Column(Modifier.fillMaxWidth(), horizontalAlignment = alignment) {
        // One selection scope per turn, not one around the whole feed: the feed is a LazyColumn, and
        // a selection spanning items that scroll out of composition is undefined by contract
        // (SelectionContainer's own docs). Per turn also means a streaming delta lands in a scope of
        // its own, so it cannot collapse a selection made in an older turn. What it does not survive
        // is the turn leaving composition — scrolling it out of the feed drops the selection with it,
        // as it would in any lazy list.
        //
        // The container installs `focusable()`, which costs two things we take knowingly: each
        // visible turn becomes a Tab stop — with no focus ring, and unlabelled, since `focusable()`
        // does not merge the bubble's text into the node — and clicking a reply moves keyboard focus
        // off the terminal until the terminal is clicked again. Both are the price of the copy chord:
        // `focusProperties { canFocus = false }` removes them and takes Ctrl+C with them, because
        // that is the node Compose routes the chord to.
        SelectionContainer {
            Column(
                Modifier
                    // Width follows the content up to the cap, so a short question is a short bubble
                    // while an answer with a command block still gets the room it needs.
                    .widthIn(max = BUBBLE_MAX_WIDTH)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (fromUser) Skerry.colors.cyan.copy(alpha = 0.12f) else Skerry.colors.overlaySoft)
                    .border(
                        1.dp,
                        if (fromUser) Skerry.colors.lineStrong else Skerry.colors.line,
                        RoundedCornerShape(12.dp),
                    )
                    .padding(horizontal = 11.dp, vertical = 9.dp),
            ) {
                // A user turn is plain text: it is what the person typed, not model output to parse
                // for commands. Filtered all the same — a question is often a line pasted out of
                // terminal output, i.e. text the host chose, and the bubble is selectable now.
                if (fromUser) {
                    Txt(
                        remember(text) { AssistantAnswer.safeText(text) },
                        color = Skerry.colors.text, size = 12.5.sp, lineHeight = 19.sp,
                    )
                    // What actually left the machine with this question. Terminal output is the part
                    // worth stating: it is not what the user typed, and under a cloud policy it is the
                    // part that reaches someone else's server. Out of the selection scope like the
                    // action row — chrome about the turn, and `Sym` draws its icon as a ligature whose
                    // *text* is the glyph name, so a sweep would paste the word `attach_file`.
                    if (attached > 0) {
                        Box(Modifier.height(5.dp))
                        DisableSelection {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Sym("attach_file", size = 11.sp, color = Skerry.colors.faint)
                                Txt(
                                    stringResource(Res.string.assistant_attached, attached),
                                    color = Skerry.colors.faint,
                                    size = 10.5.sp,
                                )
                            }
                        }
                    }
                    return@Column
                }
                // Parsed once per reply text, not per frame: this runs on every streaming delta.
                val segments = remember(text) { AssistantAnswer.segments(text) }
                var seenCode = false
                segments.forEachIndexed { index, segment ->
                    // A reply that lists several commands is one bubble of alternating block and
                    // explanation; a rule before every block but the first cuts it into the groups the
                    // model meant, so it is clear which sentence belongs to which command.
                    if (segment is AssistantSegment.Code && seenCode) {
                        Box(Modifier.height(10.dp))
                        HLine()
                        Box(Modifier.height(10.dp))
                    } else if (index > 0) {
                        Box(Modifier.height(6.dp))
                    }
                    when (segment) {
                        is AssistantSegment.Prose -> ProseText(segment.text)
                        is AssistantSegment.Code -> {
                            CodeBlock(segment, actions)
                            seenCode = true
                        }
                    }
                }
            }
        }
    }
}

/** Prose with inline markers resolved: `` `code` `` in mono, `**text**` in bold. */
@Composable
private fun ProseText(text: String) {
    val mono = LocalFonts.current.mono
    val spans = remember(text) { AssistantAnswer.spans(text) }
    val styled = remember(spans, mono) { annotate(spans, mono) }
    Txt(styled, color = Skerry.colors.textMid, size = 12.5.sp)
}

private fun annotate(spans: List<AiSpan>, mono: FontFamily): AnnotatedString = buildAnnotatedString {
    spans.forEach { span ->
        when {
            span.mono -> withStyle(SpanStyle(fontFamily = mono, fontSize = 11.5.sp)) { append(span.text) }
            span.bold -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(span.text) }
            else -> append(span.text)
        }
    }
}

/**
 * A fenced block. One card per runnable command: a model asked for "five commands" answers with one
 * block holding all five, and a single action row under it would run only the first one while
 * looking like it ran the block. A block with nothing runnable (comments only) still renders as text.
 */
@Composable
private fun CodeBlock(segment: AssistantSegment.Code, actions: AssistantCommandActions) {
    if (segment.commands.size <= 1) {
        CommandCard(segment.text, segment.commands.firstOrNull(), actions)
        return
    }
    segment.commands.forEachIndexed { index, command ->
        if (index > 0) Box(Modifier.height(8.dp))
        CommandCard(command, command, actions)
    }
}

/**
 * One command as shown and, if it is runnable, its actions. Nothing runs on its own — Run sends
 * [command] plus CR, and a [CommandRisk.Danger] one takes a second click, the same confirmation the
 * one-shot bar required.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CommandCard(display: String, command: String?, actions: AssistantCommandActions) {
    val mono = LocalFonts.current.mono
    val risk = remember(command) { command?.let { CommandRiskClassifier.assess(it) } }
    val severe = risk?.risk == CommandRisk.Danger || risk?.destructive == true
    var armed by remember(command) { mutableStateOf(false) }
    Column(Modifier.padding(top = 2.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Skerry.colors.terminalBg)
                .border(1.dp, Skerry.colors.line, RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp)
                .horizontalScroll(rememberScrollState()),
        ) {
            // A long command scrolls rather than wrapping: a wrapped shell line is easy to misread
            // before confirming it.
            //
            // Direction pinned rather than inherited: a shell line is LTR by nature, and an unstyled
            // Txt resolves its paragraph base from the layout direction. Under an RTL UI locale a
            // command whose first strong character is RTL would render with its trailing `#` hoisted
            // to the front — a live command reading as a comment, next to the Run button. No
            // character filter can prevent that; stating the direction can.
            Txt(
                display,
                color = if (severe) Skerry.colors.sunset else Skerry.colors.moss,
                size = 11.5.sp,
                lineHeight = 18.sp,
                font = mono,
                textDirection = TextDirection.Ltr,
            )
        }
        if (command == null) return@Column
        // Out of the turn's selection scope: a sweep meant for the command must not pick up "Run",
        // and a copied turn should read as what the model said, not as the buttons under it.
        DisableSelection {
            // Wraps instead of clipping: "Run in session" and its two neighbours don't fit one line in
            // every language, and a half-cut button on a destructive command is the worst place for it.
            FlowRow(
                Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val runLabel = when {
                    !severe -> stringResource(Res.string.assistant_run)
                    !armed -> stringResource(Res.string.assistant_run_anyway)
                    else -> stringResource(Res.string.assistant_confirm_run)
                }
                ChipButton(
                    label = runLabel,
                    color = if (severe) Skerry.colors.sunset else Skerry.colors.teal,
                    filled = true,
                    icon = if (severe) "warning" else "play_arrow",
                    enabled = actions.runnable,
                    verticalPadding = 4.dp,
                    onClick = { if (severe && !armed) armed = true else actions.run(command) },
                )
                // Copy and Edit carry their glyph only: with three labelled buttons the row wrapped in
                // every language but English, and the primary action is the one that needs naming.
                IconBtn(
                    "content_copy",
                    onClick = { actions.copy(command) },
                    box = 24,
                    icon = 13.sp,
                    tint = Skerry.colors.textMid,
                    tooltip = stringResource(Res.string.assistant_copy),
                )
                IconBtn(
                    "edit",
                    onClick = { actions.edit(command) },
                    box = 24,
                    icon = 13.sp,
                    tint = Skerry.colors.textMid,
                    tooltip = stringResource(Res.string.assistant_edit),
                    enabled = actions.runnable,
                )
            }
        }
    }
}

/**
 * Widest a bubble may get: 92% of the panel's content width, as in the mock, so the feed keeps the
 * asymmetry that tells a question from an answer at a glance.
 */
private val BUBBLE_MAX_WIDTH = 328.dp
