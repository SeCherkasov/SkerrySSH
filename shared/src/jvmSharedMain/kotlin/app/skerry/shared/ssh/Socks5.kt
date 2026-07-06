package app.skerry.shared.ssh

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress

/**
 * Minimal SOCKS5 server (RFC 1928) for dynamic port forwarding (`-D`): "none" auth method and
 * `CONNECT` command only. Each SOCKS client reports its destination address on connect, and we open
 * a dedicated `direct-tcpip` SSH channel for it. Protocol parsing is isolated here for unit testing
 * over byte streams without a network.
 *
 * Not supported: SOCKS4/4a, authentication, `BIND`/`UDP ASSOCIATE` — these get a proper failure
 * reply. Sufficient for TCP proxying from a browser/curl/`--socks5`.
 */
internal object Socks5 {

    private const val VERSION = 0x05
    private const val CMD_CONNECT = 0x01
    private const val METHOD_NO_AUTH = 0x00
    private const val METHOD_NONE_ACCEPTABLE = 0xFF

    private const val ATYP_IPV4 = 0x01
    private const val ATYP_DOMAIN = 0x03
    private const val ATYP_IPV6 = 0x04

    /** Server reply (REP) codes. */
    const val REP_SUCCESS = 0x00
    const val REP_GENERAL_FAILURE = 0x01
    const val REP_CONNECTION_REFUSED = 0x05
    const val REP_COMMAND_NOT_SUPPORTED = 0x07
    const val REP_ADDRESS_NOT_SUPPORTED = 0x08

    /** Destination address parsed from a `CONNECT` request. */
    data class Target(val host: String, val port: Int)

    /**
     * Run method selection and read the `CONNECT` request. On a valid no-auth CONNECT returns
     * [Target] (the success reply is NOT sent here — the caller sends it after opening the channel,
     * via [replySuccess]). On any mismatch (wrong VER, no no-auth method, not CONNECT, unknown address
     * type) writes a proper failure reply itself and returns `null`.
     */
    fun accept(input: InputStream, output: OutputStream): Target? {
        // 1. Greeting: VER, NMETHODS, METHODS[NMETHODS].
        val ver = input.readByte()
        val methods = input.readN(input.readByte())
        if (ver != VERSION || METHOD_NO_AUTH.toByte() !in methods) {
            output.write(byteArrayOf(VERSION.toByte(), METHOD_NONE_ACCEPTABLE.toByte()))
            output.flush()
            return null
        }
        output.write(byteArrayOf(VERSION.toByte(), METHOD_NO_AUTH.toByte()))
        output.flush()

        // 2. Request: VER, CMD, RSV, ATYP, DST.ADDR, DST.PORT.
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

    /** Success reply (REP=0x00) with a zero BND address `0.0.0.0:0`. */
    fun replySuccess(output: OutputStream) = reply(output, REP_SUCCESS)

    /** Failure reply with the given [rep] code and a zero BND address. */
    fun replyFailure(output: OutputStream, rep: Int) = reply(output, rep)

    // Clients ignore BND.ADDR/PORT for CONNECT; send a zero IPv4 0.0.0.0:0.
    private fun reply(output: OutputStream, rep: Int) {
        output.write(byteArrayOf(VERSION.toByte(), rep.toByte(), 0x00, ATYP_IPV4.toByte(), 0, 0, 0, 0, 0, 0))
        output.flush()
    }

    private fun InputStream.readByte(): Int =
        read().also { if (it < 0) throw EOFException("SOCKS5: premature end of stream") }

    private fun InputStream.readN(n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = read(buf, off, n - off)
            if (r < 0) throw EOFException("SOCKS5: premature end of stream (need $n bytes)")
            off += r
        }
        return buf
    }
}
