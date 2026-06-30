package app.skerry.shared.telnet

import kotlin.concurrent.Volatile

/**
 * Чистый (без ввода-вывода) кодек Telnet-протокола (RFC 854 + опции ECHO/SGA/NAWS/TERMINAL-TYPE).
 * Держит только состояние неготиации опций и парсер IAC-последовательностей — сокет ему не нужен,
 * поэтому полностью тестируем и живёт в commonMain (общий для desktop и Android).
 *
 * Модель клиента-терминала:
 * - локально (наши WILL): NAWS (шлём размер окна), TERMINAL-TYPE (отвечаем `IS xterm-256color`),
 *   SUPPRESS-GO-AHEAD; ECHO — WONT (клиент не эхоит, эхо делает сервер);
 * - удалённо (наши DO): ECHO и SUPPRESS-GO-AHEAD (хотим, чтобы сервер эхоил и не слал GA);
 * - любые другие опли отвергаем (WONT/DONT).
 *
 * Ответы на DO/DONT/WILL/WONT выдаются ТОЛЬКО при смене состояния опции — это стандартная защита от
 * бесконечных петель неготиации. Вход недоверенный (Telnet без auth/TLS), поэтому кодек защищён от
 * враждебного сервера: тело под-сообщения ограничено [MAX_SUBNEG_BYTES] (не копить бесконечно на
 * `IAC SB` без `IAC SE`), а суммарный размер ответов за один [consume] — [MAX_REPLY_BYTES] (гасит
 * амплификацию через быстрое переключение опции туда-обратно). [consume] возвращает «чистые»
 * прикладные байты (для терминала) и байты ответа (в сокет); [encode] удваивает литеральный 0xFF в
 * исходящем вводе; [windowSize] собирает под-сообщение размера окна для resize.
 */
class TelnetCodec(
    private val termType: String = "xterm-256color",
    cols: Int = 80,
    rows: Int = 24,
) {
    /** Результат разбора входящего блока: [data] — прикладные байты, [reply] — ответ в сокет. */
    class Decoded(val data: ByteArray, val reply: ByteArray)

    private enum class Phase { DATA, IAC, NEGOTIATE, SUBNEG, SUBNEG_IAC, SUBNEG_DROP, SUBNEG_DROP_IAC }

    private var phase = Phase.DATA
    private var command = 0 // накопленный WILL/WONT/DO/DONT в фазе NEGOTIATE
    private val subneg = ArrayList<Int>() // тело SB…SE (без IAC-обёртки), ограничено MAX_SUBNEG_BYTES

    // Текущий размер окна для NAWS. Пишется из resize-корутины ([windowSize]), читается из
    // read-корутины ([consume]→[nawsSubnegotiation]) — @Volatile закрывает межпоточную видимость
    // (кратковременная рассинхронизация cols/rows безвредна: следующий resize её исправит).
    @Volatile private var curCols = cols.coerceIn(0, 0xFFFF)
    @Volatile private var curRows = rows.coerceIn(0, 0xFFFF)

    // Состояние опций: true — включена на нашей/удалённой стороне. Ответы шлём лишь на переход.
    private val localEnabled = HashMap<Int, Boolean>() // наши WILL
    private val remoteEnabled = HashMap<Int, Boolean>() // наши DO

    // Эхоит ли сервер ввод (remote ECHO). @Volatile: читается из UI-корутины (гейт истории
    // автодополнения), пишется из read-корутины. false = сервер не эхоит (ввод пароля / line-mode) —
    // терминал по этому флагу НЕ записывает набранное в историю (секреты не оседают в подсказках).
    @Volatile private var serverEcho = false

    /** Эхоит ли сервер ввод (remote ECHO активна). false — вероятный ввод пароля / line-mode. */
    val serverEchoEnabled: Boolean get() = serverEcho

    /**
     * Согласован ли NAWS (сервер прислал DO NAWS, мы ответили WILL). Только тогда допустимо слать
     * под-сообщение размера окна при resize — иначе строгий сервер может закрыть соединение на
     * незапрошенное SB. До согласования [windowSize] всё равно ЗАПОМНИТ размер (для будущего DO NAWS).
     */
    val nawsNegotiated: Boolean get() = localEnabled[NAWS] == true

    /** Прикладной ввод пользователя → на провод: литеральный IAC (0xFF) удваивается. */
    fun encode(data: ByteArray): ByteArray {
        if (data.none { it == IAC.toByte() }) return data
        val out = ArrayList<Byte>(data.size + 4)
        for (b in data) {
            out.add(b)
            if (b == IAC.toByte()) out.add(IAC.toByte())
        }
        return out.toByteArray()
    }

    /** Под-сообщение NAWS с новым размером окна (для отправки при resize). */
    fun windowSize(newCols: Int, newRows: Int): ByteArray {
        curCols = newCols.coerceIn(0, 0xFFFF)
        curRows = newRows.coerceIn(0, 0xFFFF)
        return nawsSubnegotiation()
    }

    /** Разобрать входящий блок с провода: выделить прикладные байты и собрать ответ неготиации. */
    fun consume(input: ByteArray): Decoded {
        val data = ArrayList<Byte>(input.size)
        val reply = ArrayList<Byte>()
        for (raw in input) {
            val b = raw.toInt() and 0xFF
            when (phase) {
                Phase.DATA ->
                    if (b == IAC) phase = Phase.IAC else data.add(raw)

                Phase.IAC -> when (b) {
                    IAC -> { data.add(IAC.toByte()); phase = Phase.DATA } // экранированный 0xFF
                    WILL, WONT, DO, DONT -> { command = b; phase = Phase.NEGOTIATE }
                    SB -> { subneg.clear(); phase = Phase.SUBNEG }
                    else -> phase = Phase.DATA // NOP/GA/DM/… — одиночные команды без данных
                }

                Phase.NEGOTIATE -> {
                    // Ответы неготиации ограничены сверху — гасим амплификацию от враждебного сервера,
                    // который быстро переключает опцию туда-обратно (каждый флип — «смена состояния»).
                    if (reply.size < MAX_REPLY_BYTES) negotiate(command, b, reply)
                    phase = Phase.DATA
                }

                Phase.SUBNEG ->
                    when {
                        b == IAC -> phase = Phase.SUBNEG_IAC
                        // Тело SB не должно расти бесконечно (сервер может не слать SE) — при превышении
                        // порога прекращаем копить и просто ищем закрывающий IAC SE, отбрасывая тело.
                        subneg.size >= MAX_SUBNEG_BYTES -> { subneg.clear(); phase = Phase.SUBNEG_DROP }
                        else -> subneg.add(b)
                    }

                Phase.SUBNEG_IAC -> when (b) {
                    IAC -> { subneg.add(IAC); phase = Phase.SUBNEG } // экранированный 0xFF в теле SB
                    SE -> { if (reply.size < MAX_REPLY_BYTES) handleSubnegotiation(subneg, reply); phase = Phase.DATA }
                    else -> phase = Phase.DATA // некорректная последовательность — сбрасываем
                }

                // Тело SB переросло лимит: молча проматываем до IAC SE, ничего не буферизуя.
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
        // Сервер запросил тип терминала: IAC SB TERMINAL-TYPE SEND IAC SE → отвечаем IS <termtype>.
        if (body.size >= 2 && body[0] == TERMINAL_TYPE && body[1] == TT_SEND) {
            reply.add(IAC.toByte()); reply.add(SB.toByte())
            reply.add(TERMINAL_TYPE.toByte()); reply.add(TT_IS.toByte())
            // Экранируем литеральный 0xFF в имени терминала (как в NAWS/encode) — иначе стрей-0xFF
            // преждевременно закрыл бы под-сообщение и хвост ушёл бы серверу как команды.
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
        // 16-битные ширина/высота, старший байт первым; 0xFF внутри тела экранируется удвоением.
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

        // Защита от враждебного сервера: тело одного под-сообщения и суммарный ответ за consume().
        const val MAX_SUBNEG_BYTES = 8192
        const val MAX_REPLY_BYTES = 16384
    }
}
