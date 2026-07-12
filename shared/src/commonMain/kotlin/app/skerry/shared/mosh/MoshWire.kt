package app.skerry.shared.mosh

/**
 * Hand-rolled protobuf wire codec for mosh's three tiny proto2 schemas
 * (transportinstruction / userinput / hostinput). A protobuf runtime would be a heavy
 * dependency for three messages with seven fields total; this covers exactly the wire
 * features mosh uses: varint and length-delimited fields, unknown fields skipped.
 */

/** mosh's `TRANSPORT_PROTOCOL_VERSION` — sent in every instruction, never negotiated. */
const val MOSH_PROTOCOL_VERSION: Int = 2

class MoshWireException(message: String) : Exception(message)

/** `TransportBuffers.Instruction`: the unit of state synchronization in both directions. */
data class MoshInstruction(
    val protocolVersion: Int = MOSH_PROTOCOL_VERSION,
    val oldNum: ULong,
    val newNum: ULong,
    val ackNum: ULong,
    val throwawayNum: ULong,
    val diff: ByteArray,
) {
    fun encode(): ByteArray {
        val out = WireWriter()
        out.varintField(1, protocolVersion.toULong())
        out.varintField(2, oldNum)
        out.varintField(3, newNum)
        out.varintField(4, ackNum)
        out.varintField(5, throwawayNum)
        out.bytesField(6, diff)
        return out.toByteArray()
    }

    // diff is a ByteArray: identity equality from data class would be wrong, but instances
    // are never compared in production code — equals/hashCode intentionally not overridden.
}

fun decodeMoshInstruction(payload: ByteArray): MoshInstruction {
    var version = MOSH_PROTOCOL_VERSION
    var oldNum = 0uL
    var newNum = 0uL
    var ackNum = 0uL
    var throwawayNum = 0uL
    var diff = ByteArray(0)
    val reader = WireReader(payload)
    while (reader.hasMore()) {
        when (val field = reader.readTag()) {
            1 -> version = reader.readVarint().toInt()
            2 -> oldNum = reader.readVarint()
            3 -> newNum = reader.readVarint()
            4 -> ackNum = reader.readVarint()
            5 -> throwawayNum = reader.readVarint()
            6 -> diff = reader.readBytes()
            else -> reader.skipField(field)
        }
    }
    return MoshInstruction(version, oldNum, newNum, ackNum, throwawayNum, diff)
}

/** One entry of the client→server `UserMessage` stream. */
sealed interface MoshUserEvent {
    data class Keystroke(val keys: ByteArray) : MoshUserEvent
    data class Resize(val cols: Int, val rows: Int) : MoshUserEvent
}

/** Encode `UserBuffers.UserMessage` — the diff payload of a client→server instruction. */
fun encodeUserMessage(events: List<MoshUserEvent>): ByteArray {
    val out = WireWriter()
    for (event in events) {
        val instruction = WireWriter()
        when (event) {
            is MoshUserEvent.Keystroke -> {
                val keystroke = WireWriter()
                keystroke.bytesField(1, event.keys)
                instruction.bytesField(2, keystroke.toByteArray())
            }
            is MoshUserEvent.Resize -> {
                val resize = WireWriter()
                resize.varintField(1, event.cols.toULong())
                resize.varintField(2, event.rows.toULong())
                instruction.bytesField(3, resize.toByteArray())
            }
        }
        out.bytesField(1, instruction.toByteArray())
    }
    return out.toByteArray()
}

/** One entry of the server→client `HostMessage` diff, in arrival order. */
sealed interface MoshHostUpdate {
    /** Raw terminal bytes (ANSI) to feed into the emulator. */
    data class Bytes(val data: ByteArray) : MoshHostUpdate

    /** Server-side screen size (mosh sends it in the first full-state diff). */
    data class Resize(val cols: Int, val rows: Int) : MoshHostUpdate

    /** Echo acknowledgement for prediction engines; Skerry ignores it for now. */
    data class EchoAck(val num: ULong) : MoshHostUpdate
}

/** Decode `HostBuffers.HostMessage` — the diff payload of a server→client instruction. */
fun decodeHostMessage(payload: ByteArray): List<MoshHostUpdate> {
    val updates = mutableListOf<MoshHostUpdate>()
    val reader = WireReader(payload)
    while (reader.hasMore()) {
        val field = reader.readTag()
        if (field != 1) {
            reader.skipField(field)
            continue
        }
        val instruction = WireReader(reader.readBytes())
        while (instruction.hasMore()) {
            when (val inner = instruction.readTag()) {
                2 -> {
                    val hostBytes = WireReader(instruction.readBytes())
                    while (hostBytes.hasMore()) {
                        when (val f = hostBytes.readTag()) {
                            1 -> updates += MoshHostUpdate.Bytes(hostBytes.readBytes())
                            else -> hostBytes.skipField(f)
                        }
                    }
                }
                3 -> {
                    val resize = WireReader(instruction.readBytes())
                    var cols = 0
                    var rows = 0
                    while (resize.hasMore()) {
                        when (val f = resize.readTag()) {
                            1 -> cols = resize.readVarint().toInt()
                            2 -> rows = resize.readVarint().toInt()
                            else -> resize.skipField(f)
                        }
                    }
                    updates += MoshHostUpdate.Resize(cols, rows)
                }
                4 -> {
                    val ack = WireReader(instruction.readBytes())
                    while (ack.hasMore()) {
                        when (val f = ack.readTag()) {
                            1 -> updates += MoshHostUpdate.EchoAck(ack.readVarint())
                            else -> ack.skipField(f)
                        }
                    }
                }
                else -> instruction.skipField(inner)
            }
        }
    }
    return updates
}

private const val WIRE_VARINT = 0
private const val WIRE_FIXED64 = 1
private const val WIRE_LEN = 2
private const val WIRE_FIXED32 = 5

private class WireWriter {
    private val out = ArrayList<Byte>(64)

    fun varintField(field: Int, value: ULong) {
        varint((field.toULong() shl 3) or WIRE_VARINT.toULong())
        varint(value)
    }

    fun bytesField(field: Int, value: ByteArray) {
        varint((field.toULong() shl 3) or WIRE_LEN.toULong())
        varint(value.size.toULong())
        for (b in value) out.add(b)
    }

    private fun varint(value: ULong) {
        var v = value
        while (v >= 0x80u) {
            out.add(((v and 0x7Fu) or 0x80u).toByte())
            v = v shr 7
        }
        out.add(v.toByte())
    }

    fun toByteArray(): ByteArray = out.toByteArray()
}

private class WireReader(private val data: ByteArray) {
    private var pos = 0
    private var lastWireType = WIRE_VARINT

    fun hasMore(): Boolean = pos < data.size

    /** Returns the field number; the wire type is remembered for [skipField]. */
    fun readTag(): Int {
        val tag = readVarint()
        lastWireType = (tag and 7u).toInt()
        return (tag shr 3).toInt()
    }

    fun readVarint(): ULong {
        var shift = 0
        var result = 0uL
        while (true) {
            if (pos >= data.size) throw MoshWireException("truncated varint")
            if (shift > 63) throw MoshWireException("varint too long")
            val b = data[pos++].toInt() and 0xFF
            result = result or ((b and 0x7F).toULong() shl shift)
            if (b < 0x80) return result
            shift += 7
        }
    }

    fun readBytes(): ByteArray {
        val len = readVarint().toInt()
        // Overflow-safe bound: a peer-controlled len near Int.MAX_VALUE must not wrap pos+len.
        if (len < 0 || len > data.size - pos) throw MoshWireException("truncated bytes field")
        val slice = data.copyOfRange(pos, pos + len)
        pos += len
        return slice
    }

    fun skipField(@Suppress("UNUSED_PARAMETER") field: Int) {
        when (lastWireType) {
            WIRE_VARINT -> readVarint()
            WIRE_FIXED64 -> advance(8)
            WIRE_LEN -> readBytes()
            WIRE_FIXED32 -> advance(4)
            else -> throw MoshWireException("unsupported wire type $lastWireType")
        }
    }

    private fun advance(n: Int) {
        if (pos + n > data.size) throw MoshWireException("truncated fixed field")
        pos += n
    }
}
