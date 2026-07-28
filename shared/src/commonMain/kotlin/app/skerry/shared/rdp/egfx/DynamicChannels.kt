package app.skerry.shared.rdp.egfx

import app.skerry.shared.rdp.RdpProtocolException
import app.skerry.shared.rdp.RdpReader
import app.skerry.shared.rdp.RdpWriter
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * What a dynamic channel's owner implements. [onOpen] is where a channel that speaks first gets its
 * chance — the graphics pipeline advertises its capabilities there — and [onMessage] receives whole
 * messages, already reassembled and decompressed.
 */
interface DynamicChannelHandler {
    suspend fun onOpen() = Unit

    suspend fun onMessage(data: ByteArray)
}

/**
 * Dynamic virtual channels (MS-RDPEDYC), the `drdynvc` static channel that carries channels of its
 * own — the graphics pipeline rides on one of them.
 *
 * The server opens each channel by name and the client answers whether it speaks it. Nothing here
 * decides what a channel *means*: a handler registered under a name gets whole messages, and the
 * fragmentation, the reassembly and the optional bulk compression stay in this layer.
 *
 * Handlers run on the session's read loop, so a handler that answers does so before the next PDU is
 * read — which is what the graphics channel relies on when it confirms capabilities.
 */
class DynamicChannels(
    private val send: suspend (ByteArray) -> Unit,
    /** Diagnostics for channels the server opens; silent unless the session turns it on. */
    private val trace: (String) -> Unit = {},
) {

    /** Registered by name before the session starts; the server decides which ones it opens. */
    private val handlers = mutableMapOf<String, DynamicChannelHandler>()

    /**
     * Open channels by id. Two coroutines reach this: the read loop opening and closing channels,
     * and whoever sends on one — a frame acknowledgement leaves the graphics channel from the same
     * loop, but a screen resize does not.
     */
    private val lock = Mutex()
    private val open = mutableMapOf<Int, OpenChannel>()

    /** Held for a whole outgoing message, so a fragmented one is not interleaved with another. */
    private val sendLock = Mutex()

    /** The version agreed with the server; 1 until it says otherwise. */
    private var version = 1

    private class OpenChannel(val name: String, val idWidth: Int) {
        /** Chunks of a message that arrived in pieces, and the total the first chunk announced. */
        var pending: RdpWriter? = null
        var pendingLength = 0

        /**
         * Bulk compression is per channel and stateful: matches reach back into everything this
         * channel has received, so the decompressor cannot be shared or restarted.
         */
        val decompressor by lazy { Zgfx() }
    }

    fun register(name: String, handler: DynamicChannelHandler) {
        handlers[name] = handler
    }

    /** Handle one payload of the `drdynvc` static channel. */
    suspend fun onData(data: ByteArray) {
        val reader = RdpReader(data)
        if (reader.remaining < 1) return
        val header = reader.u8()
        val command = (header shr 4) and 0x0F
        val lengthWidth = (header shr 2) and 0x03
        val idWidth = header and 0x03
        when (command) {
            CMD_CAPABILITIES -> capabilities(reader)
            CMD_CREATE -> create(reader, idWidth)
            CMD_DATA_FIRST -> dataFirst(reader, idWidth, lengthWidth, compressed = false)
            CMD_DATA_FIRST_COMPRESSED -> dataFirst(reader, idWidth, lengthWidth, compressed = true)
            CMD_DATA -> data(reader, idWidth, compressed = false)
            CMD_DATA_COMPRESSED -> data(reader, idWidth, compressed = true)
            CMD_CLOSE -> close(reader, idWidth)
            // Soft-sync moves channels onto the lossy transport, which this client never asked for.
            else -> Unit
        }
    }

    /** Send [payload] on the channel opened under [name], if the server opened it at all. */
    suspend fun sendTo(name: String, payload: ByteArray) {
        val entry = lock.withLock { open.entries.firstOrNull { it.value.name == name } } ?: return
        val channelId = entry.key
        val idWidth = entry.value.idWidth
        if (payload.size <= MAX_CHUNK) {
            send(dataPdu(CMD_DATA, channelId, idWidth, payload, 0, payload.size))
            return
        }
        // The first PDU announces the whole length; the rest are plain data PDUs. They have to
        // leave together: the server reassembles per channel, so another sender's fragments
        // interleaved with these would be appended to this message.
        sendLock.withLock {
            val first = RdpWriter(MAX_CHUNK + 16)
            first.u8((CMD_DATA_FIRST shl 4) or (LENGTH_WIDTH_32 shl 2) or idWidth)
            writeVariable(first, channelId, idWidth)
            first.u32le(payload.size)
            first.bytes(payload, 0, MAX_CHUNK)
            send(first.toByteArray())
            var offset = MAX_CHUNK
            while (offset < payload.size) {
                val chunk = minOf(MAX_CHUNK, payload.size - offset)
                send(dataPdu(CMD_DATA, channelId, idWidth, payload, offset, chunk))
                offset += chunk
            }
        }
    }

    private fun dataPdu(
        command: Int,
        channelId: Int,
        idWidth: Int,
        payload: ByteArray,
        offset: Int,
        length: Int,
    ): ByteArray =
        RdpWriter(length + 8).apply {
            u8((command shl 4) or idWidth)
            writeVariable(this, channelId, idWidth)
            bytes(payload, offset, length)
        }.toByteArray()

    /**
     * The server states the protocol version it speaks; the answer settles it. Echoing what it
     * offered is what mstsc does, and a server that hears a version it did not offer is entitled to
     * drop the channel.
     */
    private suspend fun capabilities(reader: RdpReader) {
        if (reader.remaining < 3) return
        reader.u8() // pad
        version = reader.u16le().coerceIn(1, MAX_VERSION)
        send(RdpWriter(4).u8(CMD_CAPABILITIES shl 4).u8(0).u16le(version).toByteArray())
    }

    private suspend fun create(reader: RdpReader, idWidth: Int) {
        val channelId = readVariable(reader, idWidth)
        val name = readChannelName(reader) ?: return
        val handler = handlers[name]
        // Each open channel carries a reassembly buffer whose size the server declares, so leaving
        // the number of them to the server as well is what turns a few bytes into a heap of them.
        val accepted = handler != null && lock.withLock {
            val room = open.containsKey(channelId) || open.size < MAX_OPEN_CHANNELS
            if (room) open[channelId] = OpenChannel(name, idWidth)
            room
        }
        trace("dvc create '$name' -> ${if (accepted) "accepted" else "refused"}")
        val response = RdpWriter(12)
        response.u8((CMD_CREATE shl 4) or idWidth)
        writeVariable(response, channelId, idWidth)
        response.u32le(if (accepted) STATUS_SUCCESS else STATUS_NOT_FOUND)
        send(response.toByteArray())
        // Only now: on some channels the client speaks first, and it may not speak before the
        // server has been told the channel was accepted. A channel that was refused has none.
        if (accepted) handler?.onOpen()
    }

    private suspend fun close(reader: RdpReader, idWidth: Int) {
        val channelId = readVariable(reader, idWidth)
        val closed = lock.withLock { open.remove(channelId) } ?: return
        val response = RdpWriter(8)
        response.u8((CMD_CLOSE shl 4) or closed.idWidth)
        writeVariable(response, channelId, closed.idWidth)
        send(response.toByteArray())
    }

    private suspend fun dataFirst(reader: RdpReader, idWidth: Int, lengthWidth: Int, compressed: Boolean) {
        val channelId = readVariable(reader, idWidth)
        val total = readVariable(reader, lengthWidth)
        val channel = lock.withLock { open[channelId] } ?: return
        if (total < 0 || total > MAX_MESSAGE_SIZE) {
            throw RdpProtocolException("a dynamic channel message of $total bytes")
        }
        val chunk = payload(channel, reader.rest(), compressed)
        if (chunk.size >= total) {
            channel.pending = null
            deliver(channel, chunk)
            return
        }
        // Grown as the chunks arrive rather than allocated at the size the server announced: the
        // announcement costs the server ten bytes and would cost this client sixteen megabytes.
        channel.pending = RdpWriter(minOf(total, INITIAL_REASSEMBLY_BUFFER)).apply { bytes(chunk) }
        channel.pendingLength = total
    }

    private suspend fun data(reader: RdpReader, idWidth: Int, compressed: Boolean) {
        val channelId = readVariable(reader, idWidth)
        val channel = lock.withLock { open[channelId] } ?: return
        val chunk = payload(channel, reader.rest(), compressed)
        val pending = channel.pending
        if (pending == null) {
            deliver(channel, chunk)
            return
        }
        pending.bytes(chunk)
        if (pending.size < channel.pendingLength) return
        channel.pending = null
        deliver(channel, pending.toByteArray())
    }

    private fun payload(channel: OpenChannel, data: ByteArray, compressed: Boolean): ByteArray =
        if (compressed) channel.decompressor.decompress(data) else data

    private suspend fun deliver(channel: OpenChannel, message: ByteArray) {
        handlers[channel.name]?.onMessage(message)
    }

    /** The channel name is ASCII and null-terminated; anything else is not a name we know. */
    private fun readChannelName(reader: RdpReader): String? {
        val builder = StringBuilder()
        while (reader.remaining > 0) {
            val byte = reader.u8()
            if (byte == 0) return builder.toString()
            builder.append(byte.toChar())
            if (builder.length > MAX_NAME_LENGTH) return null
        }
        return null
    }

    private fun readVariable(reader: RdpReader, width: Int): Int = when (width) {
        0 -> reader.u8()
        1 -> reader.u16le()
        2 -> reader.u32le()
        else -> throw RdpProtocolException("a dynamic channel field of reserved width $width")
    }

    private fun writeVariable(writer: RdpWriter, value: Int, width: Int) {
        when (width) {
            0 -> writer.u8(value)
            1 -> writer.u16le(value)
            else -> writer.u32le(value)
        }
    }

    private companion object {
        const val CMD_CREATE = 0x01
        const val CMD_DATA_FIRST = 0x02
        const val CMD_DATA = 0x03
        const val CMD_CLOSE = 0x04
        const val CMD_CAPABILITIES = 0x05
        const val CMD_DATA_FIRST_COMPRESSED = 0x06
        const val CMD_DATA_COMPRESSED = 0x07

        const val LENGTH_WIDTH_32 = 2
        const val MAX_VERSION = 3

        const val STATUS_SUCCESS = 0
        /** STATUS_NOT_FOUND: what mstsc answers for a channel it does not speak. */
        const val STATUS_NOT_FOUND = 0xC0000225.toInt()

        /** A dynamic channel PDU has to fit one static channel chunk, header included. */
        const val MAX_CHUNK = 1580

        const val MAX_MESSAGE_SIZE = 16 * 1024 * 1024
        const val MAX_NAME_LENGTH = 128

        /** What a message starts with while it is being reassembled, however much was announced. */
        const val INITIAL_REASSEMBLY_BUFFER = 64 * 1024

        /** More channels than any server opens, and few enough that their buffers stay affordable. */
        const val MAX_OPEN_CHANNELS = 64
    }
}
