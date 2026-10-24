package app.skerry.shared.securitykey

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbRequest
import android.os.Build
import com.yubico.yubikit.core.fido.FidoConnection
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.TimeoutException

/** Generic CTAPHID discovery: no vendor whitelist. HID report descriptor is checked after permission. */
internal class AndroidFidoUsbDiscovery(
    private val activity: Activity,
    private val attached: (UsbDevice) -> Unit,
    private val detached: (UsbDevice) -> Unit,
) {
    private val manager = activity.getSystemService(Context.USB_SERVICE) as UsbManager
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            @Suppress("DEPRECATION")
            val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) ?: return
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> if (candidate(device)) attached(device)
                UsbManager.ACTION_USB_DEVICE_DETACHED -> detached(device)
            }
        }
    }
    fun start() {
        val filter = IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED).apply { addAction(UsbManager.ACTION_USB_DEVICE_DETACHED) }
        if (Build.VERSION.SDK_INT >= 33) activity.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else activity.registerReceiver(receiver, filter)
        manager.deviceList.values.filter(::candidate).forEach(attached)
    }
    fun stop() = activity.unregisterReceiver(receiver)
}
private fun candidate(device: UsbDevice): Boolean = (0 until device.interfaceCount).any { index ->
    val iface = device.getInterface(index)
    iface.interfaceClass == UsbConstants.USB_CLASS_HID && iface.interfaceSubclass == 0 && iface.interfaceProtocol == 0 && endpoints(iface) != null
}
private fun endpoints(iface: UsbInterface): Pair<UsbEndpoint, UsbEndpoint>? {
    val endpoints = (0 until iface.endpointCount).map(iface::getEndpoint).filter {
        it.type == UsbConstants.USB_ENDPOINT_XFER_INT && it.maxPacketSize == 64
    }
    val input = endpoints.firstOrNull { it.direction == UsbConstants.USB_DIR_IN } ?: return null
    val output = endpoints.firstOrNull { it.direction == UsbConstants.USB_DIR_OUT } ?: return null
    return input to output
}
internal fun openGenericFidoUsb(activity: Activity, device: UsbDevice): FidoConnection {
    val manager = activity.getSystemService(Context.USB_SERVICE) as UsbManager
    if (!manager.hasPermission(device)) throw SecurityKeyException(SecurityKeyFailure.PermissionDenied)
    val connection = manager.openDevice(device) ?: throw SecurityKeyException(SecurityKeyFailure.Detached)
    try {
        for (index in 0 until device.interfaceCount) {
            val iface = device.getInterface(index)
            if (!isGenericHid(iface)) continue
            val (input, output) = endpoints(iface) ?: continue
            val descriptor = ByteArray(4096)
            val length = connection.controlTransfer(0x81, 6, 0x2200, iface.id, descriptor, descriptor.size, 1000)
            if (length <= 0 || !isFidoHidDescriptor(descriptor.copyOf(length))) continue
            if (!connection.claimInterface(iface, true)) throw SecurityKeyException(SecurityKeyFailure.PermissionDenied)
            return GenericFidoUsbConnection(connection, iface, input, output)
        }
        throw SecurityKeyException(SecurityKeyFailure.InvalidKey)
    } catch (e: Exception) { connection.close(); throw e }
}
private class GenericFidoUsbConnection(
    private val connection: UsbDeviceConnection,
    private val iface: UsbInterface,
    private val input: UsbEndpoint,
    private val output: UsbEndpoint,
) : FidoConnection {
    @Volatile private var closed = false
    override fun send(packet: ByteArray) = transfer(output, packet, receive = false)
    override fun receive(packet: ByteArray) = transfer(input, packet, receive = true)
    private fun transfer(endpoint: UsbEndpoint, packet: ByteArray, receive: Boolean) {
        if (closed || packet.size != 64) throw IOException("Authenticator detached")
        val buffer = ByteBuffer.allocateDirect(64)
        if (!receive) { buffer.put(packet); buffer.flip() }
        val request = UsbRequest()
        try {
            if (!request.initialize(connection, endpoint) || !request.queue(buffer)) throw IOException("Authenticator transfer failed")
            val completed = try { connection.requestWait(2000) } catch (_: TimeoutException) { request.cancel(); throw SecurityKeyException(SecurityKeyFailure.Timeout) }
            if (completed !== request || buffer.position() != 64 || closed) throw IOException("Authenticator detached")
            if (receive) { buffer.flip(); buffer.get(packet) }
        } finally { request.close() }
    }
    override fun close() {
        closed = true
        connection.releaseInterface(iface)
        connection.close()
    }
}

private fun isGenericHid(iface: UsbInterface): Boolean =
    iface.interfaceClass == UsbConstants.USB_CLASS_HID && iface.interfaceSubclass == 0 && iface.interfaceProtocol == 0
