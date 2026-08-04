package app.skerry.ui.host

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import app.skerry.shared.host.Host
import app.skerry.shared.ssh.ConnectionType
import app.skerry.shared.vault.CredentialSecret
import app.skerry.ui.connection.jumpHostCandidates
import app.skerry.ui.design.DropdownField
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
import app.skerry.ui.generated.resources.conn_cancel
import app.skerry.ui.generated.resources.conn_create
import app.skerry.ui.generated.resources.conn_group_new
import app.skerry.ui.generated.resources.conn_group_new_title
import app.skerry.ui.generated.resources.conn_group_none
import app.skerry.ui.generated.resources.conn_jump_none
import app.skerry.ui.generated.resources.conn_protocol_container
import app.skerry.ui.generated.resources.conn_protocol_local
import app.skerry.ui.generated.resources.conn_protocol_serial
import app.skerry.ui.generated.resources.conn_protocol_mosh
import app.skerry.ui.generated.resources.conn_protocol_ssh
import app.skerry.ui.generated.resources.conn_protocol_telnet
import app.skerry.ui.generated.resources.conn_protocol_vnc
import app.skerry.ui.generated.resources.conn_protocol_rdp
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.design.AnchoredDropdown
import app.skerry.ui.design.CancelButton
import app.skerry.ui.design.HLine
import app.skerry.ui.app.LocalCredentials
import app.skerry.ui.design.ModalScrim
import app.skerry.ui.design.consumeClicks
import app.skerry.ui.design.PrimaryButton
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.i18n.label
import app.skerry.ui.vault.title
import app.skerry.ui.theme.Skerry

@Composable
internal fun ProtocolPicker(form: NewConnectionFormState, protocols: List<ConnectionType>) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).background(Skerry.colors.bg).border(1.dp, Skerry.colors.cyan14, RoundedCornerShape(7.dp)).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        // [protocols] is this section's set (see connectionTypesIn): a new transport gets its segment
        // for free once it declares a section, and the exhaustive `when`s behind labelRes/icon fail
        // the build until it's given a label and an icon.
        protocols.forEach { type ->
            ProtocolSegment(stringResource(type.labelRes), type.icon, form.connectionType == type, Modifier.weight(1f)) {
                form.chooseConnectionType(type)
            }
        }
    }
}

/** Localized protocol name for the picker segment; the icon counterpart is [ConnectionType.icon]. */
private val ConnectionType.labelRes: StringResource
    get() = when (this) {
        ConnectionType.SSH -> Res.string.conn_protocol_ssh
        ConnectionType.MOSH -> Res.string.conn_protocol_mosh
        ConnectionType.TELNET -> Res.string.conn_protocol_telnet
        ConnectionType.SERIAL -> Res.string.conn_protocol_serial
        ConnectionType.VNC -> Res.string.conn_protocol_vnc
        ConnectionType.RDP -> Res.string.conn_protocol_rdp
        ConnectionType.LOCAL -> Res.string.conn_protocol_local
        ConnectionType.CONTAINER -> Res.string.conn_protocol_container
    }

/** One pill of the segmented protocol picker: the active one sits on a cyan backing. */
@Composable
internal fun ProtocolSegment(label: String, icon: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier.clip(RoundedCornerShape(5.dp)).background(if (selected) Skerry.colors.cyan10 else Color.Transparent).clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        Sym(icon, size = 15.sp, color = if (selected) Skerry.colors.cyanBright else Skerry.colors.faint)
        Txt(label, color = if (selected) Skerry.colors.cyanBright else Skerry.colors.dim, size = 12.5.sp, weight = if (selected) FontWeight.Medium else FontWeight.Normal)
    }
}

/**
 * Host auth selection: a working dropdown (Ask every time / new password / new key / already-saved
 * keychain secrets from the vault) plus inline fields for a new secret. The saved list comes from the
 * live [LocalCredentials] (behind the vault gate); in the mock path only the no-vault options remain.
 */
@Composable
internal fun AuthPicker(form: NewConnectionFormState, allowKey: Boolean = true) {
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
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).background(Skerry.colors.bg).border(1.dp, Skerry.colors.cyan14, RoundedCornerShape(7.dp)).clickable { menuOpen = !menuOpen }.padding(horizontal = 11.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Txt(selectedLabel, color = Skerry.colors.text, size = 13.sp)
                    Sym(if (menuOpen) "expand_less" else "expand_more", size = 16.sp, color = Skerry.colors.faint)
                }
            },
            menu = { width ->
                // The menu floats ABOVE the form (Popup) rather than pushing it apart; width = trigger width, scrolls on overflow.
                Column(Modifier.width(width).clip(RoundedCornerShape(7.dp)).background(Skerry.colors.surfaceDeep).border(1.dp, Skerry.colors.cyan14, RoundedCornerShape(7.dp)).heightIn(max = 320.dp).verticalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
                    AuthOption("vpn_key_off", stringResource(Res.string.conn_auth_ask), stringResource(Res.string.conn_auth_ask_desc), form.authMode == AuthMode.ASK) {
                        form.authMode = AuthMode.ASK; menuOpen = false
                    }

                    AuthOption("pin", stringResource(Res.string.conn_auth_interactive), stringResource(Res.string.conn_auth_interactive_desc), form.authMode == AuthMode.INTERACTIVE) {
                        form.authMode = AuthMode.INTERACTIVE; menuOpen = false
                    }
                    AuthOption("password", stringResource(Res.string.conn_auth_password_option), stringResource(Res.string.conn_auth_password_desc), form.authMode == AuthMode.NEW_PASSWORD) {
                        form.authMode = AuthMode.NEW_PASSWORD; menuOpen = false
                    }
                    // VNC has no key auth (allowKey = false): the RFB VNC-Auth scheme is password-only.
                    if (allowKey) {
                        AuthOption("key", stringResource(Res.string.conn_auth_key_option), stringResource(Res.string.conn_auth_key_desc), form.authMode == AuthMode.NEW_KEY) {
                            form.authMode = AuthMode.NEW_KEY; menuOpen = false
                        }
                    }
                    if (saved.isNotEmpty()) {
                        HLine(modifier = Modifier.padding(vertical = 4.dp))
                        saved.forEach { cred ->
                            AuthOption(cred.secret.pickerIcon(), cred.label, cred.secret.pickerTypeLabel(), form.authMode == AuthMode.EXISTING && form.existingCredentialId == cred.id) {
                                form.authMode = AuthMode.EXISTING; form.existingCredentialId = cred.id; menuOpen = false
                            }
                        }
                    }
                }
            },
        )
        when (form.authMode) {
            AuthMode.NEW_PASSWORD -> {
                Spacer14()
                ModalTextField(form.password, { form.password = it }, stringResource(Res.string.conn_auth_password_placeholder), icon = "key", masked = true)
            }
            AuthMode.NEW_KEY -> {
                Spacer14()
                // keyboardType=Password suppresses IME autocorrect/suggestions (Android) so the key doesn't end up in the dictionary.
                ModalTextField(form.privateKeyPem, { form.privateKeyPem = it }, "-----BEGIN OPENSSH PRIVATE KEY-----", keyboardType = KeyboardType.Password, singleLine = false, mono = true, minHeightDp = 96)
                Spacer14()
                ModalTextField(form.passphrase, { form.passphrase = it }, stringResource(Res.string.conn_auth_passphrase_placeholder), icon = "lock", masked = true)
            }
            else -> {}
        }
    }
}

/** One option row in the auth dropdown: icon plus title plus subtitle plus a checkmark when selected. */
@Composable
internal fun AuthOption(icon: String, title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(if (selected) Skerry.colors.cyan10 else Color.Transparent).clickable(onClick = onClick).padding(horizontal = 11.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Sym(icon, size = 16.sp, color = if (selected) Skerry.colors.cyanBright else Skerry.colors.dim)
        Column(Modifier.weight(1f)) {
            Txt(title, color = if (selected) Skerry.colors.cyanBright else Skerry.colors.text, size = 12.5.sp, weight = FontWeight.Medium)
            Txt(subtitle, color = Skerry.colors.faint, size = 10.5.sp)
        }
        if (selected) Sym("check", size = 15.sp, color = Skerry.colors.cyanBright)
    }
}

/**
 * "Jump host" field: "None — direct" plus eligible saved SSH profiles ([jumpHostCandidates] — no
 * self-reference, no cycle through the edited host). Stores only the id
 * ([NewConnectionFormState.jumpHostId]); the chain itself is resolved at connect time.
 */
@Composable
internal fun JumpHostPicker(form: NewConnectionFormState, allHosts: List<Host>, editingId: String?) {
    val candidates = remember(allHosts, editingId) { jumpHostCandidates(allHosts, editingId) }
    // Selected by id over ALL hosts (not just candidates): a reference that became ineligible after
    // other edits still shows its label instead of silently reading as "none".
    val selected = allHosts.firstOrNull { it.id == form.jumpHostId }
    DropdownField(
        value = selected,
        options = listOf<Host?>(null) + candidates,
        label = { it?.label ?: stringResource(Res.string.conn_jump_none) },
        onPick = { form.jumpHostId = it?.id },
    )
}

/**
 * "Keep-alive" field: cadence of the session's keepalive pings for this profile
 * ([NewConnectionFormState.keepAliveSeconds], 0 = off). Fixed option list [KEEP_ALIVE_OPTIONS].
 */
@Composable
internal fun KeepAlivePicker(form: NewConnectionFormState) {
    DropdownField(
        value = form.keepAliveSeconds,
        options = KEEP_ALIVE_OPTIONS,
        label = { keepAliveLabel(it) },
        onPick = { form.keepAliveSeconds = it },
    )
}

/**
 * "Group" field: a dropdown select (like [AuthPicker]) - "No group", already-created catalog groups
 * ([groupSuggestions]), and "New group..." opening the create dialog. The selected group is stored in
 * [NewConnectionFormState.group]; creating a new one just sets its name (the profile creates the folder
 * on save). No free-text input in the field itself, only the list plus explicit creation, to avoid
 * typo-duplicate groups.
 */
@Composable
internal fun GroupPicker(form: NewConnectionFormState, allHosts: List<Host>) {
    var menuOpen by remember { mutableStateOf(false) }
    var createOpen by remember { mutableStateOf(false) }
    val groups = remember(allHosts) { groupSuggestions(allHosts) }
    val hasGroup = form.group.isNotBlank()
    AnchoredDropdown(
        expanded = menuOpen,
        onDismiss = { menuOpen = false },
        trigger = {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).background(Skerry.colors.bg).border(1.dp, Skerry.colors.cyan14, RoundedCornerShape(7.dp)).clickable { menuOpen = !menuOpen }.padding(horizontal = 11.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Txt(if (hasGroup) form.group else stringResource(Res.string.conn_group_none), color = if (hasGroup) Skerry.colors.text else Skerry.colors.faint, size = 13.sp)
                Sym(if (menuOpen) "expand_less" else "expand_more", size = 16.sp, color = Skerry.colors.faint)
            }
        },
        menu = { width ->
            SuggestionMenu(width) {
                GroupOption(stringResource(Res.string.conn_group_none), selected = !hasGroup) { form.group = ""; menuOpen = false }
                groups.forEach { group ->
                    key(group) { GroupOption(group, selected = form.group == group) { form.group = group; menuOpen = false } }
                }
                HLine(modifier = Modifier.padding(vertical = 4.dp))
                GroupOption(stringResource(Res.string.conn_group_new), selected = false, icon = "add") { menuOpen = false; createOpen = true }
            }
        },
    )
    if (createOpen) {
        GroupCreateDialog(onDismiss = { createOpen = false }, onCreate = { name -> form.group = name.trim(); createOpen = false })
    }
}

/** One option row of the group select: optional icon plus title plus a checkmark when selected. */
@Composable
private fun GroupOption(title: String, selected: Boolean, icon: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(if (selected) Skerry.colors.cyan10 else Color.Transparent).clickable(onClick = onClick).padding(horizontal = 11.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        if (icon != null) Sym(icon, size = 15.sp, color = Skerry.colors.cyanBright)
        Txt(title, color = if (selected) Skerry.colors.cyanBright else Skerry.colors.text, size = 12.5.sp, weight = if (selected) FontWeight.Medium else FontWeight.Normal, modifier = Modifier.weight(1f))
        if (selected) Sym("check", size = 15.sp, color = Skerry.colors.cyanBright)
    }
}

/**
 * Modal dialog for creating a new group (Popup over the connection modal): name field plus Cancel/Create.
 * A blank name doesn't create anything (button disabled). The name is only set on the form, the folder
 * appears in the catalog when the host is saved.
 */
@Composable
private fun GroupCreateDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    val canCreate = name.isNotBlank()
    Popup(alignment = Alignment.Center, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        ModalScrim(onDismiss = onDismiss) {
            Column(
                Modifier
                    .widthIn(max = 360.dp)
                    .fillMaxWidth()
                    .padding(20.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Skerry.colors.surfaceDeep)
                    .border(1.dp, Skerry.colors.cyan14, RoundedCornerShape(12.dp))
                    .consumeClicks()
                    .padding(22.dp),
            ) {
                Txt(stringResource(Res.string.conn_group_new_title), color = Skerry.colors.text, size = 16.sp, weight = FontWeight.SemiBold)
                Spacer14()
                ModalTextField(name, { name = it }, "Production")
                Spacer14()
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                    CancelButton(stringResource(Res.string.conn_cancel), onClick = onDismiss)
                    PrimaryButton(stringResource(Res.string.conn_create), onClick = { onCreate(name) }, enabled = canCreate)
                }
            }
        }
    }
}
