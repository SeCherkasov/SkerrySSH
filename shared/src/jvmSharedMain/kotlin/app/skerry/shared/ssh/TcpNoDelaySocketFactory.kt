package app.skerry.shared.ssh

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.SocketFactory

/**
 * TCP connect timeout for the SSH transport. sshj's own default is 0 = wait forever; without a cap,
 * "Test connection" to a nonexistent/firewalled address hangs with no way to cancel from the UI.
 */
internal const val CONNECT_TIMEOUT_MILLIS = 10_000

/**
 * Socket factory for the SSH transport with Nagle's algorithm off.
 *
 * Interactive SSH traffic is many small packets — one per keystroke — and Nagle holds each one back
 * until the previous segment is ACKed, adding up to one RTT of typing latency on WAN links. OpenSSH
 * disables it for interactive sessions for exactly this reason; sshj keeps the JVM default (Nagle
 * on) and its SocketClient has no knob for it, so the factory is the supported way in. Forwarded
 * sockets already set the flag (see SshjForwards) — this brings the transport itself in line.
 *
 * sshj only ever calls the no-arg [createSocket] (then `socket.connect(addr, connectTimeout)`).
 * The connecting overloads still route through it and connect under [CONNECT_TIMEOUT_MILLIS]:
 * the eagerly-connecting `Socket(host, port)` constructors have no timeout at all, and an sshj
 * upgrade that switched overloads would otherwise silently reintroduce the indefinite hang the
 * timeout exists to kill.
 */
internal object TcpNoDelaySocketFactory : SocketFactory() {

    override fun createSocket(): Socket = Socket().apply { tcpNoDelay = true }

    override fun createSocket(host: String, port: Int): Socket =
        connected(InetSocketAddress(host, port), local = null)

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        connected(InetSocketAddress(host, port), InetSocketAddress(localHost, localPort))

    override fun createSocket(host: InetAddress, port: Int): Socket =
        connected(InetSocketAddress(host, port), local = null)

    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
        connected(InetSocketAddress(address, port), InetSocketAddress(localAddress, localPort))

    private fun connected(remote: InetSocketAddress, local: InetSocketAddress?): Socket =
        createSocket().apply {
            try {
                local?.let(::bind)
                connect(remote, CONNECT_TIMEOUT_MILLIS)
            } catch (e: Exception) {
                runCatching { close() }
                throw e
            }
        }
}
