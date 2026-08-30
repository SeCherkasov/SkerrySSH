package app.skerry.ui.design

import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp

/**
 * Filler character of the hidden IME field. Zero-width space (U+200B): invisible, not echoed by a
 * shell, does not break alignment. Given as a code point rather than a literal so it is not
 * invisible in the source (Read/grep).
 */
internal val ANCHOR: Char = Char(0x200b)

/** DEL — shell Backspace; CR — Enter. Code points, so they are not invisible in Read/grep. */
private val DEL: Char = Char(0x7f)
private val CR: Char = Char(0x0d)

/**
 * What the field is reset to after every edit, and all it ever holds at rest.
 *
 * Six anchors rather than one: a Backspace is only visible to the funnel as the deletion of a
 * character, so the field needs something before the caret to delete. It is refilled once per
 * composition, and a held Backspace repeating at ~50 ms puts several presses inside one frame — a
 * frame that a terminal streaming output can stretch. Each anchor past the first is one more press
 * that survives the wait, as long as the keyboard reports each press as its own edit; below four,
 * an exhaustive model loses presses.
 *
 * The budget is what sits *before the caret*: six from the untouched state, five once anything has
 * been typed, because the resting caret alternates between the two positions. Past that the caret is
 * at 0, a delete before it has nothing to remove, and the IME reports no edit at all — the press is
 * lost with nothing to observe it. Presses the keyboard coalesces into one edit are one deletion
 * whatever the count, and no number of anchors changes that.
 */
internal val FUNNEL_TEXT: String = ANCHOR.toString().repeat(6)

/**
 * Turns what the soft keyboard typed into bytes for a session, and keeps none of it at rest.
 *
 * The field is held at [FUNNEL_TEXT] and reset to it after every edit, so what it holds is never
 * what was typed: on a login prompt that is a password, and a field's value is `EditableText` in
 * the semantics tree.
 *
 * **The one thing this class has to know is whether that reset has already reached the editor.**
 * `CoreTextField` resets its `EditProcessor` to the value it was composed with on every
 * recomposition, and it invalidates itself after every IME edit — so the reset lands once a frame
 * runs, and edits the keyboard delivers before that frame still build on what the field held
 * before. Samsung Keyboard delivers part of its number row as two edits ~2 ms apart, and reading
 * the second one against the anchors sent the digit twice: `1234567` arrived as `11233455677` on a
 * Galaxy S24.
 *
 * So the answer is observed rather than guessed. [resetLanded] is called from the composition that
 * carries the reset into the editor, and until then [accept] diffs against what it last saw. That
 * is also why the caret alternates: this class only hears about a composition through its own
 * composable, which recomposes only when the value written to the field differs from the value it
 * was last composed with. The field's *text* stays constant across resets, which is what keeps
 * `TextDelegate` and the text layout alive — rebuilding them per keystroke made typing visibly
 * sluggish on the device.
 */
internal class ImeFunnel {
    val text: String = FUNNEL_TEXT

    /** Caret offset the field is reset to — never the one the editor is already holding. */
    var caret: Int = text.length
        private set

    /** Caret the editor was last composed with — the field is constructed with [text]/[caret]. */
    private var landedCaret: Int = caret

    /** The value the funnel last saw in the field — the baseline the next edit is read against. */
    private var seen: String = text

    /**
     * Bytes for the session, given what the field now holds. The caller then resets the field to
     * [text]/[caret]; whether that reset has reached the editor is not inferred from the value —
     * [resetLanded] says so.
     */
    fun accept(value: String): String {
        val bytes = editBetween(seen, value)
        seen = value
        caret = if (landedCaret == text.length) text.length - 1 else text.length
        return bytes
    }

    /**
     * The field has been composed with [text]/[caret]: the editor's buffer is the anchors again, so
     * the next edit is an edit of those and not of what the previous one left behind.
     */
    fun resetLanded() {
        landedCaret = caret
        seen = text
    }
}

/**
 * The edit that turns [base] into [value], as PTY bytes: one [DEL] per character deleted, then the
 * characters added, with `\n` mapped to [CR] (Enter).
 *
 * Matched from both ends rather than the front alone — the caret rests among the anchors, so a
 * typed character lands in the middle of the value and a prefix-only diff would read the anchors
 * behind it as freshly typed text.
 *
 * Deletions are counted in characters the session already has: one Backspace per deleted character
 * the user typed, and — only when the edit took nothing but anchors — one for the gesture itself.
 * A single press takes one anchor; a "clear" or word-delete gesture takes the rest with it, and
 * charging those would erase characters nobody typed. The cost of that choice: a keyboard that
 * batches two Backspace repeats into one edit is indistinguishable from a clear gesture here, and
 * gets one DEL for the two presses.
 */
private fun editBetween(base: String, value: String): String {
    var head = 0
    while (head < base.length && head < value.length && base[head] == value[head]) head++
    var tail = 0
    while (
        tail < base.length - head &&
        tail < value.length - head &&
        base[base.length - 1 - tail] == value[value.length - 1 - tail]
    ) {
        tail++
    }
    val deleted = base.substring(head, base.length - tail)
    val added = value.substring(head, value.length - tail)
    val typedDeletions = deleted.count { it != ANCHOR }
    val backspaces = when {
        typedDeletions > 0 -> typedDeletions
        deleted.isNotEmpty() -> 1
        else -> 0
    }
    return buildString {
        repeat(backspaces) { append(DEL) }
        // An edit that changed both ends of the value carries the anchors between them; they are
        // scaffolding, and a zero-width space on a shell line is invisible in the command the
        // production guard shows and in the history it is saved to.
        for (ch in added) if (ch != ANCHOR) append(if (ch == '\n') CR else ch)
    }
}

/**
 * The invisible field itself: holds IME focus and feeds [onInput], drawing nothing.
 *
 * Nothing is drawn here, so [name] — the accessible name — is all a screen reader has, and the
 * field owns its own 1 dp size: a caller that forgot it would put a full-size transparent field over
 * the surface it belongs to. [modifier] carries the caller's focus requester. [keyboardOptions]
 * differ per caller: a shell needs `Ascii` (IME_FLAG_FORCE_ASCII), a remote desktop login prefers
 * the password variation. The field is left multi-line on purpose: a single-line `EditorInfo` takes
 * the newline away from the soft keyboard's return key, and Enter is how a shell command is run.
 *
 * One edit is invisible from here: `BasicTextField`'s `TextFieldValue` overload compares against the
 * value it was composed with and drops an edit that lands on it (`BasicTextField.kt:899`). Typing a
 * character and deleting it again *within one frame* returns the field to exactly that value, so the
 * funnel is never told — and because it is never told, its baseline still holds the character: an
 * identical keypress right after is read as no change and is lost too. Two presses can go missing
 * from a burst of `type · delete · type`.
 *
 * It stays, deliberately. Sub-frame type-and-delete is not a thumb's timing, and every escape is
 * worse on the device this exists for: a resting value that a delete cannot land on means either a
 * text that changes per edit — the layout rebuild that made typing visibly sluggish here — or a
 * resting *selection*, which puts selection handles and a floating toolbar over the session. The
 * clean fix is the `TextFieldState` overload, whose `InputTransformation` sees every edit
 * synchronously and needs no frame at all; that is an input-path rewrite, and it needs the device.
 */
@Composable
internal fun ImeFunnelField(
    name: String,
    modifier: Modifier,
    keyboardOptions: KeyboardOptions,
    onInput: (String) -> Unit,
) {
    val funnel = remember { ImeFunnel() }
    var value by remember { mutableStateOf(TextFieldValue(funnel.text, TextRange(funnel.caret))) }
    BasicTextField(
        value = value,
        onValueChange = { new ->
            val typed = funnel.accept(new.text)
            value = TextFieldValue(funnel.text, TextRange(funnel.caret))
            if (typed.isNotEmpty()) onInput(typed)
        },
        modifier = Modifier.size(1.dp).fieldName(name).then(modifier),
        textStyle = FUNNEL_TEXT_STYLE,
        cursorBrush = FUNNEL_CURSOR,
        keyboardOptions = keyboardOptions,
    )
    // Composing the field is what carries the reset into its EditProcessor, and this runs after that
    // composition — so from here the keyboard is editing the anchors again. It also runs for
    // recompositions the funnel did not cause, which is harmless: outside the window between an edit
    // and the next frame, the editor is holding the anchors anyway.
    SideEffect { funnel.resetLanded() }
    // Leaving the composition ends the field without a further frame; drop what the last edit
    // carried rather than hold it for as long as the funnel is referenced.
    DisposableEffect(Unit) { onDispose { funnel.resetLanded() } }
}

private val FUNNEL_TEXT_STYLE = TextStyle(color = Color.Transparent)
// Unspecified rather than Transparent: Compose reads any specified colour as "draw a cursor" and
// keeps a blink coroutine and a 2 Hz draw invalidation alive for it. Nothing is drawn either way.
private val FUNNEL_CURSOR = SolidColor(Color.Unspecified)
