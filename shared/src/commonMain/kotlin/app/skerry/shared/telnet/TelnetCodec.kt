package app.skerry.shared.telnet

import kotlin.concurrent.Volatile

/**
 * Pure (no I/O) Telnet protocol codec (RFC 854 + ECHO/SGA/NAWS/TERMINAL-TYPE options). Holds only
 * option-negotiation state and the IAC-sequence parser; needs no socket, so it's fully testable
 * and lives in commonMain (shared by desktop and Android).
 *
 * Client model: locally offers (WILL) NAWS (sends window size), TERMINAL-TYPE (replies
 * `IS xterm-256color`), and SUPPRESS-GO-AHEAD; ECHO is WONT (the server echoes, not the client).
 * Remotely requests (DO) ECHO and SUPPRESS-GO-AHEAD. Any other option is refused (WONT/DONT).
 *
 * Replies to DO/DONT/WILL/WONT are sent only on a state change — a standard guard against
 * negotiation loops. Input is untrusted (Telnet has no auth/TLS), so the codec caps a
 * subnegotiation body at [MAX_SUBNEG_BYTES] (no unbounded buffering on `IAC SB` without
 * `IAC SE`), and total reply size per [consume] call at [MAX_REPLY_BYTES] (limits amplification
 * from rapidly flipping an option back and forth). [consume] returns application bytes (for the
 * terminal) and reply bytes (for the socket) separately; [encode] doubles a literal 0xFF in
 * outgoing data; [windowSize] builds the NAWS subnegotiation for a resize.
 */
class TelnetCodec(
    private val termType: String = "xterm-256color",
    cols: Int = 80,
    rows: Int = 24,
) {
    /** Result of parsing an incoming block: [data] is application bytes, [reply] is the socket reply. */
    class Decoded(val data: ByteArray, val reply: ByteArray)

    private enum class Phase { DATA, IAC, NEGOTIATE, SUBNEG, SUBNEG_IAC, SUBNEG_DROP, SUBNEG_DROP_IAC }

    private var phase = Phase.DATA
    private var command = 0 // accumulated WILL/WONT/DO/DONT in the NEGOTIATE phase
    private val subneg = ArrayList<Int>() // body of SB…SE (without IAC wrapper), capped at MAX_SUBNEG_BYTES

    // Current window size for NAWS. Written from the resize coroutine ([windowSize]), read from
    // the read coroutine ([consume] -> [nawsSubnegotiation]); @Volatile gives cross-thread
    // visibility (a brief cols/rows desync is harmless — the next resize corrects it).
    @Volatile private var curCols = cols.coerceIn(0, 0xFFFF)
    @Volatile private var curRows = rows.coerceIn(0, 0xFFFF)

    // Option state: true means enabled on our/remote side. Replies are sent only on a transition.
    private val localEnabled = HashMap<Int, Boolean>() // our WILL
    private val remoteEnabled = HashMap<Int, Boolean>() // our DO

    // Whether the server echoes input (remote ECHO). @Volatile: read from the UI coroutine (gates
    // autocomplete history), written from the read coroutine. false means the server isn't
    // echoing (password entry / line mode) — the terminal skips recording input to history then,
    // so secrets don't end up in suggestions.
    @Volatile private var serverEcho = false

    /** Whether the server echoes input (remote ECHO active). false likely means password entry / line mode. */
    val serverEchoEnabled: Boolean get() = serverEcho

    /**
     * Whether NAWS is negotiated (server sent DO NAWS, we replied WILL). Only then is it safe to
     * send a window-size subnegotiation on resize — a strict server may otherwise close the
     * connection on an unsolicited SB. Before negotiation, [windowSize] still records the size.
     */
    val nawsNegotiated: Boolean get() = localEnabled[NAWS] == true

    /** Encodes user input for the wire: doubles a literal IAC (0xFF). */
    fun encode(data: ByteArray): ByteArray {
        if (data.none { it == IAC.toByte() }) return data
        val out = ArrayList<Byte>(data.size + 4)
        for (b in data) {
            out.add(b)
            if (b == IAC.toByte()) out.add(IAC.toByte())
        }
        return out.toByteArray()
    }

    /** Builds the NAWS subnegotiation for a new window size (sent on resize). */
    fun windowSize(newCols: Int, newRows: Int): ByteArray {
        curCols = newCols.coerceIn(0, 0xFFFF)
        curRows = newRows.coerceIn(0, 0xFFFF)
        return nawsSubnegotiation()
    }

    /** Parses an incoming block from the wire: extracts application bytes and builds negotiation replies. */
    fun consume(input: ByteArray): Decoded {
        val data = ArrayList<Byte>(input.size)
        val reply = ArrayList<Byte>()
        for (raw in input) {
            val b = raw.toInt() and 0xFF
            when (phase) {
                Phase.DATA ->
                    if (b == IAC) phase = Phase.IAC else data.add(raw)

                Phase.IAC -> when (b) {
                    IAC -> { data.add(IAC.toByte()); phase = Phase.DATA } // escaped 0xFF
                    WILL, WONT, DO, DONT -> { command = b; phase = Phase.NEGOTIATE }
                    SB -> { subneg.clear(); phase = Phase.SUBNEG }
                    else -> phase = Phase.DATA // NOP/GA/DM/… single commands with no data
                }

                Phase.NEGOTIATE -> {
                    // Negotiation replies are capped — limits amplification from a hostile server
                    // rapidly flipping an option back and forth (each flip is a "state change").
                    if (reply.size < MAX_REPLY_BYTES) negotiate(command, b, reply)
                    phase = Phase.DATA
                }

                Phase.SUBNEG ->
                    when {
                        b == IAC -> phase = Phase.SUBNEG_IAC
                        // The SB body must not grow unbounded (the server may never send SE) —
                        // past the threshold, stop buffering and scan for the closing IAC SE.
                        subneg.size >= MAX_SUBNEG_BYTES -> { subneg.clear(); phase = Phase.SUBNEG_DROP }
                        else -> subneg.add(b)
                    }

                Phase.SUBNEG_IAC -> when (b) {
                    IAC -> { subneg.add(IAC); phase = Phase.SUBNEG } // escaped 0xFF in the SB body
                    SE -> { if (reply.size < MAX_REPLY_BYTES) handleSubnegotiation(subneg, reply); phase = Phase.DATA }
                    else -> phase = Phase.DATA // malformed sequence — reset
                }

                // SB body exceeded the limit: silently scan forward to IAC SE without buffering.
                Phase.SUBNEG_DROP ->
                    if (b == IAC) phase = Phase.SUBNEG_DROP_IAC

                Phase.SUBNEG_DROP_IAC -> if (b == SE) phase = Phase.DATA else if (b != IAC) phase = Phase.SUBNEG_DROP
            }
        }
        return Decoded(data.toByteArray(), reply.toByteArray())
    }

    private fun negotiate(cmd: Int, option: Int, reply: ArrayList<Byte>) {
        when (cmd) {
            DO -> if (supportedLocal(option)) {
                if (localEnabled[option] != true) {
                    localEnabled[option] = true
                    reply.iac(WILL, option)
                    if (option == NAWS) reply.addAll(nawsSubnegotiation().toList())
                }
            } else if (localEnabled[option] != false) {
                localEnabled[option] = false
                reply.iac(WONT, option)
            }

            DONT -> if (localEnabled[option] != false) {
                localEnabled[option] = false
                reply.iac(WONT, option)
            }

            WILL -> if (wantedRemote(option)) {
                if (remoteEnabled[option] != true) {
                    remoteEnabled[option] = true
                    reply.iac(DO, option)
                    if (option == ECHO) serverEcho = true
                }
            } else if (remoteEnabled[option] != false) {
                remoteEnabled[option] = false
                reply.iac(DONT, option)
            }

            WONT -> if (remoteEnabled[option] != false) {
                remoteEnabled[option] = false
                reply.iac(DONT, option)
                if (option == ECHO) serverEcho = false
            }
        }
    }

    private fun handleSubnegotiation(body: List<Int>, reply: ArrayList<Byte>) {
        // Server requested the terminal type: IAC SB TERMINAL-TYPE SEND IAC SE -> reply IS <termtype>.
        if (body.size >= 2 && body[0] == TERMINAL_TYPE && body[1] == TT_SEND) {
            reply.add(IAC.toByte()); reply.add(SB.toByte())
            reply.add(TERMINAL_TYPE.toByte()); reply.add(TT_IS.toByte())
            // Escape a literal 0xFF in the terminal name (as in NAWS/encode) — otherwise a stray
            // 0xFF would prematurely close the subnegotiation and the tail would read as commands.
            for (ch in termType.encodeToByteArray()) {
                reply.add(ch)
                if (ch == IAC.toByte()) reply.add(IAC.toByte())
            }
            reply.add(IAC.toByte()); reply.add(SE.toByte())
        }
    }

    private fun nawsSubnegotiation(): ByteArray {
        val out = ArrayList<Byte>(9)
        out.add(IAC.toByte()); out.add(SB.toByte()); out.add(NAWS.toByte())
        // 16-bit width/height, high byte first; a 0xFF inside the body is escaped by doubling.
        for (v in intArrayOf(curCols shr 8, curCols and 0xFF, curRows shr 8, curRows and 0xFF)) {
            out.add(v.toByte())
            if (v == IAC) out.add(IAC.toByte())
        }
        out.add(IAC.toByte()); out.add(SE.toByte())
        return out.toByteArray()
    }

    private fun supportedLocal(option: Int) = option == NAWS || option == TERMINAL_TYPE || option == SGA
    private fun wantedRemote(option: Int) = option == ECHO || option == SGA

    private fun ArrayList<Byte>.iac(cmd: Int, option: Int) {
        add(IAC.toByte()); add(cmd.toByte()); add(option.toByte())
    }

    private companion object {
        const val IAC = 255
        const val SE = 240
        const val SB = 250
        const val WILL = 251
        const val WONT = 252
        const val DO = 253
        const val DONT = 254

        const val ECHO = 1
        const val SGA = 3 // suppress go-ahead
        const val TERMINAL_TYPE = 24
        const val NAWS = 31 // negotiate about window size

        const val TT_IS = 0
        const val TT_SEND = 1

        // Guards against a hostile server: caps a single subnegotiation body and total reply per consume().
        const val MAX_SUBNEG_BYTES = 8192
        const val MAX_REPLY_BYTES = 16384
    }
}
