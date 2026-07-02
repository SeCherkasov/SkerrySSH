package app.skerry.ui.mobile

import app.skerry.shared.ssh.PtySize
import app.skerry.shared.terminal.TerminalSession
import app.skerry.shared.terminal.TerminalState
import app.skerry.ui.connection.ConnectionUiState
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

/** Чистая логика мобильного терминал-экрана: статус-строка, решение Connect, sticky-ctrl. */
class MobileTerminalTest {

    private fun connected(): ConnectionUiState.Connected {
        val session = object : TerminalSession {
            override val state: StateFlow<TerminalState> = MutableStateFlow(TerminalState.Open)
            override val output: Flow<ByteArray> = emptyFlow()
            override suspend fun send(data: ByteArray) {}
            override suspend fun resize(size: PtySize) {}
            override suspend fun close() {}
        }
        return ConnectionUiState.Connected(TerminalScreenState(session, CoroutineScope(Job())))
    }

    // Статус-строка шапки

    @Test
    fun status_text_reflects_connection_state() {
        assertEquals("connected", mobileTerminalStatusText(connected()))
        assertEquals("connecting…", mobileTerminalStatusText(ConnectionUiState.Connecting))
        assertEquals("disconnected", mobileTerminalStatusText(ConnectionUiState.Error("boom")))
        assertEquals("no session", mobileTerminalStatusText(ConnectionUiState.Form))
        assertEquals("no session", mobileTerminalStatusText(null))
    }

    // Метрики статус-бара шапки (RTT/throughput).

    @Test
    fun rtt_label_formats_ms_or_dash_before_first_ping() {
        assertEquals("42 ms", mobileRttLabel(42))
        assertEquals("0 ms", mobileRttLabel(0))
        assertEquals("—", mobileRttLabel(null)) // до первого замера/при сбое пинга
    }

    @Test
    fun rate_label_humanizes_or_dash_before_first_sample() {
        // Гуманизация B/s, «—» пока нет замера.
        assertEquals("0 B/s", mobileRateLabel(0))
        assertEquals("1 KB/s", mobileRateLabel(1024))
        assertEquals("—", mobileRateLabel(null))
    }

    // Решение при тапе Connect

    @Test
    fun connect_resumes_live_session_else_opens_fresh() {
        // Живая (подключена/подключается) сессия хоста — возобновляем, не плодим вкладки.
        assertEquals(MobileConnectAction.Resume, mobileConnectAction(connected()))
        assertEquals(MobileConnectAction.Resume, mobileConnectAction(ConnectionUiState.Connecting))
        // Мёртвая/ошибочная/отсутствующая — переподключаемся заново.
        assertEquals(MobileConnectAction.OpenFresh, mobileConnectAction(ConnectionUiState.Error("x")))
        assertEquals(MobileConnectAction.OpenFresh, mobileConnectAction(ConnectionUiState.Form))
        assertEquals(MobileConnectAction.OpenFresh, mobileConnectAction(null))
    }

    // sticky-ctrl на клавишной панели

    @Test
    fun control_byte_encodes_ctrl_combos() {
        // Ctrl+<буква> = код в верхнем регистре & 0x1F (C0). Регистр не важен.
        assertEquals("\u0003", controlByte('c')) // Ctrl+C = ETX
        assertEquals("\u0003", controlByte('C'))
        assertEquals("\u0004", controlByte('d')) // Ctrl+D = EOT
        assertEquals("\u001a", controlByte('z')) // Ctrl+Z = SUB
        assertEquals("\u001b", controlByte('[')) // Ctrl+[ = ESC
    }

    // sticky-ctrl поверх ввода с софт-клавиатуры (IME-путь).
    // ESC и control-байты строим из кодов (27.toChar()/controlByte) — никаких невидимых литералов.

    @Test
    fun sticky_ctrl_encodes_first_soft_keyboard_char() {
        // Армированный ctrl + буква с экранной клавиатуры → Ctrl+<буква>; остаток (если есть) как есть.
        assertEquals(controlByte('c'), applyStickyCtrl(armed = true, input = "c"))
        assertEquals(controlByte('c') + "rest", applyStickyCtrl(armed = true, input = "crest"))
    }

    @Test
    fun sticky_ctrl_passes_through_when_not_armed_or_empty() {
        assertEquals("c", applyStickyCtrl(armed = false, input = "c"))
        assertEquals("", applyStickyCtrl(armed = true, input = ""))
    }

    // Стрелки с учётом DECCKM (application-cursor-keys)

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
        // В DECCKM (vim/less прислали ESC[?1h) стрелки идут как SS3 ESC O <буква>.
        val esc = 27.toChar().toString()
        assertEquals("${esc}OA", arrowSequence(ArrowKey.Up, applicationCursor = true))
        assertEquals("${esc}OB", arrowSequence(ArrowKey.Down, applicationCursor = true))
        assertEquals("${esc}OC", arrowSequence(ArrowKey.Right, applicationCursor = true))
        assertEquals("${esc}OD", arrowSequence(ArrowKey.Left, applicationCursor = true))
    }
}
