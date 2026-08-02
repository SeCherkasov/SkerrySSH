package app.skerry.ui.tunnel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.design.IconBtn
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.ports_autostart_failed
import app.skerry.ui.generated.resources.ports_bind_beyond_loopback
import app.skerry.ui.generated.resources.ports_dismiss
import app.skerry.ui.theme.Skerry
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Aggregate report of the last autostart run. The rows already carry each failure, but nothing
 * otherwise distinguishes "the vault unlocked" from "everything the user asked to come up did":
 * the tunnels that dial themselves are exactly the ones nobody is watching. Renders nothing when
 * they all came up, or once dismissed. Shared by the desktop section and the mobile screen.
 */
@Composable
internal fun AutostartFailureBanner(manager: TunnelManager, modifier: Modifier = Modifier) {
    val failures = manager.autostartFailures
    if (failures.isEmpty()) return
    Row(
        modifier.fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Skerry.colors.amberSoft)
            .border(1.dp, Skerry.colors.amber.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Sym("bolt", size = 15.sp, color = Skerry.colors.amber)
        Txt(
            pluralStringResource(Res.plurals.ports_autostart_failed, failures.size, failures.size),
            color = Skerry.colors.amber,
            size = 12.sp,
            modifier = Modifier.weight(1f),
        )
        // A button, not a tappable label: the banner is shared with the phone, where 11 sp of text
        // is not something a thumb can hit.
        IconBtn(
            "close",
            onClick = { manager.dismissAutostartReport() },
            box = 34,
            icon = 16.sp,
            tint = Skerry.colors.amber,
            tooltip = stringResource(Res.string.ports_dismiss),
        )
    }
}

/** Warning under the bind address: this listener is reachable from outside this machine. */
@Composable
internal fun BindExposureWarning(bindHost: String, modifier: Modifier = Modifier) {
    if (!bindsBeyondLoopback(bindHost)) return
    Row(
        modifier.fillMaxWidth().padding(top = 6.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Sym("warning", size = 13.sp, color = Skerry.colors.amber)
        Txt(
            stringResource(Res.string.ports_bind_beyond_loopback),
            color = Skerry.colors.amber,
            size = 11.sp,
            lineHeight = 15.sp,
        )
    }
}
