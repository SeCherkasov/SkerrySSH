package app.skerry.android

import app.skerry.ui.keepalive.KeepAliveVendor

/**
 * The ROM autostart pages this app knows how to open, as plain package/activity names.
 *
 * A table rather than a method of [AndroidKeepAlivePower] because it has to agree with the
 * `<queries>` block of the manifest and nothing at runtime says when it stops: an undeclared package
 * reads as "not installed" on Android 11+, [AndroidKeepAlivePower] then falls back to the app's own
 * details page, and the only trace is a debug log. A pure table is checkable by a test; a `when`
 * inside a `Context`-constructed class is not.
 *
 * Explicit components rather than the vendors' documented-nowhere implicit actions: an implicit
 * action is answerable by any installed app, and this one opens a page about our own background
 * permissions.
 */
internal object KeepAliveAutostartPages {

    /**
     * Known autostart activities for [vendor], most current release first — each family has moved
     * the activity between releases, and the caller tries them in order.
     */
    fun forVendor(vendor: KeepAliveVendor): List<Pair<String, String>> = when (vendor) {
        KeepAliveVendor.Xiaomi -> listOf(
            "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
        )
        KeepAliveVendor.Huawei -> listOf(
            "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity",
            "com.hihonor.systemmanager" to "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        )
        KeepAliveVendor.Oppo -> listOf(
            "com.oplus.safecenter" to "com.oplus.safecenter.permission.startup.StartupAppListActivity",
            "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
        )
        KeepAliveVendor.Vivo -> listOf(
            "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
            "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
        )
        KeepAliveVendor.Samsung, KeepAliveVendor.Other -> emptyList()
    }

    /** Every package the table names — what the manifest's `<queries>` block has to declare. */
    val packages: Set<String>
        get() = KeepAliveVendor.entries.flatMap { forVendor(it) }.map { it.first }.toSet()
}
