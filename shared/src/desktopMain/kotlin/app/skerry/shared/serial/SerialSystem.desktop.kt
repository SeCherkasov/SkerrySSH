package app.skerry.shared.serial

import com.fazecast.jSerialComm.SerialPort as NativePort

/**
 * Desktop-реализация доступа к последовательным портам через jSerialComm (Linux/Windows/macOS).
 * Чтение — SEMI_BLOCKING (блокирует до появления хотя бы одного байта, `-1` при закрытии/ошибке),
 * что ровно ложится на контракт [SerialPortHandle.read] и цикл [output] канала.
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
            throw SerialUnavailableException("Порт ${config.portName} не найден", e)
        }
        // Всё от настройки параметров до захвата потоков — под единым try: любая ошибка (неверная
        // скорость/формат, сбой драйвера, бросок в getInputStream) закрывает уже открытый порт (не
        // течёт) и превращается в SerialUnavailableException, как обещает контракт [SerialSystem.open].
        return try {
            port.setBaudRate(config.baudRate)
            port.setNumDataBits(config.dataBits)
            port.setNumStopBits(config.stopBits.toNative())
            port.setParity(config.parity.toNative())
            // SEMI_BLOCKING + readTimeout 0: read блокирует до первого байта; при закрытии порта → -1.
            port.setComPortTimeouts(NativePort.TIMEOUT_READ_SEMI_BLOCKING, 0, 0)
            if (!port.openPort()) {
                throw SerialUnavailableException("Не удалось открыть порт ${config.portName}")
            }
            NativeSerialPortHandle(port)
        } catch (e: SerialUnavailableException) {
            runCatching { port.closePort() }
            throw e
        } catch (e: Exception) {
            runCatching { port.closePort() }
            throw SerialUnavailableException("Не удалось настроить порт ${config.portName}", e)
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
 * Обёртка над нативным портом через его [java.io.InputStream]/[java.io.OutputStream] — стабильный API
 * jSerialComm между версиями (в отличие от `readBytes`, чья сигнатура Int/Long менялась). В режиме
 * SEMI_BLOCKING `read` блокирует до первого байта и возвращает `-1` при закрытии порта.
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
        port.closePort() // закрывает и потоки → read вернёт -1
    }
}
