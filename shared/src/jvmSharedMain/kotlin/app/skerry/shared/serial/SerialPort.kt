package app.skerry.shared.serial

/** Чётность последовательного порта. */
enum class SerialParity { NONE, ODD, EVEN, MARK, SPACE }

/** Число стоп-битов. */
enum class SerialStopBits { ONE, ONE_POINT_FIVE, TWO }

/**
 * Параметры открытия последовательного порта. [portName] — системное имя устройства
 * (`/dev/ttyUSB0`, `COM3`); [baudRate] — скорость; [dataBits]/[stopBits]/[parity] — формат кадра
 * (по умолчанию классический `8N1`).
 */
data class SerialConfig(
    val portName: String,
    val baudRate: Int = 9600,
    val dataBits: Int = 8,
    val stopBits: SerialStopBits = SerialStopBits.ONE,
    val parity: SerialParity = SerialParity.NONE,
)

/** Обнаруженный на системе последовательный порт (для списка выбора). */
data class SerialPortInfo(
    val systemName: String,
    val description: String,
)

/**
 * Открытый последовательный порт — минимальный блокирующий контракт байтового IO. Реализуется
 * платформенно ([SerialSystem]): desktop — нативный порт (jSerialComm), Android — USB-OTG (позже).
 * [read] блокирует до появления данных и возвращает число прочитанных байтов либо `-1`, если порт
 * закрыт/пропал (устройство отключили).
 */
interface SerialPortHandle {
    val isOpen: Boolean
    fun read(buffer: ByteArray): Int
    fun write(data: ByteArray)
    fun close()
}

/**
 * Платформенный доступ к последовательным портам. Живёт в JVM-узле (desktop + Android); actual на
 * desktop использует jSerialComm, на Android пока сообщает об отсутствии поддержки (USB-OTG требует
 * выбора устройства и runtime-разрешения — отдельный шаг). [SerialUnavailableException] отражает
 * платформенную недоступность (нет нативной библиотеки / не реализовано).
 */
expect object SerialSystem {
    /** Список доступных портов (пустой, если платформа не поддерживает serial). */
    fun listPorts(): List<SerialPortInfo>

    /**
     * Открыть порт по [config].
     * @throws SerialUnavailableException порт недоступен/платформа не поддерживает serial
     */
    fun open(config: SerialConfig): SerialPortHandle
}

/** Порт недоступен: платформа не поддерживает serial или устройство нельзя открыть. */
class SerialUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)
