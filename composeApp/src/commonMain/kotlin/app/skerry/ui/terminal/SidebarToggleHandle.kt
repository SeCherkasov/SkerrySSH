package app.skerry.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.design.Sym
import app.skerry.ui.design.handsKeyboardBack
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.shell_tip_hide_hosts
import app.skerry.ui.generated.resources.shell_tip_show_hosts
import app.skerry.ui.theme.Skerry
import org.jetbrains.compose.resources.stringResource

/**
 * Slim strip on the hosts panel's edge, painted in the panel's own surface so it reads as the panel
 * peeking out. The chevron points the way the panel will travel, and the strip is all chevron, so it
 * carries the action's name itself.
 *
 * 16dp wide, deliberately: the strip runs the full height of the panel, so the only axis a click
 * can miss on is the one the pointer is already travelling along when it reaches the panel's edge.
 * That is under WCAG 2.5.8's 24dp, and accepted as such rather than padded out with dead space.
 *
 * The one control for the panel in both sections: the terminal used to collapse it from a chevron in
 * its work bar instead, which put the control over the session rather than beside the thing it
 * moves — and made the same action two different shapes depending on which section was open.
 */
@Composable
internal fun SidebarToggleHandle(hidden: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.width(16.dp).fillMaxHeight().background(Skerry.colors.surface2)
            .handsKeyboardBack().clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Sym(
            if (hidden) "chevron_right" else "chevron_left",
            contentDescription = stringResource(
                if (hidden) Res.string.shell_tip_show_hosts else Res.string.shell_tip_hide_hosts,
            ),
            size = 16.sp,
            color = Skerry.colors.faint,
        )
    }
}
