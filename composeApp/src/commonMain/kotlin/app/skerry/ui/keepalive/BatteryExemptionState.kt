package app.skerry.ui.keepalive

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Whether the system currently exempts the app from battery optimisation, re-read every time the
 * window comes back.
 *
 * The answer is changed in a *system* page we sent the user to, so a value read once at composition
 * is stale exactly when it matters: the person taps "Open", allows the exemption, comes back, and
 * the screen still says the system may suspend the app. `ON_RESUME` rather than `ON_START` because
 * the settings list is a full activity of another app, and returning from it is a resume.
 *
 * `null` power (desktop, preview) reads as not exempt, and returns before touching
 * [LocalLifecycleOwner]: that local has no default value and throws where nothing provides one, which
 * is every offscreen mobile render (`renderMobile` in `Screenshot.kt` wraps no lifecycle owner around
 * the scene). A screen that only draws must not need one.
 */
@Composable
internal fun rememberBatteryExemption(power: KeepAlivePower?): Boolean {
    if (power == null) return false
    val owner = LocalLifecycleOwner.current
    var reads by remember(power) { mutableStateOf(0) }
    DisposableEffect(owner, power) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) reads++
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return remember(power, reads) { power.isExemptFromBatteryOptimization() }
}
