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
 * Android serial implementation over USB-OTG (USB Host API + usb-serial-for-android): CDC-ACM/FTDI/
 * CP210x/CH34x adapters. Devices are listed without permission ([listPorts] for the picker); opening
 * ([open]) requests the runtime USB permission (system dialog) and blocks until answered — safe
 * since it's called from [SerialTransport.connect] on `Dispatchers.IO`.
 *
 * Context comes from [SerialUsbBridge] (installed in `MainActivity`). If unset or the device has no
 * USB Host, [listPorts] is empty and [open] throws [SerialUnavailableException]. Port identifier
 * ([SerialConfig.portName]) is `UsbDevice.getDeviceName()` (+`#index` for multiple ports).
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
            ?: throw SerialUnavailableException(SerialProblem.UNSUPPORTED)
        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
            ?: throw SerialUnavailableException(SerialProblem.UNSUPPORTED)

        val (driver, portIndex) = locate(usbManager, config.portName)
            ?: throw SerialUnavailableException(SerialProblem.PORT_NOT_FOUND, config.portName)

        if (!ensurePermission(context, usbManager, driver.device)) {
            throw SerialUnavailableException(SerialProblem.PERMISSION_DENIED, config.portName)
        }

        val connection: UsbDeviceConnection = usbManager.openDevice(driver.device)
            ?: throw SerialUnavailableException(SerialProblem.OPEN_FAILED, config.portName)

        val port = driver.ports.getOrNull(portIndex)
            ?: run {
                runCatching { connection.close() }
                throw SerialUnavailableException(SerialProblem.PORT_NOT_FOUND, config.portName)
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
            throw SerialUnavailableException(SerialProblem.CONFIGURE_FAILED, config.portName, e)
        }
    }

    private fun drivers(usbManager: UsbManager): List<UsbSerialDriver> =
        UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)

    /** Find the driver and port index for a `deviceName[#index]` identifier. */
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
     * Ensure a runtime permission on [device]: true immediately if already granted; otherwise
     * request it and block for the user's answer (system dialog). Called on the IO thread.
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
        // Deliver the broadcast on a dedicated background looper rather than the main thread, so
        // awaiting the latch (we're on IO) doesn't depend on the main thread being free — avoids
        // deadlock even if open() is ever called off Dispatchers.IO.
        val handlerThread = HandlerThread("skerry-usb-permission").apply { start() }
        val handler = Handler(handlerThread.looper)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, null, handler, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter, null, handler)
        }
        return try {
            // FLAG_MUTABLE (API31+): the system appends the result/device to the intent. Explicit
            // package so the broadcast doesn't leak outside the app.
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
 * Wraps an open [UsbSerialPort] under the [SerialPortHandle] contract. [read] with timeout 0 blocks
 * until bytes arrive; on port close/device disconnect the lower layer throws [IOException], which
 * we turn into `-1` as [SerialShellChannel] expects. [close] closes both the port and [UsbDeviceConnection].
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
            port.read(buffer, READ_TIMEOUT_MS) // 0 -> blocks until data arrives
        } catch (_: IOException) {
            -1 // port closed / device disconnected
        }
    }

    override fun write(data: ByteArray) {
        if (!open.get()) return // port closed / device disconnected — don't write to a dead port
        port.write(data, WRITE_TIMEOUT_MS)
    }

    override fun close() {
        if (!open.compareAndSet(true, false)) return
        runCatching { port.close() }
        runCatching { connection.close() }
    }

    private companion object {
        const val READ_TIMEOUT_MS = 0 // blocking read until the first byte
        const val WRITE_TIMEOUT_MS = 2000
    }
}
