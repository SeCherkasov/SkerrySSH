package app.skerry.ui.keepalive

/**
 * The phone's own power management, and what a person can be sent to change about it.
 *
 * Android only. The foreground service behind [SessionKeepAliveBridge] keeps the *process* alive,
 * which is not the same as keeping the *connection* alive: with the screen off the CPU still
 * suspends, so the keepalive timers slip past the server's idle limit, and an OEM ROM still kills
 * a whitelisted-nowhere app on its own schedule. Every remedy for that sits outside the app — a
 * partial wake lock, a battery-optimisation exemption, the ROM's autostart list — so this is the
 * one surface that owns them. Desktop supplies nothing and the More screen drops the row.
 *
 * Deliberately not folded into [SessionKeepAliveBridge]: that is the session hot path (must never
 * throw, called from coroutine workers, one instance per process), this is driven by taps and
 * opens other apps' activities.
 */
interface KeepAlivePower {

    /** The ROM family: it decides what the steps say and which system page a step opens. */
    val vendor: KeepAliveVendor

    /** Raw `Build.MANUFACTURER`, shown when [vendor] is [KeepAliveVendor.Other], which has no name of its own. */
    val manufacturer: String

    /**
     * Whether the CPU is held awake while at least one session is open. Off by default: it is a
     * battery trade-off only the user can make. Stored on the device, not in the account — it
     * describes this phone.
     */
    val wakeLockEnabled: Boolean

    /**
     * Stores [enabled] and pushes it to the sessions already running.
     *
     * Returns false when the value was stored but could not reach them — the system refuses a
     * background service start often enough that the caller has to be able to say so, rather than
     * leave a switch reading "on" over sessions still running without the lock. The next connect
     * re-reads the store either way, so the failure is bounded, not permanent.
     */
    fun setWakeLockEnabled(enabled: Boolean): Boolean

    /**
     * Whether the system exempts this app from Doze and App Standby. Read again whenever the screen
     * resumes rather than cached: the answer changes in a system page we navigated away to.
     */
    fun isExemptFromBatteryOptimization(): Boolean

    /** Opens the system's list of battery-optimised apps. */
    fun openBatteryOptimizationSettings()

    /** Opens the ROM's autostart page. Only offered when [KeepAliveVendor.hasAutostartPage]. */
    fun openAutostartSettings()

    /** Opens this app's system details page — the one page that exists on every device. */
    fun openAppDetailsSettings()
}

/**
 * ROM families that manage background apps their own way, grouped by the settings app they share:
 * Redmi and POCO are Xiaomi's MIUI/HyperOS, Honor still ships Huawei's manager under its own
 * package, OnePlus and realme are ColorOS, iQOO is vivo's OriginOS.
 *
 * [hasAutostartPage] is whether that family has an autostart whitelist to send someone to at all.
 * Samsung is named without one on purpose: its background killing is real ("Deep sleeping apps"),
 * but it lives inside Device care with no page we may open directly, so the step is words only.
 */
enum class KeepAliveVendor(val hasAutostartPage: Boolean) {
    Xiaomi(true),
    Huawei(true),
    Oppo(true),
    Vivo(true),
    Samsung(false),
    Other(false),
}

/**
 * [manufacturer] (`Build.MANUFACTURER`) to the ROM family it belongs to.
 *
 * Matched inside the string rather than against it: firmware writes the legal entity as often as
 * the brand ("Xiaomi Communications Co., Ltd."). The brand tokens are long and specific enough that
 * a substring match cannot pick the wrong family.
 */
fun keepAliveVendorOf(manufacturer: String): KeepAliveVendor {
    val name = manufacturer.lowercase()
    return KeepAliveVendor.entries.firstOrNull { vendor -> brandsOf(vendor).any { it in name } }
        ?: KeepAliveVendor.Other
}

private fun brandsOf(vendor: KeepAliveVendor): List<String> = when (vendor) {
    KeepAliveVendor.Xiaomi -> listOf("xiaomi", "redmi", "poco")
    KeepAliveVendor.Huawei -> listOf("huawei", "honor")
    KeepAliveVendor.Oppo -> listOf("oppo", "oplus", "oneplus", "realme")
    KeepAliveVendor.Vivo -> listOf("vivo", "iqoo")
    KeepAliveVendor.Samsung -> listOf("samsung")
    KeepAliveVendor.Other -> emptyList()
}
