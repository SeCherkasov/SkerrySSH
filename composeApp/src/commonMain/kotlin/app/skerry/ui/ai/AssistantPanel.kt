package app.skerry.ui.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.ai.AiProviderKind
import app.skerry.shared.ai.AiRole
import app.skerry.shared.ai.local.LocalModelCatalog
import app.skerry.ui.design.fieldName
import app.skerry.ui.design.handsKeyboardBack
import app.skerry.ui.design.AnchoredDropdown
import app.skerry.ui.design.HLine
import app.skerry.ui.design.IconBtn
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.assistant_ask_placeholder
import app.skerry.ui.generated.resources.assistant_clear
import app.skerry.ui.generated.resources.assistant_context_chip
import app.skerry.ui.generated.resources.assistant_context_off
import app.skerry.ui.generated.resources.assistant_context_title
import app.skerry.ui.generated.resources.assistant_empty
import app.skerry.ui.generated.resources.assistant_explain_action
import app.skerry.ui.generated.resources.assistant_explain_request
import app.skerry.ui.generated.resources.assistant_thinking
import app.skerry.ui.generated.resources.assistant_title
import app.skerry.ui.terminal.WORK_BAR_HEIGHT
import app.skerry.ui.terminal.TerminalScreenState
import app.skerry.ui.terminal.lastCommandBlocks
import app.skerry.ui.theme.Skerry
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

/**
 * Whether the feed is at (or within a few pixels of) its bottom, and so should keep following a
 * streaming reply. Pure so the rule can be tested without a laid-out list: [lastVisibleIndex] is
 * `null` for an empty feed, [lastItemBottom] is that item's bottom edge and [viewportEnd] the
 * viewport's, both in the list's own pixels.
 */
internal fun followsTail(lastVisibleIndex: Int?, totalItems: Int, lastItemBottom: Int, viewportEnd: Int): Boolean {
    if (lastVisibleIndex == null) return true
    if (lastVisibleIndex < totalItems - 1) return false
    return lastItemBottom <= viewportEnd + AUTOSCROLL_SLACK_PX
}

/** A partially drawn last line still counts as "at the bottom". */
private const val AUTOSCROLL_SLACK_PX = 24

/**
 * Offset asked for when following the tail: larger than any item, so the list clamps it to its very
 * end instead of parking the last turn's first line at the top of the viewport.
 */
private const val SCROLL_PAST_ITEM = 100_000

/** Width of the assistant panel, matching the design mock. */
internal val ASSISTANT_PANEL_WIDTH = 380.dp

/**
 * The assistant panel beside the terminal: model badge, the conversation, and the ask row.
 *
 * Everything the panel sends is visible in the feed, and everything it proposes waits for a click —
 * [controller] answers for routing and redaction, this layer only for showing and for handing a
 * confirmed command to [terminal].
 */
@Composable
internal fun AssistantPanel(
    controller: SessionAssistantController,
    terminal: TerminalScreenState?,
    modelLabel: String,
    focusPending: Boolean = false,
    onFocusConsumed: () -> Unit = {},
) {
    val actions = rememberAssistantCommandActions(terminal)
    Column(Modifier.width(ASSISTANT_PANEL_WIDTH).fillMaxHeight().background(Skerry.colors.surface)) {
        AssistantHeader(controller, modelLabel)
        HLine()
        AssistantFeed(controller, actions, Modifier.weight(1f))
        HLine()
        AssistantAskRow(controller, terminal, focusPending, onFocusConsumed)
    }
}

/**
 * The actions a message's code blocks may offer. A reply still streaming is text in motion: the
 * fence is parsed on every delta (deliberately — the block has to appear as it arrives), so the
 * "command" on screen is whatever prefix has landed. Running that would send a truncated line to the
 * shell, which can mean something quite different from the command the model was about to finish.
 * Copy stays: copying a prefix is harmless.
 */
internal fun actionsFor(actions: AssistantCommandActions, streaming: Boolean): AssistantCommandActions =
    if (streaming) actions.copy(runnable = false) else actions

/** Panel title, the endpoint actually answering, and the clear action. */
@Composable
private fun AssistantHeader(controller: SessionAssistantController, modelLabel: String) {
    val mono = LocalFonts.current.mono
    Row(
        Modifier.fillMaxWidth().height(WORK_BAR_HEIGHT).padding(start = 12.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Txt(stringResource(Res.string.assistant_title), size = 13.sp, weight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        Row(
            Modifier.clip(RoundedCornerShape(6.dp)).background(Skerry.colors.tealSoft).padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Sym("memory", size = 12.sp, color = Skerry.colors.teal)
            Txt(modelLabel, color = Skerry.colors.teal, size = 10.5.sp, font = mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (controller.turns.isNotEmpty()) {
            IconBtn("delete_sweep", onClick = controller::clear, box = 26, icon = 16.sp, tooltip = stringResource(Res.string.assistant_clear))
        }
    }
}

/**
 * The conversation. A [LazyColumn] rather than a scrolling [Column]: a long session keeps the whole
 * transcript, and only the visible turns are composed.
 */
@Composable
private fun AssistantFeed(
    controller: SessionAssistantController,
    actions: AssistantCommandActions,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val streaming = controller.streaming
    // Whether the feed should keep following the tail. Read inside the effect rather than keyed on
    // it: scrolling up must not restart the effect, it must stop it from acting.
    val following by remember(listState) {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            followsTail(
                lastVisibleIndex = last?.index,
                totalItems = info.totalItemsCount,
                lastItemBottom = last?.let { it.offset + it.size } ?: 0,
                viewportEnd = info.viewportEndOffset,
            )
        }
    }
    // Follow the newest turn while an answer arrives. Snap, not animate: a delta lands every few
    // frames and an animation would still be running when the next one starts, so the viewport
    // would fight both the stream and the mouse wheel. Scrolls to the END of the list rather than
    // to the start of the last item, or a long reply would jump back to its first line each delta.
    LaunchedEffect(controller.turns.size, streaming) {
        if (!following) return@LaunchedEffect
        val last = listState.layoutInfo.totalItemsCount - 1
        if (last >= 0) listState.scrollToItem(last, SCROLL_PAST_ITEM)
    }
    LazyColumn(
        modifier.fillMaxWidth(),
        state = listState,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Nothing asked yet: one line on what the panel is for, instead of an empty column.
        if (controller.turns.isEmpty() && controller.streaming == null) {
            item {
                Txt(
                    stringResource(Res.string.assistant_empty),
                    color = Skerry.colors.faint,
                    size = 12.5.sp,
                    lineHeight = 18.sp,
                )
            }
        }
        controller.turns.forEach { turn ->
            item {
                AssistantMessage(
                    turn.text,
                    fromUser = turn.role == AiRole.USER,
                    actions = actions,
                    attached = turn.outputs,
                )
            }
        }
        // Partial reply, and "Thinking…" for the gap before the first delta. Its commands are shown
        // but not runnable — see [actionsFor].
        streaming?.let { partial ->
            item {
                if (partial.isEmpty()) {
                    Txt(stringResource(Res.string.assistant_thinking), color = Skerry.colors.dim, size = 12.5.sp)
                } else {
                    AssistantMessage(
                        partial,
                        fromUser = false,
                        actions = actionsFor(actions, streaming = true),
                        streaming = true,
                    )
                }
            }
        }
        controller.notice?.let { notice -> item { AssistantNotice(notice) } }
        // Shown while the panel is still empty: what the policy allows and what leaves the machine.
        // Once there is a conversation it would just push it up the panel every turn.
        if (controller.turns.isEmpty()) item { AssistantPolicyNote(controller) }
    }
}

/** Input row: the prompt, how much context travels with it, and send/stop. */
@Composable
private fun AssistantAskRow(
    controller: SessionAssistantController,
    terminal: TerminalScreenState?,
    focusPending: Boolean,
    onFocusConsumed: () -> Unit,
) {
    var prompt by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val submit = {
        val text = prompt.trim()
        if (text.isNotEmpty() && !controller.busy) {
            // Context is read at send time, so it is the output the user is looking at.
            controller.ask(text, outputs = recentOutputs(terminal, controller.contextOutputs))
            prompt = ""
        }
    }
    // The chord (⌘/ / Ctrl+Shift+/) usually fires while the panel is closed, so the request waits as
    // a flag until this row exists — an event emitted into no collector would be dropped and the
    // caret would stay in the terminal. requestFocus is guarded: the field may still be arriving.
    LaunchedEffect(focusPending) {
        if (!focusPending) return@LaunchedEffect
        // The "/" of the chord leaks in as a KEY_TYPED, which bypasses onPreviewKeyEvent (only the
        // KeyDown is consumed). Wait out the window it arrives in and undo it — the draft itself is
        // kept, since the chord is also pressed with the panel already open and half a question in
        // the field.
        val before = prompt
        runCatching { focus.requestFocus() }
        delay(FOCUS_SETTLE_MS)
        if (prompt == before + CHORD_KEY || prompt == CHORD_KEY + before) prompt = before
        // Consumed last, not first: clearing the flag is what this effect is keyed on, so doing it
        // before the delay would cancel this very coroutine and the undo would never run.
        onFocusConsumed()
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = ASK_ROW_HEIGHT).background(Skerry.colors.surface).padding(horizontal = 12.dp, vertical = 8.dp),
        // Bottom, not centre: the controls stay put on their line while the field grows upwards.
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Explain what is on screen: the selection if there is one, else the last command block.
        // A one-click question, since it is the one thing always worth asking about the session.
        if (terminal != null) {
            val request = stringResource(Res.string.assistant_explain_request)
            Box(
                Modifier.size(26.dp).clip(RoundedCornerShape(8.dp)).background(Skerry.colors.overlaySoft)
                    .border(1.dp, Skerry.colors.line, RoundedCornerShape(8.dp))
                    // The hand-back follows the click: a press while the assistant is busy does
                    // nothing, and moving the caret for it would be a jump the user did not ask for.
                    .handsKeyboardBack(!controller.busy).clickable(enabled = !controller.busy) {
                        controller.explain(request, terminal.selectedText() ?: terminal.lastOutput() ?: terminal.output)
                    },
                contentAlignment = Alignment.Center,
            ) {
                // The glyph is the button's only label — [Sym] clears its own semantics, so without
                // this the control is an unnamed box to a screen reader.
                Sym(
                    "summarize",
                    size = 15.sp,
                    color = if (controller.busy) Skerry.colors.faint else Skerry.colors.amber,
                    contentDescription = stringResource(Res.string.assistant_explain_action),
                )
            }
        }
        val placeholder = stringResource(Res.string.assistant_ask_placeholder)
        Box(Modifier.weight(1f).padding(bottom = 4.dp)) {
            if (prompt.isEmpty()) {
                Txt(placeholder, color = Skerry.colors.faint, size = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            // Wraps and grows up to ASK_MAX_LINES, then scrolls: a pasted error or a long question
            // has to be readable before it is sent, not hidden past the right edge of one line.
            BasicTextField(
                value = prompt,
                onValueChange = { prompt = it },
                singleLine = false,
                maxLines = ASK_MAX_LINES,
                textStyle = TextStyle(color = Skerry.colors.text, fontSize = 12.5.sp, lineHeight = 18.sp, fontFamily = LocalFonts.current.ui),
                cursorBrush = SolidColor(Skerry.colors.cyan),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submit() }),
                // The placeholder is the only label this field draws (see fieldName).
                modifier = Modifier.fillMaxWidth().focusRequester(focus).fieldName(placeholder).onPreviewKeyEvent { event ->
                    // Enter sends, Shift+Enter breaks the line — the field is multi-line now, so the
                    // key has to be claimed here or it would only ever insert a newline.
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Enter && !event.isShiftPressed) {
                        submit()
                        true
                    } else {
                        false
                    }
                },
            )
        }
        ContextChip(controller)
        // Send doubles as stop while an answer streams: one control, never two states at once.
        Box(
            Modifier.size(26.dp).clip(RoundedCornerShape(8.dp))
                .background(if (controller.busy) Skerry.colors.overlayMed else Skerry.colors.cyan)
                .clickable { if (controller.busy) controller.cancel() else submit() },
            contentAlignment = Alignment.Center,
        ) {
            if (controller.busy) {
                Sym("stop", size = 15.sp, color = Skerry.colors.textMid)
            } else {
                Sym("arrow_upward", size = 15.sp, color = Skerry.colors.ink)
            }
        }
    }
}

/**
 * How much recent output goes with the next question. An icon rather than a labelled chip: the ask
 * row is narrow, and the value matters when it is being changed, not on every glance — the tooltip
 * and the menu both state it. Dimmed at zero, the one setting worth noticing at rest.
 */
@Composable
private fun ContextChip(controller: SessionAssistantController) {
    var open by remember { mutableStateOf(false) }
    AnchoredDropdown(
        expanded = open,
        onDismiss = { open = false },
        trigger = {
            IconBtn(
                "tune",
                onClick = { open = !open },
                box = 26,
                icon = 15.sp,
                tint = if (controller.contextOutputs == 0) Skerry.colors.faint else Skerry.colors.textMid,
                tooltip = stringResource(Res.string.assistant_context_chip, controller.contextOutputs),
            )
        },
        menu = {
            Column(
                Modifier
                    .width(200.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(Skerry.colors.surface2)
                    .border(1.dp, Skerry.colors.cyan14, RoundedCornerShape(7.dp))
                    .padding(4.dp),
            ) {
                Txt(
                    stringResource(Res.string.assistant_context_title),
                    color = Skerry.colors.faint,
                    size = 10.sp,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                )
                SessionAssistantController.CONTEXT_CHOICES.forEach { count ->
                    val selected = count == controller.contextOutputs
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(5.dp))
                            .handsKeyboardBack().clickable {
                                controller.selectContextOutputs(count)
                                open = false
                            }
                            .padding(horizontal = 8.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Sym(
                            if (selected) "radio_button_checked" else "radio_button_unchecked",
                            size = 14.sp,
                            color = if (selected) Skerry.colors.cyanBright else Skerry.colors.faint,
                        )
                        Txt(
                            if (count == 0) stringResource(Res.string.assistant_context_off) else count.toString(),
                            color = if (selected) Skerry.colors.text else Skerry.colors.dim,
                            size = 12.sp,
                        )
                    }
                }
            }
        },
    )
}

/**
 * The recent command blocks of the focused pane, oldest first. Reads the live screen at send time;
 * an empty list when nothing is connected or the prompt can't be recognised, in which case the
 * question travels alone rather than dragging the whole screen along.
 */
private fun recentOutputs(terminal: TerminalScreenState?, count: Int): List<String> =
    if (terminal == null || count <= 0) emptyList() else lastCommandBlocks(terminal.output, count)

/**
 * Label of the endpoint that will answer: the on-device model, or the configured cloud model. Shown
 * in the header so it is clear where the session's output would go before a question is sent.
 */
@Composable
internal fun assistantModelLabel(assistant: AiAssistantController): String {
    val settings = assistant.settings
    return if (settings.provider == AiProviderKind.DEVICE) {
        "local · " + LocalModelCatalog.resolve(settings.localModelId).displayName
    } else {
        "cloud · " + settings.model
    }
}

/** Ask row height at rest, matching the mock's 42dp strip; it grows with the input. */
private val ASK_ROW_HEIGHT = 42.dp

/** How long the leaked "/" of the focus chord may take to arrive after the field takes focus. */
private const val FOCUS_SETTLE_MS = 50L

/** The character the focus chord leaks into the field (⌘/ and Ctrl+Shift+/ both end in it). */
private const val CHORD_KEY = "/"

/** How tall the input may grow before it starts scrolling instead. */
private const val ASK_MAX_LINES = 6
