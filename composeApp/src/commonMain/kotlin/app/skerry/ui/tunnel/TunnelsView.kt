package app.skerry.ui.tunnel

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.skerry.ui.app.LocalHosts
import app.skerry.ui.app.LocalTunnels
import app.skerry.ui.design.ConfirmActionDialog
import app.skerry.ui.design.EmptyState
import app.skerry.ui.design.GhostButton
import app.skerry.ui.design.HLine
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.PrimaryButton
import app.skerry.ui.design.SectionHeader
import app.skerry.ui.design.VLine
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.ports_add_tunnel_right
import app.skerry.ui.generated.resources.ports_autostart
import app.skerry.ui.generated.resources.ports_find_services
import app.skerry.ui.generated.resources.ports_new_tunnel
import app.skerry.ui.generated.resources.ports_no_tunnels_yet
import app.skerry.ui.generated.resources.ports_remove
import app.skerry.ui.generated.resources.ports_remove_active_message
import app.skerry.ui.generated.resources.ports_remove_confirm_title
import app.skerry.ui.generated.resources.ports_remove_inactive_message
import app.skerry.ui.generated.resources.ports_tunnels
import app.skerry.ui.host.HostManagerController
import app.skerry.ui.theme.Skerry
import org.jetbrains.compose.resources.stringResource

/** What the right-hand column is showing. Exactly one at a time — they share the slot. */
private enum class TunnelPanel { None, Editor, Services, Autostart }

/**
 * Tunnels — global section: a table of saved tunnels with their live status and throughput, a
 * dashboard of what is flowing, what starts by itself and what recently broke, plus a side panel
 * that edits one tunnel, scans a host for services, or manages autostart.
 *
 * A tunnel is standalone (references a host by id) and opens its own SSH connection via
 * [TunnelManager] on activation. With no manager supplied ([LocalTunnels] is null — offscreen
 * render/preview) the table renders sample rows instead.
 */
@Composable
fun TunnelsView() {
    val mono = LocalFonts.current.mono
    val manager = LocalTunnels.current
    val hosts = LocalHosts.current

    var selectedId by remember { mutableStateOf<String?>(null) }
    // Discovery survives leaving and re-entering the section: the panel reopens on whatever the
    // scan still holds, and only closing it (which resets the scan) puts the editor back.
    var panel by remember {
        mutableStateOf(if (manager?.services?.state != ServiceScanState.Idle) TunnelPanel.Services else TunnelPanel.None)
    }
    // Editor with no selection means "new tunnel"; selecting a row keeps the same panel open.
    var adding by remember { mutableStateOf(false) }

    val counts = tunnelCounts(manager?.tunnels.orEmpty())

    Column(Modifier.fillMaxSize().background(Skerry.colors.bg)) {
        SectionHeader(
            title = stringResource(Res.string.ports_tunnels),
            subtitle = tunnelCountsSubtitle(counts),
            actions = {
                GhostButton(
                    stringResource(Res.string.ports_find_services),
                    onClick = { panel = TunnelPanel.Services; adding = false; selectedId = null },
                    icon = "radar",
                )
                GhostButton(
                    stringResource(Res.string.ports_autostart),
                    onClick = { panel = TunnelPanel.Autostart; adding = false; selectedId = null },
                    icon = "bolt",
                )
                PrimaryButton(
                    stringResource(Res.string.ports_new_tunnel),
                    onClick = { panel = TunnelPanel.Editor; adding = true; selectedId = null },
                    icon = "add",
                )
            },
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (manager == null) {
                MockTunnelsBody(mono)
            } else {
                GlobalTunnelsBody(
                    manager = manager,
                    hosts = hosts,
                    mono = mono,
                    panel = panel,
                    adding = adding,
                    selectedId = selectedId,
                    onSelect = { selectedId = it; adding = false; panel = TunnelPanel.Editor },
                    onClosePanel = {
                        if (panel == TunnelPanel.Services) manager.services.reset()
                        panel = TunnelPanel.None
                        adding = false
                        selectedId = null
                    },
                    // After deletion, returns to "New tunnel" mode instead of jumping to an
                    // arbitrary remaining tunnel: selectedId still holds the removed id, and
                    // without resetting it, selected would resolve via firstOrNull().
                    onNew = { selectedId = null; adding = true; panel = TunnelPanel.Editor },
                )
            }
        }
    }
}

@Composable
private fun GlobalTunnelsBody(
    manager: TunnelManager,
    hosts: HostManagerController?,
    mono: FontFamily,
    panel: TunnelPanel,
    adding: Boolean,
    selectedId: String?,
    onSelect: (String) -> Unit,
    onClosePanel: () -> Unit,
    onNew: () -> Unit,
) {
    val tunnels = manager.tunnels
    // No auto-selection: the right panel stays closed until the user opens it (New tunnel / a row /
    // Scan ports / Autostart). `selected` is null unless a real tunnel is being edited.
    val selected = tunnels.firstOrNull { it.id == selectedId }
    val editorVisible = panel == TunnelPanel.Editor && (adding || selected != null)

    fun hostLabel(hostId: String): String = hosts?.find(hostId)?.label ?: hostId

    // Tunnel for which the delete-confirmation dialog is shown (null — no dialog). Local state
    // suffices since deletion (manager.delete) is self-contained, unlike session close.
    var pendingRemove by remember { mutableStateOf<TunnelEntry?>(null) }

    Box(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize()) {
            if (tunnels.isEmpty()) {
                EmptyState(
                    icon = "lan",
                    title = stringResource(Res.string.ports_no_tunnels_yet),
                    subtitle = stringResource(Res.string.ports_add_tunnel_right),
                    modifier = Modifier.weight(1f),
                )
            } else {
                Column(
                    Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())
                        .padding(horizontal = 22.dp, vertical = 18.dp),
                ) {
                    AutostartFailureBanner(manager, Modifier.padding(bottom = 14.dp))
                    TableFrame {
                        TunnelHeaderRow()
                        tunnels.forEach { entry ->
                            HLine()
                            // Lambdas stabilized by id: active-tunnel telemetry ticks every
                            // second, and without remember would recreate onSelect/onToggle,
                            // recomposing the whole list.
                            val onRowSelect = remember(entry.id, onSelect) { { onSelect(entry.id) } }
                            val onRowToggle = remember(entry.id, manager) {
                                {
                                    if (entry.status is TunnelStatus.Active) manager.deactivate(entry.id)
                                    else manager.activate(entry.id)
                                }
                            }
                            TunnelRow(
                                entry = entry,
                                via = hostLabel(entry.tunnel.hostId),
                                mono = mono,
                                selected = entry.id == selectedId,
                                onSelect = onRowSelect,
                                onToggle = onRowToggle,
                            )
                        }
                    }
                    TunnelDashboard(
                        manager = manager,
                        hostLabel = ::hostLabel,
                        mono = mono,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
            }
            // Right column: the panels share ONE slide-in slot (not sibling AnimatedVisibility
            // blocks — those each reserved width in the Row, so switching between them briefly
            // expanded both at once and jolted the list width). A single slot expands from the
            // right edge (the list reflows to fill the freed width); clipToBounds keeps the panel
            // content from painting outside the animating slot.
            //
            // `shown*` latch what the panel is displaying and only update while it's open, so the
            // outgoing content survives the slide-out unchanged — otherwise the exit would recompose
            // against the just-cleared state and flash (a blank "New tunnel" form on editor close, or
            // the editor on services close).
            val panelVisible = editorVisible || panel == TunnelPanel.Services || panel == TunnelPanel.Autostart
            var shownPanel by remember { mutableStateOf(panel) }
            var shownEntry by remember { mutableStateOf<TunnelEntry?>(null) }
            var shownAdding by remember { mutableStateOf(false) }
            if (panelVisible) {
                shownPanel = panel
                if (panel == TunnelPanel.Editor) { shownEntry = selected; shownAdding = adding }
            }
            AnimatedVisibility(
                visible = panelVisible,
                enter = expandHorizontally(expandFrom = Alignment.End) + fadeIn(),
                exit = shrinkHorizontally(shrinkTowards = Alignment.End) + fadeOut(),
            ) {
                Row(Modifier.clipToBounds()) {
                    VLine(Skerry.colors.line)
                    // Exhaustive on purpose: `None` cannot reach here (the slot is invisible for
                    // it), but a catch-all `else` would silently render an empty editor if that ever
                    // stopped holding, instead of failing where the invariant broke.
                    when (shownPanel) {
                        TunnelPanel.None -> Unit
                        TunnelPanel.Services -> ServicesPanel(manager, hosts, mono, onClose = onClosePanel)
                        TunnelPanel.Autostart -> AutostartPanel(manager, ::hostLabel, onClose = onClosePanel)
                        TunnelPanel.Editor -> TunnelEditor(
                            manager = manager,
                            hosts = hosts,
                            mono = mono,
                            existing = if (shownAdding) null else shownEntry,
                            onSaved = { onSelect(it) },
                            onRequestRemove = { shownEntry?.let { pendingRemove = it } },
                            onClose = onClosePanel,
                        )
                    }
                }
            }
        }
        pendingRemove?.let { entry ->
            ConfirmActionDialog(
                title = stringResource(Res.string.ports_remove_confirm_title, entry.tunnel.label),
                message = if (entry.status is TunnelStatus.Active) {
                    stringResource(Res.string.ports_remove_active_message)
                } else {
                    stringResource(Res.string.ports_remove_inactive_message)
                },
                confirmLabel = stringResource(Res.string.ports_remove),
                onConfirm = { manager.delete(entry.id); pendingRemove = null; onNew() },
                onDismiss = { pendingRemove = null },
            )
        }
    }
}

/** Offscreen render/preview without a manager: the same table over sample rows. */
@Composable
private fun MockTunnelsBody(mono: FontFamily) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 18.dp),
    ) {
        TableFrame {
            TunnelHeaderRow()
            mockTunnelEntries().forEach { (entry, host) ->
                HLine()
                TunnelRow(entry = entry, via = host, mono = mono, selected = false, onSelect = {}, onToggle = {})
            }
        }
    }
}

/** The outlined frame the table sits in. */
@Composable
private fun TableFrame(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, Skerry.colors.cyan08, RoundedCornerShape(10.dp)),
    ) {
        content()
    }
}
