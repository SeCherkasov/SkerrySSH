package app.skerry.ui.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * Caret state for a text field whose value lives outside it as a plain [String].
 *
 * `BasicTextField`'s String overload seeds its own caret at offset 0 and never moves it on focus,
 * so a prefilled field typed into after Tab or an autofocus prepends instead of replacing. This
 * holds the missing piece — selection and IME composition — next to the caller's string, and keeps
 * the two in step: a caret only survives for the exact text it was measured against.
 */
@Stable
internal class FieldDraft(private val masked: Boolean = false, private val singleLine: Boolean = true) {
    /** The text [selection] was measured against; a value replaced from outside no longer matches. */
    private var anchor by mutableStateOf<String?>(null)
    private var selection by mutableStateOf<TextRange?>(null)
    private var composition by mutableStateOf<TextRange?>(null)

    /** The caller's value as of the last [accept]; a change means it was replaced from outside. */
    private var lastCurrent by mutableStateOf<String?>(null)

    /** Whether the last [accept] handed the caller new text — see [caretIn]. */
    private var emitted by mutableStateOf(false)

    /**
     * Where the gesture that brought focus here put the caret, until the one write it still owes
     * arrives. A mouse click reaches the field twice — the selection gesture places the caret on the
     * press, `detectTapAndPress` places it again on the release — and a selection made on focus goes
     * on between the two, so the release would undo it. Exactly one write is ignored, and the next
     * one lands wherever it points. Not read during composition, so it stays a plain field.
     *
     * Only a gesture still holding the field owes anything, which is what a mouse press does and a
     * finished tap does not. Focus arriving from the keyboard records nothing at all — `Home` and
     * `Left` both collapse a full selection onto offset 0, the same shape a click there has, and
     * `Left` is how a screen reader reads a field it has just landed on.
     */
    private var gestureCaret: Int? = null

    /** Written by [fieldFocus]; read by [rememberFieldDraft] to drive the select-on-focus rule. */
    internal var focused by mutableStateOf(false)

    /** Written by [fieldFocus]: whether a pointer is on the field right now. See [gestureCaret]. */
    internal var pressed = false

    /**
     * The value to hand to `BasicTextField`, built around the caller's [text].
     *
     * A single-line field taking focus with no caret of its own starts after the text, where an
     * editor puts it, never at offset 0, which turns the first keystroke into a prepend. A
     * multi-line one starts at the top the way a file opens in the editor — its content is read
     * before it is edited, and the end of it may be several screens down.
     *
     * A [text] that no longer matches the caret's [anchor] changed while the field held it. A
     * caller only ever takes away from what it was handed — `capNotes` truncates, a sanitizer
     * strips — so a text no lengthier than the one emitted is that edit coming back, and the
     * offset it was typed at survives, clamped. A text that grew came from somewhere else (the
     * port refilled when the protocol changes) and starts at its end. Either way the range goes:
     * one measured against text that is gone means nothing.
     *
     * An unfocused field keeps the caret at the start: the field scrolls to follow the caret
     * whether it has focus or not (`TextFieldScroll`), and a value wider than its box would
     * otherwise sit there showing its tail with the beginning clipped.
     */
    fun textFieldValue(text: String): TextFieldValue = when {
        anchor == text -> TextFieldValue(text, selection ?: TextRange(text.length), composition)
        anchor != null -> TextFieldValue(text, TextRange(caretIn(text)))
        focused && singleLine -> TextFieldValue(text, TextRange(text.length))
        else -> TextFieldValue(text)
    }

    /** Where the caret lands in a [text] the caller rewrote or replaced; see [textFieldValue]. */
    private fun caretIn(text: String): Int {
        // Both halves are needed. Without [emitted], a replacement that happens to be as wide as the
        // value it replaces (RDP 3389 to VNC 5900) reads as a rewrite and keeps a caret from the
        // middle of the old one; without the length, an edit still in flight when the value is
        // replaced (the stepper's + button) reads as a rewrite of text the caller never saw.
        val rewritten = emitted && text.length <= (anchor?.length ?: 0)
        return if (rewritten) (selection?.end ?: text.length).coerceAtMost(text.length) else text.length
    }

    /**
     * Take the caret back from the field after an edit, a click or a drag, and forward the text to
     * [onText] — but only when it actually changed. The String overload of `BasicTextField` filters
     * that call itself; every caret move reaching a caller's setter would re-run its side effects
     * (clearing an error banner, committing a setting) on nothing more than a click. A caller free
     * to store something other than what it was handed — a capped note, a sanitized parameter — is
     * accounted for by [textFieldValue], which keeps the caret across the rewrite. What [onText]
     * must not do is act on being called rather than on the text: an edit and a caret move landing
     * in the same frame can hand it the same string twice, and no signal available here separates
     * that from a caller refusing the edit.
     */
    fun accept(next: TextFieldValue, current: String, onText: (String) -> Unit) {
        val owed = gestureCaret
        gestureCaret = null
        if (next.text == anchor && next.selection.collapsed && next.selection.end == owed) return
        // Compared against the last text this field produced, not against [current]: the field can
        // emit twice before the caller's state recomposes (batched IME edits), and the second
        // emission would be measured against a value already one edit stale — dropping the newer
        // text. Once [current] itself moves, though, the caller has replaced the value and its own
        // string is the baseline again — otherwise a field whose value was set from outside would
        // swallow an edit that happens to reproduce the text this field last emitted.
        val previous = if (lastCurrent == current) anchor ?: current else current
        lastCurrent = current
        anchor = next.text
        selection = next.selection
        composition = next.composition
        // Also when the field and the caller simply disagree: a caller free to refuse an edit (the
        // web-access password ignores input while a save is in flight) leaves its own value where
        // it was, so the retyped character would match the text this field last emitted and the key
        // would go dead for good.
        emitted = next.text != previous || next.text != current
        if (emitted) onText(next.text)
    }

    /**
     * Focus arrived: select the whole value when the caller says it is still a default. Masked
     * input and multi-line areas never do, whatever they ask for — a selected password reveals its
     * length, and a pasted key is edited, not replaced wholesale.
     */
    fun focusGained(text: String, selectAll: Boolean) {
        val selectable = !masked && singleLine
        if (!selectAll || !selectable || text.isEmpty()) return
        // The caret the focusing gesture already placed, and only while that gesture is still under
        // a finger or a button — one that is over reports once, before this runs, and owes nothing.
        // Its repeat on the release is ignored; a click anywhere else is the user asking to edit in
        // place. With nothing on record the click landed on offset 0 — that is the caret an
        // unfocused field is handed, and a gesture that does not move it is never reported at all.
        gestureCaret = if (!pressed) null else if (anchor == text) selection?.end else 0
        anchor = text
        selection = TextRange(0, text.length)
        emitted = false
        // A composing region under a full selection is about to be committed anyway; keeping it
        // would restart the IME with a stale range.
        composition = null
    }

    /** Focus left: forget the caret so the next visit starts from a known state. */
    fun focusLost() {
        anchor = null
        lastCurrent = null
        emitted = false
        gestureCaret = null
        selection = null
        composition = null
    }
}

/**
 * Remembers a [FieldDraft] for a field currently showing [text], selecting the whole value when the
 * field takes focus and [selectAllOnFocus] holds (a still-default port, a name arriving prefilled
 * for a rename). Everything else keeps the caret where it was put. [masked] and [singleLine]
 * describe the shape of the field, are the same values its `BasicTextField` is given, and do not
 * change over its life — the draft is rebuilt if they do, and a rebuilt one has no caret.
 */
@Composable
internal fun rememberFieldDraft(
    text: String,
    selectAllOnFocus: Boolean = false,
    masked: Boolean = false,
    singleLine: Boolean = true,
): FieldDraft {
    val draft = remember(masked, singleLine) { FieldDraft(masked, singleLine) }
    // An effect rather than the focus callback: a tap requests focus and then drops the caret at
    // the tapped offset inside the same gesture, so a selection made while the focus is being
    // granted is undone by the very click that asked for it. Applying it once the gesture is over
    // — the effect runs after the recomposition it triggered — outlives both paths, click and Tab.
    LaunchedEffect(draft.focused) {
        if (draft.focused) draft.focusGained(text, selectAllOnFocus) else draft.focusLost()
    }
    return draft
}

/**
 * A [FieldDraft] for a find or filter bar: [text] is selected on focus for as long as it is the
 * value the bar opened with — reopened over a query carried in from the last time, the next
 * keystroke starts a new search; once the user has typed over it, clicking back in refines instead
 * of wiping. [keys] are what makes the bar a new one (the pane and path behind a file filter).
 */
@Composable
internal fun rememberSeededDraft(text: String, vararg keys: Any?): FieldDraft {
    val seeded = remember(*keys) { text }
    return rememberFieldDraft(text, selectAllOnFocus = text == seeded && text.isNotEmpty())
}

/** Feeds focus changes into [draft]; goes on the `BasicTextField` itself, not on its decoration. */
internal fun Modifier.fieldFocus(draft: FieldDraft): Modifier = this
    .onFocusChanged { draft.focused = it.isFocused }
    // Watched in the initial pass and never consumed: the field's own handlers still see every
    // event. This only tells focus that arrived under a finger or a mouse button from focus that
    // arrived off the keyboard, which owes no caret write and must not have one taken.
    .pointerInput(draft) {
        // The gesture can end by being cancelled rather than released — the field leaving
        // composition under a finger, a parent taking the stream over — and a flag left standing
        // would make the next keyboard focus owe a write it never received.
        try {
            awaitPointerEventScope {
                while (true) {
                    draft.pressed = awaitPointerEvent(PointerEventPass.Initial).changes.any { it.pressed }
                }
            }
        } finally {
            draft.pressed = false
        }
    }
