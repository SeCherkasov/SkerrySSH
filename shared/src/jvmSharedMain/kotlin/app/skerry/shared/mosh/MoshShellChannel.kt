package app.skerry.shared.mosh

import app.skerry.shared.ssh.PtySize
import app.skerry.shared.ssh.ShellChannel
import app.skerry.shared.ssh.SshConnectionException
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The live Mosh session: an unconnected UDP socket speaking mosh's datagram protocol
 * (AES-128-OCB packets → zlib+fragmented SSP instructions). Fitted to the [ShellChannel]
 * contract: `output` carries the server's terminal diffs (plain ANSI for the emulator),
 * `write`/`resize` become user-stream events.
 *
 * Threading: one loop (the `output` collector) receives datagrams and drives timers;
 * `write`/`resize` transmit directly. All protocol state is guarded by [stateLock].
 * The socket stays unconnected on purpose: authenticity comes from OCB, and an
 * unconnected socket ignores ICMP port-unreachable bursts during network roaming.
 */
internal class MoshShellChannel(
    private val remoteHost: String,
    private val remotePort: Int,
    key: MoshKey,
    private val initialSize: PtySize,
    private val firstContactTimeoutMillis: Long,
) : ShellChannel {

    private val codec = MoshPacketCodec(key)
    private val socket = DatagramSocket()
    private var remote: InetSocketAddress? = null // set once by handshake

    private val stateLock = Mutex()
    private val rtt = MoshRtt()
    private val outgoing = MoshOutgoing(rtt, startMs = clockMs())
    private val incoming = MoshIncoming()
    private val fragmenter = MoshFragmenter()
    private val assembler = MoshFragmentAssembler()
    private var txSeq = 0uL
    // rxNextSeq and assembler are deliberately NOT under stateLock: they are touched only by
    // the single receive path (handshake, then the sole output collector), never concurrently.
    private var rxNextSeq = 0uL
    private var lastServerTs = -1
    private var lastServerTsAt = 0L

    private val collected = AtomicBoolean(false)
    private val closing = AtomicBoolean(false)
    @Volatile private var serverShutdown = false
    @Volatile private var eof = false
    @Volatile private var up = 0L
    @Volatile private var down = 0L
    private var pendingFirstBytes: ByteArray? = null

    override val isOpen: Boolean get() = !socket.isClosed
    override val bytesUp: Long get() = up
    override val bytesDown: Long get() = down
    override val endedWithEof: Boolean get() = eof

    /** Smoothed protocol RTT for the status bar (mosh measures it on every datagram). */
    fun rttMs(): Long? = rtt.currentRttMs()

    /**
     * First contact: resolve the host, announce our terminal size and wait until the first
     * authentic server datagram proves the UDP path works.
     *
     * @throws MoshSetupException nothing came back — UDP is filtered or unreachable
     */
    suspend fun handshake() {
        try {
            val address = withContext(Dispatchers.IO) { InetAddress.getByName(remoteHost) }
            remote = InetSocketAddress(address, remotePort)
        } catch (e: IOException) {
            abort()
            throw SshConnectionException("Failed to resolve the Mosh host address", e)
        }
        stateLock.withLock { outgoing.addResize(initialSize.cols, initialSize.rows) }
        val deadline = clockMs() + firstContactTimeoutMillis
        while (true) {
            pumpSend()
            val datagram = receiveOrNull(soTimeoutMs = 250)
            if (datagram != null) {
                val bytes = processDatagram(datagram)
                if (bytes != null) { // any authentic server packet proves the UDP path
                    if (bytes.isNotEmpty()) pendingFirstBytes = bytes
                    pumpSend() // flush the ack of the server's first state right away
                    return
                }
            }
            if (clockMs() >= deadline) {
                abort()
                throw MoshSetupException(
                    reason = MoshSetupException.Reason.UDP_UNREACHABLE,
                    detail = remotePort.toString(),
                    message = "mosh-server started, but nothing came back on UDP port $remotePort",
                )
            }
        }
    }

    /** Close the socket without the shutdown exchange (failed/cancelled handshake). */
    fun abort() {
        runCatching { socket.close() }
    }

    override val output: Flow<ByteArray> = flow {
        check(collected.compareAndSet(false, true)) { "Mosh output can only be collected once" }
        try {
            pendingFirstBytes?.let {
                pendingFirstBytes = null
                emit(it)
            }
            while (!socket.isClosed) {
                val wake = stateLock.withLock { outgoing.nextWakeMs(clockMs()) }
                val datagram = receiveOrNull(soTimeoutMs = wake.coerceIn(1, 250).toInt())
                if (datagram != null) {
                    val bytes = processDatagram(datagram)
                    if (bytes != null && bytes.isNotEmpty()) emit(bytes)
                }
                if (serverShutdown) {
                    // The remote shell exited: acknowledge the server's shutdown state so
                    // mosh-server can quit instead of lingering, then report a clean EOF.
                    sendShutdownAck()
                    eof = true
                    break
                }
                pumpSend()
            }
        } finally {
            // Collector cancelled or the loop errored: send the shutdown notice before the
            // socket dies (a bare socket.close() here would race MoshConnection.disconnect()
            // and skip the notice, leaving mosh-server detached). After a server-initiated
            // shutdown there is nobody left to notify — just release the socket.
            if (eof) abort() else close()
        }
    }

    override suspend fun write(data: ByteArray) {
        if (data.isEmpty()) return
        if (socket.isClosed) throw SshConnectionException("Mosh channel is closed")
        stateLock.withLock { outgoing.addKeystroke(data.copyOf()) }
        flushSoon()
    }

    override suspend fun resize(size: PtySize) {
        if (socket.isClosed) return
        stateLock.withLock { outgoing.addResize(size.cols, size.rows) }
        flushSoon()
    }

    /**
     * Send now; if the send-rate gate held the event back, wait out the coalescing window
     * and send then — user input must never sit waiting for the receive loop to wake up.
     */
    private suspend fun flushSoon() {
        pumpSend()
        if (stateLock.withLock { outgoing.hasUnsentData() }) {
            delay(MOSH_SEND_MINDELAY_MS)
            pumpSend()
        }
    }

    override suspend fun close() {
        if (!closing.compareAndSet(false, true)) return
        withContext(NonCancellable) {
            // Best-effort shutdown notice (twice, spaced): if both datagrams are lost the
            // server lingers detached — the same trade-off mosh itself accepts on close.
            runCatching {
                stateLock.withLock { outgoing.startShutdown() }
                pumpSend()
                delay(60)
                stateLock.withLock {
                    transmitLocked(
                        MoshInstruction(
                            oldNum = 0u,
                            newNum = ULong.MAX_VALUE,
                            ackNum = incoming.ackNum,
                            throwawayNum = 0u,
                            diff = ByteArray(0),
                        ),
                        clockMs(),
                    )
                }
            }
            abort()
        }
    }

    /** Send whatever the state machine considers due (fresh input, retransmit, ack, heartbeat). */
    private suspend fun pumpSend() {
        stateLock.withLock {
            val now = clockMs()
            val instruction = outgoing.poll(now, incoming.ackNum, forceAck = incoming.ackOutstanding)
                ?: return
            incoming.clearAckOutstanding()
            transmitLocked(instruction, now)
        }
    }

    private suspend fun sendShutdownAck() {
        stateLock.withLock {
            transmitLocked(
                MoshInstruction(
                    oldNum = incoming.ackNum,
                    newNum = incoming.ackNum,
                    ackNum = ULong.MAX_VALUE,
                    throwawayNum = 0u,
                    diff = ByteArray(0),
                ),
                clockMs(),
            )
        }
    }

    /** Callers hold [stateLock]. */
    private suspend fun transmitLocked(instruction: MoshInstruction, nowMs: Long) {
        val target = remote ?: return
        val payload = moshDeflate(instruction.encode())
        for (fragment in fragmenter.split(payload, MAX_FRAGMENT_DATA)) {
            val sealed = codec.seal(
                MoshPacket(
                    seq = txSeq++,
                    toServer = true,
                    timestamp = timestamp16(nowMs),
                    timestampReply = echoTimestampLocked(nowMs),
                    payload = fragment.encode(),
                ),
            )
            try {
                withContext(Dispatchers.IO) {
                    socket.send(DatagramPacket(sealed, sealed.size, target))
                }
                up += sealed.size
            } catch (e: IOException) {
                if (socket.isClosed) throw SshConnectionException("Mosh channel is closed", e)
                // Transient send failure (network mid-roam): the retransmit timer covers it.
            }
        }
    }

    /**
     * Decrypt/parse one datagram. Returns null if it isn't an authentic in-sequence packet
     * from the server, otherwise the terminal bytes it produced (often empty: acks,
     * heartbeats, not-yet-applicable diffs).
     */
    private suspend fun processDatagram(datagram: ByteArray): ByteArray? {
        val packet = codec.open(datagram) ?: return null
        if (packet.toServer) return null // reflected/self traffic
        if (packet.seq < rxNextSeq) return null // replay or reordered duplicate
        rxNextSeq = packet.seq + 1u
        down += datagram.size
        val now = clockMs()
        stateLock.withLock {
            if (packet.timestampReply != MOSH_TS_NONE) {
                rtt.onSample(rttSample(timestamp16(now), packet.timestampReply))
            }
            lastServerTs = packet.timestamp
            lastServerTsAt = now
        }
        val fragment = MoshFragment.parse(packet.payload) ?: return ByteArray(0)
        val whole = assembler.add(fragment) ?: return ByteArray(0)
        val inflated = moshInflate(whole) ?: return ByteArray(0)
        val instruction = try {
            decodeMoshInstruction(inflated)
        } catch (_: MoshWireException) {
            return ByteArray(0)
        }
        stateLock.withLock { outgoing.onAck(instruction.ackNum) }
        if (instruction.newNum == ULong.MAX_VALUE) {
            serverShutdown = true
            return ByteArray(0)
        }
        val updates = try {
            // Under stateLock: pumpSend() reads incoming.ackNum/ackOutstanding from other
            // coroutines (write/resize), so this mutation must not race those reads.
            stateLock.withLock { incoming.onInstruction(instruction) } ?: return ByteArray(0)
        } catch (_: MoshWireException) {
            return ByteArray(0)
        }
        var total = 0
        for (u in updates) if (u is MoshHostUpdate.Bytes) total += u.data.size
        if (total == 0) return ByteArray(0) // Resize/EchoAck updates: the UI owns the size
        val bytes = ByteArray(total)
        var offset = 0
        for (u in updates) {
            if (u is MoshHostUpdate.Bytes) {
                u.data.copyInto(bytes, offset)
                offset += u.data.size
            }
        }
        return bytes
    }

    private suspend fun receiveOrNull(soTimeoutMs: Int): ByteArray? =
        withContext(Dispatchers.IO) {
            try {
                socket.soTimeout = soTimeoutMs
                val buffer = ByteArray(RECV_BUFFER)
                val dp = DatagramPacket(buffer, buffer.size)
                socket.receive(dp)
                buffer.copyOf(dp.length)
            } catch (_: SocketTimeoutException) {
                null
            } catch (e: IOException) {
                if (socket.isClosed) null
                else throw SshConnectionException("Mosh socket failed", e)
            }
        }

    /** Callers hold [stateLock]: echoed server timestamp, advanced by our hold time. */
    private fun echoTimestampLocked(nowMs: Long): Int {
        val ts = lastServerTs
        if (ts < 0) return MOSH_TS_NONE
        return (ts + (nowMs - lastServerTsAt)).toInt() and 0xFFFF
    }

    private companion object {
        /** mosh's DEFAULT_SEND_MTU (500) minus nonce+timestamps+tag+fragment header. */
        const val MAX_FRAGMENT_DATA = 462
        const val RECV_BUFFER = 4096
    }
}

/** Monotonic milliseconds — wall-clock jumps must not distort protocol timers. */
internal fun clockMs(): Long = System.nanoTime() / 1_000_000
