package app.skerry.shared.serial

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android-реализация serial через USB-OTG (USB Host API + usb-serial-for-android): CDC-ACM/FTDI/
 * CP210x/CH34x переходники. Устройства перечисляются без разрешения ([listPorts] для пикера), а
 * открытие ([open]) запрашивает runtime-разрешение USB (системный диалог) и блокирует до ответа —
 * это допустимо, т.к. вызывается из [SerialTransport.connect] на `Dispatchers.IO`.
 *
 * Контекст берётся из [SerialUsbBridge] (ставится в `MainActivity`). Если он не установлен или на
 * устройстве нет USB Host — [listPorts] пуст, [open] бросает [SerialUnavailableException]. Идентификатор
 * порта ([SerialConfig.portName]) — `UsbDevice.getDeviceName()` (+`#index` при нескольких портах).
 */
actual object SerialSystem {

    actual fun listPorts(): List<SerialPortInfo> {
        val context = SerialUsbBridge.context() ?: return emptyList()
        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return emptyList()
        return runCatching {
            drivers(usbManager).flatMap { driver ->
                driver.ports.mapIndexed { index, _ ->
                    SerialPortInfo(
                        systemName = portName(driver.device, index),
                        description = describe(driver.device),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    actual fun open(config: SerialConfig): SerialPortHandle {
        val context = SerialUsbBridge.context()
            ?: throw SerialUnavailableException("USB-контекст недоступен (приложение не инициализировано)")
        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
            ?: throw SerialUnavailableException("USB Host API недоступен на этом устройстве")

        val (driver, portIndex) = locate(usbManager, config.portName)
            ?: throw SerialUnavailableException("USB-устройство ${config.portName} не найдено")

        if (!ensurePermission(context, usbManager, driver.device)) {
            throw SerialUnavailableException("Нет разрешения на доступ к USB-устройству ${config.portName}")
        }

        val connection: UsbDeviceConnection = usbManager.openDevice(driver.device)
            ?: throw SerialUnavailableException("Не удалось открыть USB-устройство ${config.portName}")

        val port = driver.ports.getOrNull(portIndex)
            ?: run {
                runCatching { connection.close() }
                throw SerialUnavailableException("Порт #$portIndex отсутствует на ${config.portName}")
            }
        return try {
            port.open(connection)
            port.setParameters(
                config.baudRate,
                config.dataBits,
                config.stopBits.toUsb(),
                config.parity.toUsb(),
            )
            UsbSerialPortHandle(port, connection)
        } catch (e: Exception) {
            runCatching { port.close() }
            runCatching { connection.close() }
            throw SerialUnavailableException("Не удалось настроить USB-порт ${config.portName}", e)
        }
    }

    private fun drivers(usbManager: UsbManager): List<UsbSerialDriver> =
        UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)

    /** Найти драйвер и индекс порта по идентификатору `deviceName[#index]`. */
    private fun locate(usbManager: UsbManager, portName: String): Pair<UsbSerialDriver, Int>? {
        val hashIndex = portName.lastIndexOf('#')
        val deviceName = if (hashIndex >= 0) portName.substring(0, hashIndex) else portName
        val portIndex = if (hashIndex >= 0) portName.substring(hashIndex + 1).toIntOrNull() ?: 0 else 0
        val driver = drivers(usbManager).firstOrNull { it.device.deviceName == deviceName } ?: return null
        return driver to portIndex
    }

    private fun portName(device: UsbDevice, index: Int): String =
        if (index == 0) device.deviceName else "${device.deviceName}#$index"

    private fun describe(device: UsbDevice): String {
        val product = runCatching { device.productName }.getOrNull()?.takeIf { it.isNotBlank() }
        val ids = "%04x:%04x".format(device.vendorId, device.productId)
        return product?.let { "$it ($ids)" } ?: "USB serial $ids"
    }

    /**
     * Гарантировать runtime-разрешение на [device]: если уже есть — сразу true; иначе запросить и
     * блокирующе дождаться ответа пользователя (системный диалог). Вызывается на IO-потоке.
     */
    private fun ensurePermission(context: Context, usbManager: UsbManager, device: UsbDevice): Boolean {
        if (usbManager.hasPermission(device)) return true
        val action = "${context.packageName}.USB_PERMISSION"
        val latch = CountDownLatch(1)
        val granted = AtomicBoolean(false)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.action == action) {
                    granted.set(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
                    latch.countDown()
                }
            }
        }
        val filter = IntentFilter(action)
        // Доставляем broadcast на выделенный фоновый looper, а не на главный поток: тогда ожидание
        // на latch (мы на IO) не зависит от свободы main-потока — нет риска взаимоблокировки, даже
        // если open() однажды позовут не с Dispatchers.IO.
        val handlerThread = HandlerThread("skerry-usb-permission").apply { start() }
        val handler = Handler(handlerThread.looper)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, null, handler, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter, null, handler)
        }
        return try {
            // FLAG_MUTABLE (API31+) — система дописывает в intent результат/устройство. Explicit-пакет,
            // чтобы broadcast не утёк наружу.
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val pending = PendingIntent.getBroadcast(
                context, 0, Intent(action).setPackage(context.packageName), flags,
            )
            usbManager.requestPermission(device, pending)
            latch.await(PERMISSION_TIMEOUT_SECONDS, TimeUnit.SECONDS) && granted.get()
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
            handlerThread.quitSafely()
        }
    }

    private fun SerialStopBits.toUsb(): Int = when (this) {
        SerialStopBits.ONE -> UsbSerialPort.STOPBITS_1
        SerialStopBits.ONE_POINT_FIVE -> UsbSerialPort.STOPBITS_1_5
        SerialStopBits.TWO -> UsbSerialPort.STOPBITS_2
    }

    private fun SerialParity.toUsb(): Int = when (this) {
        SerialParity.NONE -> UsbSerialPort.PARITY_NONE
        SerialParity.ODD -> UsbSerialPort.PARITY_ODD
        SerialParity.EVEN -> UsbSerialPort.PARITY_EVEN
        SerialParity.MARK -> UsbSerialPort.PARITY_MARK
        SerialParity.SPACE -> UsbSerialPort.PARITY_SPACE
    }

    private const val PERMISSION_TIMEOUT_SECONDS = 60L
}

/**
 * Обёртка над открытым [UsbSerialPort] под контракт [SerialPortHandle]. [read] с timeout 0 блокирует
 * до появления байтов; при закрытии порта/отключении устройства нижний слой бросает [IOException] —
 * отдаём `-1`, как ждёт [SerialShellChannel]. [close] закрывает и порт, и [UsbDeviceConnection].
 */
private class UsbSerialPortHandle(
    private val port: UsbSerialPort,
    private val connection: UsbDeviceConnection,
) : SerialPortHandle {

    private val open = AtomicBoolean(true)

    override val isOpen: Boolean get() = open.get()

    override fun read(buffer: ByteArray): Int {
        if (!open.get()) return -1
        return try {
            port.read(buffer, READ_TIMEOUT_MS) // 0 → блокирует до данных
        } catch (_: IOException) {
            -1 // порт закрыт/устройство отключено
        }
    }

    override fun write(data: ByteArray) {
        if (!open.get()) return // порт закрыт/устройство отключено — не пишем в мёртвый порт
        port.write(data, WRITE_TIMEOUT_MS)
    }

    override fun close() {
        if (!open.compareAndSet(true, false)) return
        runCatching { port.close() }
        runCatching { connection.close() }
    }

    private companion object {
        const val READ_TIMEOUT_MS = 0 // блокирующее чтение до первого байта
        const val WRITE_TIMEOUT_MS = 2000
    }
}
