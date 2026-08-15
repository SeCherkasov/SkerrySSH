package app.skerry.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import app.skerry.ui.keepalive.KeepAliveNotificationRegistry

/**
 * Foreground service that keeps the app process — and with it the open SSH keep-alive loops —
 * alive while sessions are open. Backgrounding the app therefore does not freeze the connection:
 * the keepalive pings keep flowing, NAT/firewall idle timers don't fire, and returning to the app
 * shows the same live terminal instead of a dropped session.
 *
 * One notification per live session (Termius-style): the summary notification ([SUMMARY_ID]) is
 * the foreground one that must stay up while the service is foreground; each session gets its own
 * notification titled with the host, and tapping it routes back to that exact terminal (the
 * session id travels in the intent). The moment the last session closes, [ACTION_REMOVE] tears the
 * service down and every notification disappears — the persistent notification is present exactly
 * when it matters, never parked forever.
 *
 * [ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE] is deliberate: unlike dataSync, the
 * connectedDevice type has no 6-hour timeout on Android 14+, and its semantics fit — we keep a
 * network connection to an external device/server alive.
 */
class SessionKeepAliveService : Service() {

    companion object {
        const val ACTION_ADD = "app.skerry.android.action.KEEPALIVE_ADD"
        const val ACTION_REMOVE = "app.skerry.android.action.KEEPALIVE_REMOVE"
        const val ACTION_NOTIFICATION_DISMISSED = "app.skerry.android.action.KEEPALIVE_NOTIFICATION_DISMISSED"
        const val EXTRA_SESSION_ID = "app.skerry.android.extra.SESSION_ID"
        const val EXTRA_HOST_LABEL = "app.skerry.android.extra.HOST_LABEL"

        private const val CHANNEL_ID = "session_keepalive"
        // Foreground summary notification (must stay up while the service is foreground).
        private const val SUMMARY_ID = 0x5E77
        // Per-session notifications start here and increment.
        private const val SESSION_ID_BASE = 0x5E78

        /**
         * The process-side bridge, registered by [AndroidSessionKeepAlive] so a system-restarted
         * service (START_STICKY, null intent) can rebuild its notification map from the still-live
         * session set instead of showing nothing.
         */
        @Volatile
        var bridgeInstance: AndroidSessionKeepAlive? = null
    }

    // sessionId -> notification id. Kept only while the service lives; the process-side
    // AndroidSessionKeepAlive map survives service death and re-promotes on the next change.
    private val sessions = KeepAliveNotificationRegistry(SESSION_ID_BASE)
    // sessionId -> host label, kept alongside [sessions] so a dismissed notification can be
    // re-shown with its host title without consulting the process-side bridge.
    private val sessionHosts = LinkedHashMap<String, String>()
    // Registered while the service lives; re-shows notifications when one is swiped away.
    private var dismissReceiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        registerDismissReceiver()
    }

    override fun onDestroy() {
        dismissReceiver?.let { unregisterReceiver(it) }
        dismissReceiver = null
        super.onDestroy()
    }

    /**
     * Registers the receiver for [ACTION_NOTIFICATION_DISMISSED]. A foreground-service
     * notification can be swiped away by the user even with [Notification.Builder.setOngoing]
     * (MIUI/HyperOS are lenient here); the keep-alive state itself is unaffected, but the
     * notification is the only visible handle back to the live terminals — so re-show it.
     * Re-registered on every service create (START_STICKY restart included).
     */
    private fun registerDismissReceiver() {
        if (dismissReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                restoreNotifications()
            }
        }
        val filter = IntentFilter(ACTION_NOTIFICATION_DISMISSED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
        dismissReceiver = receiver
    }

    /** Re-shows the summary plus every per-session notification after a dismiss. */
    private fun restoreNotifications() {
        if (sessions.isEmpty) return
        createChannel()
        // The summary must be re-posted through startForeground so the service is still
        // foreground (Android requires the foreground notification to be present).
        startForegroundCompat(buildSummaryNotification(sessions.size))
        for (sessionId in sessions.idsInOrder()) {
            val hostLabel = sessionHosts[sessionId] ?: continue
            val notifId = sessions.register(sessionId) // stable id, no-op if already registered
            notificationManager().notify(notifId, buildSessionNotification(hostLabel, sessionId, notifId))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            // Service restarted by the system (START_STICKY) after it was killed while the process
            // stayed alive. Our session map is gone, but AndroidSessionKeepAlive keeps the
            // authoritative process-side copy — replay it so notifications come back.
            replaySessionsFromBridge()
            return START_STICKY
        }
        when (intent.action) {
            ACTION_REMOVE -> removeSession(intent)
            else -> addSession(intent) // ACTION_ADD; also the plain-start fallback path
        }
        return START_STICKY
    }

    /**
     * Rebuilds the service's session map from the process-side [AndroidSessionKeepAlive] snapshot.
     * Used when the system restarts this service (START_STICKY) with a null intent after the
     * service was killed: the process and its sessions are still live, so the per-session
     * notifications must come back.
     *
     * When the process itself is gone (a fresh START_STICKY launch after process death) the bridge
     * holds nothing — the SSH keep-alive loops died with the process, so there is nothing to show.
     * The service still has to call [startForeground] within 5s of start (it was restarted AS a
     * foreground service), so it briefly goes foreground with an empty summary and then stops
     * itself, rather than crashing with ForegroundServiceDidNotStartInTimeException.
     */
    private fun replaySessionsFromBridge() {
        createChannel()
        val snapshot = SessionKeepAliveService.bridgeInstance?.snapshotSessions().orEmpty()
        if (snapshot.isEmpty()) {
            startForegroundCompat(buildSummaryNotification(0))
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        snapshot.forEach { (sessionId, hostLabel) ->
            sessionHosts[sessionId] = hostLabel
            addSessionInternal(sessionId, hostLabel)
        }
        updateSummary()
    }

    /**
     * [Service.startForeground] with the manifest-declared type. API 29+ takes an explicit type;
     * API 34+ requires it to match a manifest type and throws if none is given. connectedDevice is
     * only defined from 34 — earlier versions accept 0.
     */
    private fun startForegroundCompat(summary: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            } else {
                0
            }
            startForeground(SUMMARY_ID, summary, type)
        } else {
            startForeground(SUMMARY_ID, summary)
        }
    }

    private fun addSession(intent: Intent) {
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: return
        val hostLabel = intent.getStringExtra(EXTRA_HOST_LABEL) ?: return
        addSessionInternal(sessionId, hostLabel)
    }

    private fun addSessionInternal(sessionId: String, hostLabel: String) {
        createChannel()
        if (sessions.isEmpty) {
            // First session (or the service was dead and is re-promoting): go foreground. The
            // summary notification is the one Android requires to stay; sessions follow below.
            startForegroundCompat(buildSummaryNotification(1))
        }
        sessionHosts[sessionId] = hostLabel
        val notifId = sessions.register(sessionId)
        notificationManager().notify(notifId, buildSessionNotification(hostLabel, sessionId, notifId))
        updateSummary()
    }

    private fun removeSession(intent: Intent) {
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: return
        val notifId = sessions.remove(sessionId) ?: return // idempotent
        sessionHosts.remove(sessionId)
        notificationManager().cancel(notifId)
        if (sessions.isEmpty) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else {
            updateSummary()
        }
    }

    private fun updateSummary() {
        if (sessions.isEmpty) return
        notificationManager().notify(SUMMARY_ID, buildSummaryNotification(sessions.size))
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.session_keepalive_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { setShowBadge(false) }
            manager.createNotificationChannel(channel)
        }
    }

    /** Summary (foreground) notification: N sessions alive; tap opens the app. */
    private fun buildSummaryNotification(count: Int): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.session_keepalive_title))
            .setContentText(
                resources.getQuantityString(R.plurals.session_keepalive_count, count, count)
            )
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentIntent(contentIntent)
            .setDeleteIntent(notificationDismissedPendingIntent(SUMMARY_ID))
            .build()
    }

    /** Per-session notification: host label as the title; tap routes to that terminal. */
    private fun buildSessionNotification(hostLabel: String, sessionId: String, notifId: Int): Notification {
        // requestCode = notifId (unique per session in this service) so FLAG_UPDATE_CURRENT can
        // never mix two sessions' extras on a hashCode collision.
        val tap = PendingIntent.getActivity(
            this,
            notifId,
            Intent(this, MainActivity::class.java)
                .putExtra(EXTRA_SESSION_ID, sessionId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(hostLabel)
            .setContentText(getString(R.string.session_keepalive_text))
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentIntent(tap)
            .setDeleteIntent(notificationDismissedPendingIntent(notifId))
            .build()
    }

    /**
     * Fired when the user swipes a notification away: the receiver re-shows everything, so the
     * keep-alive state never silently disappears from the shade. Request code distinct per
     * notification (SUMMARY_ID / notifId) so FLAG_UPDATE_CURRENT never reuses a stale one.
     */
    private fun notificationDismissedPendingIntent(requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            this,
            requestCode,
            Intent(ACTION_NOTIFICATION_DISMISSED),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun notificationManager() = getSystemService(NotificationManager::class.java)
}
