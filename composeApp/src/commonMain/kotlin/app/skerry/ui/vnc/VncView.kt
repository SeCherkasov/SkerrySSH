package app.skerry.ui.vnc

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.graphics.RemoteDesktopQuality
import app.skerry.ui.app.DesktopDesignState
import app.skerry.ui.app.LocalSessions
import app.skerry.ui.app.remoteChromeHidden
import app.skerry.ui.design.EmptyState
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.design.handsKeyboardBack
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.rd_no_session
import app.skerry.ui.generated.resources.rd_pick_to_connect
import app.skerry.ui.generated.resources.shell_tip_hide_hosts
import app.skerry.ui.generated.resources.shell_tip_show_hosts
import app.skerry.ui.generated.resources.vnc_connecting
import app.skerry.ui.generated.resources.vnc_connection_lost
import app.skerry.ui.generated.resources.vnc_quality_auto
import app.skerry.ui.generated.resources.vnc_quality_high
import app.skerry.ui.generated.resources.vnc_quality_low
import app.skerry.ui.generated.resources.vnc_quality_medium
import app.skerry.ui.generated.resources.vnc_session_closed
import app.skerry.ui.remote.REMOTE_BAR_AUTO_HIDE_MS
import app.skerry.ui.remote.REMOTE_BAR_EDGE
import app.skerry.ui.remote.RemoteBarState
import app.skerry.ui.remote.RemoteDesktopBar
import app.skerry.ui.remote.RemoteDesktopController
import app.skerry.ui.remote.RemoteDesktopUiState
import app.skerry.ui.remote.ReportOutputVisibility
import app.skerry.ui.remote.rememberClipboardActions
import app.skerry.ui.remote.rememberScreenshotAction
import app.skerry.ui.terminal.HostsSidebar
import app.skerry.ui.terminal.SidebarToggleHandle
import app.skerry.ui.theme.Skerry
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource



/**
 * The remote-desktop section: its own host sidebar (the desktops catalog) beside the work area,
 * mirroring how the terminal section is laid out. With no desktop session open the area explains
 * what to do instead of sitting blank — the sidebar is the only way into one.
 */
@Composable
fun RemoteDesktopsView(state: DesktopDesignState) {
    val sessions = LocalSessions.current
    // Full-window mode takes the catalog with the rest of the chrome; the desktop's floating bar is
    // the way back out.
    val immersive = remoteChromeHidden(
        immersive = state.remoteImmersive,
        desktopSession = sessions?.activeDesktop != null,
        overlayOpen = state.appOverlay != null,
    )
    Row(Modifier.fillMaxSize()) {
        // Same collapse behaviour as the terminal sidebar (shared [DesktopDesignState.sidebarHidden]),
        // so hiding the panel in one section hides it in both — one shell, one preference.
        AnimatedVisibility(
            visible = !state.sidebarHidden && !immersive,
            enter = expandHorizontally(expandFrom = Alignment.End),
            exit = shrinkHorizontally(shrinkTowards = Alignment.End),
        ) {
            // Same as the terminal side: the catalog follows the rail, the framebuffer follows the
            // selected tab (see workAreaSection).
            HostsSidebar(state, state.section)
        }
        // The section renders no work bar, so this strip is its only sidebar control — present
        // whether the panel is open or shut, one click either way (issue #178). Only full-window
        // mode takes it off screen, along with the rest of the chrome.
        AnimatedVisibility(
            visible = !immersive,
            enter = fadeIn() + expandHorizontally(expandFrom = Alignment.Start),
            exit = fadeOut() + shrinkHorizontally(shrinkTowards = Alignment.Start),
        ) {
            SidebarToggleHandle(hidden = state.sidebarHidden, onClick = state::toggleSidebar)
        }
        Box(Modifier.weight(1f).fillMaxHeight()) {
            if (sessions?.activeDesktop != null) {
                VncView(state)
            } else {
                EmptyState(
                    icon = "desktop_windows",
                    title = stringResource(Res.string.rd_no_session),
                    subtitle = stringResource(Res.string.rd_pick_to_connect),
                    tint = Skerry.colors.dim,
                )
            }
        }
    }
}

/**
 * The VNC tab's work area. Renders the active session's [RemoteDesktopController] state: connecting /
 * live framebuffer / error / disconnected. The framebuffer sibling of `TerminalView`, rendered by
 * [RemoteDesktopsView] beside the desktops sidebar.
 */
@Composable
fun VncView(state: DesktopDesignState) {
    val sessions = LocalSessions.current
    val tab = sessions?.activeDesktop ?: return
    val vnc = tab.focusedPane.vncController ?: return
    Box(Modifier.fillMaxSize()) {
        when (val ui = vnc.uiState) {
            is RemoteDesktopUiState.Connecting -> CenterNotice("hourglass_empty", stringResource(Res.string.vnc_connecting))
            // key(tab.id): switching between two connected desktops keeps this same branch, so
            // without it Compose reuses the slot and the next session would inherit the previous
            // one's bar — hidden or pinned, its menus open — and the full-window mode with it.
            is RemoteDesktopUiState.Connected -> androidx.compose.runtime.key(tab.id) {
                // clipToBounds: the bar slides out through the top edge, and without a clip it would
                // be drawn over the chrome above the work area on its way out.
                Box(Modifier.fillMaxSize().clipToBounds()) {
                    // A minimised window, or another tab taking the screen, stops the server drawing.
                    ReportOutputVisibility(ui.screen)
                    val bar = remember { RemoteBarState() }
                    val screenshot = rememberScreenshotAction(ui.screen)
                    val clipboardActions = rememberClipboardActions(ui.screen)
                    val edge = with(LocalDensity.current) { REMOTE_BAR_EDGE.toPx() }
                    // The full-window mode belongs to this session: leaving it (another tab, a
                    // closed desktop) must not leave the window stripped of its chrome.
                    DisposableEffect(Unit) { onDispose { state.exitRemoteImmersive() } }
                    // Auto-hide, restarted by every reveal (revealCount) and disarmed while the
                    // pointer is on the bar, one of its menus is open, or it is pinned.
                    LaunchedEffect(bar.autoHides, bar.revealCount) {
                        if (bar.autoHides) {
                            delay(REMOTE_BAR_AUTO_HIDE_MS)
                            bar.hide()
                        }
                    }
                    // The screenshot answers on the bar itself, so a failure has to bring the bar
                    // back — otherwise the only word about an unwritten file lands off screen.
                    LaunchedEffect(screenshot.note) { if (screenshot.note != null) bar.reveal() }
                    // The reveal zone is read off the framebuffer's own pointer stream rather than
                    // from a strip laid over the top of it: a strip would swallow every mouse move
                    // in its band, and the remote cursor would stop dead a few pixels below the edge.
                    VncSurface(ui.screen, onPointerY = { y -> bar.onPointerY(y, edge) })
                    AnimatedVisibility(
                        visible = bar.visible,
                        modifier = Modifier.align(Alignment.TopCenter),
                        enter = slideInVertically { -it } + fadeIn(),
                        exit = slideOutVertically { -it } + fadeOut(),
                    ) {
                        RemoteDesktopBar(
                            screen = ui.screen,
                            bar = bar,
                            screenshot = screenshot,
                            clipboardActions = clipboardActions,
                            immersive = state.remoteImmersive,
                            onToggleImmersive = state::toggleRemoteImmersive,
                            onDisconnect = { state.requestCloseSession(tab.id) },
                        )
                    }
                }
            }
            is RemoteDesktopUiState.Error -> CenterNotice(
                "error",
                vncFailureText(ui.failure),
                color = Skerry.colors.sunset,
            )
            is RemoteDesktopUiState.Disconnected -> Box(Modifier.fillMaxSize()) {
                VncSurface(ui.screen, interactive = false)
                CenterNotice(
                    "link_off",
                    stringResource(if (ui.cleanExit) Res.string.vnc_session_closed else Res.string.vnc_connection_lost),
                    color = Skerry.colors.sunset,
                )
            }
        }
    }
}

/** Localized label for a quality level in the graphics menu (shared with the mobile VNC screen). */
@Composable
internal fun RemoteDesktopQuality.label(): String = stringResource(
    when (this) {
        RemoteDesktopQuality.Auto -> Res.string.vnc_quality_auto
        RemoteDesktopQuality.Low -> Res.string.vnc_quality_low
        RemoteDesktopQuality.Medium -> Res.string.vnc_quality_medium
        RemoteDesktopQuality.High -> Res.string.vnc_quality_high
    },
)

@Composable
private fun CenterNotice(icon: String, message: String, color: Color = Skerry.colors.dim) {
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Sym(icon, size = 28.sp, color = color)
            Txt(message, color = color, size = 13.sp)
        }
    }
}
