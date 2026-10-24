package app.skerry.shared.securitykey

import com.yubico.yubikit.core.Transport
import com.yubico.yubikit.core.fido.FidoConnection
import com.yubico.yubikit.core.smartcard.SmartCardConnection
import java.util.concurrent.atomic.AtomicBoolean

/** Bound and structurally validate whole responses before any SDK CBOR decoding, including getInfo. */
internal class BoundedFidoConnection(private val delegate: FidoConnection) : FidoConnection {
    private var packets = 0
    private var keepalives = 0
    private var command = 4
    private val buffered = ArrayDeque<ByteArray>()
    private val closed = AtomicBoolean()
    override fun send(packet: ByteArray) {
        if (++packets > 2048 || packet.size != 64 || closed.get()) invalidAuthenticator()
        if (packet[4] == 0x90.toByte()) command = packet[7].toInt() and 255
        delegate.send(packet)
    }
    private fun physicalRead(): ByteArray = ByteArray(64).also { packet ->
        delegate.receive(packet)
        if (packet[4] == 0xbb.toByte()) {
            if (++keepalives > 1800) invalidAuthenticator()
        } else if (++packets > 2048) invalidAuthenticator()
    }
    override fun receive(packet: ByteArray) {
        if (packet.size != 64 || closed.get()) invalidAuthenticator()
        if (buffered.isEmpty()) {
            val first = physicalRead()
            if (first[4] == 0x90.toByte()) buffered.addAll(validatedHidCborFrames(first, command, ::physicalRead))
            else buffered.add(first)
        }
        buffered.removeFirst().copyInto(packet)
    }
    override fun close() { if (closed.compareAndSet(false, true)) delegate.close() }
}
internal class BoundedSmartCardConnection(private val delegate: SmartCardConnection) : SmartCardConnection {
    private var commands = 0
    private var bytes = 0
    private var ctapCommand: Int? = null
    private val closed = AtomicBoolean()
    private fun exchange(apdu: ByteArray): ByteArray {
        if (++commands > 256 || apdu.size > 16384 || closed.get()) invalidAuthenticator()
        val response = delegate.sendAndReceive(apdu)
        bytes += response.size
        if (response.size !in 2..16384 || bytes > 262144) invalidAuthenticator()
        return response
    }
    override fun sendAndReceive(apdu: ByteArray): ByteArray {
        if (apdu.size < 4) invalidAuthenticator()
        if (apdu[1] != 0x10.toByte()) return exchange(apdu)
        if (ctapCommand == null) {
            if (apdu.size < 6) invalidAuthenticator()
            val offset = if (apdu[4].toInt() == 0) 7 else 5
            if (apdu.size <= offset) invalidAuthenticator()
            ctapCommand = apdu[offset].toInt() and 255
        }
        val response = exchange(apdu)
        if (apdu[0].toInt() and 0x10 != 0) return response // Command chaining has not sent its final piece yet.
        return try {
            validatedCtapApduResponse(response, ctapCommand!!) { exchange(byteArrayOf(0, 0xc0.toByte(), 0, 0)) }
        } finally { ctapCommand = null }
    }
    override fun getTransport(): Transport = delegate.transport
    override fun isExtendedLengthApduSupported(): Boolean = delegate.isExtendedLengthApduSupported
    override fun getAtr(): ByteArray = delegate.atr
    override fun close() { if (closed.compareAndSet(false, true)) delegate.close() }
}
