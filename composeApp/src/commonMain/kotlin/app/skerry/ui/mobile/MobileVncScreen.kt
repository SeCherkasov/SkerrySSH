package app.skerry.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.vnc.VncQuality
import app.skerry.ui.app.LocalSessions
import app.skerry.ui.app.MobileDesignState
import app.skerry.ui.design.AnchoredDropdown
import app.skerry.ui.design.D
import app.skerry.ui.design.HLine
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.vnc.VncScreenState
import app.skerry.ui.vnc.VncSurface
import app.skerry.ui.vnc.VncUiState
import app.skerry.ui.vnc.keySymFor
import androidx.compose.ui.input.key.Key

/**
 * Mobile VNC (remote-desktop) screen: a top bar (back / server name / keyboard / disconnect) over
 * the framebuffer. Touch acts as the mouse (tap = click, drag = move) via the shared [VncSurface];
 * pinch zooms and pans, and the keyboard button raises a hidden IME field that forwards typed
 * characters as RFB key events. The framebuffer sibling of [MobileTerminalScreen].
 */
@Composable
fun MobileVncScreen(state: MobileDesignState) {
    val sessions = LocalSessions.current
    val vnc = sessions?.active?.vncController
    var keyboardOn by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        // Top bar
        Row(
            Modifier.fillMaxWidth().background(D.surfaceDeep).padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val connectedScreen = (vnc?.uiState as? VncUiState.Connected)?.screen
            MobileVncIcon("arrow_back") { state.pop() }
            Txt(
                connectedScreen?.serverName ?: sessions?.active?.title ?: "VNC",
                color = D.text, size = 14.sp, modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            if (connectedScreen != null) MobileVncGraphics(connectedScreen)
            MobileVncIcon(if (keyboardOn) "keyboard_hide" else "keyboard") { keyboardOn = !keyboardOn }
            MobileVncIcon("close") {
                sessions?.active?.let { sessions.close(it.id) }
                state.pop()
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (val ui = vnc?.uiState) {
                is VncUiState.Connected -> MobileVncBody(ui.screen, keyboardOn) { keyboardOn = false }
                is VncUiState.Error -> CenterText(ui.message, D.sunset)
                is VncUiState.Disconnected -> MobileVncBody(ui.screen, keyboardOn = false) {}
                else -> CenterText("Connecting…", D.dim)
            }
        }
    }
}

@Composable
private fun MobileVncBody(screen: VncScreenState, keyboardOn: Boolean, onImeClosed: () -> Unit) {
    Box(
        Modifier.fillMaxSize().clipToBounds().pointerInput(screen) {
            // Pinch to zoom + pan on top of the fit; the surface below handles taps/drags as the mouse.
            detectTransformGestures { _, pan, zoom, _ ->
                val newScale = screen.userScale * zoom
                screen.setZoom(newScale, screen.userOffset + pan)
            }
        },
    ) {
        VncSurface(screen)
        if (keyboardOn) VncImeField(screen, onImeClosed)
    }
}

/**
 * Hidden 1-pixel text field that holds IME focus and forwards typed characters as RFB key events.
 * Diffing the field value turns insertions into key press+release and deletions into Backspace.
 */
@Composable
private fun VncImeField(screen: VncScreenState, onClosed: () -> Unit) {
    var value by remember { mutableStateOf(TextFieldValue("")) }
    val focus = remember { FocusRequester() }
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
    androidx.compose.runtime.LaunchedEffect(Unit) { focus.requestFocus() }
}

@Composable
private fun CenterText(text: String, color: Color) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Txt(text, color = color, size = 13.sp)
    }
}

/** Graphics settings dropdown for the mobile VNC bar: quality, view-only, reset zoom. */
@Composable
private fun MobileVncGraphics(screen: VncScreenState) {
    var open by remember { mutableStateOf(false) }
    AnchoredDropdown(
        expanded = open,
        onDismiss = { open = false },
        // Must not steal focus: with the IME field open, a focusable popup would drop the keyboard.
        focusable = false,
        trigger = { MobileVncIcon("tune") { open = !open } },
        menu = { width ->
            Column(
                Modifier.width(width.coerceAtLeast(200.dp)).clip(RoundedCornerShape(11.dp))
                    .background(D.surfaceDeep).border(1.dp, D.cyan14, RoundedCornerShape(11.dp)).padding(vertical = 4.dp),
            ) {
                Txt("Quality", color = D.faint, size = 11.sp, modifier = Modifier.padding(start = 14.dp, top = 6.dp, bottom = 2.dp))
                VncQuality.entries.forEach { q ->
                    MobileVncMenuRow(q.name, selected = screen.quality == q) { screen.applyQuality(q) }
                }
                HLine(modifier = Modifier.padding(vertical = 4.dp))
                MobileVncMenuRow("View only", selected = screen.viewOnly, icon = if (screen.viewOnly) "check_box" else "check_box_outline_blank") { screen.toggleViewOnly() }
                // Only offered once the server has said it accepts SetDesktopSize.
                if (screen.canResizeRemote) {
                    MobileVncMenuRow("Resize to window", selected = screen.remoteResize, icon = if (screen.remoteResize) "check_box" else "check_box_outline_blank") { screen.toggleRemoteResize() }
                }
                MobileVncMenuRow("Reset zoom", selected = false, icon = "fit_screen") { screen.resetZoom(); open = false }
            }
        },
    )
}

@Composable
private fun MobileVncMenuRow(label: String, selected: Boolean, icon: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(if (selected) D.cyan10 else Color.Transparent).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (icon != null) Sym(icon, size = 17.sp, color = if (selected) D.cyanBright else D.dim)
        Txt(label, color = if (selected) D.cyanBright else D.text, size = 14.sp, modifier = Modifier.weight(1f))
        if (selected && icon == null) Sym("check", size = 16.sp, color = D.cyanBright)
    }
}

@Composable
private fun MobileVncIcon(icon: String, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(8.dp)).size(36.dp)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Sym(icon, size = 20.sp, color = D.dim)
    }
}
