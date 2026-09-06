package app.skerry.ui.mobile

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.skerry.shared.host.Host
import app.skerry.shared.ssh.SshAuth
import app.skerry.shared.ssh.SshJump
import app.skerry.ui.connection.connectionSubtitle
import app.skerry.ui.connection.toTarget
import app.skerry.ui.host.rowLabel
import app.skerry.ui.remote.toRdpRequest
import app.skerry.ui.session.SessionsController
import app.skerry.ui.app.MobileDesignState
import app.skerry.ui.app.MobileRoute
import app.skerry.ui.app.MobileTab
import app.skerry.ui.app.UiTags
import app.skerry.ui.theme.Skerry

/**
 * Host waiting for a password, with the destination after connecting (terminal/files) and the
 * ProxyJump chain already resolved at tap time (a broken one never reaches the prompt).
 */
internal data class PendingConnect(val host: Host, val dest: MobileConnectDest, val jump: SshJump? = null)

/** Open a session to [host] with [auth] (via the resolved [jump] chain, `null` — direct) and navigate to [dest]. */
internal fun openMobileSession(
    sessions: SessionsController?,
    state: MobileDesignState,
    host: Host,
    auth: SshAuth,
    jump: SshJump?,
    dest: MobileConnectDest,
) {
    sessions?.open(
        hostId = host.id,
        title = host.rowLabel(),
        subtitle = host.connectionSubtitle(),
        target = host.toTarget(jump),
        auth = auth,
    )
    navigateAfterConnect(state, dest)
}

/** Open a VNC tab for [host] with [auth] and navigate to it. Shared by the typed-password and picked-secret paths. */
internal fun openMobileRdp(
    sessions: SessionsController?,
    state: MobileDesignState,
    hostManager: app.skerry.ui.host.HostManagerController?,
    host: Host,
    /** Built by the caller from the screen it measured — see [app.skerry.ui.remote.toRdpRequest]. */
    request: app.skerry.ui.remote.RdpConnectRequest,
) {
    sessions?.openRdp(
        host.id,
        host.rowLabel(),
        host.connectionSubtitle(),
        request,
        remoteResize = host.vncResizeToWindow,
        onRemoteResizeChanged = { on -> hostManager?.setVncResizeToWindow(host.id, on) },
        quality = host.vncQuality,
        onQualityChanged = { q -> hostManager?.setVncQuality(host.id, q) },
    )
    if (sessions != null) state.push(MobileRoute.Vnc)
}

/** The screen an RDP session dialled from the mobile chrome lives in, with its scaling. */
internal fun mobileRdpRequest(host: Host, password: String, viewport: app.skerry.ui.remote.RemoteViewport) =
    host.toRdpRequest(
        password,
        viewport,
        clientName = "Skerry",
        fallback = androidx.compose.ui.unit.IntSize(MOBILE_RDP_WIDTH, MOBILE_RDP_HEIGHT),
    )

internal fun openMobileVnc(
    sessions: SessionsController?,
    state: MobileDesignState,
    hostManager: app.skerry.ui.host.HostManagerController?,
    host: Host,
    auth: app.skerry.shared.vnc.VncAuth,
) {
    sessions?.openVnc(
        host.id, host.rowLabel(), host.connectionSubtitle(), host.toTarget(), auth,
        remoteResize = host.vncResizeToWindow,
        onRemoteResizeChanged = { on -> hostManager?.setVncResizeToWindow(host.id, on) },
        quality = host.vncQuality,
        onQualityChanged = { q -> hostManager?.setVncQuality(host.id, q) },
    )
    if (sessions != null) state.push(MobileRoute.Vnc)
}

// Content: root tabs and push screens.

/**
 * Root screen for the current tab. [onLock] is threaded into the More hub ("Lock Skerry").
 */
@Composable
internal fun MobileTabPane(state: MobileDesignState, onLock: (() -> Unit)?) {
    // Tagged with the tab it drew, the mobile counterpart of [app.skerry.ui.desktop.Viewport].
    Box(Modifier.fillMaxSize().testTag(UiTags.mobileScreen(state.tab))) {
        when (state.tab) {
            MobileTab.Hosts -> MobileHostsScreen(state)
            MobileTab.Desktops -> MobileDesktopsScreen(state)
            MobileTab.Sessions -> MobileSessionsScreen(state)
            MobileTab.More -> MobileMoreScreen(state, onLock)
        }
    }
}

/**
 * Full-screen push screen. [MobileRoute.HostDetail] opens [MobileHostDetailScreen]; the rest are
 * back arrow + title, body not implemented.
 */
@Composable
internal fun MobileRoutePane(state: MobileDesignState, route: MobileRoute) {
    Box(Modifier.fillMaxSize().testTag(UiTags.mobileScreen(route))) {
        when (route) {
            MobileRoute.HostDetail -> MobileHostDetailScreen(state)
            MobileRoute.Terminal -> MobileTerminalScreen(state)
            MobileRoute.Vnc -> MobileVncScreen(state)
            MobileRoute.Files -> MobileFilesScreen(onBack = state::pop)
            MobileRoute.Vault -> MobileVaultScreen(state)
            MobileRoute.Snippets -> MobileSnippetsScreen(state)
            MobileRoute.Runbooks -> MobileRunbooksScreen(state)
            MobileRoute.Ports -> MobilePortsScreen(state)
            MobileRoute.Known -> MobileKnownScreen(state)
            MobileRoute.Team -> MobileTeamsScreen(state)
            MobileRoute.Appearance -> MobileAppearanceScreen(state)
            MobileRoute.Sync -> MobileSyncScreen(state)
            MobileRoute.Ai -> MobileAiScreen(state)
            MobileRoute.Security -> MobileSecurityScreen(state)
            MobileRoute.Trash -> MobileTrashScreen(state)
            MobileRoute.About -> MobileAboutScreen(state)
            MobileRoute.KeepAlive -> MobileKeepAliveScreen(state)
        }
    }
}

/**
 * Desktop size an RDP session asks for on a phone. Smaller than the desktop default on purpose: the
 * session is fixed at connect time and then scaled to the screen, and a 1080p desktop on a handset
 * is unreadable before the user zooms.
 */
private const val MOBILE_RDP_WIDTH = 1280
private const val MOBILE_RDP_HEIGHT = 720
