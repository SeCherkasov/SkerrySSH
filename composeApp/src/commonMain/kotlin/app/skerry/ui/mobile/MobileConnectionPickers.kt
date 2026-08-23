package app.skerry.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.host.Host
import app.skerry.shared.ssh.ConnectionType
import app.skerry.ui.host.connectionTypesIn
import app.skerry.shared.vault.CredentialSecret
import app.skerry.ui.host.AuthMode
import app.skerry.ui.host.NewConnectionFormState
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.conn_auth_ask
import app.skerry.ui.generated.resources.conn_auth_ask_desc
import app.skerry.ui.generated.resources.conn_auth_interactive
import app.skerry.ui.generated.resources.conn_auth_interactive_desc
import app.skerry.ui.generated.resources.conn_auth_existing_saved
import app.skerry.ui.generated.resources.conn_auth_key_desc
import app.skerry.ui.generated.resources.conn_auth_key_option
import app.skerry.ui.generated.resources.conn_auth_passphrase_placeholder
import app.skerry.ui.generated.resources.conn_auth_password
import app.skerry.ui.generated.resources.conn_auth_password_desc
import app.skerry.ui.generated.resources.conn_auth_password_option
import app.skerry.ui.generated.resources.conn_auth_password_placeholder
import app.skerry.ui.generated.resources.conn_auth_private_key
import app.skerry.ui.generated.resources.conn_auth_select_credential
import app.skerry.ui.generated.resources.conn_jump_none
import app.skerry.ui.generated.resources.conn_group_new
import app.skerry.ui.generated.resources.conn_group_none
import app.skerry.ui.generated.resources.conn_protocol_serial
import app.skerry.ui.generated.resources.conn_protocol_mosh
import app.skerry.ui.generated.resources.conn_protocol_container
import app.skerry.ui.generated.resources.conn_protocol_ssh
import app.skerry.ui.generated.resources.conn_protocol_telnet
import app.skerry.ui.generated.resources.conn_protocol_vnc
import app.skerry.ui.generated.resources.conn_protocol_rdp
import app.skerry.ui.generated.resources.conn_protocol_local
import app.skerry.ui.host.rowLabel
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.app.AiPolicy
import app.skerry.ui.ai.shortLabel
import app.skerry.ui.design.AnchoredDropdown
import app.skerry.ui.design.HLine
import app.skerry.ui.app.LocalCredentials
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.connection.jumpHostCandidates
import app.skerry.ui.host.KEEP_ALIVE_OPTIONS
import app.skerry.ui.host.groupSuggestions
import app.skerry.ui.host.keepAliveLabel
import app.skerry.ui.i18n.label
import app.skerry.ui.host.listSerialPorts
import app.skerry.ui.host.serialPortOptions
import app.skerry.ui.host.pickerIcon
import app.skerry.ui.host.pickerTypeLabel
import app.skerry.ui.theme.Skerry

/**
 * Segmented transport picker (SSH / Telnet / Serial) on the phone — parity with desktop
 * ProtocolPicker. Writes the type through [NewConnectionFormState.chooseConnectionType] (fills in
 * the default port/baud) and rebuilds the form.
 */
@Composable
internal fun MobileSerialPortPicker(form: NewConnectionFormState) {
    // Same order as the desktop form: adapters first, the kernel's legacy UARTs last, no duplicates.
    val ports = remember { serialPortOptions(listSerialPorts()) }
    if (ports.isEmpty()) return
    FlowRow(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ports.forEach { port ->
            key(port.systemName) {
                val selected = form.address == port.systemName
                Row(
                    Modifier
                        .clip(RoundedCornerShape(7.dp))
                        .background(if (selected) Skerry.colors.cyan14 else Skerry.colors.bg)
                        .border(1.dp, if (selected) Skerry.colors.cyan else Skerry.colors.cyan14, RoundedCornerShape(7.dp))
                        .clickable { form.address = port.systemName }
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Sym("usb", size = 14.sp, color = if (selected) Skerry.colors.cyanBright else Skerry.colors.faint)
                    Txt(port.description, color = if (selected) Skerry.colors.text else Skerry.colors.dim, size = 12.sp)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MobileProtocolPicker(form: NewConnectionFormState, protocols: List<ConnectionType>) {
    // Protocols don't fit one phone-width row, so they wrap three per row (a segment narrower than
    // its label reads as a truncated mess). Driven off the section's set, like the desktop picker.
    FlowRow(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp)).background(Skerry.colors.bg).border(1.dp, Skerry.colors.cyan14, RoundedCornerShape(11.dp)).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        maxItemsInEachRow = 3,
    ) {
        protocols.forEach { type ->
            MobileProtocolSegment(stringResource(type.protocolLabel), form.connectionType == type, Modifier.weight(1f)) {
                form.chooseConnectionType(type)
            }
        }
    }
}

/** Localized protocol name for a picker segment (LOCAL is never offered — see [connectionTypesIn]). */
private val ConnectionType.protocolLabel: org.jetbrains.compose.resources.StringResource
    get() = when (this) {
        ConnectionType.SSH -> Res.string.conn_protocol_ssh
        ConnectionType.MOSH -> Res.string.conn_protocol_mosh
        ConnectionType.TELNET -> Res.string.conn_protocol_telnet
        ConnectionType.SERIAL -> Res.string.conn_protocol_serial
        ConnectionType.VNC -> Res.string.conn_protocol_vnc
        ConnectionType.RDP -> Res.string.conn_protocol_rdp
        ConnectionType.CONTAINER -> Res.string.conn_protocol_container
        ConnectionType.LOCAL -> Res.string.conn_protocol_local
    }

@Composable
internal fun MobileProtocolSegment(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) Skerry.colors.cyan10 else Color.Transparent)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Txt(label, color = if (selected) Skerry.colors.cyanBright else Skerry.colors.dim, size = 14.sp, weight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

/**
 * Host authentication picker in the mobile sheet style: a select trigger expands into options —
 * Ask every time / new password / new key / existing keychain secrets from the live
 * [LocalCredentials] — plus inline fields for a new secret. In the mock path (no vault) only the
 * non-saving options remain.
 */
@Composable
internal fun MobileAuthPicker(form: NewConnectionFormState, allowKey: Boolean = true) {
    val credentials = LocalCredentials.current
    // VNC (allowKey = false) can only use a password secret: a key/certificate would silently map
    // to no auth at connect (toVncAuth), so those are not offered.
    val saved = (credentials?.credentials ?: emptyList())
        .filter { allowKey || it.secret is CredentialSecret.Password }
    var menuOpen by remember { mutableStateOf(false) }
    val selectedLabel = when (form.authMode) {
        AuthMode.ASK -> stringResource(Res.string.conn_auth_ask)
        AuthMode.INTERACTIVE -> stringResource(Res.string.conn_auth_interactive)
        AuthMode.NEW_PASSWORD -> stringResource(Res.string.conn_auth_password)
        AuthMode.NEW_KEY -> stringResource(Res.string.conn_auth_private_key)
        AuthMode.EXISTING -> saved.firstOrNull { it.id == form.existingCredentialId }?.let { stringResource(Res.string.conn_auth_existing_saved, it.label) } ?: stringResource(Res.string.conn_auth_select_credential)
    }
    Column {
        AnchoredDropdown(
            expanded = menuOpen,
            onDismiss = { menuOpen = false },
            trigger = {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(11.dp))
                        .background(Skerry.colors.bg)
                        .border(1.dp, Skerry.colors.cyan14, RoundedCornerShape(11.dp))
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { menuOpen = !menuOpen }
                        .padding(horizontal = 14.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Txt(selectedLabel, color = Skerry.colors.text, size = 15.sp)
                    Sym(if (menuOpen) "expand_less" else "expand_more", size = 20.sp, color = Skerry.colors.faint)
                }
            },
            menu = { width ->
                // A real dropdown over the sheet (Popup), not expanding the form; width matches the trigger, scrolls on overflow.
                Column(
                    Modifier
                        .width(width)
                        .clip(RoundedCornerShape(11.dp))
                        .background(Skerry.colors.surface2)
                        .border(1.dp, Skerry.colors.cyan14, RoundedCornerShape(11.dp))
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 4.dp),
                ) {
                    MobileAuthOption("vpn_key_off", stringResource(Res.string.conn_auth_ask), stringResource(Res.string.conn_auth_ask_desc), form.authMode == AuthMode.ASK) {
                        form.authMode = AuthMode.ASK; menuOpen = false
                    }
                    MobileAuthOption("pin", stringResource(Res.string.conn_auth_interactive), stringResource(Res.string.conn_auth_interactive_desc), form.authMode == AuthMode.INTERACTIVE) {
                        form.authMode = AuthMode.INTERACTIVE; menuOpen = false
                    }
                    MobileAuthOption("password", stringResource(Res.string.conn_auth_password_option), stringResource(Res.string.conn_auth_password_desc), form.authMode == AuthMode.NEW_PASSWORD) {
                        form.authMode = AuthMode.NEW_PASSWORD; menuOpen = false
                    }
                    // VNC has no key auth (allowKey = false): RFB VNC-Auth is password-only.
                    if (allowKey) {
                        MobileAuthOption("key", stringResource(Res.string.conn_auth_key_option), stringResource(Res.string.conn_auth_key_desc), form.authMode == AuthMode.NEW_KEY) {
                            form.authMode = AuthMode.NEW_KEY; menuOpen = false
                        }
                    }
                    // Divider before saved secrets (parity with desktop AuthPicker).
                    if (saved.isNotEmpty()) {
                        HLine(modifier = Modifier.padding(vertical = 4.dp))
                        saved.forEach { cred ->
                            MobileAuthOption(cred.secret.pickerIcon(), cred.label, cred.secret.pickerTypeLabel(), form.authMode == AuthMode.EXISTING && form.existingCredentialId == cred.id) {
                                form.authMode = AuthMode.EXISTING; form.existingCredentialId = cred.id; menuOpen = false
                            }
                        }
                    }
                }
            },
        )
        when (form.authMode) {
            AuthMode.NEW_PASSWORD -> {
                Spacer(Modifier.height(12.dp))
                MobileFormInput(form.password, { form.password = it }, stringResource(Res.string.conn_auth_password_placeholder), masked = true)
            }
            AuthMode.NEW_KEY -> {
                Spacer(Modifier.height(12.dp))
                // keyboardType=Password suppresses IME autocorrect/suggestions (Android) so the key
                // doesn't end up in the dictionary. PEM is shown unmasked: masking a multiline field
                // would break pasting the key and visually verifying it — a deliberate trade-off, same as desktop ModalTextField.
                MobileFormInput(form.privateKeyPem, { form.privateKeyPem = it }, "-----BEGIN OPENSSH PRIVATE KEY-----", keyboardType = KeyboardType.Password, singleLine = false, mono = true, minHeightDp = 104)
                Spacer(Modifier.height(12.dp))
                MobileFormInput(form.passphrase, { form.passphrase = it }, stringResource(Res.string.conn_auth_passphrase_placeholder), masked = true)
            }
            else -> {}
        }
    }
}

/**
 * The sheet's "Jump host" field: "None — direct" plus eligible saved SSH profiles
 * ([jumpHostCandidates] — no self-reference, no cycle through the edited host). Stores only the id
 * ([NewConnectionFormState.jumpHostId]); the chain resolves at connect time. Same dropdown chrome
 * as [MobileGroupPicker].
 */
@Composable
internal fun MobileJumpHostPicker(form: NewConnectionFormState, allHosts: List<Host>, editingId: String?) {
    var menuOpen by remember { mutableStateOf(false) }
    val candidates = remember(allHosts, editingId) { jumpHostCandidates(allHosts, editingId) }
    // Selected by id over ALL hosts: a reference that became ineligible after other edits still
    // shows its label instead of silently reading as "none".
    val selected = allHosts.firstOrNull { it.id == form.jumpHostId }
    AnchoredDropdown(
        expanded = menuOpen,
        onDismiss = { menuOpen = false },
        trigger = {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(11.dp))
                    .background(Skerry.colors.bg)
                    .border(1.dp, Skerry.colors.cyan14, RoundedCornerShape(11.dp))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { menuOpen = !menuOpen }
                    .padding(horizontal = 14.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Txt(selected?.label ?: stringResource(Res.string.conn_jump_none), color = if (selected != null) Skerry.colors.text else Skerry.colors.faint, size = 15.sp)
                Sym(if (menuOpen) "expand_less" else "expand_more", size = 20.sp, color = Skerry.colors.faint)
            }
        },
        menu = { width ->
            Column(
                Modifier
                    .width(width)
                    .clip(RoundedCornerShape(11.dp))
                    .background(Skerry.colors.surface2)
                    .border(1.dp, Skerry.colors.cyan14, RoundedCornerShape(11.dp))
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 4.dp),
            ) {
                MobileGroupOption(stringResource(Res.string.conn_jump_none), selected = selected == null) { form.jumpHostId = null; menuOpen = false }
                candidates.forEach { host ->
                    key(host.id) {
                        MobileGroupOption(host.rowLabel(), selected = form.jumpHostId == host.id) { form.jumpHostId = host.id; menuOpen = false }
                    }
                }
            }
        },
    )
}

/**
 * The sheet's "Keep-alive" field: cadence of the session's keepalive pings for this profile
 * ([NewConnectionFormState.keepAliveSeconds], 0 = off), options from [KEEP_ALIVE_OPTIONS]. Same
 * dropdown chrome as [MobileGroupPicker].
 */
@Composable
internal fun MobileKeepAlivePicker(form: NewConnectionFormState) {
    var menuOpen by remember { mutableStateOf(false) }
    AnchoredDropdown(
        expanded = menuOpen,
        onDismiss = { menuOpen = false },
        trigger = {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(11.dp))
                    .background(Skerry.colors.bg)
                    .border(1.dp, Skerry.colors.cyan14, RoundedCornerShape(11.dp))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { menuOpen = !menuOpen }
                    .padding(horizontal = 14.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Txt(keepAliveLabel(form.keepAliveSeconds), color = Skerry.colors.text, size = 15.sp)
                Sym(if (menuOpen) "expand_less" else "expand_more", size = 20.sp, color = Skerry.colors.faint)
            }
        },
        menu = { width ->
            Column(
                Modifier
                    .width(width)
                    .clip(RoundedCornerShape(11.dp))
                    .background(Skerry.colors.surface2)
                    .border(1.dp, Skerry.colors.cyan14, RoundedCornerShape(11.dp))
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 4.dp),
            ) {
                KEEP_ALIVE_OPTIONS.forEach { seconds ->
                    key(seconds) {
                        MobileGroupOption(keepAliveLabel(seconds), selected = form.keepAliveSeconds == seconds) {
                            form.keepAliveSeconds = seconds; menuOpen = false
                        }
                    }
                }
            }
        },
    )
}

/**
 * The sheet's "Group" field over the shared control ([MobileGroupSelectField]): the catalog's
 * already-created groups ([groupSuggestions]) plus "No group" and "New group…". The selected name is
 * stored in [NewConnectionFormState.group]; creating a new one only sets the name (the profile
 * creates the folder on save).
 */
@Composable
internal fun MobileGroupPicker(form: NewConnectionFormState, allHosts: List<Host>, onCreateGroup: () -> Unit) {
    val groups = remember(allHosts) { groupSuggestions(allHosts) }
    MobileGroupSelectField(
        value = form.group,
        groups = groups,
        onChange = { form.group = it },
        onCreateGroup = onCreateGroup,
    )
}

/** Authentication dropdown option row: icon + name + subtitle + checkmark when selected. */
@Composable
private fun MobileAuthOption(icon: String, title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) Skerry.colors.cyan10 else Color.Transparent)
            // No explicit interactionSource (parity with desktop AuthOption): remember in forEach is
            // positional — reordering saved would shift the slot onto a different row.
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Sym(icon, size = 18.sp, color = if (selected) Skerry.colors.cyanBright else Skerry.colors.dim)
        Column(Modifier.weight(1f)) {
            Txt(title, color = if (selected) Skerry.colors.cyanBright else Skerry.colors.text, size = 14.sp, weight = FontWeight.Medium)
            Txt(subtitle, color = Skerry.colors.faint, size = 11.sp)
        }
        if (selected) Sym("check", size = 17.sp, color = Skerry.colors.cyanBright)
    }
}

/** AI policy pills (all 4 [AiPolicy] values) — selection writes into the form (Host.aiPolicy). */
@Composable
internal fun AiPolicyPills(form: NewConnectionFormState) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        AiPolicy.entries.forEach { policy ->
            val on = form.aiPolicy == policy
            val onPick = remember(policy) { { form.aiPolicy = policy } }
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (on) Skerry.colors.cyan.copy(alpha = 0.1f) else Color.Transparent)
                    .border(1.dp, if (on) Skerry.colors.cyan else Skerry.colors.cyan.copy(alpha = 0.1f), RoundedCornerShape(10.dp))
                    .clickable(
                        interactionSource = remember(policy) { MutableInteractionSource() },
                        indication = null,
                        onClick = onPick,
                    )
                    .padding(vertical = 9.dp, horizontal = 2.dp),
                contentAlignment = Alignment.Center,
            ) {
                Txt(
                    policy.shortLabel(),
                    color = if (on) Skerry.colors.cyanBright else Skerry.colors.dim,
                    size = 11.sp,
                    weight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}
