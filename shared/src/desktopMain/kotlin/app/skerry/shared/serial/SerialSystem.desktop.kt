package app.skerry.shared.serial

import com.fazecast.jSerialComm.SerialPort as NativePort

/**
 * Desktop serial port access via jSerialComm (Linux/Windows/macOS). Reads are SEMI_BLOCKING
 * (blocks until at least one byte arrives, returns `-1` on close/error), matching the
 * [SerialPortHandle.read] contract and the channel's [output] loop.
 */
actual object SerialSystem {

    actual fun listPorts(): List<SerialPortInfo> =
        runCatching {
            NativePort.getCommPorts().map {
                SerialPortInfo(systemName = it.systemPortName, description = it.descriptivePortName)
            }
        }.getOrDefault(emptyList())

    actual fun open(config: SerialConfig): SerialPortHandle {
        val port = try {
            NativePort.getCommPort(config.portName)
        } catch (e: Exception) {
            throw SerialUnavailableException("Port ${config.portName} not found", e)
        }
        // Everything from parameter setup to stream acquisition is under one try: any failure
        // (invalid baud/format, driver failure, throw in getInputStream) closes the already-open
        // port (no leak) and becomes a SerialUnavailableException, per the [SerialSystem.open] contract.
        return try {
            port.setBaudRate(config.baudRate)
            port.setNumDataBits(config.dataBits)
            port.setNumStopBits(config.stopBits.toNative())
            port.setParity(config.parity.toNative())
            // SEMI_BLOCKING + readTimeout 0: read blocks until the first byte; -1 when the port closes.
            port.setComPortTimeouts(NativePort.TIMEOUT_READ_SEMI_BLOCKING, 0, 0)
            if (!port.openPort()) {
                throw SerialUnavailableException("Failed to open port ${config.portName}")
            }
            NativeSerialPortHandle(port)
        } catch (e: SerialUnavailableException) {
            runCatching { port.closePort() }
            throw e
        } catch (e: Exception) {
            runCatching { port.closePort() }
            throw SerialUnavailableException("Failed to configure port ${config.portName}", e)
        }
    }

    private fun SerialStopBits.toNative(): Int = when (this) {
        SerialStopBits.ONE -> NativePort.ONE_STOP_BIT
        SerialStopBits.ONE_POINT_FIVE -> NativePort.ONE_POINT_FIVE_STOP_BITS
        SerialStopBits.TWO -> NativePort.TWO_STOP_BITS
    }

    private fun SerialParity.toNative(): Int = when (this) {
        SerialParity.NONE -> NativePort.NO_PARITY
        SerialParity.ODD -> NativePort.ODD_PARITY
        SerialParity.EVEN -> NativePort.EVEN_PARITY
        SerialParity.MARK -> NativePort.MARK_PARITY
        SerialParity.SPACE -> NativePort.SPACE_PARITY
    }
}

/**
 * Wraps the native port via its [java.io.InputStream]/[java.io.OutputStream] — a stable jSerialComm
 * API across versions (unlike `readBytes`, whose Int/Long signature has changed). In SEMI_BLOCKING
 * mode, `read` blocks until the first byte and returns `-1` when the port closes.
 */
private class NativeSerialPortHandle(private val port: NativePort) : SerialPortHandle {
    private val input = port.inputStream
    private val output = port.outputStream
    override val isOpen: Boolean get() = port.isOpen
    override fun read(buffer: ByteArray): Int = input.read(buffer)
    override fun write(data: ByteArray) {
        output.write(data)
        output.flush()
    }
    override fun close() {
        port.closePort() // closes the streams too => read returns -1
    }
}
