package app.skerry.ui.design

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
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
 * What the field holds at rest, and what every edit is read against.
 *
 * A Backspace is only visible to the funnel as the deletion of a character, so the field needs
 * something before the caret for the IME to take. Six rather than one because the buffer has to
 * survive an edit that deletes more than one character — a keyboard batching repeats into a single
 * `endBatchEdit`, a word-delete gesture, a keyboard that reads the text before the cursor before
 * deciding to send a deletion at all. They are not a press budget: the funnel is handed the edit
 * synchronously and refills the field before the next one, and characters taken in one edit are one
 * deletion whatever their count ([editBetween]).
 */
internal val FUNNEL_TEXT: String = ANCHOR.toString().repeat(6)

/**
 * The edit that turns [base] into [value], as PTY bytes: a [DEL] if it deleted anything, then the
 * characters it added, with `\n` mapped to [CR] (Enter).
 *
 * Matched from both ends rather than the front alone — the caret rests among the anchors, so a
 * typed character lands in the middle of the value and a prefix-only diff would read the anchors
 * behind it as freshly typed text.
 *
 * An edit that took characters is one Backspace, however many it took. All it can ever take is
 * anchors — [base] is what the field rests at, and nothing the user typed is left there — so the
 * count says nothing about presses: a single Backspace takes one anchor, and a "clear" or
 * word-delete gesture takes the rest with it. Charging per character would erase characters nobody
 * typed; the cost of the other choice is a keyboard that batches two Backspace repeats into one
 * edit, which gets one DEL for the two presses.
 */
internal fun editBetween(base: String, value: String): String {
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
    return buildString {
        if (deleted.isNotEmpty()) append(DEL)
        // An edit that changed both ends of the value carries the anchors between them; they are
        // scaffolding, and a zero-width space on a shell line is invisible in the command the
        // production guard shows and in the history it is saved to.
        for (ch in added) if (ch != ANCHOR) append(if (ch == '\n') CR else ch)
    }
}

/**
 * The invisible field itself: holds IME focus and feeds [onInput], drawing nothing and keeping
 * nothing.
 *
 * What the keyboard types never becomes the field's value: an [InputTransformation] is handed the
 * edited buffer synchronously, reads the difference from the anchors as bytes for the session, and
 * reverts the buffer in place. The field is at [FUNNEL_TEXT] before the edit and after it, so what
 * an accessibility service can read out of `EditableText` is six zero-width spaces — on a login
 * prompt the alternative is a password.
 *
 * Synchronously is the whole point. The `TextFieldValue` overload this used to be built on carried
 * its reset through a recomposition, so edits the keyboard delivered inside one frame were read
 * against a stale value — Samsung Keyboard delivers part of its number row as two edits ~2 ms
 * apart, and `1234567` arrived as `11233455677` on a Galaxy S24 (#347). That overload also drops an
 * edit whose result equals the value the field was composed with, which cost a Backspace and the
 * keypress after it (#348). Neither exists here: the transformation runs inside the edit, before
 * the IME is told anything, and the value it reads against is the one the field is resting at.
 *
 * [onInput] is therefore called on the main thread from inside the edit, and it may neither block nor
 * throw: foundation commits the reverted buffer only after the transformation returns, so an
 * exception would strand the typed character in the editor's own buffer and send it again with the
 * next keypress. Callers write state and hand bytes to a session, both non-blocking.
 *
 * Nothing is drawn, so [name] — the accessible name — is all a screen reader has, and the field
 * owns its own 1 dp size: a caller that forgot it would put a full-size transparent field over the
 * surface it belongs to. [modifier] carries the caller's focus requester. [keyboardOptions] differ
 * per caller: a shell needs `Ascii` (IME_FLAG_FORCE_ASCII), a remote desktop login prefers the
 * password variation. The field is left multi-line on purpose: a single-line `EditorInfo` takes the
 * newline away from the soft keyboard's return key, and Enter is how a shell command is run.
 */
@Composable
internal fun ImeFunnelField(
    name: String,
    modifier: Modifier,
    keyboardOptions: KeyboardOptions,
    onInput: (String) -> Unit,
) {
    val state = remember { TextFieldState(FUNNEL_TEXT, TextRange(FUNNEL_TEXT.length)) }
    val input = rememberUpdatedState(onInput)
    val funnel = remember {
        InputTransformation {
            val typed = editBetween(originalText.toString(), asCharSequence().toString())
            // Read first, then revert, then report: the buffer foundation commits after this
            // returns has to be the anchors, and the caller must not see an edit the field kept.
            revertAllChanges()
            if (typed.isNotEmpty()) input.value(typed)
        }
    }
    BasicTextField(
        state = state,
        modifier = Modifier.size(1.dp).fieldName(name).then(modifier),
        inputTransformation = funnel,
        textStyle = FUNNEL_TEXT_STYLE,
        keyboardOptions = keyboardOptions,
        // Spelled out although it is the default — see the multi-line note above. Nothing in a test
        // can catch SingleLine here: it shapes EditorInfo, not the buffer the transformation reads.
        lineLimits = TextFieldLineLimits.MultiLine(),
        cursorBrush = FUNNEL_CURSOR,
    )
}

private val FUNNEL_TEXT_STYLE = TextStyle(color = Color.Transparent)
// Unspecified rather than Transparent: Compose reads any specified colour as "draw a cursor" and
// keeps a blink coroutine and a 2 Hz draw invalidation alive for it. Nothing is drawn either way.
private val FUNNEL_CURSOR = SolidColor(Color.Unspecified)
