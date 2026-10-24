package app.skerry.shared.securitykey

import android.app.Activity
import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import com.yubico.yubikit.android.YubiKitManager
import com.yubico.yubikit.android.transport.nfc.NfcConfiguration
import com.yubico.yubikit.android.transport.nfc.NfcNotAvailable
import com.yubico.yubikit.core.YubiKeyConnection
import com.yubico.yubikit.core.smartcard.SmartCardConnection
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext

/** One operation's permission queue, NFC reader and detach ownership. Probes never retain a raw handle. */
internal class AndroidSecurityKeyDiscovery(private val activity: Activity, private val owner: Job) {
    private val manager = YubiKitManager(activity)
    private val usbManager = activity.getSystemService(Context.USB_SERVICE) as UsbManager
    private val rawConnection = AtomicReference<YubiKeyConnection?>()
    private val selectedUsb = AtomicReference<Int?>()
    private val pending = AtomicReference<Pair<UsbDevice, CompletableDeferred<Boolean>>?>()
    private val permissionAction = activity.packageName + ".FIDO_PERMISSION." + java.util.UUID.randomUUID()
    private val discovery = SecurityKeyDiscovery<AuthenticatorCandidate>(::permission, ::validate, capacity = 128)
    private val seen = mutableSetOf<Int>()
    private val usb = AndroidFidoUsbDiscovery(activity, ::attached, ::detached)
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != permissionAction) return
            val request = pending.get() ?: return
            request.second.complete(usbManager.hasPermission(request.first))
        }
    }
    private val lifecycle = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityPaused(a: Activity) { if (a === activity) manager.stopNfcDiscovery(activity) }
        override fun onActivityDestroyed(a: Activity) { if (a === activity) owner.cancel() }
        override fun onActivityCreated(a: Activity, b: Bundle?) = Unit
        override fun onActivityStarted(a: Activity) = Unit
        override fun onActivityResumed(a: Activity) { if (a === activity && owner.isActive) startNfc() }
        override fun onActivityStopped(a: Activity) { if (a === activity) owner.cancel() }
        override fun onActivitySaveInstanceState(a: Activity, b: Bundle) = Unit
    }
    val lastFailure: SecurityKeyFailure? get() = discovery.lastFailure

    suspend fun start() = withContext(Dispatchers.Main.immediate) {
        if (Build.VERSION.SDK_INT >= 33) activity.registerReceiver(receiver, IntentFilter(permissionAction), Context.RECEIVER_NOT_EXPORTED)
        else activity.registerReceiver(receiver, IntentFilter(permissionAction))
        activity.application.registerActivityLifecycleCallbacks(lifecycle)
        usb.start()
        startNfc()
    }
    suspend fun close() = withContext(NonCancellable + Dispatchers.Main.immediate) {
        usb.stop()
        manager.stopNfcDiscovery(activity)
        activity.application.unregisterActivityLifecycleCallbacks(lifecycle)
        activity.unregisterReceiver(receiver)
    }
    suspend fun await(): () -> YubiKeyConnection {
        val candidate = discovery.await()
        selectedUsb.set(candidate.usb?.deviceId)
        return candidate.openConnection
    }
    fun publish(raw: YubiKeyConnection) { rawConnection.set(raw) }
    fun clear(raw: YubiKeyConnection) { rawConnection.compareAndSet(raw, null) }

    private suspend fun permission(candidate: AuthenticatorCandidate): Boolean {
        val device = candidate.usb ?: return true
        if (usbManager.deviceList.values.none { it.deviceId == device.deviceId }) throw SecurityKeyException(SecurityKeyFailure.Detached)
        if (usbManager.hasPermission(device)) return true
        val answer = CompletableDeferred<Boolean>()
        val request = device to answer
        pending.set(request)
        return try {
            withContext(Dispatchers.Main.immediate) {
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                val intent = PendingIntent.getBroadcast(activity, 0, Intent(permissionAction).setPackage(activity.packageName), flags)
                usbManager.requestPermission(device, intent)
            }
            answer.await()
        } finally { pending.compareAndSet(request, null) }
    }
    private suspend fun validate(candidate: AuthenticatorCandidate): Boolean {
        val device = candidate.usb ?: return true
        return withContext(Dispatchers.IO) { openGenericFidoUsb(activity, device).use { true } }
    }
    private fun attached(device: UsbDevice) {
        val candidate = AuthenticatorCandidate(device) { BoundedFidoConnection(openGenericFidoUsb(activity, device)) }
        if (seen.add(device.deviceId) && !discovery.offer(candidate)) seen.remove(device.deviceId)
    }
    private fun detached(device: UsbDevice) {
        seen.remove(device.deviceId)
        pending.get()?.takeIf { it.first.deviceId == device.deviceId }?.second
            ?.completeExceptionally(SecurityKeyException(SecurityKeyFailure.Detached))
        if (selectedUsb.get() == device.deviceId) rawConnection.get()?.close()
    }
    private fun startNfc() {
        try {
            manager.startNfcDiscovery(NfcConfiguration(), activity) { device ->
                discovery.offerAlternative(AuthenticatorCandidate { BoundedSmartCardConnection(device.openConnection(SmartCardConnection::class.java)) })
            }
        } catch (_: NfcNotAvailable) { /* USB remains available. */ }
    }
}
private class AuthenticatorCandidate(val usb: UsbDevice? = null, val openConnection: () -> YubiKeyConnection)
