package app.skerry.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import app.skerry.shared.host.Host
import app.skerry.ui.design.Dot
import app.skerry.ui.design.HLine
import app.skerry.ui.design.IconBtn
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.Txt
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.term_tip_sidebar
import app.skerry.ui.generated.resources.term_wbar_panes
import app.skerry.ui.generated.resources.term_wbar_sync
import app.skerry.ui.session.sessionDotColor
import app.skerry.ui.theme.Skerry
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Height of the bar above the work area. The info and assistant panels open beside it and start
 * their own content at the same line, so the three read as one row across the window.
 */
val WORK_BAR_HEIGHT = 38.dp

/**
 * The bar's leftmost button. What it does depends on what the work area holds: the terminal
 * collapses the hosts sidebar there ([sidebarToggle] — the chevron points the way the panel will
 * travel, and it is the terminal's only sidebar handle), a view that fills the whole work area and
 * shows no sidebar leaves the terminal instead ([back]). Whatever the button does, it stays in the
 * same place across views.
 */
data class WorkBarLeading(val icon: String, val tooltip: StringResource, val onClick: () -> Unit) {
    companion object {
        fun sidebarToggle(hidden: Boolean, onToggle: () -> Unit) =
            WorkBarLeading(if (hidden) "chevron_right" else "chevron_left", Res.string.term_tip_sidebar, onToggle)

        fun back(tooltip: StringResource, onBack: () -> Unit) = WorkBarLeading("chevron_left", tooltip, onBack)
    }
}

/**
 * The strip above the work area: what is open on the left, what can be done to it on the right.
 * One bar per tab, not per pane — the session-wide actions used to float over the top-right pane's
 * header, which put them over a pane they did not belong to and made them move as panes were added.
 *
 * [label] names the tab (`null` while nothing is open, where only the actions apply). [onPickHost]
 * makes the title the host picker; it is offered on a single-pane tab, where the bar *is* that
 * pane's header — a split tab picks per pane, in the pane's own header.
 *
 * [tabKey] identifies the tab the bar currently speaks for. The bar itself is one composable for
 * every tab, so an open host picker would otherwise survive a tab switch (Ctrl+Tab reaches the root
 * handler past the picker's non-focusable popup) and rebind to whatever became active — picking a
 * host there would repoint a pane the user never opened the list for.
 */
@Composable
fun WorkBar(
    label: WorkBarLabel?,
    tabKey: Any?,
    leading: WorkBarLeading,
    onPickHost: ((Host) -> Unit)?,
    actions: @Composable RowScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth().background(Skerry.colors.surface)) {
        Row(
            Modifier.fillMaxWidth().height(WORK_BAR_HEIGHT).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconBtn(
                leading.icon,
                onClick = leading.onClick,
                box = 26,
                tooltip = stringResource(leading.tooltip),
            )
            WorkBarTitle(label, tabKey, onPickHost, Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp), content = actions)
        }
        HLine()
    }
}

/**
 * The bar's left half: the host (or the split) it speaks for, its address, its dot. On a
 * single-pane tab the whole block is the host picker — clicking it is how that pane is pointed
 * somewhere else, the way a pane header does it on a split.
 */
@Composable
private fun WorkBarTitle(
    label: WorkBarLabel?,
    tabKey: Any?,
    onPickHost: ((Host) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val mono = LocalFonts.current.mono
    // Keyed on the tab: a picker left open must not follow a tab switch onto another session.
    var pickerOpen by remember(tabKey) { mutableStateOf(false) }
    Box(modifier) {
        Row(
            Modifier.fillMaxWidth().then(
                if (onPickHost != null) Modifier.clickable { pickerOpen = !pickerOpen } else Modifier,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (label) {
                null -> Unit
                is WorkBarLabel.Solo -> {
                    Txt(label.title, size = 13.sp, weight = FontWeight.SemiBold, font = mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Txt(
                        label.subtitle, color = Skerry.colors.faint, size = 12.sp, font = mono,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                    )
                }
                is WorkBarLabel.Split -> {
                    Txt(
                        pluralStringResource(Res.plurals.term_wbar_panes, label.paneCount, label.paneCount),
                        size = 13.sp, weight = FontWeight.SemiBold, font = mono, maxLines = 1,
                    )
                    // Synchronized input is named in the title rather than badged on every pane:
                    // what makes it dangerous is forgetting it is on, and the title is read before
                    // the first keystroke of a session, not after it.
                    if (label.syncInput) {
                        Txt("·", color = Skerry.colors.faint, size = 13.sp, font = mono)
                        Txt(stringResource(Res.string.term_wbar_sync), color = Skerry.colors.teal, size = 12.5.sp, font = mono, maxLines = 1)
                    }
                    Txt(
                        label.hosts, color = Skerry.colors.faint, size = 12.sp, font = mono,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                    )
                }
            }
            if (label != null) Dot(sessionDotColor(label.status))
        }
        if (pickerOpen && onPickHost != null) {
            Popup(alignment = Alignment.BottomStart, onDismissRequest = { pickerOpen = false }) {
                PaneHostPicker { host ->
                    onPickHost(host)
                    pickerOpen = false
                }
            }
        }
    }
}
