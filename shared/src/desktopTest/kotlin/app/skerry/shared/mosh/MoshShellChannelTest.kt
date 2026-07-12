package app.skerry.shared.mosh

import app.skerry.shared.ssh.PtySize
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Loopback stand-in for mosh-server: same codec/fragment/zlib stack, opposite direction.
 * Driven synchronously by the test.
 */
private class FakeMoshServer {
    val key = MoshKey.parse("zM7RhBUAAcTLKwZTHYzGaw")!!
    private val socket = DatagramSocket(0, InetAddress.getLoopbackAddress())
    private val codec = MoshPacketCodec(key)
    private val assembler = MoshFragmentAssembler()
    private val fragmenter = MoshFragmenter()
    private var clientAddress: SocketAddress? = null
    private var seq = 0uL

    val port: Int get() = socket.localPort

    fun receiveInstruction(timeoutMs: Int = 5000): MoshInstruction {
        socket.soTimeout = timeoutMs
        while (true) {
            val buffer = ByteArray(4096)
            val dp = DatagramPacket(buffer, buffer.size)
            socket.receive(dp)
            val packet = codec.open(buffer.copyOf(dp.length)) ?: continue
            if (!packet.toServer) continue
            clientAddress = dp.socketAddress
            val fragment = MoshFragment.parse(packet.payload) ?: continue
            val whole = assembler.add(fragment) ?: continue
            val inflated = moshInflate(whole) ?: continue
            return decodeMoshInstruction(inflated)
        }
    }

    /** Skips empty-diff traffic (acks/heartbeats) until a data-bearing instruction arrives. */
    fun receiveDataInstruction(timeoutMs: Int = 5000): MoshInstruction {
        while (true) {
            val instruction = receiveInstruction(timeoutMs)
            if (instruction.diff.isNotEmpty()) return instruction
        }
    }

    fun sendInstruction(old: ULong, new: ULong, ack: ULong, diff: ByteArray = ByteArray(0)) {
        val target = checkNotNull(clientAddress) { "no client datagram seen yet" }
        val payload = moshDeflate(
            MoshInstruction(oldNum = old, newNum = new, ackNum = ack, throwawayNum = 0u, diff = diff)
                .encode(),
        )
        for (fragment in fragmenter.split(payload, 400)) {
            val sealed = codec.seal(
                MoshPacket(
                    seq = seq++,
                    toServer = false,
                    timestamp = 1,
                    timestampReply = MOSH_TS_NONE,
                    payload = fragment.encode(),
                ),
            )
            socket.send(DatagramPacket(sealed, sealed.size, target))
        }
    }

    /** Raw bytes straight to the client — simulates garbage/foreign-key datagrams. */
    fun sendRaw(bytes: ByteArray) {
        val target = checkNotNull(clientAddress) { "no client datagram seen yet" }
        socket.send(DatagramPacket(bytes, bytes.size, target))
    }

    fun close() = socket.close()
}

class MoshShellChannelTest {

    private val servers = mutableListOf<FakeMoshServer>()
    private val connections = mutableListOf<MoshConnection>()

    private fun server(): FakeMoshServer = FakeMoshServer().also { servers += it }

    private fun connection(server: FakeMoshServer, timeoutMs: Long = 5000) =
        MoshConnection("127.0.0.1", server.port, server.key, timeoutMs).also { connections += it }

    @AfterTest
    fun tearDown() {
        runBlocking { connections.forEach { runCatching { it.disconnect() } } }
        servers.forEach { it.close() }
    }

    @Test
    fun `handshake announces the terminal size and completes on first server state`() =
        runBlocking {
            val server = server()
            val conn = connection(server)
            val opening = async(Dispatchers.Default) {
                conn.openShell(PtySize(cols = 100, rows = 30))
            }
            val first = server.receiveDataInstruction()
            assertEquals(0uL, first.oldNum)
            assertContentEquals(
                encodeUserMessage(listOf(MoshUserEvent.Resize(cols = 100, rows = 30))),
                first.diff,
            )
            server.sendInstruction(
                old = 0u,
                new = 1u,
                ack = first.newNum,
                diff = encodeHostBytesDiff("greetings".encodeToByteArray()),
            )
            val channel = opening.await()
            val emitted = withTimeout(5000) { channel.output.first() }
            assertEquals("greetings", emitted.decodeToString())
        }

    @Test
    fun `write is delivered as a keystroke diff from the acked state`() = runBlocking {
        val server = server()
        val conn = connection(server)
        val opening = async(Dispatchers.Default) { conn.openShell(PtySize(cols = 80, rows = 24)) }
        val first = server.receiveDataInstruction()
        server.sendInstruction(old = 0u, new = 1u, ack = first.newNum)
        val channel = opening.await()

        channel.write("ls\r".encodeToByteArray())
        val keystrokes = server.receiveDataInstruction()
        // The initial resize state was acked, so the diff starts from it.
        assertEquals(first.newNum, keystrokes.oldNum)
        assertEquals(first.newNum + 1u, keystrokes.newNum)
        assertContentEquals(
            encodeUserMessage(listOf(MoshUserEvent.Keystroke("ls\r".encodeToByteArray()))),
            keystrokes.diff,
        )
        assertEquals(1uL, keystrokes.ackNum)
    }

    @Test
    fun `server shutdown ends the output with a clean eof and gets acknowledged`() =
        runBlocking {
            val server = server()
            val conn = connection(server)
            val opening = async(Dispatchers.Default) { conn.openShell(PtySize()) }
            val first = server.receiveDataInstruction()
            server.sendInstruction(old = 0u, new = 1u, ack = first.newNum)
            val channel = opening.await()

            val collecting = async(Dispatchers.Default) { channel.output.toList() }
            server.sendInstruction(old = 1u, new = ULong.MAX_VALUE, ack = first.newNum)
            withTimeout(5000) { collecting.await() }
            assertTrue(channel.endedWithEof)
            // The client must confirm the shutdown so mosh-server can exit.
            var acked = false
            repeat(10) {
                if (acked) return@repeat
                val inst = server.receiveInstruction()
                if (inst.ackNum == ULong.MAX_VALUE) acked = true
            }
            assertTrue(acked, "expected an instruction acknowledging the server shutdown")
        }

    @Test
    fun `silent udp peer fails the handshake with a typed unreachable error`() = runBlocking {
        // A bound socket that never answers simulates a firewall that drops UDP.
        val mute = DatagramSocket(0, InetAddress.getLoopbackAddress())
        try {
            val conn = MoshConnection(
                "127.0.0.1",
                mute.localPort,
                MoshKey.parse("AAAAAAAAAAAAAAAAAAAAAA")!!,
                firstContactTimeoutMillis = 400,
            )
            val e = assertFailsWith<MoshSetupException> { conn.openShell(PtySize()) }
            assertEquals(MoshSetupException.Reason.UDP_UNREACHABLE, e.reason)
            assertEquals(mute.localPort.toString(), e.detail)
        } finally {
            mute.close()
        }
    }

    @Test
    fun `garbage and foreign-key datagrams are ignored, not fatal`() = runBlocking {
        val server = server()
        val conn = connection(server)
        val opening = async(Dispatchers.Default) { conn.openShell(PtySize()) }
        val first = server.receiveDataInstruction()

        // Junk and a datagram sealed under a different key must neither complete the
        // handshake nor break the channel — only an authentic packet counts.
        server.sendRaw(ByteArray(3))
        server.sendRaw(ByteArray(64) { it.toByte() })
        val foreign = MoshPacketCodec(MoshKey.parse("AAAAAAAAAAAAAAAAAAAAAA")!!)
        server.sendRaw(foreign.seal(MoshPacket(0u, toServer = false, 1, MOSH_TS_NONE, byteArrayOf(7))))

        server.sendInstruction(old = 0u, new = 1u, ack = first.newNum)
        val channel = opening.await()
        assertTrue(channel.isOpen)
    }
}
