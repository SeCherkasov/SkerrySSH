package app.skerry.android

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import app.skerry.ui.keepalive.SessionKeepAliveBridge

/**
 * Android implementation of [SessionKeepAliveBridge]: runs the foreground service while at least
 * one session is open, one notification per live session (Termius-style), and stops the service
 * when the last one closes.
 *
 * ONE instance per process (held by [KeepAliveRuntime.bridge]): the session map here is the
 * authoritative process-side record the service replays after a system restart, and sessions
 * started before an Activity recreation must still find their entry when they end — a per-Activity
 * instance would forget them and never stop the service.
 *
 * The contract is "never throws": a platform refusal (background service-start restrictions) is
 * logged and swallowed — it may cost the notification, never the SSH session that triggered it.
 * The map tolerates unbalanced calls ([onSessionEnded] for an unknown id is a no-op), so stray
 * events can never wedge the service.
 */
class AndroidSessionKeepAlive(
    private val context: Context,
    /**
     * Called once, when the FIRST session of the process opens (transition empty -> non-empty).
     * The platform uses it for the lazy notification-permission request on Android 13+ — tied to
     * the user's first explicit connect instead of app startup. Must itself be safe to call from
     * any thread (see [KeepAliveRuntime.onFirstSession]).
     */
    private val onFirstSession: (() -> Unit)? = null,
) : SessionKeepAliveBridge {

    private companion object {
        const val TAG = "SkerryKeepAlive"
    }

    override val isKeepAliveConfigSupported: Boolean = true

    override fun isOptimizedForKeepAlive(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = context.getSystemService(PowerManager::class.java)
            pm?.isIgnoringBatteryOptimizations(context.packageName) == true
        } else {
            true
        }
    }

    override fun getManufacturer(): String {
        return Build.MANUFACTURER.orEmpty()
    }

    override fun requestKeepAliveOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (!launchIntent(intent)) {
                val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (!launchIntent(fallback)) {
                    openAppDetailsSettings()
                }
            }
        }
    }

    override fun openAutostartSettings() {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val intents = getManufacturerAutostartIntents(manufacturer)

        val launched = intents.any { launchIntent(it) }
        if (!launched) {
            openAppDetailsSettings()
        }
    }

    private fun getManufacturerAutostartIntents(manufacturer: String): List<Intent> {
        return when {
            manufacturer.contains("xiaomi") || manufacturer.contains("redmi") -> getXiaomiAutostartIntents()
            manufacturer.contains("huawei") || manufacturer.contains("honor") -> getHuaweiAutostartIntents()
            manufacturer.contains("oppo") || manufacturer.contains("realme") || manufacturer.contains("oneplus") -> getOppoAutostartIntents()
            manufacturer.contains("vivo") || manufacturer.contains("iqoo") -> getVivoAutostartIntents()
            else -> emptyList()
        }
    }

    private fun getXiaomiAutostartIntents(): List<Intent> = listOf(
        Intent().apply {
            component = ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
        },
        Intent("miui.intent.action.OP_AUTO_START"),
        Intent().apply {
            component = ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity")
            putExtra("package_name", context.packageName)
            putExtra("package_label", context.applicationInfo.loadLabel(context.packageManager))
        }
    )

    private fun getHuaweiAutostartIntents(): List<Intent> = listOf(
        Intent().apply {
            component = ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
        },
        Intent().apply {
            component = ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.bootstart.BootStartActivity")
        },
        Intent().apply {
            component = ComponentName("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
        }
    )

    private fun getOppoAutostartIntents(): List<Intent> = listOf(
        Intent().apply {
            component = ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity")
        },
        Intent().apply {
            component = ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")
        },
        Intent().apply {
            component = ComponentName("com.oplus.battery", "com.oplus.powermanager.fuelgaue.PowerUsageModelActivity")
        }
    )

    private fun getVivoAutostartIntents(): List<Intent> = listOf(
        Intent().apply {
            component = ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity")
        },
        Intent().apply {
            component = ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")
        },
        Intent().apply {
            component = ComponentName("com.iqoo.secure", "com.iqoo.secure.safeguard.PurviewTabActivity")
        }
    )

    override fun openAppDetailsSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        launchIntent(intent)
    }

    private fun launchIntent(intent: Intent): Boolean {
        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.w(TAG, "failed to launch intent: $intent", e)
            false
        }
    }

    // sessionId -> host label. The source of truth for "is the service needed at all"; the service
    // keeps its own copy for notification ids. Guarded by [lock]: the empty -> non-empty transition
    // must be computed atomically with the insert (bridge calls arrive from coroutine workers).
    private val sessions = LinkedHashMap<String, String>()
    private val lock = Any()

    init {
        // Let the service rebuild its notification map after a system restart (see
        // SessionKeepAliveService.replaySessionsFromBridge).
        SessionKeepAliveService.bridgeInstance = this
    }

    /** Point-in-time copy of the live sessions, for the service to replay after a restart. */
    fun snapshotSessions(): Map<String, String> = synchronized(lock) { LinkedHashMap(sessions) }

    override fun onSessionStarted(sessionId: String, hostLabel: String) {
        val wasEmpty = synchronized(lock) {
            val empty = sessions.isEmpty()
            sessions[sessionId] = hostLabel
            empty
        }
        if (wasEmpty) runCatching { onFirstSession?.invoke() }
            .onFailure { Log.w(TAG, "first-session hook failed", it) }
        val intent = Intent(context, SessionKeepAliveService::class.java)
            .setAction(SessionKeepAliveService.ACTION_ADD)
            .putExtra(SessionKeepAliveService.EXTRA_SESSION_ID, sessionId)
            .putExtra(SessionKeepAliveService.EXTRA_HOST_LABEL, hostLabel)
        if (wasEmpty) {
            // First session: bring the service up as foreground (required on API 26+). Normally the
            // app is in the foreground here (the user just opened a session), where this is always
            // allowed. Must never throw into the caller — a refusal costs the notification, not the
            // just-established session.
            runCatching { context.startForegroundService(intent) }
                .onFailure { Log.w(TAG, "keep-alive service start refused", it) }
        } else {
            // Service already up (or a reconnect of a known session) — plain start is enough to
            // deliver the intent; a dead service re-promotes itself inside onStartCommand.
            runCatching { context.startService(intent) }
                .onFailure { Log.w(TAG, "keep-alive add for $sessionId not delivered", it) }
        }
    }

    override fun onSessionEnded(sessionId: String) {
        val removed = synchronized(lock) { sessions.remove(sessionId) }
        if (removed == null) return // idempotent: unknown id is a no-op
        val intent = Intent(context, SessionKeepAliveService::class.java)
            .setAction(SessionKeepAliveService.ACTION_REMOVE)
            .putExtra(SessionKeepAliveService.EXTRA_SESSION_ID, sessionId)
        runCatching { context.startService(intent) }
            .onFailure {
                // The service was never told: its notification stays until the next delivered
                // event or service restart (which replays this map). Log so a stuck notification
                // is diagnosable.
                Log.w(TAG, "keep-alive remove for $sessionId not delivered", it)
            }
    }
}
