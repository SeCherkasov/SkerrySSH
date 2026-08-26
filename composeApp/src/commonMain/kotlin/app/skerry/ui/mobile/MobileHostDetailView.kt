package app.skerry.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.ssh.carriesSftp
import app.skerry.ui.design.sanitizeServerText
import app.skerry.ui.design.untrustedLabel
import app.skerry.ui.host.HostSection
import app.skerry.ui.host.rowSubtitle
import app.skerry.ui.host.rowLabel
import app.skerry.ui.host.section
import app.skerry.shared.host.Host
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.shtail_host_address
import app.skerry.ui.generated.resources.shtail_host_ask_on_connect
import app.skerry.ui.generated.resources.shtail_host_auth
import app.skerry.ui.generated.resources.shtail_host_group
import app.skerry.ui.generated.resources.shtail_host_jump
import app.skerry.ui.generated.resources.shtail_host_notes
import app.skerry.ui.generated.resources.shtail_host_port
import app.skerry.ui.generated.resources.shtail_host_saved_credential
import app.skerry.ui.connection.jumpRouteLabel
import app.skerry.ui.host.ungroupedLabel
import app.skerry.ui.generated.resources.shell_host
import app.skerry.ui.generated.resources.shell_host_not_found
import app.skerry.ui.generated.resources.shell_connect
import app.skerry.ui.generated.resources.shell_quick_sftp
import app.skerry.ui.generated.resources.shell_quick_tunnels
import app.skerry.ui.generated.resources.shell_quick_snippets
import app.skerry.ui.generated.resources.shell_details
import app.skerry.ui.generated.resources.shell_edit_host
import app.skerry.ui.generated.resources.shell_duplicate_host
import app.skerry.ui.generated.resources.shell_delete_host
import app.skerry.ui.design.MAX_NOTE_CHARS
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.app.LocalConnectHost
import app.skerry.ui.design.HLine
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.app.LocalHosts
import app.skerry.ui.app.LocalOpenSftp
import app.skerry.ui.app.MobileDesignState
import app.skerry.ui.app.MobileRoute
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.i18n.label
import app.skerry.ui.theme.Skerry

/** Details card row: label, value, and whether the value is monospace (address/port). */
@Immutable
data class HostDetailRow(val label: String, val value: String, val mono: Boolean)

/**
 * Reduces a [Host] profile to Details card rows: Address, Port, Auth, jump route (only when the
 * profile has one — [jumpRouteLabel] over [findHost]), Group, Notes (only when the profile carries
 * one — the desktop shows the same text as a sidebar hover tooltip, which a phone has no equivalent
 * of). Auth reflects whether a keychain secret is bound (`Saved credential` / `Ask on connect`);
 * Group falls back to "Ungrouped". AI policy and online status are not in the model.
 */
@Composable
fun mobileHostDetailRows(host: Host, findHost: (String) -> Host? = { null }): List<HostDetailRow> = listOfNotNull(
    HostDetailRow(stringResource(Res.string.shtail_host_address), untrustedLabel(host.address), mono = true),
    HostDetailRow(stringResource(Res.string.shtail_host_port), host.port.toString(), mono = true),
    HostDetailRow(
        stringResource(Res.string.shtail_host_auth),
        if (host.credentialId != null) stringResource(Res.string.shtail_host_saved_credential) else stringResource(Res.string.shtail_host_ask_on_connect),
        mono = false,
    ),
    jumpRouteLabel(host, findHost)?.let { HostDetailRow(stringResource(Res.string.shtail_host_jump), it, mono = false) },
    HostDetailRow(stringResource(Res.string.shtail_host_group), host.group?.takeIf { it.isNotBlank() } ?: ungroupedLabel(), mono = false),
    // A note is free-form prose: it keeps its lines, and is capped like every other peer string.
    host.notes?.takeIf { it.isNotBlank() }?.let {
        HostDetailRow(
            stringResource(Res.string.shtail_host_notes),
            sanitizeServerText(it, MAX_NOTE_CHARS, allowNewlines = true),
            mono = false,
        )
    },
)

/**
 * Full-screen host detail (pushed over Hosts). Profile comes from the live [LocalHosts] by
 * [MobileDesignState.selectedHostId] (or the preview catalog outside the gate). Connect navigates
 * to [MobileRoute.Terminal]; Tunnels to [MobileRoute.Ports]; Delete removes the profile via
 * [app.skerry.ui.host.HostManagerController] and pops back. SFTP connects to the host and opens
 * the Files tab; Snippets pushes the command library. Edit is still a stub.
 */
@Composable
fun MobileHostDetailScreen(state: MobileDesignState) {
    val controller = LocalHosts.current
    val id = state.selectedHostId
    val host = id?.let { controller?.find(it) ?: MOBILE_PREVIEW_HOSTS.firstOrNull { h -> h.id == it } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        MobilePushHeader(stringResource(Res.string.shell_host), onBack = state::pop, plainBack = true)

        if (host == null) {
            Txt(
                stringResource(Res.string.shell_host_not_found),
                color = Skerry.colors.faint,
                size = 13.sp,
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp),
            )
            return@Column
        }

        // Identity: icon tile, name, user@address. Online status from the mock is omitted (not in the model).
        Column(
            Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 8.dp, bottom = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.size(62.dp).clip(RoundedCornerShape(18.dp)).background(Skerry.colors.cyan.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center,
            ) {
                Sym("dns", size = 32.sp, color = Skerry.colors.cyanBright)
            }
            Spacer(Modifier.height(12.dp))
            Txt(host.rowLabel(), color = Skerry.colors.text, size = 20.sp, weight = FontWeight.Bold)
            Spacer(Modifier.height(3.dp))
            Txt(
                host.rowSubtitle(),
                color = Skerry.colors.dim,
                size = 12.5.sp,
                font = LocalFonts.current.mono,
            )
        }

        // Connect: opens a live session via LocalConnectHost (resolves identity or prompts for a
        // password) and pushes the terminal (or framebuffer) screen; a no-op in preview/outside the gate.
        val connect = LocalConnectHost.current
        val remoteDesktop = host.section == HostSection.RemoteDesktops
        Box(Modifier.padding(horizontal = 22.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Skerry.colors.cyan)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { connect(host) },
                    )
                    .padding(15.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            ) {
                // A remote desktop opens a framebuffer, not a shell — the button says so.
                Sym(if (remoteDesktop) "desktop_windows" else "terminal", size = 21.sp, color = Skerry.colors.ink)
                Txt(stringResource(Res.string.shell_connect), color = Skerry.colors.ink, size = 16.sp, weight = FontWeight.Bold)
            }
        }

        // Quick actions: SFTP connects to the host (like Connect, resolving the secret/password)
        // and opens the Files tab (the active session's remote browser). Tunnels opens Ports,
        // Snippets the command library — the same screen the More hub pushes.
        val openSftp = LocalOpenSftp.current
        Row(
            Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 14.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            // Only an SSH session carries a file channel ([carriesSftp], the same predicate the
            // terminal's path chip reads). On anything else — a remote desktop, Telnet, serial, a
            // container exec — the card is left out rather than drawn like a working one over a
            // browser that can never list anything (#305 — an action that can do nothing is not
            // drawn at all).
            if (host.connectionType.carriesSftp) {
                QuickAction("folder", stringResource(Res.string.shell_quick_sftp), Modifier.weight(1f)) { openSftp(host) }
            }
            QuickAction("lan", stringResource(Res.string.shell_quick_tunnels), Modifier.weight(1f)) { state.push(MobileRoute.Ports) }
            QuickAction("code_blocks", stringResource(Res.string.shell_quick_snippets), Modifier.weight(1f)) { state.push(MobileRoute.Snippets) }
        }

        HostsDetailLabel(stringResource(Res.string.shell_details))
        Column(
            Modifier
                .padding(horizontal = 18.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(Skerry.colors.card)
                .border(1.dp, Skerry.colors.cyan.copy(alpha = 0.07f), RoundedCornerShape(13.dp)),
        ) {
            val rows = mobileHostDetailRows(host) { jumpId -> controller?.find(jumpId) }
            rows.forEachIndexed { i, row ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Txt(row.label, color = Skerry.colors.dim, size = 13.sp)
                    Txt(
                        row.value,
                        color = Skerry.colors.text,
                        size = 13.sp,
                        lineHeight = 18.sp,
                        font = if (row.mono) LocalFonts.current.mono else null,
                        // A note runs long: let the value wrap and stay right-aligned instead of
                        // squeezing the label off the row.
                        align = TextAlign.End,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (i < rows.lastIndex) {
                    HLine()
                }
            }
        }

        // Edit host: opens the New connection sheet in edit mode for this profile (unavailable
        // outside the gate, nothing to save to). Duplicate: the same sheet prefilled as a copy.
        // Delete: removes the profile from the live catalog and pops back.
        Column(
            Modifier.padding(start = 22.dp, end = 22.dp, top = 20.dp, bottom = 30.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (controller != null) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(13.dp))
                        .border(1.dp, Skerry.colors.cyan.copy(alpha = 0.4f), RoundedCornerShape(13.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { state.openEditConn(host) },
                        )
                        .padding(13.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Txt(stringResource(Res.string.shell_edit_host), color = Skerry.colors.cyanBright, size = 14.sp, weight = FontWeight.Medium)
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(13.dp))
                        .border(1.dp, Skerry.colors.cyan.copy(alpha = 0.4f), RoundedCornerShape(13.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { state.openDuplicateConn(host) },
                        )
                        .padding(13.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Txt(stringResource(Res.string.shell_duplicate_host), color = Skerry.colors.cyanBright, size = 14.sp, weight = FontWeight.Medium)
                }
            }
            val onDelete = controller?.let { ctrl -> { ctrl.delete(host.id); state.pop() } }
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(13.dp))
                    .border(1.dp, Skerry.colors.sunset.copy(alpha = 0.25f), RoundedCornerShape(13.dp))
                    .then(if (onDelete != null) Modifier.clickable(onClick = onDelete) else Modifier)
                    .padding(13.dp),
                contentAlignment = Alignment.Center,
            ) {
                Txt(stringResource(Res.string.shell_delete_host), color = Skerry.colors.sunset, size = 14.sp, weight = FontWeight.Medium)
            }
        }
    }
}

/** Quick-action card (icon above label). Every card drawn leads somewhere — see the row above. */
@Composable
private fun QuickAction(icon: String, label: String, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(13.dp))
            .background(Skerry.colors.card)
            .border(1.dp, Skerry.colors.cyan08, RoundedCornerShape(13.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Sym(icon, size = 22.sp, color = Skerry.colors.cyanBright)
        Txt(label, color = Skerry.colors.dim, size = 11.sp)
    }
}

/** Uppercase section label on the detail screen (Details). */
@Composable
private fun HostsDetailLabel(name: String) {
    Txt(
        name.uppercase(),
        color = Skerry.colors.faint,
        size = 11.5.sp,
        weight = FontWeight.SemiBold,
        letterSpacing = 0.6.sp,
        modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 12.dp, bottom = 6.dp),
    )
}
