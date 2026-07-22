package app.skerry.ui.mobile

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.vnc.VncQuality
import app.skerry.ui.app.LocalSessions
import app.skerry.ui.app.MobileDesignState
import app.skerry.ui.design.AnchoredDropdown
import app.skerry.ui.design.HLine
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.vnc_connecting
import app.skerry.ui.generated.resources.vnc_connection_lost
import app.skerry.ui.generated.resources.vnc_quality
import app.skerry.ui.generated.resources.vnc_reset_zoom
import app.skerry.ui.generated.resources.vnc_resize_to_window
import app.skerry.ui.generated.resources.vnc_session_closed
import app.skerry.ui.generated.resources.vnc_view_only
import app.skerry.ui.immersive.ImmersiveScreen
import app.skerry.ui.immersive.hiddenSystemBarsPadding
import app.skerry.ui.vnc.VncScreenState
import app.skerry.ui.vnc.VncTouchSurface
import app.skerry.ui.vnc.VncUiState
import app.skerry.ui.vnc.keySymFor
import app.skerry.ui.vnc.label
import androidx.compose.ui.input.key.Key
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
    val vnc = sessions?.active?.vncController
    var keyboardOn by remember { mutableStateOf(false) }
    // The bar starts visible so the screen still explains itself on arrival, then gets out of the way.
    var barVisible by remember { mutableStateOf(true) }
    // The graphics menu hangs off the bar, so the bar has to outlive it — auto-hiding underneath would
    // take the open menu down with it mid-choice.
    var menuOpen by remember { mutableStateOf(false) }
    // Bumped by every reveal, and part of the auto-hide effect's key: re-setting an already-true
    // `barVisible` is not a state change and would leave the running timer to expire on its old
    // schedule — a swipe would then be answered by the bar vanishing a moment later.
    var revealNonce by remember { mutableStateOf(0) }

    ImmersiveScreen()

    // Auto-hide, restarted by every reveal. Held open while the keyboard is up: the button that puts
    // it away lives on the bar, and hiding the bar under the user's hands would strand them.
    LaunchedEffect(barVisible, keyboardOn, menuOpen, revealNonce) {
        if (barVisible && !keyboardOn && !menuOpen) {
            delay(BAR_AUTO_HIDE_MS)
            barVisible = false
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when (val ui = vnc?.uiState) {
            is VncUiState.Connected -> VncTouchSurface(ui.screen)
            is VncUiState.Error -> CenterText(vncFailureText(ui.failure), Skerry.colors.sunset)
            is VncUiState.Disconnected -> Box(Modifier.fillMaxSize()) {
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
                screen = (vnc?.uiState as? VncUiState.Connected)?.screen,
                title = sessions?.active?.title ?: "VNC",
                keyboardOn = keyboardOn,
                menuOpen = menuOpen,
                onMenuOpenChange = { menuOpen = it },
                onToggleKeyboard = { keyboardOn = !keyboardOn },
                onClose = {
                    sessions?.active?.let { sessions.close(it.id) }
                    state.pop()
                },
            )
        }

        val connected = (vnc?.uiState as? VncUiState.Connected)?.screen
        if (keyboardOn && connected != null) VncImeField(connected) { keyboardOn = false }
    }
}

/** The slide-over bar: the chrome that would otherwise eat the top of the remote desktop. */
@Composable
private fun MobileVncBar(
    state: MobileDesignState,
    screen: VncScreenState?,
    title: String,
    keyboardOn: Boolean,
    menuOpen: Boolean,
    onMenuOpenChange: (Boolean) -> Unit,
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
            screen?.serverName ?: title,
            color = Skerry.colors.text, size = 13.sp, modifier = Modifier.weight(1f).padding(start = 4.dp),
        )
        if (screen != null) MobileVncGraphics(screen, menuOpen, onMenuOpenChange)
        MobileVncIcon(if (keyboardOn) "keyboard_hide" else "keyboard", onClick = onToggleKeyboard)
        MobileVncIcon("close", onClick = onClose)
    }
}

/**
 * Hidden 1-pixel text field that holds IME focus and forwards typed characters as RFB key events.
 * Diffing the field value turns insertions into key press+release and deletions into Backspace.
 * The soft keyboard is raised explicitly: focus alone is not enough once the field has been focused
 * before (requestFocus on an already-focused field is a no-op, so the keyboard would never return).
 */
@Composable
private fun VncImeField(screen: VncScreenState, onClosed: () -> Unit) {
    var value by remember { mutableStateOf(TextFieldValue("")) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    BasicTextField(
        value = value,
        onValueChange = { new ->
            val old = value.text
            when {
                new.text.length > old.length -> new.text.substring(old.length).forEach { c ->
                    val sym = if (c == '\n') 0xFF0DL else keySymFor(Key.Unknown, c.code)
                    if (sym != 0L) { screen.onKey(sym, true); screen.onKey(sym, false) }
                }
                new.text.length < old.length -> repeat(old.length - new.text.length) {
                    screen.onKey(0xFF08L, true); screen.onKey(0xFF08L, false) // Backspace
                }
            }
            // Keep a small buffer so the field doesn't grow unbounded; reset when it gets long.
            value = if (new.text.length > 64) TextFieldValue("") else new
        },
        modifier = Modifier.size(1.dp).focusRequester(focus),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.Transparent),
    )
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

/** Graphics settings dropdown for the mobile VNC bar: quality, view-only, reset zoom. */
@Composable
private fun MobileVncGraphics(screen: VncScreenState, open: Boolean, onOpenChange: (Boolean) -> Unit) {
    AnchoredDropdown(
        expanded = open,
        onDismiss = { onOpenChange(false) },
        // Must not steal focus: with the IME field open, a focusable popup would drop the keyboard.
        focusable = false,
        trigger = { MobileVncIcon("tune") { onOpenChange(!open) } },
        menu = { width ->
            Column(
                Modifier.width(width.coerceAtLeast(200.dp)).clip(RoundedCornerShape(11.dp))
                    .background(Skerry.colors.surfaceDeep).border(1.dp, Skerry.colors.cyan14, RoundedCornerShape(11.dp)).padding(vertical = 4.dp),
            ) {
                Txt(stringResource(Res.string.vnc_quality), color = Skerry.colors.faint, size = 11.sp, modifier = Modifier.padding(start = 14.dp, top = 6.dp, bottom = 2.dp))
                VncQuality.entries.forEach { q ->
                    MobileVncMenuRow(q.label(), selected = screen.quality == q) { screen.applyQuality(q) }
                }
                HLine(modifier = Modifier.padding(vertical = 4.dp))
                MobileVncMenuRow(stringResource(Res.string.vnc_view_only), selected = screen.viewOnly, icon = if (screen.viewOnly) "check_box" else "check_box_outline_blank") { screen.toggleViewOnly() }
                // Only offered once the server has said it accepts SetDesktopSize.
                if (screen.canResizeRemote) {
                    MobileVncMenuRow(stringResource(Res.string.vnc_resize_to_window), selected = screen.remoteResize, icon = if (screen.remoteResize) "check_box" else "check_box_outline_blank") { screen.toggleRemoteResize() }
                }
                MobileVncMenuRow(stringResource(Res.string.vnc_reset_zoom), selected = false, icon = "fit_screen") { screen.resetZoom(); onOpenChange(false) }
            }
        },
    )
}

@Composable
private fun MobileVncMenuRow(label: String, selected: Boolean, icon: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(if (selected) Skerry.colors.cyan10 else Color.Transparent).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (icon != null) Sym(icon, size = 17.sp, color = if (selected) Skerry.colors.cyanBright else Skerry.colors.dim)
        Txt(label, color = if (selected) Skerry.colors.cyanBright else Skerry.colors.text, size = 14.sp, modifier = Modifier.weight(1f))
        if (selected && icon == null) Sym("check", size = 16.sp, color = Skerry.colors.cyanBright)
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
