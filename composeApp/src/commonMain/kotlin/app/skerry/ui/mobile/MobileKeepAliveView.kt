package app.skerry.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.app.LocalKeepAlivePower
import app.skerry.ui.app.MobileDesignState
import app.skerry.ui.design.HLine
import app.skerry.ui.design.Notice
import app.skerry.ui.design.StatusAnnouncer
import app.skerry.ui.design.ToggleRow
import app.skerry.ui.design.Txt
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.keepalive_autostart
import app.skerry.ui.generated.resources.keepalive_autostart_desc
import app.skerry.ui.generated.resources.keepalive_battery
import app.skerry.ui.generated.resources.keepalive_battery_off
import app.skerry.ui.generated.resources.keepalive_battery_on
import app.skerry.ui.generated.resources.keepalive_desc
import app.skerry.ui.generated.resources.keepalive_device
import app.skerry.ui.generated.resources.keepalive_open
import app.skerry.ui.generated.resources.keepalive_recents
import app.skerry.ui.generated.resources.keepalive_recents_generic
import app.skerry.ui.generated.resources.keepalive_recents_samsung
import app.skerry.ui.generated.resources.keepalive_recents_xiaomi
import app.skerry.ui.generated.resources.keepalive_title
import app.skerry.ui.generated.resources.keepalive_wakelock
import app.skerry.ui.generated.resources.keepalive_wakelock_deferred
import app.skerry.ui.generated.resources.keepalive_wakelock_desc
import app.skerry.ui.keepalive.KeepAliveVendor
import app.skerry.ui.keepalive.label
import app.skerry.ui.keepalive.rememberBatteryExemption
import app.skerry.ui.theme.Skerry
import org.jetbrains.compose.resources.stringResource

/**
 * More → "Background & lock screen" push screen (Android only — the More row is absent where no
 * [app.skerry.ui.keepalive.KeepAlivePower] is supplied).
 *
 * The one setting the app owns is the wake lock; the rest is the firmware's, so each row states what
 * the system does and what to change about it. A row offers "Open" only where it has a page of its
 * own to open: the autostart row is drawn for the ROM families that have one, and the recents row —
 * whose steps live in the ROM's own security app and in the task switcher — offers nothing, because
 * a button landing on this app's details page under those words would be a lie.
 *
 * Without a power hook (preview/offscreen) every row is inert: the screen still draws so the
 * layout can be rendered, but nothing pretends to be configurable.
 */
@Composable
fun MobileKeepAliveScreen(state: MobileDesignState) {
    val power = LocalKeepAlivePower.current
    val vendor = power?.vendor ?: KeepAliveVendor.Other
    val exempt = rememberBatteryExemption(power)
    // Mirrors the stored flag so the switch answers the tap; the store is the source of truth on
    // the next entry (and for the service, which reads it when a session opens).
    var wakeLock by remember(power) { mutableStateOf(power?.wakeLockEnabled == true) }
    // The store took the value but the running session did not; the next connect will.
    var deferred by remember(power) { mutableStateOf(false) }
    val batteryStatus = stringResource(if (exempt) Res.string.keepalive_battery_on else Res.string.keepalive_battery_off)
    val deferredNote = stringResource(Res.string.keepalive_wakelock_deferred)

    Box(Modifier.fillMaxSize().background(Skerry.colors.bg)) {
        Column(Modifier.fillMaxSize()) {
            MobilePushHeader(stringResource(Res.string.keepalive_title), onBack = state::pop)
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
                Txt(
                    stringResource(Res.string.keepalive_desc),
                    color = Skerry.colors.dim,
                    size = 12.5.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                )

                ToggleRow(
                    label = stringResource(Res.string.keepalive_wakelock),
                    subtitle = stringResource(Res.string.keepalive_wakelock_desc),
                    subtitleColor = Skerry.colors.dim,
                    labelSize = 14.5.sp,
                    on = wakeLock,
                    onToggle = {
                        val target = power ?: return@ToggleRow
                        wakeLock = !wakeLock
                        deferred = !target.setWakeLockEnabled(wakeLock)
                    },
                    modifier = Modifier.padding(vertical = 14.dp),
                )
                // Composed across both states, not inserted with the message: a live region only
                // reports a change on a node that outlives it (see StatusAnnouncer).
                StatusAnnouncer(if (deferred) deferredNote else "")
                if (deferred) {
                    Notice(
                        deferredNote,
                        // Drawn for the eye only: the announcer above already carries this sentence,
                        // and left readable the line would be heard twice in a row.
                        Modifier.padding(bottom = 12.dp).semantics { hideFromAccessibility() },
                    )
                }
                HLine()

                MobileSettingLinkRow(
                    label = stringResource(Res.string.keepalive_battery),
                    subtitle = batteryStatus,
                    action = stringResource(Res.string.keepalive_open),
                    onAction = power?.let { { it.openBatteryOptimizationSettings() } },
                )
                // The exemption is granted in a system page, so the status changes while the screen
                // is stopped and comes back silently — visible to a sighted user, to no one else.
                StatusAnnouncer(batteryStatus)
                HLine()

                if (vendor.hasAutostartPage) {
                    MobileSettingLinkRow(
                        label = stringResource(Res.string.keepalive_autostart),
                        subtitle = stringResource(Res.string.keepalive_autostart_desc),
                        action = stringResource(Res.string.keepalive_open),
                        onAction = power?.let { { it.openAutostartSettings() } },
                    )
                    HLine()
                }

                MobileSettingNoteRow(
                    label = stringResource(Res.string.keepalive_recents),
                    subtitle = stringResource(recentsAdviceFor(vendor)),
                )
                HLine()

                // Only with a device to name: without a power hook this would read "Detected: ".
                if (power != null) {
                    Txt(
                        stringResource(Res.string.keepalive_device, vendor.label(power.manufacturer)),
                        color = Skerry.colors.faint,
                        size = 11.5.sp,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/**
 * Where a ROM family hides its lock-screen cleanup. Named per family only where the path is one we
 * can actually spell — everyone else gets the shape of the setting rather than a menu path that
 * doesn't exist on their phone.
 */
private fun recentsAdviceFor(vendor: KeepAliveVendor) = when (vendor) {
    KeepAliveVendor.Xiaomi -> Res.string.keepalive_recents_xiaomi
    KeepAliveVendor.Samsung -> Res.string.keepalive_recents_samsung
    else -> Res.string.keepalive_recents_generic
}
