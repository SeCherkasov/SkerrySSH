package app.skerry.android

/**
 * What the Disconnect button on a session notification does, kept free of Android types so it is
 * testable on the JVM.
 *
 * [closeSession] closes the pane as its close button in the app does and says whether one held the
 * id; the controller then reports the session ended, which removes the notification the ordinary
 * way. When nothing held it, the notification outlived its session and [forget] drops it.
 *
 * [deviceLocked] (a credential is needed to unlock) is checked because Android 11 and older cannot
 * require an unlock for a notification action, and a lock screen showing full content would
 * otherwise let anyone holding the phone drop its sessions.
 */
internal class NotificationDisconnect(
    private val closeSession: (String) -> Boolean,
    private val forget: (String) -> Unit,
    private val deviceLocked: () -> Boolean,
) {
    fun handle(sessionId: String?) {
        if (sessionId == null || deviceLocked()) return
        if (!closeSession(sessionId)) forget(sessionId)
    }
}
