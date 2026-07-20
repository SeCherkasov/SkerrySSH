package app.skerry.ui.mobile

import app.skerry.shared.ssh.PtySize
import app.skerry.shared.terminal.TerminalSession
import app.skerry.shared.terminal.TerminalState
import app.skerry.ui.connection.ConnectionUiState
import app.skerry.ui.forward.RateParts
import app.skerry.ui.forward.RateUnit
import app.skerry.ui.forward.rateParts
import app.skerry.ui.terminal.TerminalScreenState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import app.skerry.ui.terminal.ArrowKey
import app.skerry.ui.terminal.arrowSequence

/** Pure logic for the mobile terminal screen: status line, Connect decision, sticky-ctrl. */
class MobileTerminalTest {

    private fun screen(): TerminalScreenState {
        val session = object : TerminalSession {
            override val state: StateFlow<TerminalState> = MutableStateFlow(TerminalState.Open)
            override val output: Flow<ByteArray> = emptyFlow()
            override suspend fun send(data: ByteArray) {}
            override suspend fun resize(size: PtySize) {}
            override suspend fun close() {}
        }
        return TerminalScreenState(session, CoroutineScope(Job()))
    }

    private fun connected(): ConnectionUiState.Connected = ConnectionUiState.Connected(screen())

    private fun disconnected(cleanExit: Boolean) =
        ConnectionUiState.Disconnected(screen(), reconnecting = false, attempt = 0, cleanExit = cleanExit)

    // Header status line

    @Test
    fun status_reflects_connection_state() {
        assertEquals(MobileTerminalStatus.Connected, mobileTerminalStatus(connected()))
        assertEquals(MobileTerminalStatus.Connecting, mobileTerminalStatus(ConnectionUiState.Connecting))
        assertEquals(MobileTerminalStatus.Disconnected, mobileTerminalStatus(ConnectionUiState.Error("boom")))
        assertEquals(MobileTerminalStatus.NoSession, mobileTerminalStatus(ConnectionUiState.Form))
        assertEquals(MobileTerminalStatus.NoSession, mobileTerminalStatus(null))
        // Clean shell exit reads as "closed", a transport drop as "disconnected".
        assertEquals(MobileTerminalStatus.Closed, mobileTerminalStatus(disconnected(cleanExit = true)))
        assertEquals(MobileTerminalStatus.Disconnected, mobileTerminalStatus(disconnected(cleanExit = false)))
    }

    // Header status-bar metrics (RTT/throughput).

    @Test
    fun rtt_label_formats_ms_or_dash_before_first_ping() {
        assertEquals("42 ms", mobileRttLabel(42))
        assertEquals("0 ms", mobileRttLabel(0))
        assertEquals("—", mobileRttLabel(null)) // before the first measurement / on ping failure
    }

    @Test
    fun rate_parts_scale_the_header_throughput() {
        // mobileRateLabel itself is @Composable (localized unit template), so the split is asserted here.
        assertEquals(RateParts(RateUnit.Bytes, 0), rateParts(0))
        assertEquals(RateParts(RateUnit.KB, 1), rateParts(1024))
    }

    // Decision on Connect tap

    @Test
    fun connect_resumes_live_session_else_opens_fresh() {
        // A live (connected/connecting) session for the host is resumed, not duplicated as a new tab.
        assertEquals(MobileConnectAction.Resume, mobileConnectAction(connected()))
        assertEquals(MobileConnectAction.Resume, mobileConnectAction(ConnectionUiState.Connecting))
        // Dead/errored/missing session reconnects fresh.
        assertEquals(MobileConnectAction.OpenFresh, mobileConnectAction(ConnectionUiState.Error("x")))
        assertEquals(MobileConnectAction.OpenFresh, mobileConnectAction(ConnectionUiState.Form))
        assertEquals(MobileConnectAction.OpenFresh, mobileConnectAction(null))
    }

    // sticky-ctrl on the key panel

    @Test
    fun control_byte_encodes_ctrl_combos() {
        // Ctrl+<letter> = uppercase code & 0x1F (C0). Case-insensitive.
        assertEquals("\u0003", controlByte('c')) // Ctrl+C = ETX
        assertEquals("\u0003", controlByte('C'))
        assertEquals("\u0004", controlByte('d')) // Ctrl+D = EOT
        assertEquals("\u001a", controlByte('z')) // Ctrl+Z = SUB
        assertEquals("\u001b", controlByte('[')) // Ctrl+[ = ESC
    }

    // sticky-ctrl over soft-keyboard input (IME path).
    // ESC and control bytes are built from codes (27.toChar()/controlByte), never invisible literals.

    @Test
    fun sticky_ctrl_encodes_first_soft_keyboard_char() {
        // Armed ctrl + a letter from the on-screen keyboard maps to Ctrl+<letter>; any remainder passes through.
        assertEquals(controlByte('c'), applyStickyCtrl(armed = true, input = "c"))
        assertEquals(controlByte('c') + "rest", applyStickyCtrl(armed = true, input = "crest"))
    }

    @Test
    fun sticky_ctrl_passes_through_when_not_armed_or_empty() {
        assertEquals("c", applyStickyCtrl(armed = false, input = "c"))
        assertEquals("", applyStickyCtrl(armed = true, input = ""))
    }

    // Arrows respecting DECCKM (application-cursor-keys)

    @Test
    fun arrows_use_csi_in_normal_mode() {
        val esc = 27.toChar().toString()
        assertEquals("$esc[A", arrowSequence(ArrowKey.Up, applicationCursor = false))
        assertEquals("$esc[B", arrowSequence(ArrowKey.Down, applicationCursor = false))
        assertEquals("$esc[C", arrowSequence(ArrowKey.Right, applicationCursor = false))
        assertEquals("$esc[D", arrowSequence(ArrowKey.Left, applicationCursor = false))
    }

    @Test
    fun arrows_use_ss3_in_application_cursor_mode() {
        // In DECCKM (vim/less sent ESC[?1h) arrows go as SS3 ESC O <letter>.
        val esc = 27.toChar().toString()
        assertEquals("${esc}OA", arrowSequence(ArrowKey.Up, applicationCursor = true))
        assertEquals("${esc}OB", arrowSequence(ArrowKey.Down, applicationCursor = true))
        assertEquals("${esc}OC", arrowSequence(ArrowKey.Right, applicationCursor = true))
        assertEquals("${esc}OD", arrowSequence(ArrowKey.Left, applicationCursor = true))
    }
}
