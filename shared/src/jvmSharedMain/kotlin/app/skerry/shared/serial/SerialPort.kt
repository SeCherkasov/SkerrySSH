package app.skerry.shared.serial

/** Serial port parity. */
enum class SerialParity { NONE, ODD, EVEN, MARK, SPACE }

/** Number of stop bits. */
enum class SerialStopBits { ONE, ONE_POINT_FIVE, TWO }

/**
 * Serial port open parameters. [portName] is the system device name (`/dev/ttyUSB0`, `COM3`);
 * [baudRate] is the speed; [dataBits]/[stopBits]/[parity] form the frame format (default `8N1`).
 */
data class SerialConfig(
    val portName: String,
    val baudRate: Int = 9600,
    val dataBits: Int = 8,
    val stopBits: SerialStopBits = SerialStopBits.ONE,
    val parity: SerialParity = SerialParity.NONE,
)

/** A serial port discovered on the system (for selection lists). */
data class SerialPortInfo(
    val systemName: String,
    val description: String,
)

/**
 * An open serial port — minimal blocking byte-IO contract. Implemented per platform
 * ([SerialSystem]): desktop via native port (jSerialComm), Android via USB-OTG (later).
 * [read] blocks until data arrives, returning the byte count read or `-1` if the port is
 * closed or gone (device unplugged).
 */
interface SerialPortHandle {
    val isOpen: Boolean
    fun read(buffer: ByteArray): Int
    fun write(data: ByteArray)
    fun close()
}

/**
 * Platform access to serial ports. Lives in the JVM node (desktop + Android); the desktop actual
 * uses jSerialComm, Android currently reports no support (USB-OTG needs device selection and a
 * runtime permission — separate work). [SerialUnavailableException] signals platform
 * unavailability (no native library / not implemented).
 */
expect object SerialSystem {
    /** Available ports (empty if the platform has no serial support). */
    fun listPorts(): List<SerialPortInfo>

    /**
     * Open a port with [config].
     * @throws SerialUnavailableException port unavailable / platform has no serial support
     */
    fun open(config: SerialConfig): SerialPortHandle
}

/** Port unavailable: platform has no serial support, or the device could not be opened. */
class SerialUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)
