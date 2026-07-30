package app.skerry.ui.remote

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Keeps [screen] told whether anyone can see it, so a server need not render and stream a desktop
 * that is off screen (RDP's Suppress Output, MS-RDPBCGR 2.2.11.3).
 *
 * Two things hide a session, and both are covered here. The window itself: an Android activity sent
 * to the background and a minimised desktop window both leave the started state, which is what
 * [windowVisibleAt] reads. And the composition: only the session on screen is composed, so the
 * effect leaving takes the picture with it — another tab was selected, or the session is being torn
 * down. Coming back re-adds the observer, which replays the current state and un-suppresses the
 * server.
 */
@Composable
fun ReportOutputVisibility(screen: RemoteDesktopScreenState) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, screen) { onDispose(observeWindowVisibility(owner.lifecycle, screen::setVisible)) }
}

/**
 * Watch [lifecycle] and hand every change of "the window is on screen" to [report]; the returned
 * action stops watching and reports the session hidden, which is what a session leaving the screen
 * means. Attaching reports the current state on its own — that replay is what un-suppresses a
 * session on the way back, since by then the window has long been up and no further event is coming.
 */
internal fun observeWindowVisibility(lifecycle: Lifecycle, report: (Boolean) -> Unit): () -> Unit {
    val observer = LifecycleEventObserver { _, event -> windowVisibleAt(event)?.let(report) }
    lifecycle.addObserver(observer)
    return {
        lifecycle.removeObserver(observer)
        report(false)
    }
}

/**
 * Whether [event] means the window is on screen, or `null` where it says nothing about that. Only
 * the started state carries the answer: focus changes (`ON_PAUSE`/`ON_RESUME`) fire whenever a
 * desktop window is clicked away from, and the remote desktop is still in plain sight there.
 */
internal fun windowVisibleAt(event: Lifecycle.Event): Boolean? = when (event) {
    Lifecycle.Event.ON_START -> true
    Lifecycle.Event.ON_STOP -> false
    else -> null
}
