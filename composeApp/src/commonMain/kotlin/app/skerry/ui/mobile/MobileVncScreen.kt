package app.skerry.ui.mobile

import app.skerry.ui.design.untrustedLabel
import app.skerry.ui.generated.resources.rd_keyboard_input
import app.skerry.ui.remote.remoteKeyEvent
import app.skerry.ui.remote.RemoteDesktopPanel
import app.skerry.ui.remote.rememberClipboardActions
import app.skerry.ui.remote.rememberScreenshotAction
import app.skerry.ui.remote.RemoteDesktopScreenState
import app.skerry.ui.remote.RemoteDesktopUiState
import app.skerry.ui.remote.ReportOutputVisibility
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.app.LocalSessions
import app.skerry.ui.app.LocalUserActivity
import app.skerry.ui.app.MobileDesignState
import app.skerry.ui.design.ImeFunnelField
import app.skerry.ui.design.StatusAnnouncer
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.vnc_connecting
import app.skerry.ui.generated.resources.vnc_connection_lost
import app.skerry.ui.generated.resources.vnc_session_closed
import app.skerry.ui.immersive.ImmersiveScreen
import app.skerry.ui.immersive.hiddenSystemBarsPadding
import app.skerry.ui.vnc.VncTouchSurface
import androidx.compose.ui.input.key.Key
import app.skerry.ui.vnc.remoteDesktopAnnouncement
import app.skerry.ui.vnc.vncFailureText
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.theme.Skerry

/**
 * Mobile VNC (remote-desktop) screen: the framebuffer edge to edge, with the system bars hidden
 * ([ImmersiveScreen]) and the app's own bar (back / server name / graphics / keyboard / disconnect)
 * hidden too until a swipe down near the top summons it. Touch drives the mouse like a trackpad
 * — see [VncTouchSurface] for the gestures — and the keyboard button raises a hidden IME field that
 * forwards typed characters as RFB key events. The framebuffer sibling of [MobileTerminalScreen].
 */
@Composable
fun MobileVncScreen(state: MobileDesignState) {
    val sessions = LocalSessions.current
    val vnc = sessions?.activeSession?.vncController
    var keyboardOn by remember { mutableStateOf(false) }
    // The bar starts visible so the screen still explains itself on arrival, then gets out of the way.
    var barVisible by remember { mutableStateOf(true) }
    // The session panel is opened from the bar, so the bar has to outlive it — auto-hiding underneath
    // would take the panel's own way back with it.
    var panelOpen by remember { mutableStateOf(false) }
    // Bumped by every reveal, and part of the auto-hide effect's key: re-setting an already-true
    // `barVisible` is not a state change and would leave the running timer to expire on its old
    // schedule — a swipe would then be answered by the bar vanishing a moment later.
    var revealNonce by remember { mutableStateOf(0) }

    // Full-bleed only on request (More → Appearance → Interface); off, the phone keeps its bars
    // and the shell keeps this screen inside the safe area (see MobileChrome.fullBleed).
    ImmersiveScreen(state.hideSessionSystemBars)

    // Auto-hide, restarted by every reveal. Held open while the keyboard is up: the button that puts
    // it away lives on the bar, and hiding the bar under the user's hands would strand them.
    LaunchedEffect(barVisible, keyboardOn, panelOpen, revealNonce) {
        if (barVisible && !keyboardOn && !panelOpen) {
            delay(BAR_AUTO_HIDE_MS)
            barVisible = false
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // See [VncView]: the live region has to outlive the states it describes, so it sits above
        // the `when` rather than inside the branch that draws the line.
        StatusAnnouncer(vnc?.uiState?.let { remoteDesktopAnnouncement(it) }.orEmpty())
        when (val ui = vnc?.uiState) {
            is RemoteDesktopUiState.Connected -> {
                // The app going to the background stops the server drawing a desktop nobody sees.
                ReportOutputVisibility(ui.screen)
                VncTouchSurface(ui.screen)
            }
            is RemoteDesktopUiState.Error -> CenterText(vncFailureText(ui.failure), Skerry.colors.sunset)
            is RemoteDesktopUiState.Disconnected -> Box(Modifier.fillMaxSize()) {
                VncTouchSurface(ui.screen, interactive = false)
                CenterText(
                    stringResource(if (ui.cleanExit) Res.string.vnc_session_closed else Res.string.vnc_connection_lost),
                    Skerry.colors.sunset,
                )
            }
            else -> CenterText(stringResource(Res.string.vnc_connecting), Skerry.colors.dim)
        }

        // Swipe down near the top reveals the bar. A transparent catcher rather than a gesture on the
        // surface itself: it must not compete with cursor drags, and it consumes nothing on a tap, so
        // a tap here still reaches the framebuffer below as a click. It starts below the edge — a
        // swipe from the very edge belongs to the system (that is how the hidden bars come back), and
        // the gesture would never reach us.
        Box(
            Modifier.align(Alignment.TopCenter).fillMaxWidth()
                .padding(top = SYSTEM_EDGE_GESTURE).height(TOP_EDGE_STRIP)
                .pointerInput(Unit) {
                    detectVerticalDragGestures { _, dy ->
                        if (dy > 0f) { barVisible = true; revealNonce++ }
                    }
                },
        )

        AnimatedVisibility(
            visible = barVisible,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = slideInVertically { -it },
            exit = slideOutVertically { -it },
        ) {
            MobileVncBar(
                state = state,
                screen = (vnc?.uiState as? RemoteDesktopUiState.Connected)?.screen,
                title = sessions?.activeSession?.title ?: "VNC",
                keyboardOn = keyboardOn,
                panelOpen = panelOpen,
                onPanelOpenChange = { panelOpen = it },
                onToggleKeyboard = { keyboardOn = !keyboardOn },
                onClose = {
                    sessions?.active?.let { sessions.close(it.id) }
                    state.pop()
                },
            )
        }

        val connected = (vnc?.uiState as? RemoteDesktopUiState.Connected)?.screen
        // Kept across the frame the session drops on: the exit animation still has to draw the panel
        // it is sliding away, and a content lambda reading `connected` would compose nothing there —
        // the panel would vanish rather than leave.
        var lastScreen by remember { mutableStateOf(connected) }
        if (connected != null) lastScreen = connected
        // Remembered here rather than in the panel, which slides away and would take an in-flight
        // save with it (see [rememberScreenshotAction]).
        val screenshot = rememberScreenshotAction(lastScreen)
        val clipboardActions = rememberClipboardActions(lastScreen)
        // The panel slides over the picture rather than beside it: on a phone a column of its width
        // would leave the desktop a strip.
        AnimatedVisibility(
            visible = panelOpen && connected != null,
            modifier = Modifier.align(Alignment.CenterEnd),
            enter = slideInHorizontally { it },
            exit = slideOutHorizontally { it },
        ) {
            lastScreen?.let {
                RemoteDesktopPanel(
                    it,
                    screenshot = screenshot,
                    clipboardActions = clipboardActions,
                    onHide = { panelOpen = false },
                    modifier = Modifier.hiddenSystemBarsPadding(),
                    // Pinch-zoom is real here, so the fit can be off and worth resetting.
                    showResetZoom = true,
                )
            }
        }
        if (keyboardOn && connected != null) VncImeField(connected) { keyboardOn = false }
    }
}

/** The slide-over bar: the chrome that would otherwise eat the top of the remote desktop. */
@Composable
private fun MobileVncBar(
    state: MobileDesignState,
    screen: RemoteDesktopScreenState?,
    title: String,
    keyboardOn: Boolean,
    panelOpen: Boolean,
    onPanelOpenChange: (Boolean) -> Unit,
    onToggleKeyboard: () -> Unit,
    onClose: () -> Unit,
) {
    Row(
        // Its own inset padding: the screen below draws under the (hidden) system bars, so without
        // this the buttons would sit under a display cutout or a transiently-shown status bar.
        Modifier.fillMaxWidth().background(Skerry.colors.surfaceDeep.copy(alpha = 0.94f))
            .hiddenSystemBarsPadding()
            // Deliberately tight: this bar sits on top of the remote desktop, and every dp of it is a
            // dp the desktop does not get. The status-bar reserve above already adds height.
            .padding(horizontal = 4.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        MobileVncIcon("arrow_back") { state.pop() }
        Txt(
            screen?.serverName?.let { untrustedLabel(it) } ?: title,
            color = Skerry.colors.text, size = 13.sp, modifier = Modifier.weight(1f).padding(start = 4.dp),
        )
        if (screen != null) MobileVncIcon("tune") { onPanelOpenChange(!panelOpen) }
        MobileVncIcon(if (keyboardOn) "keyboard_hide" else "keyboard", onClick = onToggleKeyboard)
        MobileVncIcon("close", onClick = onClose)
    }
}

/**
 * Hidden 1-pixel text field that holds IME focus and forwards typed characters as RFB key events.
 *
 * The same funnel the terminal uses ([ImeFunnelField]): the field is reset to its anchors after
 * every edit, so what it holds at rest is never the text typed — on a Windows or VNC login screen
 * that text is a password, and a field's value is `EditableText` in the semantics tree. Diffing
 * against the anchors turns insertions into key press+release and deletions into Backspace; the
 * anchors are what make a deletion visible at all. [KeyboardOptions] keep the
 * same characters out of autocorrect and the IME's personalised dictionary.
 *
 * The soft keyboard is raised explicitly: focus alone is not enough once the field has been focused
 * before (requestFocus on an already-focused field is a no-op, so the keyboard would never return).
 */
@Composable
internal fun VncImeField(screen: RemoteDesktopScreenState, onClosed: () -> Unit) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val userActivity = LocalUserActivity.current
    ImeFunnelField(
        name = stringResource(Res.string.rd_keyboard_input),
        modifier = Modifier.focusRequester(focus),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            // Password rather than Ascii: it is the variation an IME reads as "do not learn this",
            // and Ascii alone leaves suggestions and personalised learning on — what is typed here
            // is a remote login. Nothing is drawn in the field, so masking costs no visual.
            //
            // The terminal's funnel deliberately keeps Ascii: a shell needs IME_FLAG_FORCE_ASCII
            // (which the password variation drops), and the password type also costs glide typing,
            // voice input and stylus handwriting on the app's primary Android input surface. Here
            // none of those are worth a keystroke of a remote login screen.
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.None,
        ),
    ) { delta ->
        // The vault's idle auto-lock sees no key or pointer event for this path — the soft
        // keyboard is its own window — and typing into a session is the plainest evidence
        // there is that the user is still here (issue #291).
        userActivity()
        for (ch in delta) {
            val event = when (ch.code) {
                127, 8 -> remoteKeyEvent(Key.Backspace, 0) // DEL / BS
                13, 10 -> remoteKeyEvent(Key.Enter, 0) // CR / LF
                else -> remoteKeyEvent(Key.Unknown, ch.code)
            }
            if (event != null) { screen.onKey(event, true); screen.onKey(event, false) }
        }
    }
    LaunchedEffect(Unit) {
        focus.requestFocus()
        keyboard?.show()
    }
    DisposableEffect(Unit) { onDispose { keyboard?.hide() } }
    // Dismissing the keyboard from the system (back gesture) leaves this field focused and the bar's
    // button stuck on "hide"; watching the IME inset is the only way that reaches us.
    val imeUp = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    var everUp by remember { mutableStateOf(false) }
    LaunchedEffect(imeUp) {
        if (imeUp) everUp = true else if (everUp) onClosed()
    }
}

@Composable
private fun CenterText(text: String, color: Color) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Txt(text, color = color, size = 13.sp)
    }
}

@Composable
private fun MobileVncIcon(icon: String, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(8.dp)).size(34.dp)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Sym(icon, size = 18.sp, color = Skerry.colors.dim)
    }
}

private const val BAR_AUTO_HIDE_MS = 3000L
// Where the reveal strip starts and how tall it is: clear of the system's own edge-swipe zone,
// still within thumb reach of the top of the screen.
private val SYSTEM_EDGE_GESTURE = 40.dp
private val TOP_EDGE_STRIP = 72.dp
