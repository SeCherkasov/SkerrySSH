package app.skerry.ui.mobile

import androidx.compose.runtime.Composable
import app.skerry.ui.connection.ConnectionUiState
import app.skerry.ui.forward.humanRate
import app.skerry.ui.app.MobileDesignState
import app.skerry.ui.app.MobileRoute

/**
 * Pure logic for the mobile terminal screen, separated from the Composable view
 * ([MobileTerminalScreen]) so it can be unit-tested without Compose.
 */

/** Session state shown in the terminal header status line. The UI localizes it. */
enum class MobileTerminalStatus { Connected, Connecting, Disconnected, Closed, NoSession }

/**
 * Status shown under the host name in the terminal header, from the active session's connection
 * state. Color comes separately via [sessionDotColor]. Live metrics (RTT/throughput) render alongside
 * as separate elements ([mobileRttLabel]/[mobileRateLabel]), not in this line.
 */
fun mobileTerminalStatus(state: ConnectionUiState?): MobileTerminalStatus = when (state) {
    is ConnectionUiState.Connected -> MobileTerminalStatus.Connected
    ConnectionUiState.Connecting -> MobileTerminalStatus.Connecting
    is ConnectionUiState.Error -> MobileTerminalStatus.Disconnected
    // Clean shell exit (`exit`) → neutral "closed"; transport drop → "disconnected".
    is ConnectionUiState.Disconnected ->
        if (state.cleanExit) MobileTerminalStatus.Closed else MobileTerminalStatus.Disconnected
    else -> MobileTerminalStatus.NoSession
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
@Composable
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

/**
 * The action to actually take when the connect runs, given the [planned] one decided when the
 * production confirmation was put on screen and whether that session is [stillLive] now.
 *
 * The decision is made once, when the question is asked, and carried to the answer: re-deciding on
 * OK would act on a state the user never saw. The one thing that must still be checked is whether
 * the session survived the wait — resuming one that died in between would land on an empty screen.
 */
fun mobileResolvedAction(planned: MobileConnectAction, stillLive: Boolean): MobileConnectAction =
    if (planned == MobileConnectAction.Resume && !stillLive) MobileConnectAction.OpenFresh else planned

/**
 * Whether tapping Connect on a production host must confirm first ([app.skerry.shared.guard.ProductionGuard]).
 * Returning to a session that is already live is not a new connection, so it doesn't ask — a
 * confirmation on every tab switch would be trained away within a day. A VNC tap always opens a
 * fresh framebuffer screen, so it always asks.
 */
fun mobileProdConfirmNeeded(production: Boolean, isVnc: Boolean, action: MobileConnectAction): Boolean =
    production && (isVnc || action != MobileConnectAction.Resume)

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

/** ESC (0x1B) — the meta prefix sticky-alt puts in front of what it modifies. */
internal const val ESC = "\u001b"

/**
 * Control sequence for the terminal key panel's Ctrl+key (sticky-ctrl): the C0 code = the uppercased
 * char code masked with 0x1F. So Ctrl+C → ETX (0x03), Ctrl+[ → ESC (0x1B). Returns a one-char string
 * to send to the PTY ([app.skerry.ui.terminal.TerminalScreenState.send]).
 */
fun controlByte(c: Char): String = (c.uppercaseChar().code and 0x1F).toChar().toString()

/**
 * Whether armed ctrl applies to this soft-keyboard input at all — i.e. whether it starts with a
 * printable character. Backspace and Enter reach the IME path as the bytes they already are (DEL,
 * CR), and [controlByte] masks a control byte into a different one: Ctrl armed over a Backspace sent
 * a stray 0x1F and the line lost nothing. The caller disarms on this predicate, so a Backspace does
 * not burn a modifier the user armed for the next letter.
 */
fun takesStickyCtrl(input: String): Boolean = input.isNotEmpty() && input[0].code in 0x20..0x7e

/**
 * Applies sticky-ctrl to a string typed on the soft keyboard (terminal IME path: text captured by a
 * hidden field, bypassing the key panel). If ctrl is armed and the input [takesStickyCtrl], the first
 * char is encoded as Ctrl+<char> ([controlByte]) and the rest passes through; the modifier applies to
 * one keystroke (the caller disarms it via the same predicate).
 */

fun applyStickyCtrl(armed: Boolean, input: String): String =
    if (armed && takesStickyCtrl(input)) controlByte(input[0]) + input.substring(1) else input

/**
 * Applies sticky-alt (Meta) to soft-keyboard input: an ESC prefix, which is how a terminal carries
 * Alt+key (readline word operations — Alt+B/F, Alt+Backspace to delete a word). Unlike sticky-ctrl
 * this applies to any non-empty input, printable or not: Alt+Backspace is one of the combinations
 * the modifier is armed for. Composed over [applyStickyCtrl] so Ctrl+Alt+key keeps the ESC outermost,
 * as [app.skerry.ui.terminal.mapTerminalKey] encodes it for the physical keyboard.
 */
fun applyStickyMeta(armed: Boolean, input: String): String =
    if (armed && input.isNotEmpty()) ESC + input else input
