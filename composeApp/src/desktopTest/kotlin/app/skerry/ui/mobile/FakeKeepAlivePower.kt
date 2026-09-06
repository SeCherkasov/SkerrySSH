package app.skerry.ui.mobile

import app.skerry.ui.keepalive.KeepAlivePower
import app.skerry.ui.keepalive.KeepAliveVendor

/** A device whose power settings the test owns. */
internal class FakeKeepAlivePower(
    override val vendor: KeepAliveVendor,
    override val manufacturer: String = "TestCo",
    /** false replays the system refusing the background service start that carries the change. */
    private val delivers: Boolean = true,
) : KeepAlivePower {

    var exempt = false
    var batteryPagesOpened = 0
    var autostartPagesOpened = 0
    var detailPagesOpened = 0

    override var wakeLockEnabled: Boolean = false

    override fun setWakeLockEnabled(enabled: Boolean): Boolean {
        wakeLockEnabled = enabled
        return delivers
    }

    override fun isExemptFromBatteryOptimization(): Boolean = exempt

    override fun openBatteryOptimizationSettings() {
        batteryPagesOpened++
    }

    override fun openAutostartSettings() {
        autostartPagesOpened++
    }

    override fun openAppDetailsSettings() {
        detailPagesOpened++
    }
}
