package app.skerry.ui.tunnel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.ssh.usesSshAuth
import app.skerry.ui.design.Badge
import app.skerry.ui.design.FieldLabel
import app.skerry.ui.design.GhostButton
import app.skerry.ui.design.PrimaryButton
import app.skerry.ui.design.Sym
import app.skerry.ui.design.ToggleRow
import app.skerry.ui.design.labelUppercase
import app.skerry.ui.design.Txt
import app.skerry.ui.forward.humanRate
import app.skerry.ui.forward.rateFraction
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.shell_tip_close
import app.skerry.ui.generated.resources.ports_already_forwarded
import app.skerry.ui.generated.resources.ports_autostart
import app.skerry.ui.generated.resources.ports_autostart_hint
import app.skerry.ui.generated.resources.ports_autostart_switch
import app.skerry.ui.generated.resources.ports_changes_apply_after_restart
import app.skerry.ui.generated.resources.ports_field_autostart
import app.skerry.ui.generated.resources.ports_field_bind_address
import app.skerry.ui.generated.resources.ports_field_destination
import app.skerry.ui.generated.resources.ports_field_live_throughput
import app.skerry.ui.generated.resources.ports_field_name
import app.skerry.ui.generated.resources.ports_field_port
import app.skerry.ui.generated.resources.ports_field_type
import app.skerry.ui.generated.resources.ports_field_via_host
import app.skerry.ui.generated.resources.ports_forward
import app.skerry.ui.generated.resources.ports_new_tunnel
import app.skerry.ui.generated.resources.ports_no_services
import app.skerry.ui.generated.resources.ports_no_tunnels_yet
import app.skerry.ui.generated.resources.ports_open_in_browser
import app.skerry.ui.generated.resources.ports_ph_web_tunnel
import app.skerry.ui.generated.resources.ports_pick_host_to_scan
import app.skerry.ui.generated.resources.ports_remove
import app.skerry.ui.generated.resources.ports_save
import app.skerry.ui.generated.resources.ports_scan
import app.skerry.ui.generated.resources.ports_scanning
import app.skerry.ui.generated.resources.ports_select_host
import app.skerry.ui.generated.resources.ports_service_port
import app.skerry.ui.generated.resources.ports_services_hint
import app.skerry.ui.generated.resources.ports_services_title
import app.skerry.ui.generated.resources.ports_services_unsupported
import app.skerry.ui.generated.resources.ports_socks_hint
import app.skerry.ui.generated.resources.ports_tunnel_detail
import app.skerry.ui.host.HostManagerController
import app.skerry.ui.theme.Skerry
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.design.FormField
import androidx.compose.ui.platform.testTag
import app.skerry.ui.app.UiTags

/**
 * The three things the right-hand column of the tunnel section can hold: the create/edit form,
 * service discovery, and the autostart list. They are alternatives, never shown together — the
 * column is one slot (see `TunnelsView`).
 */

private val PANEL_WIDTH = 308.dp

/**
 * Tunnel editor (create/edit): name, type, via-host, bind and destination, plus the autostart
 * switch. Save builds [TunnelFormState.draft] and writes it via [TunnelManager]; for an existing
 * tunnel it also shows Remove and live throughput. Fields reset when [existing] changes.
 */
@Composable
internal fun TunnelEditor(
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
    // The tunnel dials this host over SSH, so remote desktops are not candidates.
    val hostList = hosts?.hosts?.filter { it.connectionType.usesSshAuth } ?: emptyList()
    val hostLabel = form.hostId?.let { id -> hostList.firstOrNull { it.id == id }?.label } ?: stringResource(Res.string.ports_select_host)

    SidePanel {
        PanelHeader(
            title = if (existing == null) stringResource(Res.string.ports_new_tunnel) else stringResource(Res.string.ports_tunnel_detail),
            onClose = onClose,
            leading = { Badge(form.direction.badgeLabel(), bg = badgeBg, fg = badgeFg, radius = 4, size = 10.sp) },
        )
        Box(Modifier.padding(bottom = 10.dp))
        FormField(stringResource(Res.string.ports_field_name), top = 0.dp) {
            EditField(form.label, { form.label = it }, stringResource(Res.string.ports_ph_web_tunnel), mono)
        }
        Box(Modifier.padding(bottom = 12.dp))
        FormField(stringResource(Res.string.ports_field_type), top = 0.dp) {
            TypePicker(form.direction, onPick = { form.direction = it })
        }
        Box(Modifier.padding(bottom = 12.dp))
        FormField(stringResource(Res.string.ports_field_via_host), top = 0.dp) {
            HostPicker(hostLabel, hostList.map { it.id to it.label }, onPick = { form.hostId = it })
        }
        Box(Modifier.padding(bottom = 12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                FormField(stringResource(Res.string.ports_field_bind_address), top = 0.dp) {
                    EditField(form.bindHost, { form.bindHost = it }, TunnelFormState.DEFAULT_BIND_HOST, mono, selectAllOnFocus = form.isDefaultBindHost)
                }
            }
            Column(Modifier.width(70.dp)) {
                FormField(stringResource(Res.string.ports_field_port), top = 0.dp) {
                    EditField(form.bindPort, { form.bindPort = it }, "0", mono, KeyboardType.Number)
                }
            }
        }
        BindExposureWarning(form.bindHost)
        if (!form.isDynamic) {
            Box(Modifier.padding(bottom = 12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    FormField(stringResource(Res.string.ports_field_destination), top = 0.dp) {
                        EditField(form.destHost, { form.destHost = it }, "10.0.0.5", mono)
                    }
                }
                Column(Modifier.width(70.dp)) {
                    FormField(stringResource(Res.string.ports_field_port), top = 0.dp) {
                        EditField(form.destPort, { form.destPort = it }, "80", mono, KeyboardType.Number)
                    }
                }
            }
        } else {
            Box(Modifier.padding(bottom = 4.dp))
            Txt(stringResource(Res.string.ports_socks_hint), color = Skerry.colors.faint, size = 11.sp, lineHeight = 15.sp)
        }
        Box(Modifier.padding(bottom = 14.dp))
        ToggleRow(
            label = stringResource(Res.string.ports_field_autostart),
            on = form.autostart,
            onToggle = { form.autostart = !form.autostart },
        )
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
            FieldLabel(labelUppercase(stringResource(Res.string.ports_field_live_throughput)), top = 0.dp)
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
                modifier = Modifier.weight(1f).testTag(UiTags.FORM_SAVE),
                enabled = draft != null,
            )
            if (existing != null) {
                GhostButton(
                    stringResource(Res.string.ports_remove),
                    onClick = onRequestRemove,
                    fg = Skerry.colors.sunset,
                    border = Skerry.colors.sunset.copy(alpha = 0.3f),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * Autostart list: every saved tunnel with the switch that decides whether it comes up on its own
 * after unlock. A per-tunnel field also lives in the editor; this panel exists because the flag is
 * a property of the set — the question "what starts by itself" is not answerable one form at a time.
 */
@Composable
internal fun AutostartPanel(manager: TunnelManager, hostLabel: (String) -> String, onClose: () -> Unit) {
    SidePanel {
        PanelHeader(
            title = stringResource(Res.string.ports_autostart),
            onClose = onClose,
            leading = { Sym("bolt", size = 16.sp, color = Skerry.colors.cyanBright) },
        )
        Txt(
            stringResource(Res.string.ports_autostart_hint),
            color = Skerry.colors.faint,
            size = 11.sp,
            lineHeight = 15.sp,
            modifier = Modifier.padding(bottom = 14.dp),
        )
        if (manager.tunnels.isEmpty()) {
            Txt(stringResource(Res.string.ports_no_tunnels_yet), color = Skerry.colors.faint, size = 11.5.sp)
            return@SidePanel
        }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            manager.tunnels.forEach { entry ->
                // Stabilized by id, as the table rows are: the panel sits next to a list that
                // repaints on every telemetry tick.
                val onToggle = remember(entry.id, manager) {
                    { manager.save(entry.tunnel.toDraft().copy(autostart = !entry.tunnel.autostart)); Unit }
                }
                // The row names the listener it arms, and says so in amber when that listener is
                // reachable from the network: this switch, not the editor, is what makes the
                // forward come up unattended.
                val exposed = bindsBeyondLoopback(entry.tunnel.bindHost)
                ToggleRow(
                    label = entry.tunnel.label,
                    on = entry.tunnel.autostart,
                    onToggle = onToggle,
                    modifier = boxedRow(),
                    subtitle = "${hostLabel(entry.tunnel.hostId)} · ${entry.tunnel.bindHost}:${entry.tunnel.bindPort}",
                    subtitleColor = if (exposed) Skerry.colors.amber else Skerry.colors.faint,
                    // The table behind this panel has a switch per tunnel too, and it means
                    // something else: on now, versus on after the next unlock.
                    switchName = stringResource(Res.string.ports_autostart_switch, entry.tunnel.label),
                )
            }
        }
    }
}

/**
 * Service discovery panel: pick a saved host, scan it for listening TCP ports, and forward one in a
 * tap (a local forward is created and activated right away).
 */
@Composable
internal fun ServicesPanel(
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

    SidePanel {
        PanelHeader(
            title = stringResource(Res.string.ports_services_title),
            onClose = onClose,
            leading = { Sym("radar", size = 16.sp, color = Skerry.colors.cyanBright) },
        )
        Txt(stringResource(Res.string.ports_services_hint), color = Skerry.colors.faint, size = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(bottom = 14.dp))
        FormField(stringResource(Res.string.ports_field_via_host), top = 0.dp) {
            HostPicker(hostLabel, hostList.map { it.id to it.label }, onPick = { hostId = it })
        }
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
    Row(boxedRow(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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

/** The scrolling column every side panel lives in. */
@Composable
private fun SidePanel(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.width(PANEL_WIDTH)
            .fillMaxHeight()
            .background(Skerry.colors.surface2)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 18.dp),
        content = content,
    )
}

/** Panel title with its leading mark and the close affordance. */
@Composable
private fun PanelHeader(title: String, onClose: () -> Unit, leading: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            leading()
            Txt(title, color = Skerry.colors.text, size = 13.sp, weight = FontWeight.SemiBold)
        }
        Sym(
            "close",
            contentDescription = stringResource(Res.string.shell_tip_close),
            size = 16.sp, color = Skerry.colors.faint,
            modifier = Modifier.clickable(onClick = onClose),
        )
    }
}

/** Outlined row used for list items inside a panel. */
@Composable
private fun boxedRow(): Modifier = Modifier.fillMaxWidth()
    .clip(RoundedCornerShape(7.dp))
    .background(Skerry.colors.bg)
    .border(1.dp, Skerry.colors.cyan08, RoundedCornerShape(7.dp))
    .padding(horizontal = 10.dp, vertical = 8.dp)
