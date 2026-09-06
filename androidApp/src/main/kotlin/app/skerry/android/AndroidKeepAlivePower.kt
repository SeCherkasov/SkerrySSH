package app.skerry.android

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import app.skerry.ui.keepalive.KeepAlivePower
import app.skerry.ui.keepalive.KeepAliveVendor
import app.skerry.ui.keepalive.keepAliveVendorOf

/**
 * Android side of [KeepAlivePower]: the stored wake-lock preference plus the system pages a person
 * has to visit for a session to survive the screen going off.
 *
 * The preference is device-local ([KeepAliveWakeLockSetting]) rather than a vault record, because it
 * describes this phone's power behaviour — carrying it to a desktop or another phone would mean
 * nothing. [SessionKeepAliveService] reads the same store when a session opens; a switch flipped
 * while sessions are already running is pushed to the service so it takes effect now.
 *
 * Nothing here throws: an intent that no activity answers, or that the system refuses, falls back to
 * this app's own details page, which exists on every device.
 */
internal class AndroidKeepAlivePower(
    private val context: Context,
    /** Whether a session is open right now; a switch flipped in between must reach the live service. */
    private val hasLiveSessions: () -> Boolean,
) : KeepAlivePower {

    override val manufacturer: String = Build.MANUFACTURER.orEmpty()

    override val vendor: KeepAliveVendor = keepAliveVendorOf(manufacturer)

    override val wakeLockEnabled: Boolean
        get() = KeepAliveWakeLockSetting.isEnabled(context)

    override fun setWakeLockEnabled(enabled: Boolean): Boolean {
        KeepAliveWakeLockSetting.set(context, enabled)
        if (!hasLiveSessions()) return true
        // The service owns the lock. Without this the switch would only be honoured by the next
        // connect, while the screen already showed it as on.
        val intent = Intent(context, SessionKeepAliveService::class.java)
            .setAction(SessionKeepAliveService.ACTION_SYNC_WAKE_LOCK)
        return try {
            context.startService(intent)
            true
        } catch (e: IllegalStateException) { // background start restrictions
            Log.w(TAG, "wake-lock change not delivered to the service", e)
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "wake-lock change not delivered to the service", e)
            false
        }
    }

    override fun isExemptFromBatteryOptimization(): Boolean {
        val power = context.getSystemService(PowerManager::class.java) ?: return false
        return power.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * The system list of battery-optimised apps, not the one-tap
     * `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` dialog: that intent needs the restricted
     * `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` permission, which Google Play gates behind a
     * declaration form, and without the permission the dialog closes without asking anything.
     * The row's text walks through the extra taps the list costs.
     */
    override fun openBatteryOptimizationSettings() {
        if (!launch(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) openAppDetailsSettings()
    }

    /**
     * The ROM's autostart list. Each family has moved the activity between releases, so the known
     * ones are tried in order; when none of them opens — an unknown release, a ROM without the app —
     * the app's own details page opens instead, which is the closest thing every device has.
     */
    override fun openAutostartSettings() {
        if (autostartPages().none { launchSystemComponent(it) }) openAppDetailsSettings()
    }

    override fun openAppDetailsSettings() {
        launch(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null),
            ),
        )
    }

    private fun autostartPages(): List<ComponentName> =
        KeepAliveAutostartPages.forVendor(vendor).map { (pkg, activity) -> ComponentName(pkg, activity) }

    /**
     * Opens a component of another app only where the system itself installed that app.
     *
     * A package name is unique on a device, but only among the packages actually installed: on a
     * phone whose ROM app is missing, any sideloaded app is free to call itself
     * `com.miui.securitycenter` and receive this intent. Nothing secret travels in it, but a page
     * that claims to be the system's background settings is worth not handing to an unknown app.
     * The packages are listed in the manifest's `<queries>`, without which Android 11+ answers
     * "not installed" for every one of them.
     */
    private fun launchSystemComponent(component: ComponentName): Boolean {
        if (!isSystemPackage(component.packageName)) return false
        return launch(Intent().setComponent(component))
    }

    private fun isSystemPackage(packageName: String): Boolean = try {
        val info = context.packageManager.getApplicationInfo(packageName, 0)
        info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
    } catch (e: PackageManager.NameNotFoundException) {
        Log.d(TAG, "no system package $packageName on this device", e)
        false
    }

    private fun launch(intent: Intent): Boolean = try {
        // Started from a settings screen, but through the application context — a task of its own.
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "no activity for $intent", e)
        false
    } catch (e: SecurityException) { // an exported-false activity of another app
        Log.w(TAG, "refused to open $intent", e)
        false
    }

    private companion object {
        const val TAG = "SkerryKeepAlive"
    }
}

/**
 * The "keep the CPU awake" switch, stored on the device.
 *
 * Its own object because two sides read it and neither may depend on the other: the settings screen
 * through [AndroidKeepAlivePower], and [SessionKeepAliveService] when a session opens — the service
 * can be re-created by the system with no Activity alive to have built anything.
 */
internal object KeepAliveWakeLockSetting {

    private const val PREFS = "skerry_keepalive"
    private const val KEY_WAKE_LOCK = "wake_lock_enabled"

    /** Off by default: keeping the CPU awake is a battery cost only the user can agree to. */
    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_WAKE_LOCK, false)

    fun set(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_WAKE_LOCK, enabled).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
