package app.skerry.ui.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.runtime.State
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.zIndex
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo

/**
 * Chrome that takes the keyboard away from the session it sits beside, and hands it straight back.
 *
 * `Modifier.clickable` claims focus on a mouse press, so a click on the sidebar's collapse handle or
 * a host row leaves the terminal or the framebuffer typing nowhere. The controls that do this say so
 * here ([handsKeyboardBack]) and [ClaimKeyboard] takes the keyboard back on the other side.
 *
 * A counter rather than a flag: the same button is clicked again and again, and each click has to
 * read as a fresh hand-back.
 */
object KeyboardClaim {
    var handBacks by mutableStateOf(0)
        private set

    /** This control took the keyboard for a click; the session that owns it should take it back. */
    fun handBack() {
        handBacks++
    }
}

/**
 * Everything a prompt that opens over a live session owes: it counts as a modal, it holds the
 * caret, and it is drawn where the caret is. Returns the modifier its own root must carry.
 *
 * The order is the point: the keyboard is cleared off whatever had it *first*, and only then
 * claimed — a request that does not land leaves the keys going nowhere, never into the shell or the
 * remote host the prompt opened over. That is what keeps a connect password out of a live session.
 *
 * Retried across a few frames rather than asked once: a field inside a sheet or a subcomposition is
 * not placed on the frame this effect first runs, and a request made then is simply lost (the
 * requester returns false), leaving a prompt nobody can type into. Claimed again when the window
 * comes back, since Compose releases focus app-wide on the way out and restores nothing.
 *
 * The z-order is the same rule seen from the user's side. Two prompts can be up at once — a connect
 * password for one host, a second factor another host asks for while it is up — and they are
 * composed in a fixed order that has nothing to do with which opened last. Laid out by modal token,
 * the one holding the caret is the one on top, so the field being typed into is the field on
 * screen: a password typed into another host's prompt is sent to that host in the clear.
 *
 * [key] is what the prompt is for — a host, a challenge — so a second prompt takes the caret again.
 */
@Composable
fun rememberPromptFocus(focus: FocusRequester, key: Any?): Modifier {
    val token = rememberModalPresence()
    val focusManager = LocalFocusManager.current
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(focus, key) {
        focusManager.clearFocus(force = true)
        claimCaret(focus)
    }
    LaunchedEffect(focus, key, windowFocused) {
        // Not cleared again: whatever the window came back to is this prompt's own field or nothing.
        if (windowFocused) claimCaret(focus)
    }
    return Modifier.zIndex(token.toFloat())
}

private suspend fun claimCaret(focus: FocusRequester) {
    repeat(CARET_FRAMES) {
        withFrameNanos {}
        if (focus.requestFocus(FocusDirection.Enter)) return
    }
}

/**
 * Frames a prompt is given to place its field; beyond this nothing is going to place it. A field
 * inside a sheet needs the second one — and the directional request is what says so honestly: the
 * plain `requestFocus()` answers the first frame with success and leaves the caret where it was.
 */
private const val CARET_FRAMES = 8

/**
 * Marks a control that steals the keyboard on a mouse press without wanting it. Watches the initial
 * pass and consumes nothing — the control's own click handling is untouched.
 *
 * [enabled] follows the control's own: a press on a greyed-out button opens nothing and takes no
 * focus, so there is nothing to hand back — and doing it anyway would move the caret for a click
 * that did nothing.
 *
 * Mouse only, deliberately: focus-on-press is what `Modifier.clickable` does in mouse input mode,
 * and on touch there is nothing to hand back. Keyboard activation (Tab to the control, Enter) is not
 * a press at all and is left alone — yanking focus out from under a keyboard user right after they
 * reached a button is exactly the trap this must not set.
 */
fun Modifier.handsKeyboardBack(enabled: Boolean = true): Modifier = if (!enabled) this else pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            // Primary button only: `clickable` claims focus for that one, and a right-click
            // opening a context menu takes nothing to hand back.
            val takesFocus = event.buttons.isPrimaryPressed && event.changes.any { it.type == PointerType.Mouse }
            if (event.type == PointerEventType.Press && takesFocus) KeyboardClaim.handBack()
        }
    }
}

/**
 * Keeps the keyboard on the widget that owns it — a terminal, a remote framebuffer — because nothing
 * else will put it back. Both are driven entirely by typing, so a lost focus leaves a live-looking
 * session that swallows every keystroke until the user thinks to click it.
 *
 * Three ways focus goes without the widget ever being told to give it up, all of them ordinary use:
 * - **A modal.** Its scrim takes focus for Esc, and closing it disposes the focused node — Compose
 *   then clears focus to no one. That is what [ModalPresence] is counted for.
 * - **The window.** On a lasting focus loss (Alt+Tab away) the scene calls `releaseFocus`, and
 *   coming back restores nothing: `ComposeSceneMediator.focusLost` says so in as many words.
 * - **Chrome.** A control beside the session took focus for a click — see [handsKeyboardBack].
 *
 * What it must never do is take the keyboard from a sibling that was using it: the assistant's ask
 * field and the sidebar's filter sit beside the session and are no one's modal, and a connect
 * password or a 2FA answer is typed into a dialog that draws its own scrim. So the claim is only
 * ever made on behalf of the last widget the keyboard actually belonged to ([owns]) — a hand-back
 * from chrome being the one explicit instruction that overrides it.
 *
 * [key] identifies the session, so a new one claims the keyboard on its own; [focused] is whether
 * the widget holds it right now — a state rather than a value, so a focus change invalidates this
 * composable and not the terminal or framebuffer that owns it; [enabled] is the caller's own reason to stay quiet (a soft-keyboard
 * field owns the input on touch, a find bar owns it while open, an unfocused pane must not steal it).
 */
@Composable
internal fun ClaimKeyboard(focus: FocusRequester, key: Any?, focused: State<Boolean>, enabled: Boolean = true) {
    // Read here rather than in the caller: both are snapshot state, and a window focus change would
    // otherwise recompose a whole terminal or framebuffer view along with its modifier chain.
    val windowInfo = LocalWindowInfo.current
    val windowFocused = windowInfo.isWindowFocused
    val modalsOpen = ModalPresence.openCount
    val handBacks = KeyboardClaim.handBacks
    // A session opens owning the keyboard: that is what makes it typeable without a click first.
    val owns = remember(key) { mutableStateOf(true) }
    val latestEnabled = rememberUpdatedState(enabled)
    LaunchedEffect(key, focused.value, windowFocused, modalsOpen) {
        // Who the keyboard belongs to is decided only while the window is up and nothing modal is
        // over it. A modal taking focus, or the scene clearing it on the way out of the window, is
        // not the user handing the keyboard to a sibling — and both arrive as a burst of platform
        // events, so the answer is read a frame later, once all of it has landed.
        withFrameNanos {}
        if (windowInfo.isWindowFocused && ModalPresence.openCount == 0) owns.value = focused.value
    }
    // The widget's own signal, and the only one that does not ask who owns the keyboard: a session
    // opening, a pane becoming the focused one, a find bar closing over it. Each is this widget
    // saying it is the one that should be typed into — nobody else is being taken from.
    //
    // The signal is remembered rather than acted on once: it can arrive while the window is away or
    // a modal is up, where there is no keyboard to be had, and it would then be lost for good. It is
    // NOT re-raised by the window merely coming back — that is the restore path's job, and only for
    // the widget that owned the keyboard. Without that line a returning window would take the
    // keyboard off whatever field beside the session the user had left the caret in.
    val claim = remember(key) { PendingClaim() }
    LaunchedEffect(key, enabled, windowFocused, modalsOpen, claim.carried) {
        // Spent only once the keyboard was actually asked for: the wait inside [claimKeyboard] is
        // long enough for a modal to open over us, and a signal marked used on a claim that then
        // stood down would leave the widget wanting the keyboard with nothing left to ask again.
        if (claim.wanted(enabled)) {
            if (claimKeyboard(focus, latestEnabled, windowInfo)) claim.done()
        } else if (claim.carried && owns.value) {
            // A claim the restore path could not land. Carried, not adopted: it belongs to the
            // owner, so it is dropped the moment the keyboard is somebody else's.
            if (claimKeyboard(focus, latestEnabled, windowInfo)) claim.carriedDone()
        }
    }
    // The interruptions that come from outside: a modal that took focus and left it with no one, a
    // window that cleared it on the way out. Only the widget that owned the keyboard gets it back.
    //
    // Ownership is read as it stood before this frame, deliberately: by the far side of the frame
    // wait "the keyboard is nowhere" and "the keyboard is a sibling's" look the same — Compose has
    // no way to ask who holds focus — and re-reading would take the restore out for both. The cost
    // is one narrow case: coming back to the window by clicking straight into a field beside the
    // session takes the caret to the session, and the user has to click the field again. Nothing
    // secret rides on it — every field that carries one is a registered modal, which blocks the
    // claim outright. Closing it properly needs the siblings to say when they take the keyboard,
    // which is a register of their own rather than anything readable from here.
    LaunchedEffect(key, windowFocused, modalsOpen) {
        // A claim that did not land (the node is not placed yet, or is already gone) is held over
        // rather than forgotten: the effect above will take it the next time anything moves.
        if (owns.value) {
            if (claimKeyboard(focus, latestEnabled, windowInfo)) claim.carriedDone() else claim.raise()
        }
    }
    // A hand-back reads [owns] as it stood when the press landed: the click's own focus change is
    // only recorded a frame later, so this still sees the widget that owned the keyboard a moment
    // ago. That is what keeps chrome from handing the keyboard to a session the user had already
    // left — a caret in a field beside it, or a framebuffer released on purpose (Ctrl+Alt+Shift+K).
    // Keyed on the counter alone: with [enabled] in the keys, every later change of it would re-run
    // this, and the count never returns to what this session composed with.
    val handBacksAtStart = remember(key) { handBacks }
    LaunchedEffect(key, handBacks) {
        // No re-read of the owner here, unlike the restore above: a hand-back happens exactly when
        // the keyboard has just gone to the control that was clicked, so by the next frame this
        // widget is not the owner — and taking it back is the whole point.
        if (handBacks != handBacksAtStart && owns.value) {
            // Ownership is asserted here rather than left to the focus change that follows: the
            // press wrote `owns = false` on its way through, and a modal opening in the frame or
            // two before the reclaim settles would freeze it there — with nothing left to restore.
            if (claimKeyboard(focus, latestEnabled, windowInfo)) owns.value = true else claim.raise()
        }
    }
}

/**
 * Take the keyboard, unless nobody can have it. Returns whether the node actually took it — a
 * requester whose node is gone (a pane closed under a modal, a session dropped) answers false
 * rather than throwing, and the caller keeps its claim to try again.
 *
 * [enabled] is read on the far side of the frame wait, not captured: the reason to stay quiet can
 * arrive inside that frame.
 */
private suspend fun claimKeyboard(focus: FocusRequester, enabled: State<Boolean>, window: WindowInfo): Boolean {
    if (!enabled.value || blocked(window)) return false
    // The node has to be placed before it can take focus: on a session's first composition this
    // effect runs ahead of the first layout, and the request would be lost.
    withFrameNanos {}
    // Read again on the far side of that wait: a modal opened by the very click that got us here
    // registers in between, and a claim decided a frame ago would take the keyboard off it.
    if (!enabled.value || blocked(window)) return false
    return focus.requestFocus(FocusDirection.Enter)
}

/** A modal above the session owns the keyboard, and a window in the background has none to give. */
private fun blocked(window: WindowInfo): Boolean = !window.isWindowFocused || ModalPresence.openCount > 0

/**
 * The claims a widget still owes itself: its own "I should be typed into" signal ([wanted]) and one
 * carried over from a restore that could not land ([carried]). They are kept apart because only the
 * first may ignore who owns the keyboard — the second is made on the owner's behalf and dies with
 * the ownership. Plain fields, not snapshot state: only the effects that raise them read them.
 */
private class PendingClaim {
    private var enabledBefore = false
    private var pending = false

    /**
     * A claim made for the owner that did not land; retried while the keyboard is still theirs.
     * Snapshot state, unlike [pending]: it is raised by one effect and acted on by another, so
     * raising it has to bring that other one back — nothing else would.
     */
    var carried by mutableStateOf(false)
        private set

    /** Whether a claim is owed: [enabled] has just turned true, or an earlier turn never landed. */
    fun wanted(enabled: Boolean): Boolean {
        val rising = enabled && !enabledBefore
        enabledBefore = enabled
        if (!enabled) pending = false else if (rising) pending = true
        return pending
    }

    fun done() {
        pending = false
    }

    /** A claim made on the owner's behalf did not land; the next transition tries again. */
    fun raise() {
        carried = true
    }

    fun carriedDone() {
        carried = false
    }
}
