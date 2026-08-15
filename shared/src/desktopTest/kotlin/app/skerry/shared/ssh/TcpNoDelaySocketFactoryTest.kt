package app.skerry.shared.ssh

import java.net.InetAddress
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertTrue

class TcpNoDelaySocketFactoryTest {

    @Test
    fun `unconnected socket has nagle disabled`() {
        TcpNoDelaySocketFactory.createSocket().use { socket ->
            assertTrue(socket.tcpNoDelay)
        }
    }

    @Test
    fun `connected socket variants have nagle disabled`() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val host = server.inetAddress.hostAddress
            val port = server.localPort
            TcpNoDelaySocketFactory.createSocket(host, port).use { socket ->
                assertTrue(socket.tcpNoDelay)
            }
            TcpNoDelaySocketFactory.createSocket(server.inetAddress, port).use { socket ->
                assertTrue(socket.tcpNoDelay)
            }
        }
    }
}
