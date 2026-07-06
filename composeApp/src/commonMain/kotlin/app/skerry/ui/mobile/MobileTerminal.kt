package app.skerry.ui.mobile

import app.skerry.ui.connection.ConnectionUiState
import app.skerry.ui.forward.humanRate
import app.skerry.ui.app.MobileDesignState
import app.skerry.ui.app.MobileRoute

/**
 * Pure logic for the mobile terminal screen, separated from the Composable view
 * ([MobileTerminalScreen]) so it can be unit-tested without Compose.
 */

/**
 * Status-line text under the host name in the terminal header, from the active session's connection
 * state. Color comes separately via [sessionDotColor]. Live metrics (RTT/throughput) render alongside
 * as separate elements ([mobileRttLabel]/[mobileRateLabel]), not in this line.
 */
fun mobileTerminalStatusText(state: ConnectionUiState?): String = when (state) {
    is ConnectionUiState.Connected -> "connected"
    ConnectionUiState.Connecting -> "connecting…"
    is ConnectionUiState.Error -> "disconnected"
    // Clean shell exit (`exit`) → neutral "closed"; transport drop → "disconnected".
    is ConnectionUiState.Disconnected -> if (state.cleanExit) "closed" else "disconnected"
    else -> "no session"
}

/**
 * RTT ping label for terminal header metrics: `N ms`, or "—" before the first sample / on failure
 * (see [app.skerry.ui.connection.PingController.rttMs]). Parity with the desktop status bar.
 */
fun mobileRttLabel(rttMs: Long?): String = rttMs?.let { "$it ms" } ?: "—"

/**
 * Throughput label (↑/↓) for terminal header metrics: human-readable rate ([humanRate]), or "—"
 * until the first sample. Parity with the desktop status bar.
 */
fun mobileRateLabel(bytesPerSec: Long?): String = bytesPerSec?.let { humanRate(it) } ?: "—"

/** What to do on Connect when the host already has an open session. */
enum class MobileConnectAction {
    /** Session is alive (connected/connecting) — just show it, don't spawn tabs. */
    Resume,

    /** No session or it's dead (error/closed) — open a new one (reconnect). */
    OpenFresh,
}

/**
 * Decision for a host's last session: resume a live one or open fresh. On phone (unlike desktop tabs)
 * one session shows at a time, so re-Connecting to the same host must not accumulate sockets — reuse
 * a live one, replace a dead one.
 */
fun mobileConnectAction(existing: ConnectionUiState?): MobileConnectAction =
    if (existing is ConnectionUiState.Connected || existing == ConnectionUiState.Connecting) {
        MobileConnectAction.Resume
    } else {
        MobileConnectAction.OpenFresh
    }

/** Where to go from the host screen after opening/resuming a session: Connect → terminal, SFTP → files. */
enum class MobileConnectDest { Terminal, Files }

/**
 * Navigation after a host session is opened or resumed. Connect goes to the terminal push-screen,
 * SFTP to the Files push-screen (the active session's Remote browser). Extracted from the view so the
 * single connect path (including the password sheet) knows the destination.
 */
fun navigateAfterConnect(state: MobileDesignState, dest: MobileConnectDest): Unit = when (dest) {
    MobileConnectDest.Terminal -> state.push(MobileRoute.Terminal)
    MobileConnectDest.Files -> state.push(MobileRoute.Files)
}

/**
 * Control sequence for the terminal key panel's Ctrl+key (sticky-ctrl): the C0 code = the uppercased
 * char code masked with 0x1F. So Ctrl+C → ETX (0x03), Ctrl+[ → ESC (0x1B). Returns a one-char string
 * to send to the PTY ([app.skerry.ui.terminal.TerminalScreenState.send]).
 */
fun controlByte(c: Char): String = (c.uppercaseChar().code and 0x1F).toChar().toString()

/**
 * Applies sticky-ctrl to a string typed on the soft keyboard (terminal IME path: text captured by a
 * hidden field, bypassing the key panel). If ctrl is armed and input is non-empty, the first char is
 * encoded as Ctrl+<char> ([controlByte]) and the rest passes through; the modifier applies to one
 * keystroke (the caller disarms it via the same predicate). No change when unarmed or input is empty.
 */
fun applyStickyCtrl(armed: Boolean, input: String): String =
    if (armed && input.isNotEmpty()) controlByte(input[0]) + input.substring(1) else input
