package app.skerry.ui.vnc

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.ScrollWheel
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import app.skerry.ui.design.KeyboardClaim
import app.skerry.ui.design.ModalScrim
import app.skerry.ui.design.handsKeyboardBack
import app.skerry.ui.remote.FakeRemoteDesktop
import app.skerry.ui.remote.RemoteDesktopScreenState
import app.skerry.ui.remote.RemoteModifier
import app.skerry.ui.remote.RemoteModifiers
import app.skerry.ui.remote.remoteKeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Who holds the keyboard while a remote desktop is on screen. Compose gives focus away in ways the
 * surface cannot see coming — a modal takes it and leaves it with no one on the way out, the window
 * losing focus clears it outright (`ComposeSceneMediator.focusLost` → `releaseFocus`, never restored
 * on the way back), and chrome beside the picture takes it on a mouse press — and a framebuffer that
 * claims focus once per session then types nowhere until the user clicks the picture.
 *
 * The other half of the rule is here too: what it must NOT do is take the keyboard off a sibling
 * that owns it. A password prompt, the assistant's ask field and the sidebar's filter all sit beside
 * a live session, and typing meant for them must never reach the remote host.
 */
@OptIn(ExperimentalTestApi::class)
class VncSurfaceKeyboardTest {

    @Test
    fun `a closed modal hands the keyboard back to the picture`() = withKeyboard { harness ->
        assertTypes(harness, "a fresh session types into the desktop")
        harness.modalOpen = true
        waitForIdle()
        harness.modalOpen = false
        waitForIdle()
        assertTypes(harness, "the modal closed and the keyboard went nowhere")
    }

    @Test
    fun `the keyboard comes back with the window`() = withKeyboard { harness ->
        assertTypes(harness, "a fresh session types into the desktop")
        harness.leaveWindow(this)
        harness.windowFocused = true
        waitForIdle()
        assertTypes(harness, "the window came back and the keyboard did not")
    }

    @Test
    fun `chrome clicked with the mouse gives the keyboard back`() = withKeyboard { harness ->
        assertTypes(harness, "a fresh session types into the desktop")
        // The real modifier the sidebar's controls carry, over a real `clickable` — a mouse press
        // takes focus, and this is what returns it.
        onNodeWithTag(CHROME).performMouseInput { click() }
        waitForIdle()
        assertTypes(harness, "the sidebar button took the keyboard and kept it")
    }

    /**
     * The hand-back is for the press that actually takes the keyboard. A right-click opens a context
     * menu and claims no focus, and keyboard activation is not a press at all — counting either
     * would move the caret for a gesture the user never aimed at the session.
     */
    @Test
    fun `a secondary click on chrome owes no hand-back`() = withKeyboard {
        val before = KeyboardClaim.handBacks
        onNodeWithTag(CHROME).performMouseInput { rightClick() }
        waitForIdle()
        assertEquals(before, KeyboardClaim.handBacks, "a right-click counted as a hand-back")
    }

    /**
     * The rule that makes the reclaim safe. A connect password, a 2FA answer and the sidebar filter
     * are all fields beside a live session; when the window comes back the keyboard belongs to the
     * one that had it, not to the framebuffer.
     */
    @Test
    fun `a field beside the picture keeps the keyboard across a window round trip`() = withKeyboard { harness ->
        onNodeWithTag(FIELD).performMouseInput { click() }
        waitForIdle()
        harness.leaveWindow(this)
        harness.windowFocused = true
        waitForIdle()

        assertTypesNowhere(harness, "it took the keyboard off the field beside it")
    }

    /**
     * The way out for a keyboard-only user: every other key goes to the guest (Tab and Escape
     * included), so without this chord the framebuffer is a keyboard trap — and it opens holding
     * the keyboard, no click needed.
     */
    @Test
    fun `the release chord hands the keyboard back to the app`() = withKeyboard { harness ->
        assertTypes(harness, "a fresh session types into the desktop")
        harness.release(this)

        assertTypesNowhere(harness, "the chord left the keyboard on it")
    }

    /** A release the user asked for is not undone by the next click on chrome. */
    @Test
    fun `chrome does not re-arm a framebuffer the user released`() = withKeyboard { harness ->
        harness.release(this)
        onNodeWithTag(CHROME).performMouseInput { click() }
        waitForIdle()

        assertTypesNowhere(harness, "a click on chrome took it back to a desktop the user let go of")
    }

    /**
     * The bug this reads as: the window manager keeps the release of Alt (Alt+Tab) or of the Super
     * key, the server goes on holding it, and every click on the remote machine arrives as
     * Alt+click — the desktop stops answering the mouse until the user presses that modifier again,
     * which is why it looked like "the mouse works once I switch the keyboard layout".
     *
     * The click itself carries what the local machine really holds, so it is the click that lifts it.
     */
    @Test
    fun `a modifier the window manager swallowed is lifted by the next click`() = withKeyboard { harness ->
        val alt = remoteKeyEvent(Key.AltLeft, 0) ?: error("no key event for Alt")
        harness.screen.onKey(alt, down = true, modifier = RemoteModifier.Alt)
        waitForIdle()

        onRoot().performMouseInput { moveTo(Offset(150f, 100f)); click(Offset(150f, 100f)) }
        waitForIdle()
        assertTrue(
            harness.session.keys.toList().any { it.first == alt && !it.second },
            "the click left Alt down on the server: ${harness.session.keys.toList()}",
        )
    }

    /** The same reconciliation from the keyboard side: typing lifts it as surely as clicking does. */
    @Test
    fun `a swallowed modifier is lifted by the next keystroke`() = withKeyboard { harness ->
        val alt = remoteKeyEvent(Key.AltLeft, 0) ?: error("no key event for Alt")
        harness.screen.onKey(alt, down = true, modifier = RemoteModifier.Alt)
        waitForIdle()

        onRoot().performKeyInput { pressKey(Key.A) }
        waitForIdle()
        assertTrue(
            harness.session.keys.toList().any { it.first == alt && !it.second },
            "the keystroke left Alt down on the server: ${harness.session.keys.toList()}",
        )
    }

    /**
     * The wiring, not the rule: a modifier pressed through the surface's own key handler has to be
     * recorded as one, or nothing is left for the reconciliation to lift. Drives the real key path
     * and then asks the state to reconcile against a machine that holds nothing.
     */
    @Test
    fun `a modifier pressed through the surface is recorded as one`() = withKeyboard { harness ->
        onRoot().performKeyInput { keyDown(Key.AltLeft) }
        waitForIdle()
        harness.session.keys.clear()

        harness.screen.syncModifiers(RemoteModifiers(ctrl = false, alt = false, shift = false))
        waitForIdle()
        assertTrue(
            harness.session.keys.toList().any { !it.second },
            "the surface did not record Alt as a modifier: ${harness.session.keys.toList()}",
        )
        onRoot().performKeyInput { keyUp(Key.AltLeft) }
    }

    /**
     * A stuck Ctrl turns every notch into a zoom on the remote machine, and scrolling is the one
     * gesture that used to reconcile nothing — the wheel branch returned before the sync ran.
     */
    @Test
    fun `scrolling lifts a modifier the window manager swallowed`() = withKeyboard { harness ->
        // Positioned first: a move would reconcile on its own, and then this would pass with the
        // reconcile back below the scroll branch — the very thing it exists to catch.
        onRoot().performMouseInput { moveTo(Offset(150f, 100f)) }
        waitForIdle()

        val ctrl = remoteKeyEvent(Key.CtrlLeft, 0) ?: error("no key event for Ctrl")
        harness.screen.onKey(ctrl, down = true, modifier = RemoteModifier.Ctrl)
        waitForIdle()

        onRoot().performMouseInput { scroll(-1f, ScrollWheel.Vertical) }
        waitForIdle()
        assertTrue(
            harness.session.keys.toList().any { it.first == ctrl && !it.second },
            "the notch went out with Ctrl still down: ${harness.session.keys.toList()}",
        )
    }

    /**
     * The other direction: a modifier the user is really holding must survive the click it
     * modifies. Shift+click on the remote machine extends a selection — releasing Shift first
     * would turn every one of them into a plain click.
     */
    @Test
    fun `a modifier still held is not lifted by a click`() = withKeyboard { harness ->
        onRoot().performKeyInput { keyDown(Key.ShiftLeft) }
        waitForIdle()
        harness.session.keys.clear()

        onRoot().performMouseInput { moveTo(Offset(150f, 100f)); click(Offset(150f, 100f)) }
        waitForIdle()
        assertTrue(
            harness.session.keys.toList().none { !it.second },
            "the click released a modifier the user was holding: ${harness.session.keys.toList()}",
        )
        onRoot().performKeyInput { keyUp(Key.ShiftLeft) }
    }

    /** A key press reaches the session, or [why] explains what should have carried it there. */
    private fun ComposeUiTest.assertTypes(harness: KeyboardHarness, why: String) {
        val before = harness.session.keyCount()
        onRoot().performKeyInput { pressKey(Key.A) }
        waitUntil("no key reached the remote desktop: $why") { harness.session.keyCount() > before }
    }

    /** Nothing reaches the session — checked after the frame the claim would have landed on. */
    private fun ComposeUiTest.assertTypesNowhere(harness: KeyboardHarness, why: String) {
        val before = harness.session.keyCount()
        onRoot().performKeyInput { pressKey(Key.A) }
        waitForIdle()
        assertTrue(harness.session.keyCount() == before, "the desktop took the keyboard: $why")
    }
}

/** The pieces a test drives: a modal above the picture, the window's focus, and the session. */
private class KeyboardHarness {
    /** The session state behind the surface, for the rules a test has to set up by hand. */
    lateinit var screen: RemoteDesktopScreenState
    var modalOpen by mutableStateOf(false)
    var windowFocused by mutableStateOf(true)
    var fieldText by mutableStateOf("")
    /** Set from the body, before anything reads it. */
    lateinit var session: FakeRemoteDesktop
    /** Captured from the composition, so a test can do to focus what the scene does. */
    var focus: FocusManager? = null

    /** The chord that hands the keyboard back to the app, as the user would press it. */
    @OptIn(ExperimentalTestApi::class)
    fun release(test: ComposeUiTest) {
        test.onRoot().performKeyInput {
            keyDown(Key.CtrlLeft); keyDown(Key.AltLeft); keyDown(Key.ShiftLeft)
            pressKey(Key.K)
            keyUp(Key.ShiftLeft); keyUp(Key.AltLeft); keyUp(Key.CtrlLeft)
        }
        test.waitForIdle()
    }

    /**
     * The window goes away for good (Alt+Tab elsewhere): the scene lowers its focus flag and clears
     * the focus owner, which is what leaves the keyboard with nobody.
     */
    @OptIn(ExperimentalTestApi::class)
    fun leaveWindow(test: ComposeUiTest) {
        windowFocused = false
        test.waitForIdle()
        test.runOnIdle { focus?.clearFocus(force = true) }
        test.waitForIdle()
    }
}

/** Stands for the section's chrome beside the picture: the sidebar handle, its rows, the work bar. */
private const val CHROME = "chrome"

/** Stands for a field beside the session: the connect password, the assistant, the hosts filter. */
private const val FIELD = "field"

@OptIn(ExperimentalTestApi::class)
private fun withKeyboard(body: ComposeUiTest.(KeyboardHarness) -> Unit) {
    val harness = KeyboardHarness()
    val windowInfo = object : WindowInfo {
        override val isWindowFocused: Boolean get() = harness.windowFocused
    }
    withVncSurface(windowInfo = windowInfo, beside = { Chrome(harness) }) { session, screen ->
        harness.session = session
        harness.screen = screen
        body(harness)
    }
}

@Composable
private fun Chrome(harness: KeyboardHarness) {
    val focus = LocalFocusManager.current
    LaunchedEffect(focus) { harness.focus = focus }
    // Side by side, not stacked: a click has to reach the one it names.
    Row {
        Box(Modifier.size(20.dp).testTag(CHROME).handsKeyboardBack().clickable {})
        BasicTextField(
            value = harness.fieldText,
            onValueChange = { harness.fieldText = it },
            modifier = Modifier.size(20.dp).testTag(FIELD),
        )
    }
    if (harness.modalOpen) ModalScrim(onDismiss = {}, label = "modal") { }
}

/**
 * How many keys the session has been handed. Counted through a copy: the input actor appends from
 * another thread once its pacing delay has moved it off the test's, exactly as the pointer tests
 * describe.
 */
private fun FakeRemoteDesktop.keyCount(): Int {
    while (true) {
        try {
            return keys.toList().size
        } catch (_: ConcurrentModificationException) {
        }
    }
}
