package app.skerry.ui.session

import app.skerry.ui.connection.ConnectionUiState
import app.skerry.ui.remote.RemoteDesktopUiState

/**
 * Status a session reports to the chrome around it (tab chip, host row) — one scale for both kinds
 * of session. A remote desktop keeps its state in [Session.vncController] while its
 * [Session.controller] stays an idle placeholder, so reading the terminal controller alone reports
 * a live desktop as idle.
 */
enum class SessionStatus { Live, Connecting, Failed, Idle }

/** Status of a terminal session's connection. */
fun ConnectionUiState?.asSessionStatus(): SessionStatus = when (this) {
    is ConnectionUiState.Connected -> SessionStatus.Live
    ConnectionUiState.Connecting -> SessionStatus.Connecting
    is ConnectionUiState.Error -> SessionStatus.Failed
    // A clean shell exit is not a failure; a retrying reconnect reads like connecting; exhausted
    // retries mean no session, same as an error.
    is ConnectionUiState.Disconnected -> when {
        cleanExit -> SessionStatus.Idle
        reconnecting -> SessionStatus.Connecting
        else -> SessionStatus.Failed
    }
    else -> SessionStatus.Idle
}

/** Status of a remote-desktop session — the framebuffer sibling of the mapping above. */
fun RemoteDesktopUiState?.asSessionStatus(): SessionStatus = when (this) {
    is RemoteDesktopUiState.Connected -> SessionStatus.Live
    RemoteDesktopUiState.Connecting -> SessionStatus.Connecting
    is RemoteDesktopUiState.Error -> SessionStatus.Failed
    // No auto-reconnect on this side: a closed session is either a clean end or a drop.
    is RemoteDesktopUiState.Disconnected -> if (cleanExit) SessionStatus.Idle else SessionStatus.Failed
    null -> SessionStatus.Idle
}
