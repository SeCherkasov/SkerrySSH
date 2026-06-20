package app.skerry.shared.ssh

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress

/**
 * Минимальный SOCKS5-сервер (RFC 1928) для динамического проброса (`-D`): метод аутентификации
 * «none» и команда `CONNECT`. Каждый клиент SOCKS сообщает адрес назначения в момент соединения,
 * а мы открываем под него отдельный `direct-tcpip` SSH-канал. Чистый разбор протокола вынесен сюда,
 * чтобы покрыть его юнит-тестами на байтовых потоках без сети.
 *
 * Намеренно НЕ поддерживаем: SOCKS4/4a, аутентификацию, команды `BIND`/`UDP ASSOCIATE` — на них
 * отдаём корректный отказной ответ. Этого достаточно для проксирования TCP браузером/curl/`--socks5`.
 */
internal object Socks5 {

    private const val VERSION = 0x05
    private const val CMD_CONNECT = 0x01
    private const val METHOD_NO_AUTH = 0x00
    private const val METHOD_NONE_ACCEPTABLE = 0xFF

    private const val ATYP_IPV4 = 0x01
    private const val ATYP_DOMAIN = 0x03
    private const val ATYP_IPV6 = 0x04

    /** Коды ответа (REP) сервера. */
    const val REP_SUCCESS = 0x00
    const val REP_GENERAL_FAILURE = 0x01
    const val REP_CONNECTION_REFUSED = 0x05
    const val REP_COMMAND_NOT_SUPPORTED = 0x07
    const val REP_ADDRESS_NOT_SUPPORTED = 0x08

    /** Разобранный адрес назначения из запроса `CONNECT`. */
    data class Target(val host: String, val port: Int)

    /**
     * Провести выбор метода и прочитать запрос `CONNECT`. На корректном no-auth CONNECT возвращает
     * [Target] (ответ об успехе НЕ шлём — его отправит вызывающий после открытия канала через
     * [replySuccess]). На любом несоответствии (не тот VER, нет no-auth, не CONNECT, неизвестный тип
     * адреса) сам пишет корректный отказной ответ и возвращает `null`.
     */
    fun accept(input: InputStream, output: OutputStream): Target? {
        // 1. Приветствие: VER, NMETHODS, METHODS[NMETHODS].
        val ver = input.readByte()
        val methods = input.readN(input.readByte())
        if (ver != VERSION || METHOD_NO_AUTH.toByte() !in methods) {
            output.write(byteArrayOf(VERSION.toByte(), METHOD_NONE_ACCEPTABLE.toByte()))
            output.flush()
            return null
        }
        output.write(byteArrayOf(VERSION.toByte(), METHOD_NO_AUTH.toByte()))
        output.flush()

        // 2. Запрос: VER, CMD, RSV, ATYP, DST.ADDR, DST.PORT.
        val reqVer = input.readByte()
        val cmd = input.readByte()
        input.readByte() // RSV
        val atyp = input.readByte()
        if (reqVer != VERSION) {
            reply(output, REP_GENERAL_FAILURE)
            return null
        }
        if (cmd != CMD_CONNECT) {
            reply(output, REP_COMMAND_NOT_SUPPORTED)
            return null
        }
        val host = when (atyp) {
            ATYP_IPV4 -> input.readN(4).joinToString(".") { (it.toInt() and 0xFF).toString() }
            ATYP_IPV6 -> InetAddress.getByAddress(input.readN(16)).hostAddress
            ATYP_DOMAIN -> input.readN(input.readByte()).decodeToString()
            else -> {
                reply(output, REP_ADDRESS_NOT_SUPPORTED)
                return null
            }
        }
        val port = (input.readByte() shl 8) or input.readByte()
        return Target(host, port)
    }

    /** Ответ об успехе (REP=0x00) с нулевым BND-адресом `0.0.0.0:0`. */
    fun replySuccess(output: OutputStream) = reply(output, REP_SUCCESS)

    /** Отказной ответ с заданным кодом [rep] и нулевым BND-адресом. */
    fun replyFailure(output: OutputStream, rep: Int) = reply(output, rep)

    // BND.ADDR/PORT для CONNECT клиенты игнорируют — шлём нулевой IPv4 0.0.0.0:0.
    private fun reply(output: OutputStream, rep: Int) {
        output.write(byteArrayOf(VERSION.toByte(), rep.toByte(), 0x00, ATYP_IPV4.toByte(), 0, 0, 0, 0, 0, 0))
        output.flush()
    }

    private fun InputStream.readByte(): Int =
        read().also { if (it < 0) throw EOFException("SOCKS5: преждевременный конец потока") }

    private fun InputStream.readN(n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = read(buf, off, n - off)
            if (r < 0) throw EOFException("SOCKS5: преждевременный конец потока (нужно $n байт)")
            off += r
        }
        return buf
    }
}
