package app.skerry.ui.tunnel

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.ssh.usesSshAuth
import app.skerry.shared.tunnel.TunnelDirection
import app.skerry.ui.forward.humanRate
import app.skerry.ui.forward.rateFraction
import app.skerry.ui.host.HostManagerController
import app.skerry.ui.tunnel.TunnelEntry
import app.skerry.ui.tunnel.TunnelManager
import app.skerry.ui.tunnel.TunnelStatus
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.ports_active_tunnel_one
import app.skerry.ui.generated.resources.ports_active_tunnels_other
import app.skerry.ui.generated.resources.ports_add_tunnel_right
import app.skerry.ui.generated.resources.ports_already_forwarded
import app.skerry.ui.generated.resources.ports_changes_apply_after_restart
import app.skerry.ui.generated.resources.ports_col_active
import app.skerry.ui.generated.resources.ports_col_destination
import app.skerry.ui.generated.resources.ports_col_source
import app.skerry.ui.generated.resources.ports_col_type
import app.skerry.ui.generated.resources.ports_col_via_host
import app.skerry.ui.generated.resources.ports_dynamic_proxy
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
import app.skerry.ui.generated.resources.ports_open_in_browser
import app.skerry.ui.generated.resources.ports_ph_web_tunnel
import app.skerry.ui.generated.resources.ports_pick_host_to_scan
import app.skerry.ui.generated.resources.ports_port_forwarding
import app.skerry.ui.generated.resources.ports_remove
import app.skerry.ui.generated.resources.ports_remove_active_message
import app.skerry.ui.generated.resources.ports_remove_confirm_title
import app.skerry.ui.generated.resources.ports_remove_inactive_message
import app.skerry.ui.generated.resources.ports_save
import app.skerry.ui.generated.resources.ports_saved_tunnels_subtitle
import app.skerry.ui.generated.resources.ports_scan
import app.skerry.ui.generated.resources.ports_scanning
import app.skerry.ui.generated.resources.ports_select_host
import app.skerry.ui.generated.resources.ports_service_port
import app.skerry.ui.generated.resources.ports_services_hint
import app.skerry.ui.generated.resources.ports_services_title
import app.skerry.ui.generated.resources.ports_services_unsupported
import app.skerry.ui.generated.resources.ports_socks_hint
import app.skerry.ui.generated.resources.ports_tunnel_detail
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.design.AnchoredDropdown
import app.skerry.ui.design.Badge
import app.skerry.ui.design.ConfirmActionDialog
import app.skerry.ui.design.EmptyState
import app.skerry.ui.design.GhostButton
import app.skerry.ui.design.HLine
import app.skerry.ui.design.SectionHeader
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.labelUppercase
import app.skerry.ui.app.LocalHosts
import app.skerry.ui.app.LocalTunnels
import app.skerry.ui.design.MeterBar
import app.skerry.ui.design.PrimaryButton
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Toggle
import app.skerry.ui.design.Txt
import app.skerry.ui.design.VLine
import app.skerry.ui.theme.Skerry

private data class TunnelRule(
    val type: String,
    val source: String, val arrow: String, val dest: String, val destDim: Boolean,
    val via: String, val active: Boolean,
)

private val TUNNELS = listOf(
    TunnelRule("LOCAL", "127.0.0.1:8080", "arrow_forward", "10.0.0.5:80", false, "prod-web-01", true),
    TunnelRule("REMOTE", "0.0.0.0:9000", "arrow_forward", "localhost:3000", false, "homelab-pi", true),
    TunnelRule("SOCKS", "127.0.0.1:1080", "all_inclusive", "dynamic proxy", true, "db-master", false),
)

/** Badge colors (bg, fg) for a tunnel type chip, from the active theme. */
@Composable
private fun tunnelTypeColors(type: String): Pair<Color, Color> = when (type) {
    "LOCAL" -> Skerry.colors.cyan.copy(alpha = 0.12f) to Skerry.colors.cyanBright
    "REMOTE" -> Skerry.colors.amberSoft to Skerry.colors.amber
    else -> Skerry.colors.moss.copy(alpha = 0.14f) to Skerry.colors.moss
}

/**
 * Port forwarding (Tunnels) — global section: list of saved tunnels with on/off toggles plus an
 * editor on the right. A tunnel is standalone (references a host by id) and opens its own SSH
 * connection via [TunnelManager] on activation. When a manager is supplied ([LocalTunnels]) shows
 * the live list; otherwise (offscreen render/preview) shows a static mock ([TUNNELS]).
 */
@Composable
fun TunnelsView() {
    val mono = LocalFonts.current.mono
    val manager = LocalTunnels.current
    val hosts = LocalHosts.current

    // Right-panel state: selected tunnel, "create new" mode (New tunnel button), and service
    // discovery, which takes over the same panel.
    var selectedId by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    // Discovery survives leaving and re-entering the section: the panel reopens on whatever the
    // scan still holds, and only closing it (which resets the scan) puts the editor back.
    var discovering by remember { mutableStateOf(manager?.services?.state != ServiceScanState.Idle) }

    Column(Modifier.fillMaxSize().background(Skerry.colors.bg)) {
        SectionHeader(
            title = stringResource(Res.string.ports_port_forwarding),
            subtitle = stringResource(Res.string.ports_saved_tunnels_subtitle),
            actions = {
                GhostButton(stringResource(Res.string.ports_find_services), onClick = { discovering = true; adding = false; selectedId = null }, icon = "radar")
                PrimaryButton(
                    stringResource(Res.string.ports_new_tunnel),
                    onClick = { adding = true; selectedId = null; discovering = false },
                    icon = "add",
                )
            },
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (manager == null) {
                MockTunnelsBody()
            } else {
                GlobalTunnelsBody(
                    manager = manager,
                    hosts = hosts,
                    mono = mono,
                    adding = adding,
                    discovering = discovering,
                    selectedId = selectedId,
                    onSelect = { selectedId = it; adding = false; discovering = false },
                    onCloseDiscovery = { discovering = false; manager.services.reset() },
                    onCloseEditor = { adding = false; selectedId = null; discovering = false },
                    // After deletion, returns to "New tunnel" mode instead of jumping to an
                    // arbitrary remaining tunnel: selectedId still holds the removed id, and
                    // without resetting it, selected would resolve via firstOrNull().
                    onNew = { selectedId = null; adding = true; discovering = false },
                )
            }
        }
    }
}

// Live path: global list of saved tunnels plus an editor on the right.

@Composable
private fun GlobalTunnelsBody(
    manager: TunnelManager,
    hosts: HostManagerController?,
    mono: FontFamily,
    adding: Boolean,
    discovering: Boolean,
    selectedId: String?,
    onSelect: (String) -> Unit,
    onCloseDiscovery: () -> Unit,
    onCloseEditor: () -> Unit,
    onNew: () -> Unit,
) {
    val tunnels = manager.tunnels
    val activeCount = tunnels.count { it.status is TunnelStatus.Active }
    // No auto-selection: the right panel stays closed until the user opens it (New tunnel / a row /
    // Find services). `selected` is null unless a real tunnel is being edited.
    val selected = tunnels.firstOrNull { it.id == selectedId }
    // Editor slides in for a new tunnel (adding) or when editing a selected one; never together with
    // the services panel (they share the right column).
    val editorVisible = !discovering && (adding || selected != null)

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
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 18.dp)) {
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).border(1.dp, Skerry.colors.cyan08, RoundedCornerShape(10.dp))) {
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
                            TunnelRowGlobal(
                                entry = entry,
                                via = hostLabel(entry.tunnel.hostId),
                                mono = mono,
                                selected = entry.id == selectedId,
                                onSelect = onRowSelect,
                                onToggle = onRowToggle,
                            )
                        }
                    }
                    Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Sym("bolt", size = 15.sp, color = Skerry.colors.moss)
                        Txt(
                            if (activeCount == 1) stringResource(Res.string.ports_active_tunnel_one, activeCount)
                            else stringResource(Res.string.ports_active_tunnels_other, activeCount),
                            color = Skerry.colors.faint, size = 11.5.sp,
                        )
                    }
                }
            }
            // Right column: editor and services share ONE slide-in slot (not two sibling
            // AnimatedVisibility blocks — those each reserved width in the Row, so switching
            // editor↔services briefly expanded both at once and jolted the list width). A single
            // slot expands from the right edge (the list reflows to fill the freed width);
            // clipToBounds keeps the 308dp content from painting outside the animating slot.
            //
            // `shown*` latch what the panel is displaying and only update while it's open, so the
            // outgoing content survives the slide-out unchanged — otherwise the exit would recompose
            // against the just-cleared state and flash (a blank "New tunnel" form on editor close, or
            // the editor on services close).
            val panelVisible = editorVisible || discovering
            var shownServices by remember { mutableStateOf(discovering) }
            var shownEntry by remember { mutableStateOf<TunnelEntry?>(null) }
            var shownAdding by remember { mutableStateOf(false) }
            if (panelVisible) {
                shownServices = discovering
                if (!discovering) { shownEntry = selected; shownAdding = adding }
            }
            AnimatedVisibility(
                visible = panelVisible,
                enter = expandHorizontally(expandFrom = Alignment.End) + fadeIn(),
                exit = shrinkHorizontally(shrinkTowards = Alignment.End) + fadeOut(),
            ) {
                Row(Modifier.clipToBounds()) {
                    VLine(Skerry.colors.line)
                    if (shownServices) {
                        ServicesPanel(manager = manager, hosts = hosts, mono = mono, onClose = onCloseDiscovery)
                    } else {
                        TunnelEditor(
                            manager = manager,
                            hosts = hosts,
                            mono = mono,
                            existing = if (shownAdding) null else shownEntry,
                            onSaved = { onSelect(it) },
                            onRequestRemove = { shownEntry?.let { pendingRemove = it } },
                            onClose = onCloseEditor,
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

@Composable
private fun TunnelRowGlobal(
    entry: TunnelEntry,
    via: String,
    mono: FontFamily,
    selected: Boolean,
    onSelect: () -> Unit,
    onToggle: () -> Unit,
) {
    val t = entry.tunnel
    val (bg, fg) = t.direction.badgeColors()
    val arrow = if (t.direction == TunnelDirection.Dynamic) "all_inclusive" else "arrow_forward"
    val dest = if (t.direction == TunnelDirection.Dynamic) null else "${t.destHost}:${t.destPort}"
    val dim = entry.status !is TunnelStatus.Active
    Column(Modifier.fillMaxWidth().clickable(onClick = onSelect).background(if (selected) Skerry.colors.cyan08 else Color.Transparent)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(Modifier.width(76.dp)) {
                Badge(t.direction.badgeLabel(), bg = bg, fg = fg, radius = 4, size = 10.sp)
            }
            Txt(sourceText(entry), color = if (dim) Skerry.colors.dim else Skerry.colors.textBright, size = 12.5.sp, font = mono, modifier = Modifier.weight(1f))
            Box(Modifier.width(20.dp)) { Sym(arrow, size = 16.sp, color = Skerry.colors.faint) }
            Txt(dest ?: stringResource(Res.string.ports_dynamic_proxy), color = if (dest == null || dim) Skerry.colors.dim else Skerry.colors.textBright, size = 12.5.sp, font = mono, modifier = Modifier.weight(1f))
            Txt(via, color = Skerry.colors.dim, size = 11.5.sp, font = mono, modifier = Modifier.width(110.dp))
            Row(Modifier.width(84.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                OpenInBrowserAction(entry)
                ActiveCellGlobal(entry, onToggle)
            }
        }
        (entry.status as? TunnelStatus.Failed)?.let {
            Txt(tunnelFailureText(it), color = Skerry.colors.sunset, size = 11.sp, font = mono, modifier = Modifier.padding(start = 16.dp, bottom = 10.dp))
        }
    }
}

/** Source: bind address and listener port (actual boundPort when active, otherwise the requested one). */
private fun sourceText(entry: TunnelEntry): String {
    val port = (entry.status as? TunnelStatus.Active)?.boundPort ?: entry.tunnel.bindPort
    return "${entry.tunnel.bindHost}:$port"
}

/**
 * Opens a live local forward in the browser. Only shown when there is something to open: `-R`
 * listens on the server and `-D` is a SOCKS proxy, so neither gets a link.
 */
@Composable
private fun OpenInBrowserAction(entry: TunnelEntry) {
    val url = tunnelBrowserUrl(entry) ?: return
    val uriHandler = LocalUriHandler.current
    Sym(
        "open_in_new",
        size = 15.sp,
        color = Skerry.colors.cyanBright,
        // A failing system handler must not throw into the composition (see AboutSection).
        modifier = Modifier.clickable { runCatching { uriHandler.openUri(url) } },
    )
}

/** ACTIVE cell: on toggle when active, hourglass while connecting, otherwise off toggle (activate/retry). */
@Composable
private fun ActiveCellGlobal(entry: TunnelEntry, onToggle: () -> Unit) {
    when (entry.status) {
        is TunnelStatus.Active -> Toggle(on = true, onToggle = onToggle)
        TunnelStatus.Connecting -> Sym("hourglass_top", size = 16.sp, color = Skerry.colors.amber)
        else -> Toggle(on = false, onToggle = onToggle)
    }
}

/**
 * Service discovery panel: pick a saved host, scan it for listening TCP ports, and forward one in a
 * tap (a local forward is created and activated right away). Occupies the editor's column — the two
 * are alternative uses of the same panel, never both at once.
 */
@Composable
private fun ServicesPanel(
    manager: TunnelManager,
    hosts: HostManagerController?,
    mono: FontFamily,
    onClose: () -> Unit,
) {
    val scan = manager.services
    // Only SSH-authenticated hosts can be scanned: the scan is an SSH exec round-trip, so Telnet/
    // Serial/VNC profiles (no command channel) are excluded from the picker rather than offered and
    // then rejected as Unsupported. MOSH qualifies — it dials the same SSH hop.
    val hostList = hosts?.hosts?.filter { it.connectionType.usesSshAuth } ?: emptyList()
    var hostId by remember { mutableStateOf(scan.scannedHostId ?: hostList.firstOrNull()?.id) }
    val hostLabel = hostId?.let { id -> hostList.firstOrNull { it.id == id }?.label }
        ?: stringResource(Res.string.ports_select_host)

    Column(
        Modifier.width(308.dp).fillMaxHeight().background(Skerry.colors.surface2).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 18.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Sym("radar", size = 16.sp, color = Skerry.colors.cyanBright)
                Txt(stringResource(Res.string.ports_services_title), color = Skerry.colors.text, size = 13.sp, weight = FontWeight.SemiBold)
            }
            Sym("close", size = 16.sp, color = Skerry.colors.faint, modifier = Modifier.clickable(onClick = onClose))
        }
        Txt(stringResource(Res.string.ports_services_hint), color = Skerry.colors.faint, size = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(bottom = 14.dp))
        FieldLabel(stringResource(Res.string.ports_field_via_host))
        HostPicker(hostLabel, hostList.map { it.id to it.label }, onPick = { hostId = it })
        Box(Modifier.padding(bottom = 12.dp))
        PrimaryButton(
            label = stringResource(Res.string.ports_scan),
            onClick = { hostId?.let { scan.scan(it) } },
            modifier = Modifier.fillMaxWidth(),
            icon = "radar",
            enabled = hostId != null,
        )
        Box(Modifier.padding(bottom = 14.dp))
        when (val state = scan.state) {
            ServiceScanState.Idle -> ScanNote(stringResource(Res.string.ports_pick_host_to_scan), Skerry.colors.faint)
            ServiceScanState.Scanning -> ScanNote(stringResource(Res.string.ports_scanning), Skerry.colors.amber)
            ServiceScanState.Unsupported -> ScanNote(stringResource(Res.string.ports_services_unsupported), Skerry.colors.dim)
            is ServiceScanState.Failed -> ScanNote(serviceScanFailureText(state), Skerry.colors.sunset)
            is ServiceScanState.Ready -> {
                val scanned = scan.scannedHostId
                if (state.services.isEmpty() || scanned == null) {
                    ScanNote(stringResource(Res.string.ports_no_services), Skerry.colors.faint)
                } else {
                    val taken = forwardedPorts(manager.tunnels.map { it.tunnel }, scanned)
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        state.services.forEach { service ->
                            ServiceRow(
                                service = service,
                                mono = mono,
                                forwarded = service.port in taken,
                                // Saved and raised in one go — the point of the panel is not to
                                // land in the editor with fields pre-filled.
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
private fun ScanNote(text: String, color: Color) {
    Txt(text, color = color, size = 11.5.sp, lineHeight = 16.sp)
}

/** One discovered service: port, owning process, and the one-tap forward action. */
@Composable
private fun ServiceRow(service: ListeningService, mono: FontFamily, forwarded: Boolean, onForward: (String) -> Unit) {
    // Name for the tunnel when the host didn't disclose the process; localized here, since the
    // draft is built outside the composition.
    val fallback = stringResource(Res.string.ports_service_port, service.port)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).background(Skerry.colors.bg).border(1.dp, Skerry.colors.cyan08, RoundedCornerShape(7.dp)).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Txt("${service.port}", color = Skerry.colors.textBright, size = 12.5.sp, font = mono, modifier = Modifier.width(46.dp))
        Txt(serviceLabel(service), color = Skerry.colors.dim, size = 11.5.sp, modifier = Modifier.weight(1f))
        if (forwarded) {
            Txt(stringResource(Res.string.ports_already_forwarded), color = Skerry.colors.moss, size = 10.5.sp)
        } else {
            Txt(
                stringResource(Res.string.ports_forward),
                color = Skerry.colors.cyanBright,
                size = 10.5.sp,
                weight = FontWeight.SemiBold,
                modifier = Modifier.clickable { onForward(fallback) }.padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
    }
}

/**
 * Tunnel editor (create/edit): name, type, via-host (host dropdown), bind and dest. Save builds
 * [TunnelFormState.draft] and writes it via [TunnelManager]; for an existing tunnel, shows Remove
 * and live throughput (when active). Fields reset when [existing] changes (via `key`).
 */
@Composable
private fun TunnelEditor(
    manager: TunnelManager,
    hosts: HostManagerController?,
    mono: FontFamily,
    existing: TunnelEntry?,
    onSaved: (String) -> Unit,
    onRequestRemove: () -> Unit,
    onClose: () -> Unit,
) {
    val editingId = existing?.id
    // Keyed by editingId: the form is an isolated edit buffer, populated once per selected
    // tunnel. Mutations to entry.tunnel from elsewhere (save for the same id) intentionally
    // don't propagate here — unfinished user edits take priority.
    val form = remember(editingId) { TunnelFormState.fromEntry(existing) }

    val draft = form.draft
    val (badgeBg, badgeFg) = form.direction.badgeColors()
    val hostList = hosts?.hosts ?: emptyList()
    val hostLabel = form.hostId?.let { id -> hostList.firstOrNull { it.id == id }?.label } ?: stringResource(Res.string.ports_select_host)

    Column(
        Modifier.width(308.dp).fillMaxHeight().background(Skerry.colors.surface2).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 18.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Badge(form.direction.badgeLabel(), bg = badgeBg, fg = badgeFg, radius = 4, size = 10.sp)
                Txt(if (existing == null) stringResource(Res.string.ports_new_tunnel) else stringResource(Res.string.ports_tunnel_detail), color = Skerry.colors.text, size = 13.sp, weight = FontWeight.SemiBold)
            }
            Sym("close", size = 16.sp, color = Skerry.colors.faint, modifier = Modifier.clickable(onClick = onClose))
        }
        FieldLabel(stringResource(Res.string.ports_field_name))
        EditField(form.label, { form.label = it }, stringResource(Res.string.ports_ph_web_tunnel), mono)
        Box(Modifier.padding(bottom = 12.dp))
        FieldLabel(stringResource(Res.string.ports_field_type))
        TypePicker(form.direction, onPick = { form.direction = it })
        Box(Modifier.padding(bottom = 12.dp))
        FieldLabel(stringResource(Res.string.ports_field_via_host))
        HostPicker(hostLabel, hostList.map { it.id to it.label }, onPick = { form.hostId = it })
        Box(Modifier.padding(bottom = 12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) { FieldLabel(stringResource(Res.string.ports_field_bind_address)); EditField(form.bindHost, { form.bindHost = it }, "127.0.0.1", mono) }
            Column(Modifier.width(70.dp)) { FieldLabel(stringResource(Res.string.ports_field_port)); EditField(form.bindPort, { form.bindPort = it }, "0", mono, KeyboardType.Number) }
        }
        if (!form.isDynamic) {
            Box(Modifier.padding(bottom = 12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) { FieldLabel(stringResource(Res.string.ports_field_destination)); EditField(form.destHost, { form.destHost = it }, "10.0.0.5", mono) }
                Column(Modifier.width(70.dp)) { FieldLabel(stringResource(Res.string.ports_field_port)); EditField(form.destPort, { form.destPort = it }, "80", mono, KeyboardType.Number) }
            }
        } else {
            Box(Modifier.padding(bottom = 4.dp))
            Txt(stringResource(Res.string.ports_socks_hint), color = Skerry.colors.faint, size = 11.sp, lineHeight = 15.sp)
        }
        if (existing != null && existing.status is TunnelStatus.Active) {
            tunnelBrowserUrl(existing)?.let { url ->
                val uriHandler = LocalUriHandler.current
                Box(Modifier.padding(bottom = 14.dp))
                GhostButton(
                    stringResource(Res.string.ports_open_in_browser),
                    onClick = { runCatching { uriHandler.openUri(url) } },
                    icon = "open_in_new",
                    fg = Skerry.colors.cyanBright,
                    border = Skerry.colors.cyan20,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Box(Modifier.padding(bottom = 16.dp))
            FieldLabel(stringResource(Res.string.ports_field_live_throughput))
            ThroughputRow("arrow_upward", Skerry.colors.cyanBright, rateFraction(existing.upRate), humanRate(existing.upRate), mono)
            Box(Modifier.padding(bottom = 8.dp))
            ThroughputRow("arrow_downward", Skerry.colors.moss, rateFraction(existing.downRate), humanRate(existing.downRate), mono)
            Box(Modifier.padding(bottom = 10.dp))
            // Editing an active tunnel saves fine, but the forward is already up — new
            // parameters take effect on the next activation (save doesn't restart the connection).
            Txt(stringResource(Res.string.ports_changes_apply_after_restart), color = Skerry.colors.faint, size = 11.sp, lineHeight = 15.sp)
        }
        Box(Modifier.padding(bottom = 18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton(
                label = stringResource(Res.string.ports_save),
                onClick = { draft?.let { onSaved(manager.save(it)) } },
                modifier = Modifier.weight(1f),
                enabled = draft != null,
            )
            if (existing != null) {
                GhostButton(stringResource(Res.string.ports_remove), onClick = onRequestRemove, fg = Skerry.colors.sunset, border = Skerry.colors.sunset.copy(alpha = 0.3f), modifier = Modifier.weight(1f))
            }
        }
    }
}

/** Tunnel-type dropdown (-L/-R/-D) over the form (via [AnchoredDropdown]). */
@Composable
private fun TypePicker(current: TunnelDirection, onPick: (TunnelDirection) -> Unit) {
    var open by remember { mutableStateOf(false) }
    AnchoredDropdown(
        expanded = open,
        onDismiss = { open = false },
        trigger = {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable { open = !open }.background(Skerry.colors.bg).border(1.dp, Skerry.colors.cyan14, RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Txt(current.displayLabel(), color = Skerry.colors.text, size = 12.5.sp)
                Sym("expand_more", size = 16.sp, color = Skerry.colors.faint)
            }
        },
        menu = { width ->
            Column(
                Modifier.width(width).clip(RoundedCornerShape(8.dp)).background(Skerry.colors.surface2).border(1.dp, Skerry.colors.cyan14, RoundedCornerShape(8.dp)),
            ) {
                listOf(TunnelDirection.Local, TunnelDirection.Remote, TunnelDirection.Dynamic).forEach { option ->
                    Txt(
                        option.displayLabel(),
                        color = if (option == current) Skerry.colors.cyanBright else Skerry.colors.text,
                        size = 12.5.sp,
                        modifier = Modifier.fillMaxWidth().clickable { onPick(option); open = false }.padding(horizontal = 12.dp, vertical = 9.dp),
                    )
                }
            }
        },
    )
}

/** Host dropdown over the form (via [AnchoredDropdown]); empty shows a hint to add a host. */
@Composable
private fun HostPicker(current: String, options: List<Pair<String, String>>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    AnchoredDropdown(
        expanded = open,
        onDismiss = { open = false },
        trigger = {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable { open = !open }.background(Skerry.colors.bg).border(1.dp, Skerry.colors.cyan14, RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Txt(current, color = Skerry.colors.text, size = 12.5.sp)
                Sym("expand_more", size = 16.sp, color = Skerry.colors.faint)
            }
        },
        menu = { width ->
            Column(
                Modifier.width(width).clip(RoundedCornerShape(8.dp)).background(Skerry.colors.surface2).border(1.dp, Skerry.colors.cyan14, RoundedCornerShape(8.dp)).heightIn(max = 240.dp).verticalScroll(rememberScrollState()),
            ) {
                if (options.isEmpty()) {
                    Txt(stringResource(Res.string.ports_no_saved_hosts), color = Skerry.colors.faint, size = 12.sp, modifier = Modifier.padding(12.dp))
                } else {
                    options.forEach { (id, name) ->
                        Txt(
                            name, color = Skerry.colors.text, size = 12.5.sp,
                            modifier = Modifier.fillMaxWidth().clickable { onPick(id); open = false }.padding(horizontal = 12.dp, vertical = 9.dp),
                        )
                    }
                }
            }
        },
    )
}

// Mock path (offscreen render/preview): static table plus detail form.

@Composable
private fun MockTunnelsBody() {
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 18.dp)) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).border(1.dp, Skerry.colors.cyan08, RoundedCornerShape(10.dp))) {
                TunnelHeaderRow()
                TUNNELS.forEach { rule ->
                    HLine()
                    TunnelRow(rule)
                }
            }
            Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Sym("bolt", size = 15.sp, color = Skerry.colors.moss)
                Txt("2 active tunnels", color = Skerry.colors.faint, size = 11.5.sp)
            }
        }
        VLine(Skerry.colors.line)
        TunnelDetail()
    }
}

@Composable
private fun TunnelHeaderRow() {
    Row(
        Modifier.fillMaxWidth().background(Skerry.colors.overlayFaint).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        HeaderCell(stringResource(Res.string.ports_col_type), Modifier.width(76.dp))
        HeaderCell(stringResource(Res.string.ports_col_source), Modifier.weight(1f))
        Box(Modifier.width(20.dp))
        HeaderCell(stringResource(Res.string.ports_col_destination), Modifier.weight(1f))
        HeaderCell(stringResource(Res.string.ports_col_via_host), Modifier.width(110.dp))
        HeaderCell(stringResource(Res.string.ports_col_active), Modifier.width(84.dp), end = true)
    }
}

@Composable
private fun HeaderCell(text: String, modifier: Modifier = Modifier, end: Boolean = false) {
    Box(modifier, contentAlignment = if (end) Alignment.CenterEnd else Alignment.CenterStart) {
        Txt(text, color = Skerry.colors.faint, size = 10.sp, weight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
    }
}

@Composable
private fun TunnelRow(rule: TunnelRule) {
    val mono = LocalFonts.current.mono
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.width(76.dp)) {
            val (typeBg, typeFg) = tunnelTypeColors(rule.type)
            Badge(rule.type, bg = typeBg, fg = typeFg, radius = 4, size = 10.sp)
        }
        Txt(rule.source, color = Skerry.colors.textBright, size = 12.5.sp, font = mono, modifier = Modifier.weight(1f))
        Box(Modifier.width(20.dp)) { Sym(rule.arrow, size = 16.sp, color = Skerry.colors.faint) }
        Txt(rule.dest, color = if (rule.destDim) Skerry.colors.dim else Skerry.colors.textBright, size = 12.5.sp, font = mono, modifier = Modifier.weight(1f))
        Txt(rule.via, color = Skerry.colors.dim, size = 11.5.sp, font = mono, modifier = Modifier.width(110.dp))
        Box(Modifier.width(84.dp), contentAlignment = Alignment.CenterEnd) {
            Toggle(rule.active, onToggle = {})
        }
    }
}

@Composable
private fun TunnelDetail() {
    val mono = LocalFonts.current.mono
    Column(
        Modifier.width(308.dp).fillMaxHeight().background(Skerry.colors.surface2).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 18.dp),
    ) {
        Row(Modifier.padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Badge("LOCAL", bg = Skerry.colors.cyan.copy(alpha = 0.12f), fg = Skerry.colors.cyanBright, radius = 4, size = 10.sp)
            Txt("Tunnel detail", color = Skerry.colors.text, size = 13.sp, weight = FontWeight.SemiBold)
        }
        FieldLabel("Name")
        InputField("web tunnel", mono)
        Box(Modifier.padding(bottom = 12.dp))
        FieldLabel("Type")
        SelectField("Local forward")
        Box(Modifier.padding(bottom = 12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) { FieldLabel("Bind address"); InputField("127.0.0.1", mono) }
            Column(Modifier.width(70.dp)) { FieldLabel("Port"); InputField("8080", mono) }
        }
        Box(Modifier.padding(bottom = 12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) { FieldLabel("Destination"); InputField("10.0.0.5", mono) }
            Column(Modifier.width(70.dp)) { FieldLabel("Port"); InputField("80", mono) }
        }
        Box(Modifier.padding(bottom = 16.dp))
        FieldLabel("Live throughput")
        ThroughputRow("arrow_upward", Skerry.colors.cyanBright, 0.38f, "42 KB/s", mono)
        Box(Modifier.padding(bottom = 8.dp))
        ThroughputRow("arrow_downward", Skerry.colors.moss, 0.71f, "1.1 MB/s", mono)
        Box(Modifier.padding(bottom = 18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton("Save", onClick = {}, modifier = Modifier.weight(1f))
            GhostButton("Remove", onClick = {}, fg = Skerry.colors.sunset, border = Skerry.colors.sunset.copy(alpha = 0.3f), modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Txt(labelUppercase(text), color = Skerry.colors.faint, size = 10.sp, weight = FontWeight.SemiBold, letterSpacing = 0.5.sp, modifier = Modifier.padding(bottom = 5.dp))
}

@Composable
private fun SelectField(value: String) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Skerry.colors.bg).border(1.dp, Skerry.colors.cyan14, RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Txt(value, color = Skerry.colors.text, size = 12.5.sp)
        Sym("expand_more", size = 16.sp, color = Skerry.colors.faint)
    }
}

@Composable
private fun InputField(value: String, mono: FontFamily) {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Skerry.colors.bg).border(1.dp, Skerry.colors.cyan14, RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Txt(value, color = Skerry.colors.text, size = 12.5.sp, font = mono)
    }
}

/** Editable tunnel form field ([InputField] style plus placeholder and input). */
@Composable
private fun EditField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    mono: FontFamily,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    val textColor = Skerry.colors.text
    val textStyle = remember(mono, textColor) { TextStyle(color = textColor, fontSize = 12.5.sp, fontFamily = mono) }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = textStyle,
        cursorBrush = SolidColor(Skerry.colors.cyan),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Skerry.colors.bg).border(1.dp, Skerry.colors.cyan14, RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                if (value.isEmpty()) Txt(placeholder, color = Skerry.colors.faint, size = 12.5.sp, font = mono)
                inner()
            }
        },
    )
}

@Composable
private fun ThroughputRow(icon: String, color: Color, fraction: Float, value: String, mono: FontFamily) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Sym(icon, size = 14.sp, color = color)
        MeterBar(fraction, color, Modifier.weight(1f))
        Box(Modifier.width(64.dp), contentAlignment = Alignment.CenterEnd) {
            Txt(value, color = Skerry.colors.dim, size = 11.sp, font = mono)
        }
    }
}
