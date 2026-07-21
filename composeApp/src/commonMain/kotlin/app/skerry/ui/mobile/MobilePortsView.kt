package app.skerry.ui.mobile

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.tunnel.TunnelDirection
import app.skerry.ui.forward.humanRate
import app.skerry.ui.forward.rateFraction
import app.skerry.ui.host.HostManagerController
import app.skerry.ui.tunnel.ListeningService
import app.skerry.ui.tunnel.ServiceScanState
import app.skerry.ui.tunnel.TunnelEntry
import app.skerry.ui.tunnel.TunnelFormState
import app.skerry.ui.tunnel.TunnelManager
import app.skerry.ui.tunnel.TunnelStatus
import app.skerry.ui.tunnel.forwardedPorts
import app.skerry.ui.tunnel.serviceLabel
import app.skerry.ui.tunnel.serviceScanFailureText
import app.skerry.ui.tunnel.serviceTunnelDraft
import app.skerry.ui.tunnel.tunnelBrowserUrl
import app.skerry.ui.tunnel.tunnelFailureText
import app.skerry.ui.tunnel.badgeColors
import app.skerry.ui.tunnel.badgeLabel
import app.skerry.ui.tunnel.displayLabel
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.ports_add_tunnel_below
import app.skerry.ui.generated.resources.ports_already_forwarded
import app.skerry.ui.generated.resources.ports_changes_apply_after_restart
import app.skerry.ui.generated.resources.ports_edit
import app.skerry.ui.generated.resources.ports_edit_tunnel
import app.skerry.ui.generated.resources.ports_field_bind_address
import app.skerry.ui.generated.resources.ports_field_destination
import app.skerry.ui.generated.resources.ports_field_live_throughput
import app.skerry.ui.generated.resources.ports_field_name
import app.skerry.ui.generated.resources.ports_field_port
import app.skerry.ui.generated.resources.ports_field_type
import app.skerry.ui.generated.resources.ports_field_via_host
import app.skerry.ui.generated.resources.ports_find_services
import app.skerry.ui.generated.resources.ports_forward
import app.skerry.ui.generated.resources.ports_new_tunnel
import app.skerry.ui.generated.resources.ports_no_saved_hosts
import app.skerry.ui.generated.resources.ports_no_services
import app.skerry.ui.generated.resources.ports_no_tunnels_yet
import app.skerry.ui.generated.resources.ports_ph_web_tunnel
import app.skerry.ui.generated.resources.ports_pick_host_to_scan
import app.skerry.ui.generated.resources.ports_port_forwarding
import app.skerry.ui.generated.resources.ports_remove
import app.skerry.ui.generated.resources.ports_remove_tunnel
import app.skerry.ui.generated.resources.ports_save_tunnel
import app.skerry.ui.generated.resources.ports_scan
import app.skerry.ui.generated.resources.ports_scanning
import app.skerry.ui.generated.resources.ports_select_host
import app.skerry.ui.generated.resources.ports_service_port
import app.skerry.ui.generated.resources.ports_services_hint
import app.skerry.ui.generated.resources.ports_services_title
import app.skerry.ui.generated.resources.ports_services_unsupported
import app.skerry.ui.generated.resources.ports_socks_hint
import app.skerry.ui.generated.resources.ports_tunnel
import app.skerry.ui.generated.resources.ports_via
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.design.AnchoredDropdown
import app.skerry.ui.design.Badge
import app.skerry.ui.design.D
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.app.LocalHosts
import app.skerry.ui.app.LocalTunnels
import app.skerry.ui.design.MeterBar
import app.skerry.ui.app.MobileDesignState
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Toggle
import app.skerry.ui.design.Txt

/**
 * Push screen for Port forwarding: back header + saved tunnel cards + New tunnel button.
 * Tunnels are a global section: listing and toggling go through [TunnelManager] ([LocalTunnels]),
 * independent of the connected session — each tunnel opens its own connection to its host. Card
 * toggle = on/off, long-press opens Edit/Remove, New tunnel/Edit open the editor sheet. Falls back
 * to a mock body without a manager (preview/offscreen).
 */
@Composable
fun MobilePortsScreen(state: MobileDesignState) {
    val mono = LocalFonts.current.mono
    val manager = LocalTunnels.current
    val hosts = LocalHosts.current
    // Open editor: null when closed, else the edited tunnel's id or "" for a new one. Kept at
    // screen level since the sheet is a full-screen overlay in the root Box.
    var editorFor by remember { mutableStateOf<String?>(null) }
    // Discovery survives leaving and re-entering the screen (desktop does the same): the sheet
    // reopens on whatever the scan still holds, and only closing it resets the scan.
    var discovering by remember { mutableStateOf(manager?.services?.state != ServiceScanState.Idle) }
    Box(Modifier.fillMaxSize().background(D.bg)) {
        Column(Modifier.fillMaxSize()) {
            MobilePushHeader(stringResource(Res.string.ports_port_forwarding), onBack = state::pop, plainBack = true)
            if (manager == null) {
                MockMobilePortsBody(mono)
            } else {
                LiveMobilePortsBody(
                    manager = manager,
                    hosts = hosts,
                    mono = mono,
                    onNew = { editorFor = "" },
                    onEdit = { editorFor = it },
                    onDiscover = { discovering = true },
                )
            }
        }
        if (manager != null && editorFor != null) {
            MobileTunnelEditorSheet(
                manager = manager,
                hosts = hosts,
                mono = mono,
                existing = editorFor?.takeIf { it.isNotEmpty() }?.let { id -> manager.tunnels.firstOrNull { it.id == id } },
                onDismiss = { editorFor = null },
            )
        }
        if (manager != null && discovering) {
            MobileServicesSheet(
                manager = manager,
                hosts = hosts,
                mono = mono,
                onDismiss = { discovering = false; manager.services.reset() },
            )
        }
    }
}

// Live path.

@Composable
private fun LiveMobilePortsBody(
    manager: TunnelManager,
    hosts: HostManagerController?,
    mono: FontFamily,
    onNew: () -> Unit,
    onEdit: (String) -> Unit,
    onDiscover: () -> Unit,
) {
    val tunnels = manager.tunnels

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (tunnels.isEmpty()) {
            MobileEmptyTunnels()
        } else {
            tunnels.forEach { entry ->
                // Keyed by id: forEach reuses slots positionally, so an open context menu would
                // otherwise jump to a neighboring card when a tunnel is added or removed.
                key(entry.id) {
                    // Lambdas stabilized by id to avoid recreation on the once-per-second active
                    // tunnel telemetry tick; entry.status is snapshot state read at click time, so
                    // capturing entry is safe.
                    val onToggle = remember(entry.id, manager) {
                        {
                            if (entry.status is TunnelStatus.Active) manager.deactivate(entry.id)
                            else manager.activate(entry.id)
                        }
                    }
                    val onEditRow = remember(entry.id, onEdit) { { onEdit(entry.id) } }
                    val onRemoveRow = remember(entry.id, manager) { { manager.delete(entry.id) } }
                    LiveTunnelCard(
                        entry = entry,
                        via = hosts?.find(entry.tunnel.hostId)?.label ?: entry.tunnel.hostId,
                        mono = mono,
                        onToggle = onToggle,
                        onEdit = onEditRow,
                        onRemove = onRemoveRow,
                    )
                }
            }
        }
        MobileNewTunnelButton(onClick = onNew)
        MobileDashedButton(stringResource(Res.string.ports_find_services), icon = "radar", onClick = onDiscover)
        Spacer(Modifier.height(30.dp))
    }
}

/**
 * Saved tunnel card: type badge + "via host" + on/off toggle, source→dest row. Long-press opens
 * the Edit/Remove menu (no visible edit/delete affordances otherwise).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LiveTunnelCard(
    entry: TunnelEntry,
    via: String,
    mono: FontFamily,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
) {
    var menuOpen by remember(entry.id) { mutableStateOf(false) }
    val t = entry.tunnel
    val (bg, fg) = t.direction.badgeColors()
    val dim = entry.status !is TunnelStatus.Active
    val port = (entry.status as? TunnelStatus.Active)?.boundPort ?: t.bindPort
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(D.card)
            .border(1.dp, D.cyan08, RoundedCornerShape(14.dp))
            .combinedClickable(onClick = onEdit, onLongClick = { menuOpen = true })
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Badge(t.direction.badgeLabel(), bg = bg, fg = fg, radius = 4, size = 9.5.sp)
            Txt(stringResource(Res.string.ports_via, via), color = D.dim, size = 11.sp, font = mono, modifier = Modifier.weight(1f))
            // Only a live local forward has something to open; -R listens on the server, -D is SOCKS.
            tunnelBrowserUrl(entry)?.let { url ->
                val uriHandler = LocalUriHandler.current
                Sym(
                    "open_in_new",
                    size = 18.sp,
                    color = D.cyanBright,
                    // A failing system handler must not throw into the composition.
                    modifier = Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { runCatching { uriHandler.openUri(url) } }.padding(end = 4.dp),
                )
            }
            TunnelStatusControl(entry, onToggle)
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Txt("${t.bindHost}:$port", color = if (dim) D.dim else D.text, size = 12.5.sp, font = mono)
            Sym(mobileTunnelArrow(t.direction), size = 16.sp, color = D.faint)
            Txt(mobileTunnelDest(t), color = if (dim) D.faint else D.textBright, size = 12.5.sp, font = mono)
        }
        (entry.status as? TunnelStatus.Failed)?.let {
            Spacer(Modifier.height(6.dp))
            Txt(tunnelFailureText(it), color = D.sunset, size = 11.sp, font = mono)
        }
    }
    if (menuOpen) {
        MobileActionSheet(
            title = t.label.ifBlank { stringResource(Res.string.ports_tunnel) },
            subtitle = stringResource(Res.string.ports_via, via),
            actions = listOf(
                MobileSheetAction(stringResource(Res.string.ports_edit), onClick = onEdit, icon = "edit"),
                MobileSheetAction(stringResource(Res.string.ports_remove), onClick = onRemove, icon = "delete", danger = true),
            ),
            onDismiss = { menuOpen = false },
        )
    }
}

/** Card's right-side control by status: active shows the on toggle, connecting shows an hourglass, otherwise the off toggle. */
@Composable
private fun TunnelStatusControl(entry: TunnelEntry, onToggle: () -> Unit) {
    when (entry.status) {
        is TunnelStatus.Active -> Toggle(on = true, onToggle = onToggle)
        TunnelStatus.Connecting -> Sym("hourglass_top", size = 18.sp, color = D.amber)
        else -> Toggle(on = false, onToggle = onToggle)
    }
}

/** Empty state (no tunnels yet); added via the New tunnel button below. */
@Composable
private fun MobileEmptyTunnels() {
    Box(Modifier.fillMaxWidth().padding(top = 50.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Sym("lan", size = 28.sp, color = D.faint)
            Txt(stringResource(Res.string.ports_no_tunnels_yet), color = D.text, size = 14.sp, weight = FontWeight.Medium)
            Txt(stringResource(Res.string.ports_add_tunnel_below), color = D.faint, size = 12.sp)
        }
    }
}

// Tunnel editor (sheet).

/**
 * Bottom sheet for creating/editing a tunnel, matching the New connection sheet idiom: name, type,
 * via-host (saved host picker), bind and dest. Save collects [TunnelFormState.draft] and writes
 * through [TunnelManager]; Remove is shown for an existing tunnel. Fields are seeded from [existing]
 * on open.
 */
@Composable
private fun MobileTunnelEditorSheet(
    manager: TunnelManager,
    hosts: HostManagerController?,
    mono: FontFamily,
    existing: TunnelEntry?,
    onDismiss: () -> Unit,
) {
    val editingId = existing?.id
    // Keyed by editingId: the form is an isolated edit buffer, populated once per selected tunnel.
    // Out-of-band mutations to entry.tunnel (a save elsewhere for the same id) do not propagate here.
    val form = remember(editingId) { TunnelFormState.fromEntry(existing) }

    val draft = form.draft
    val (badgeBg, badgeFg) = form.direction.badgeColors()
    val hostList = hosts?.hosts ?: emptyList()
    val hostName = form.hostId?.let { id -> hostList.firstOrNull { it.id == id }?.label } ?: stringResource(Res.string.ports_select_host)

    MobileBottomSheet(
        onDismiss = onDismiss,
        panelModifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(start = 22.dp, end = 22.dp, bottom = 30.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Badge(form.direction.badgeLabel(), bg = badgeBg, fg = badgeFg, radius = 4, size = 9.5.sp)
                    Txt(if (existing == null) stringResource(Res.string.ports_new_tunnel) else stringResource(Res.string.ports_edit_tunnel), color = D.text, size = 20.sp, weight = FontWeight.Bold)
                }
                Sym(
                    "close",
                    size = 24.sp,
                    color = D.dim,
                    modifier = Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
                )
            }
            Spacer(Modifier.height(18.dp))
            MobileFormField(stringResource(Res.string.ports_field_name)) { PortInput(form.label, { form.label = it }, stringResource(Res.string.ports_ph_web_tunnel), mono) }
            Spacer(Modifier.height(14.dp))
            MobileFormField(stringResource(Res.string.ports_field_type)) { PortTypeSelect(form.direction) { form.direction = it } }
            Spacer(Modifier.height(14.dp))
            MobileFormField(stringResource(Res.string.ports_field_via_host)) { MobileHostPicker(hostName, hostList.map { it.id to it.label }) { form.hostId = it } }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MobileFormField(stringResource(Res.string.ports_field_bind_address), Modifier.weight(1f)) { PortInput(form.bindHost, { form.bindHost = it }, "127.0.0.1", mono) }
                MobileFormField(stringResource(Res.string.ports_field_port), Modifier.width(96.dp)) { PortInput(form.bindPort, { form.bindPort = it }, "8080", mono, KeyboardType.Number) }
            }
            if (!form.isDynamic) {
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MobileFormField(stringResource(Res.string.ports_field_destination), Modifier.weight(1f)) { PortInput(form.destHost, { form.destHost = it }, "10.0.0.5", mono) }
                    MobileFormField(stringResource(Res.string.ports_field_port), Modifier.width(96.dp)) { PortInput(form.destPort, { form.destPort = it }, "80", mono, KeyboardType.Number) }
                }
            } else {
                Spacer(Modifier.height(10.dp))
                Txt(
                    stringResource(Res.string.ports_socks_hint),
                    color = D.faint,
                    size = 12.sp,
                    lineHeight = 17.sp,
                )
            }
            if (existing != null && existing.status is TunnelStatus.Active) {
                Spacer(Modifier.height(16.dp))
                MobileFormField(stringResource(Res.string.ports_field_live_throughput)) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        MobileThroughputRow("arrow_upward", D.cyanBright, rateFraction(existing.upRate), humanRate(existing.upRate), mono)
                        MobileThroughputRow("arrow_downward", D.moss, rateFraction(existing.downRate), humanRate(existing.downRate), mono)
                    }
                }
                Spacer(Modifier.height(8.dp))
                // Editing an active tunnel saves, but the forward is already up; new params apply
                // on next activation since save does not restart the connection.
                Txt(stringResource(Res.string.ports_changes_apply_after_restart), color = D.faint, size = 12.sp, lineHeight = 16.sp)
            }
            Spacer(Modifier.height(22.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (draft != null) D.cyan else D.cyan.copy(alpha = 0.4f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {
                        draft?.let { manager.save(it); onDismiss() }
                    })
                    .padding(15.dp),
                contentAlignment = Alignment.Center,
            ) {
                Txt(stringResource(Res.string.ports_save_tunnel), color = D.ink, size = 16.sp, weight = FontWeight.Bold)
            }
            if (existing != null) {
                Spacer(Modifier.height(12.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .border(1.dp, D.sunset.copy(alpha = 0.3f), RoundedCornerShape(14.dp))
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {
                            manager.delete(existing.id); onDismiss()
                        })
                        .padding(15.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Txt(stringResource(Res.string.ports_remove_tunnel), color = D.sunset, size = 16.sp, weight = FontWeight.Medium)
                }
            }
        }
}

/**
 * Bottom sheet for service discovery: pick a saved host, scan it for listening TCP ports, forward
 * one in a tap (saved and raised right away). Mirrors the desktop Services panel.
 */
@Composable
private fun MobileServicesSheet(
    manager: TunnelManager,
    hosts: HostManagerController?,
    mono: FontFamily,
    onDismiss: () -> Unit,
) {
    val scan = manager.services
    val hostList = hosts?.hosts ?: emptyList()
    var hostId by remember { mutableStateOf(scan.scannedHostId ?: hostList.firstOrNull()?.id) }
    val hostName = hostId?.let { id -> hostList.firstOrNull { it.id == id }?.label }
        ?: stringResource(Res.string.ports_select_host)

    MobileBottomSheet(
        onDismiss = onDismiss,
        panelModifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(start = 22.dp, end = 22.dp, bottom = 30.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Sym("radar", size = 22.sp, color = D.cyanBright)
                Txt(stringResource(Res.string.ports_services_title), color = D.text, size = 20.sp, weight = FontWeight.Bold)
            }
            Sym(
                "close",
                size = 24.sp,
                color = D.dim,
                modifier = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
            )
        }
        Spacer(Modifier.height(10.dp))
        Txt(stringResource(Res.string.ports_services_hint), color = D.faint, size = 12.sp, lineHeight = 17.sp)
        Spacer(Modifier.height(16.dp))
        MobileFormField(stringResource(Res.string.ports_field_via_host)) {
            MobileHostPicker(hostName, hostList.map { it.id to it.label }) { hostId = it }
        }
        Spacer(Modifier.height(14.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(if (hostId != null) D.cyan else D.cyan.copy(alpha = 0.4f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    hostId?.let { scan.scan(it) }
                }
                .padding(15.dp),
            contentAlignment = Alignment.Center,
        ) {
            Txt(stringResource(Res.string.ports_scan), color = D.ink, size = 16.sp, weight = FontWeight.Bold)
        }
        Spacer(Modifier.height(16.dp))
        when (val state = scan.state) {
            ServiceScanState.Idle -> MobileScanNote(stringResource(Res.string.ports_pick_host_to_scan), D.faint)
            ServiceScanState.Scanning -> MobileScanNote(stringResource(Res.string.ports_scanning), D.amber)
            ServiceScanState.Unsupported -> MobileScanNote(stringResource(Res.string.ports_services_unsupported), D.dim)
            is ServiceScanState.Failed -> MobileScanNote(serviceScanFailureText(state), D.sunset)
            is ServiceScanState.Ready -> {
                val scanned = scan.scannedHostId
                if (state.services.isEmpty() || scanned == null) {
                    MobileScanNote(stringResource(Res.string.ports_no_services), D.faint)
                } else {
                    val taken = forwardedPorts(manager.tunnels.map { it.tunnel }, scanned)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.services.forEach { service ->
                            MobileServiceRow(
                                service = service,
                                mono = mono,
                                forwarded = service.port in taken,
                                onForward = { label -> manager.activate(manager.save(serviceTunnelDraft(service, scanned, label))) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MobileScanNote(text: String, color: Color) {
    Txt(text, color = color, size = 13.sp, lineHeight = 18.sp)
}

/** One discovered service in the sheet: port, owning process, and the one-tap forward action. */
@Composable
private fun MobileServiceRow(service: ListeningService, mono: FontFamily, forwarded: Boolean, onForward: (String) -> Unit) {
    // Name for the tunnel when the host didn't disclose the process; localized here, since the
    // draft is built outside the composition.
    val fallback = stringResource(Res.string.ports_service_port, service.port)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(D.card)
            .border(1.dp, D.cyan08, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Txt("${service.port}", color = D.textBright, size = 14.sp, font = mono, modifier = Modifier.width(58.dp))
        Txt(serviceLabel(service), color = D.dim, size = 13.sp, modifier = Modifier.weight(1f))
        if (forwarded) {
            Txt(stringResource(Res.string.ports_already_forwarded), color = D.moss, size = 12.sp)
        } else {
            Txt(
                stringResource(Res.string.ports_forward),
                color = D.cyanBright,
                size = 13.sp,
                weight = FontWeight.SemiBold,
                modifier = Modifier
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onForward(fallback) }
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
    }
}

/** Host picker in the sheet: menu opens directly below the trigger, matching its width (via [AnchoredDropdown]). */
@Composable
private fun MobileHostPicker(current: String, options: List<Pair<String, String>>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    AnchoredDropdown(
        expanded = open,
        onDismiss = { open = false },
        trigger = {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp)).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = { open = !open }).background(D.bg).border(1.dp, D.cyan14, RoundedCornerShape(11.dp)).padding(horizontal = 14.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Txt(current, color = D.text, size = 15.sp)
                Sym(if (open) "expand_less" else "expand_more", size = 20.sp, color = D.faint)
            }
        },
        menu = { width ->
            Column(
                Modifier.width(width).clip(RoundedCornerShape(11.dp)).background(D.surface2).border(1.dp, D.cyan14, RoundedCornerShape(11.dp)).heightIn(max = 260.dp).verticalScroll(rememberScrollState()).padding(vertical = 4.dp),
            ) {
                if (options.isEmpty()) {
                    Txt(stringResource(Res.string.ports_no_saved_hosts), color = D.faint, size = 13.sp, modifier = Modifier.padding(14.dp))
                } else {
                    options.forEach { (id, name) ->
                        Txt(name, color = D.text, size = 15.sp, modifier = Modifier.fillMaxWidth().clickable { onPick(id); open = false }.padding(horizontal = 14.dp, vertical = 11.dp))
                    }
                }
            }
        },
    )
}

/** Field label + content, matching the New connection sheet's field idiom. */
@Composable
private fun PortInput(value: String, onValueChange: (String) -> Unit, placeholder: String, mono: FontFamily, keyboardType: KeyboardType = KeyboardType.Text) {
    val textStyle = remember(mono) { TextStyle(color = D.text, fontSize = 15.sp, fontFamily = mono) }
    // Border lives in decorationBox so a click anywhere in the field places the caret.
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = textStyle,
        cursorBrush = SolidColor(D.cyan),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp)).background(D.bg).border(1.dp, D.cyan14, RoundedCornerShape(11.dp)).padding(horizontal = 14.dp, vertical = 13.dp),
            ) {
                if (value.isEmpty()) Txt(placeholder, color = D.faint, size = 15.sp, font = mono)
                inner()
            }
        },
    )
}

/** Tunnel type select: `-L`/`-R`/`-D` menu opens directly below the trigger, matching its width (via [AnchoredDropdown]). */
@Composable
private fun PortTypeSelect(direction: TunnelDirection, onPick: (TunnelDirection) -> Unit) {
    var open by remember { mutableStateOf(false) }
    AnchoredDropdown(
        expanded = open,
        onDismiss = { open = false },
        trigger = {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp)).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = { open = !open }).background(D.bg).border(1.dp, D.cyan14, RoundedCornerShape(11.dp)).padding(horizontal = 14.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Txt(direction.displayLabel(), color = D.text, size = 15.sp)
                Sym(if (open) "expand_less" else "expand_more", size = 20.sp, color = D.faint)
            }
        },
        menu = { width ->
            Column(
                Modifier.width(width).clip(RoundedCornerShape(11.dp)).background(D.surface2).border(1.dp, D.cyan14, RoundedCornerShape(11.dp)).padding(vertical = 4.dp),
            ) {
                listOf(TunnelDirection.Local, TunnelDirection.Remote, TunnelDirection.Dynamic).forEach { option ->
                    Txt(
                        option.displayLabel(),
                        color = if (option == direction) D.cyanBright else D.text,
                        size = 15.sp,
                        modifier = Modifier.fillMaxWidth().clickable { onPick(option); open = false }.padding(horizontal = 14.dp, vertical = 11.dp),
                    )
                }
            }
        },
    )
}

// New tunnel button.

/** Outlined full-width action under the tunnel list (cyan border, leading icon). */
@Composable
private fun MobileDashedButton(label: String, icon: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(13.dp))
            .border(1.dp, D.cyan20, RoundedCornerShape(13.dp))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
    ) {
        Sym(icon, size = 19.sp, color = D.cyanBright)
        Txt(label, color = D.cyanBright, size = 14.sp, weight = FontWeight.Medium)
    }
}

@Composable
private fun MobileNewTunnelButton(onClick: () -> Unit) =
    MobileDashedButton(stringResource(Res.string.ports_new_tunnel), icon = "add", onClick = onClick)

/** Throughput row in the editor sheet: arrow + bar + text. */
@Composable
private fun MobileThroughputRow(icon: String, color: Color, fraction: Float, value: String, mono: FontFamily) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Sym(icon, size = 16.sp, color = color)
        MeterBar(fraction, color, Modifier.weight(1f))
        Box(Modifier.width(72.dp), contentAlignment = Alignment.CenterEnd) {
            Txt(value, color = D.dim, size = 12.sp, font = mono)
        }
    }
}

// Mock (preview/offscreen).

private data class MockTunnel(val type: String, val bg: Color, val fg: Color, val via: String, val source: String, val arrow: String, val dest: String, val destDim: Boolean, val on: Boolean)

/** Static tunnels for preview/offscreen. */
private val MOCK_TUNNELS = listOf(
    MockTunnel("LOCAL", D.cyan.copy(alpha = 0.12f), D.cyanBright, "via prod-web-01", "127.0.0.1:8080", "arrow_forward", "10.0.0.5:80", false, true),
    MockTunnel("REMOTE", D.amber.copy(alpha = 0.14f), D.amber, "via homelab-pi", "0.0.0.0:9000", "arrow_forward", "localhost:3000", false, true),
    MockTunnel("SOCKS", D.moss.copy(alpha = 0.14f), D.moss, "via db-master", "127.0.0.1:1080", "all_inclusive", "dynamic proxy", true, false),
)

@Composable
private fun MockMobilePortsBody(mono: FontFamily) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        MOCK_TUNNELS.forEach { MockTunnelCard(it, mono) }
        MobileNewTunnelButton(onClick = {})
        Spacer(Modifier.height(30.dp))
    }
}

@Composable
private fun MockTunnelCard(t: MockTunnel, mono: FontFamily) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(D.card)
            .border(1.dp, D.cyan08, RoundedCornerShape(14.dp))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Badge(t.type, bg = t.bg, fg = t.fg, radius = 4, size = 9.5.sp)
            Txt(t.via, color = D.dim, size = 11.sp, font = mono, modifier = Modifier.weight(1f))
            Toggle(on = t.on, onToggle = {})
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Txt(t.source, color = D.text, size = 12.5.sp, font = mono)
            Sym(t.arrow, size = 16.sp, color = D.faint)
            Txt(t.dest, color = if (t.destDim) D.dim else D.textBright, size = 12.5.sp, font = mono)
        }
    }
}
