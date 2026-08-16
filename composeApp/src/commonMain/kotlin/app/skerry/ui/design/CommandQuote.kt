package app.skerry.ui.design

import app.skerry.shared.text.INVISIBLE_ASTRAL
import app.skerry.shared.text.drawsAsSomething
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.command_clipped
import app.skerry.ui.generated.resources.command_clipped_partial
import app.skerry.ui.theme.Skerry
import org.jetbrains.compose.resources.stringResource

/**
 * A command as a confirmation shows it — the assistant's card, the mobile AI bar, the production
 * guard's dialog. All three ask the same question ("run this?") and so owe the same answer: what is
 * drawn is what will run, or it is unmistakable that it is not.
 *
 * The rules that live here rather than in each of them, because they drifted apart the first time
 * they were written three times:
 *
 * - **Wrap, never scroll sideways.** A line drawn past the right edge is not shortened, it is
 *   invisible: the reported case was `ls`, a screen of spaces and an exfiltration tail rendered as a
 *   green one-click card reading `ls`.
 * - **Grow to [visibleLines], then scroll.** Bounded so the block cannot push its own buttons off the
 *   screen, scrollable so what it cannot show is still reachable, and a focus stop while it scrolls
 *   so reaching it does not need a mouse.
 * - **Measured in lines, not pixels**, so an exactly-full box is not called clipped by the rounding
 *   of a line height.
 * - **Cut at [MAX_DRAWN_COMMAND_CHARS].** A wrapped block draws its viewport but is measured whole,
 *   and this text is not the app's: a model reply, a pasted buffer, a snippet someone else wrote.
 * - **Anything that does not draw as itself is spelled out.** Dropping it would make the drawn
 *   text differ from what runs; leaving it is how a line renders in an order the shell never
 *   sees, or with characters nothing on screen accounts for.
 *
 * [onFit] reports whether the whole of [text] fitted — `null` until the first layout, which callers
 * that gate a Run button on it must read as "not shown". [onLayout] hands over the layout itself for
 * the one question this cannot answer: whether a particular line of a block was among what was
 * drawn.
 */
@Composable
internal fun CommandQuote(
    text: String,
    visibleLines: Int,
    modifier: Modifier = Modifier,
    color: Color = Skerry.colors.text,
    size: TextUnit = 12.sp,
    lineHeight: TextUnit = 18.sp,
    padding: Dp = 0.dp,
    /**
     * Latches the clipped state for this [text] once set. For a quote whose width depends on what
     * being clipped decides — the mobile bar's chip label sits in the command's row — recomputing
     * can oscillate; everywhere else the width is fixed and this stays off.
     */
    latchClipped: Boolean = false,
    /**
     * The caption drawn over this block, when a surface shows more than one and they have to be told
     * apart. It becomes the block's accessible name once the block is a focus stop: reaching it by
     * Tab or switch access lands on the box, not on the caption above it, and a command with no word
     * for which of the two it is says nothing about whether it is going to run.
     */
    label: String = "",
    onFit: (Boolean?) -> Unit = {},
    onLayout: (TextLayoutResult) -> Unit = {},
) {
    // Cut on both sides of the escape, and recorded as its own fact: escaping makes the drawn string
    // longer than what it came from, so comparing the two lengths afterwards would call a truncated
    // block whole.
    val escaped = remember(text) { visibleText(text.cutTo(MAX_DRAWN_COMMAND_CHARS)) }
    val cut = text.length > MAX_DRAWN_COMMAND_CHARS || escaped.length > MAX_DRAWN_COMMAND_CHARS
    val drawn = remember(escaped) { boundEscaped(escaped) }
    var fits by remember(text) { mutableStateOf<Boolean?>(null) }
    val clipped = fits == false || cut
    // Focusability is latched even where the clipped state is not (a desktop dialog is resizable, so
    // the same text can stop overflowing): dropping `focusable()` from a node that holds focus makes
    // Compose clear focus to the root, so widening the window would restart Tab at the first stop
    // and take a screen reader back to the top of the dialog mid-read.
    var everClipped by remember(text) { mutableStateOf(false) }
    if (clipped) everClipped = true
    LaunchedEffect(text, fits) { onFit(if (cut) false else fits) }
    Box(
        modifier
            .heightIn(max = with(LocalDensity.current) { lineHeight.toDp() * visibleLines } + padding * 2)
            .verticalScroll(rememberSaveable(text, saver = ScrollState.Saver) { ScrollState(0) })
            .then(
                if (!everClipped) Modifier
                else Modifier.focusable().then(
                    if (label.isEmpty()) Modifier
                    else Modifier.semantics(mergeDescendants = true) { contentDescription = "$label: $drawn" },
                ),
            )
            .padding(vertical = padding),
    ) {
        // Nothing to draw draws nothing: an empty text node is a stop with no content for anyone
        // reading the dialog in order, and it says nothing to anyone else.
        // Direction pinned rather than inherited: a shell line is LTR by nature, and an unstyled Txt
        // resolves its paragraph base from the layout direction. Under an RTL UI locale a command
        // whose first strong character is RTL would render with its trailing `#` hoisted to the
        // front — a live command reading as a comment, next to the Run button.
        if (drawn.isEmpty()) return@Box
        Txt(
            drawn,
            color = color,
            size = size,
            lineHeight = lineHeight,
            font = LocalFonts.current.mono,
            textDirection = TextDirection.Ltr,
            // Remembered, not rebuilt per recomposition: the text node compares this callback by
            // identity and re-measures and re-draws its paragraph whenever it changes, and a card's
            // actions arrive as a fresh lambda holder on every pass of the panel.
            //
            // Keyed on the text as well, and that is not decoration: the lambda captures the `fits`
            // state *object*, which `remember(text)` replaces when the text does. A callback that
            // outlived it would keep answering into the discarded one, and the live state would stay
            // unmeasured for good — no notice, no focus stop, a block silently drawn in part.
            onTextLayout = remember(text, latchClipped, visibleLines, onLayout) {
                { layout: TextLayoutResult ->
                    if (!latchClipped || fits != false) fits = layout.lineCount <= visibleLines
                    onLayout(layout)
                }
            },
        )
    }
}

/**
 * A command as a *row* shows it: one line or a few, ellipsized, never scrolled. [CommandQuote] is
 * the confirmation surface; this is the list surface, and it was written by hand at five call sites
 * that agreed on the contract by accident. The contract is: escaped and bounded exactly like the
 * quote ([boundedVisibleText]), monospaced, and pinned to LTR — a command's order is its meaning,
 * and left unpinned a first-strong RTL character flips the row into an order the shell will not use.
 *
 * A row has no live region and no scroll of its own on purpose: these are drawn per item of a list
 * that composes whole, and the surface that has to show the line entire is the confirmation.
 */
@Composable
fun CommandLine(
    command: String,
    modifier: Modifier = Modifier,
    color: Color = Skerry.colors.faint,
    size: TextUnit = 10.5.sp,
    lineHeight: TextUnit = TextUnit.Unspecified,
    maxLines: Int = 1,
) {
    Txt(
        remember(command) { boundedVisibleText(command) },
        color = color,
        size = size,
        font = LocalFonts.current.mono,
        lineHeight = lineHeight,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        textDirection = TextDirection.Ltr,
        modifier = modifier,
    )
}

/**
 * The line under a [CommandQuote] that says it is not all there, with the command's real length —
 * the part a glance cannot get wrong.
 *
 * Composed in both states, empty message and all: a node that appears together with its text is an
 * insertion rather than a change, and fires no live-region event (see [StatusAnnouncer]). The
 * announced string is set after composition for the same reason.
 *
 * Known and left: a card scrolling back into the assistant's lazy feed is a fresh composition, so a
 * notice that was already there is announced again. Suppressing that needs state that outlives the
 * list item, which is not this composable's to keep. What is suppressed is the noisier half of the
 * same problem — a count that changes under a reply still being written; see [announce].
 */
@Composable
internal fun ClippedNotice(
    clipped: Boolean,
    /** How much there is in all — null when the caller knows it is partial but not by how much. */
    fullLength: Int?,
    modifier: Modifier = Modifier,
    /**
     * Whether the notice is settled enough to be worth hearing. False while the text it describes is
     * still arriving: a streamed reply's block grows by a few characters at a time, and a live region
     * carrying the count would read a new one out for every delta.
     */
    announce: Boolean = true,
) {
    val partial = stringResource(Res.string.command_clipped_partial)
    val counted = stringResource(Res.string.command_clipped, fullLength ?: 0)
    val notice = when {
        !clipped -> ""
        fullLength == null -> partial
        else -> counted
    }
    var announced by remember { mutableStateOf("") }
    LaunchedEffect(notice, announce) { announced = if (announce) notice else "" }
    StatusAnnouncer(announced)
    if (notice.isNotEmpty()) Notice(notice, modifier)
}

/** A line about the block above it — what it is not showing, or that there was nothing to show. */
@Composable
internal fun Notice(text: String, modifier: Modifier = Modifier) {
    Txt(text, color = Skerry.colors.amber, size = 10.5.sp, modifier = modifier)
}

/**
 * [raw] with every character that does not draw as itself spelled out instead. A bidi override
 * renders the rest of the line in an order the shell will not use, a zero-width character draws as
 * nothing at all, and a control byte as less than that; each is how a quoted command lies about
 * itself. Dropping them would be the other kind of lie — the drawn text would no longer be the text
 * that runs — so they are made visible.
 *
 * The predicate is the strict one ([drawsAsItself]), stricter than the display filter untrusted
 * prose gets: the looser predicate keeps the zero-width set and the directional marks, which
 * reorder the digits of a path or a port and draw as nothing, and this is the one place where the
 * glyphs on screen have to be the bytes that will run. Newlines are the exception — a quoted block
 * is several lines by nature.
 *
 * Not reversible, and knowingly: a command that contains the literal text `<U+202E>` draws exactly
 * like one that contains the character. The ambiguity is one-directional — a real override is
 * always spelled out — so it can only warn about a line that needed no warning.
 */
internal fun visibleText(raw: String): String {
    // A block's lines arrive separated by CR on the way to a PTY — the Android IME funnel sends one
    // per line — so a CR is a break to draw, not a byte to spell out.
    val text = raw.replace("\r\n", "\n").replace('\r', '\n')
    return buildString(text.length) {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val low = if (c.isHighSurrogate() && i + 1 < text.length) text[i + 1] else null
            val paired = low?.isLowSurrogate() == true
            val code = if (paired) codePoint(c, low) else c.code
            val width = if (paired) 2 else 1
            if (drawsAsItself(code)) append(text, i, i + width) else appendEscaped(code)
            i += width
        }
    }
}

/**
 * [take], and then the half of a character the cut may have left behind — it belongs to nothing and
 * draws as the replacement glyph, which is neither what was written nor an escape of it, the same
 * reason [cutWholeTokens] exists for a cut escape.
 *
 * Only where the cut happened. A lone half in text this never touched is what the command holds, and
 * it is spelled out like anything else that does not draw as itself; dropping it there would draw
 * less than what runs.
 */
private fun String.cutTo(max: Int): String =
    if (length <= max) this else take(max).dropLastWhile { it.isHighSurrogate() }

/**
 * A command drawn outside [CommandQuote]'s own box — a library row, a palette entry, the line a
 * runbook step will send. Escaped like the quote's, and bounded like it too: spelling a character
 * out makes the string longer than what it came from, so text made of nothing else would draw as
 * several times its own size. Where the caller has room for the quote itself, use that instead —
 * it also says out loud that what is drawn was cut.
 */
internal fun boundedVisibleText(raw: String): String = boundEscaped(visibleText(raw.cutTo(MAX_DRAWN_COMMAND_CHARS)))

private fun boundEscaped(escaped: String): String =
    if (escaped.length <= MAX_DRAWN_COMMAND_CHARS) escaped
    else cutWholeTokens(escaped.cutTo(MAX_DRAWN_COMMAND_CHARS))

private fun codePoint(high: Char, low: Char): Int =
    0x10000 + ((high.code - 0xD800) shl 10) + (low.code - 0xDC00)

private fun StringBuilder.appendEscaped(code: Int) {
    append(ESCAPE_OPEN).append(code.toString(16).uppercase().padStart(4, '0')).append('>')
}

/**
 * Whether the character at [code] draws as what it is. Decided by category, not by a list: the
 * whole of CONTROL and FORMAT, the separators, and the letters that draw as nothing at all
 * (`INVISIBLE_LETTERS` in the shared filters, which no category names — `curl\u2800evil.sh` reads
 * as two words and runs as one). A blocklist is one Unicode release behind by construction, and this is the block
 * that promises the drawn text is the text that runs — the categories cover everything the
 * hand-written `isSafeTerminalInputChar` pass here used to, and whatever the next Unicode revision
 * adds to them, by default. A tab is left alone: a shell separates words on it too, so a gap there
 * is what it means.
 */
private fun drawsAsItself(code: Int): Boolean {
    // A variation selector draws nothing of its own but changes the glyph before it — `⚠\uFE0F` is
    // the emoji form of `⚠`, not a hidden character, and spelling `<U+FE0F>` into every emoji would
    // be noise rather than a warning. Only the basic-plane sixteen: emoji presentation needs no
    // more, while the supplement (U+E0100..U+E01EF) is a run of invisible code points with no use
    // in a shell line and a known one as a payload, so it stays spelled out.
    if (code in VARIATION_SELECTORS) return true
    if (code > Char.MAX_VALUE.code) return INVISIBLE_ASTRAL.none { code in it }
    val c = code.toChar()
    if (c == '\n' || c == '\t' || c == ' ') return true
    // Everything that draws as nothing at all — the format category, the separators, the control
    // bytes, a lone surrogate, the invisible letters and the default-ignorable marks. One rule,
    // shared with the filters that drop those characters instead of spelling them out.
    if (!drawsAsSomething(c)) return false
    // A blank of space width that no shell splits a word on: `curl\u00A0evil.sh` is one argument and
    // reads as two. Ordinary space and tab are answered above — a shell does split on those.
    return c.category != CharCategory.SPACE_SEPARATOR
}

private val VARIATION_SELECTORS = 0xFE00..0xFE0F

/**
 * A string the cap already cut, without a spelled-out character the cut caught halfway: the cap
 * counts characters, and a `<U+202E>` is eight of them, so the last one can end as `<U+20`. A
 * fragment that reads like a shorter code point is worse than one character less.
 *
 * Only ever called on a string that was cut, and only drops a tail that is a token being written —
 * `<U+` followed by nothing but hex digits. A command that merely contains the literal text `<U+`
 * is drawn as it is; dropping the rest of it would be this file's own bug, a quote that shows less
 * than what runs while claiming to show it all.
 */
private fun cutWholeTokens(cutOff: String): String {
    val open = cutOff.lastIndexOf(ESCAPE_OPEN)
    if (open < 0) return cutOff
    val tail = cutOff.substring(open + ESCAPE_OPEN.length)
    return if (tail.all { it.isDigit() || it in 'A'..'F' }) cutOff.substring(0, open) else cutOff
}

private const val ESCAPE_OPEN = "<U+"

/**
 * How much of a command a confirmation lays out. A few hundred kilobytes in one paragraph costs the
 * frame it appears on, and on the assistant's card one per streamed delta. What is cut is never cut
 * silently — [ClippedNotice] states the real length beside it.
 */
internal const val MAX_DRAWN_COMMAND_CHARS = 8_000
