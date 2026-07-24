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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.host.Host
import app.skerry.shared.ssh.ConnectionType
import app.skerry.shared.ssh.usesSshAuth
import app.skerry.shared.ssh.isVnc
import app.skerry.shared.vault.CredentialSecret
import app.skerry.ui.host.AuthMode
import app.skerry.ui.host.NewConnectionFormState
import app.skerry.ui.nav.PlatformBackHandler
import app.skerry.ui.secure.SecureScreen
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.conn_auth_ask
import app.skerry.ui.generated.resources.conn_auth_ask_desc
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
import app.skerry.ui.generated.resources.conn_field_ai_policy_short
import app.skerry.ui.generated.resources.conn_field_authentication
import app.skerry.ui.generated.resources.conn_field_baud
import app.skerry.ui.generated.resources.conn_field_device
import app.skerry.ui.generated.resources.conn_field_group
import app.skerry.ui.generated.resources.conn_field_jump_host
import app.skerry.ui.generated.resources.conn_field_keep_alive
import app.skerry.ui.generated.resources.conn_jump_none
import app.skerry.ui.generated.resources.conn_field_host_address
import app.skerry.ui.generated.resources.conn_field_name
import app.skerry.ui.generated.resources.conn_field_port
import app.skerry.ui.generated.resources.conn_field_protocol
import app.skerry.ui.generated.resources.conn_field_tags
import app.skerry.ui.generated.resources.conn_field_username
import app.skerry.ui.generated.resources.conn_group_delete
import app.skerry.ui.generated.resources.conn_group_new
import app.skerry.ui.generated.resources.conn_group_new_title
import app.skerry.ui.generated.resources.conn_group_none
import app.skerry.ui.generated.resources.conn_group_rename_hint
import app.skerry.ui.generated.resources.conn_group_rename_title
import app.skerry.ui.generated.resources.conn_protocol_serial
import app.skerry.ui.generated.resources.conn_protocol_mosh
import app.skerry.ui.generated.resources.conn_protocol_ssh
import app.skerry.ui.generated.resources.conn_protocol_telnet
import app.skerry.ui.generated.resources.conn_protocol_vnc
import app.skerry.ui.generated.resources.conn_save
import app.skerry.ui.generated.resources.conn_save_changes
import app.skerry.ui.generated.resources.conn_save_connection
import app.skerry.ui.generated.resources.conn_subtitle_mobile
import app.skerry.ui.generated.resources.conn_tag_add_placeholder
import app.skerry.ui.generated.resources.conn_duplicate_name
import app.skerry.ui.generated.resources.conn_title_edit
import app.skerry.ui.generated.resources.conn_title_new
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.app.AiPolicy
import app.skerry.ui.ai.shortLabel
import app.skerry.ui.design.AnchoredDropdown
import app.skerry.ui.design.HLine
import app.skerry.ui.app.LocalAi
import app.skerry.ui.app.LocalCredentials
import app.skerry.ui.app.LocalFeatures
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.app.LocalHosts
import app.skerry.ui.app.MobileDesignState
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.connection.jumpHostCandidates
import app.skerry.ui.host.KEEP_ALIVE_OPTIONS
import app.skerry.ui.host.groupSuggestions
import app.skerry.ui.host.keepAliveLabel
import app.skerry.ui.i18n.label
import app.skerry.ui.host.listSerialPorts
import app.skerry.ui.host.tagSuggestions
import app.skerry.ui.host.pickerIcon
import app.skerry.ui.host.pickerTypeLabel
import app.skerry.ui.theme.Skerry


/**
 * "New connection" bottom sheet: scrim + panel with the host profile form. With a live
 * [LocalHosts] (behind the vault gate) Save creates the profile through
 * [app.skerry.ui.host.HostManagerController] and closes the sheet; without it (preview) Save just
 * closes. Reuses the shared [NewConnectionFormState].
 *
 * Authentication is the live [MobileAuthPicker]: Ask / new password / new key / an existing
 * keychain secret from [LocalCredentials]. A new secret is sealed into the open vault and linked
 * to the host via [NewConnectionFormState.resolveCredentialId]; the AI policy is gated by
 * [FeatureFlags.ai].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MobileNewConnectionSheet(state: MobileDesignState) {
    // The form can hold entered secrets (new password/private key/passphrase) — shield the window
    // from screenshots/Recent Apps previews while the sheet is open (Android; desktop is a no-op).
    SecureScreen()
    val hosts = LocalHosts.current
    val credentials = LocalCredentials.current
    // Edit mode: the sheet is prefilled from the profile and keeps its id (parity with desktop NewConnectionModal).
    val editHost = state.editingHost
    // Duplicate mode: prefilled as a copy of the profile, saved as a new record (parity with desktop).
    val duplicateOf = state.duplicatingHost
    val copyName = duplicateOf?.let { stringResource(Res.string.conn_duplicate_name, it.label) }
    // Keyed on editHost/duplicateOf: opening the sheet to edit or duplicate (or switching target)
    // rebuilds the form from the profile.
    val form = remember(editHost, duplicateOf) {
        when {
            editHost != null -> NewConnectionFormState.fromHost(editHost)
            duplicateOf != null -> NewConnectionFormState.duplicateOf(duplicateOf, copyName.orEmpty())
            else -> NewConnectionFormState()
        }
    }
    val canSave = hosts == null || form.canSave
    // Guards a repeated Save (double tap) before the sheet closes — otherwise a duplicate secret+host in
    // the vault (same as desktop). Keyed on editHost together with form: switching target resets the guard.
    var submitting by remember(editHost, duplicateOf) { mutableStateOf(false) }
    // Uncommitted tag input (pill not yet created); Save flushes it so it isn't lost.
    var tagDraft by remember(editHost, duplicateOf) { mutableStateOf("") }
    // Whether the "New group" dialog is open — kept at the sheet level so the overlay renders at
    // the root (not inside the form's scroll) and rises correctly above the keyboard.
    var createGroupOpen by remember(editHost, duplicateOf) { mutableStateOf(false) }
    val onSave = {
        if (submitting) {
            // Repeated tap before close — ignored.
        } else if (hosts == null) {
            state.closeSheet() // mock/preview: nowhere to save
        } else if (form.canSave) {
            submitting = true
            if (tagDraft.isNotBlank()) { form.addTag(tagDraft); tagDraft = "" }
            // A new secret is created only with a live keychain (otherwise it would sit in the vault
            // unlinked to a host); ASK/mock path -> credentialId = null. EXISTING binding is passed
            // through as-is (not recreated), same in edit mode.
            val credentialId = form.resolveCredentialId(saveCredential = { draft -> credentials?.save(draft) })
            // editHost?.id != null -> update the existing profile in place, otherwise create a new one.
            hosts.save(form.toDraft(id = editHost?.id, credentialId = credentialId))
            // The secret is already sealed into the vault — drop the form state's references to it,
            // shrinking the key/password's lifetime in the heap (a JVM String can't be zeroed in
            // place, but the reference is dropped).
            form.password = ""; form.privateKeyPem = ""; form.passphrase = ""
            state.closeSheet()
        }
    }

    // Fixed-height panel (0.92 of the screen), scrollable; the shared sheet chrome lives in MobileBottomSheet.
    MobileBottomSheet(
        onDismiss = state::closeSheet,
        panelModifier = Modifier
            .fillMaxHeight(0.92f)
            .verticalScroll(rememberScrollState())
            .padding(start = 22.dp, end = 22.dp, bottom = 30.dp),
    ) {
        Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Txt(if (editHost != null) stringResource(Res.string.conn_title_edit) else stringResource(Res.string.conn_title_new), color = Skerry.colors.text, size = 20.sp, weight = FontWeight.Bold)
                Sym(
                    "close",
                    size = 24.sp,
                    color = Skerry.colors.dim,
                    modifier = Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = state::closeSheet,
                    ),
                )
            }
            Txt(
                stringResource(Res.string.conn_subtitle_mobile),
                color = Skerry.colors.dim,
                size = 12.5.sp,
                lineHeight = 18.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 18.dp),
            )

            MobileFormField(stringResource(Res.string.conn_field_name)) { MobileFormInput(form.name, { form.name = it }, "prod-web-01") }
            Spacer(Modifier.height(14.dp))
            MobileFormField(stringResource(Res.string.conn_field_protocol)) { MobileProtocolPicker(form) }
            Spacer(Modifier.height(14.dp))
            val serial = form.connectionType == ConnectionType.SERIAL
            MobileFormField(if (serial) stringResource(Res.string.conn_field_device) else stringResource(Res.string.conn_field_host_address)) {
                MobileFormInput(form.address, { form.address = it }, if (serial) "/dev/ttyUSB0 or COM3" else "192.168.1.45")
            }
            // Picker for discovered ports (Android USB-OTG): tap fills Device. Empty means manual entry only.
            if (serial) MobileSerialPortPicker(form)
            Spacer(Modifier.height(14.dp))
            if (form.connectionType.usesSshAuth) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MobileFormField(stringResource(Res.string.conn_field_username), Modifier.weight(1f)) {
                        MobileFormInput(form.username, { form.username = it }, "root")
                    }
                    MobileFormField(stringResource(Res.string.conn_field_port), Modifier.width(84.dp)) {
                        MobileFormInput(form.port, { form.port = it }, "22", keyboardType = KeyboardType.Number)
                    }
                }
                Spacer(Modifier.height(14.dp))
                MobileFormField(stringResource(Res.string.conn_field_authentication)) { MobileAuthPicker(form) }
                Spacer(Modifier.height(14.dp))
                // ProxyJump: tunnel the session through another saved SSH profile (desktop parity).
                MobileFormField(stringResource(Res.string.conn_field_jump_host)) {
                    MobileJumpHostPicker(form, hosts?.hosts ?: emptyList(), editHost?.id)
                }
                Spacer(Modifier.height(14.dp))
                // Keep-alive cadence (desktop parity); 0 = off. SSH-only: Mosh heartbeats on its own.
                if (form.connectionType == ConnectionType.SSH) {
                    MobileFormField(stringResource(Res.string.conn_field_keep_alive)) { MobileKeepAlivePicker(form) }
                    Spacer(Modifier.height(14.dp))
                }
            } else if (form.connectionType.isVnc) {
                // VNC: a password (no username), plus the RFB port. No jump host / keep-alive.
                MobileFormField(stringResource(Res.string.conn_field_port), Modifier.width(120.dp)) {
                    MobileFormInput(form.port, { form.port = it }, "5900", keyboardType = KeyboardType.Number)
                }
                Spacer(Modifier.height(14.dp))
                MobileFormField(stringResource(Res.string.conn_field_authentication)) { MobileAuthPicker(form, allowKey = false) }
                Spacer(Modifier.height(14.dp))
            } else {
                // Telnet/Serial: no authentication; show only port/baud.
                MobileFormField(if (serial) stringResource(Res.string.conn_field_baud) else stringResource(Res.string.conn_field_port), Modifier.width(120.dp)) {
                    MobileFormInput(form.port, { form.port = it }, if (serial) "9600" else "23", keyboardType = KeyboardType.Number)
                }
                Spacer(Modifier.height(14.dp))
            }
            // Group suggestions come from already-created hosts (parity with desktop GroupPicker); empty in preview.
            MobileFormField(stringResource(Res.string.conn_field_group)) { MobileGroupPicker(form, hosts?.hosts ?: emptyList(), onCreateGroup = { createGroupOpen = true }) }
            Spacer(Modifier.height(14.dp))
            MobileFormField(stringResource(Res.string.conn_field_tags)) {
                // Suggestions are tags from other hosts not yet added here (parity with desktop Tags); empty in preview.
                val allHosts = hosts?.hosts ?: emptyList()
                val suggestions = remember(allHosts, form.tags, tagDraft) { tagSuggestions(allHosts, form.tags, tagDraft) }
                MobileTagsEditor(
                    tags = form.tags,
                    onRemove = { form.removeTag(it) },
                    draft = tagDraft,
                    // A comma commits the tag(s) immediately; a single tag commits on Enter (onCommit).
                    onDraftChange = { v -> if (v.contains(',')) { form.addTag(v); tagDraft = "" } else tagDraft = v },
                    onCommit = { form.addTag(tagDraft); tagDraft = "" },
                    suggestions = suggestions,
                    placeholder = stringResource(Res.string.conn_tag_add_placeholder),
                    onPick = { tag -> form.addTag(tag); tagDraft = "" },
                    menuBackground = Skerry.colors.surface2,
                )
            }

            // AI policy: not for VNC (a remote desktop has no shell for AI to act on).
            if (!form.connectionType.isVnc && (LocalFeatures.current.ai || LocalAi.current != null)) {
                Spacer(Modifier.height(14.dp))
                MobileFormField(stringResource(Res.string.conn_field_ai_policy_short)) { AiPolicyPills(form) }
            }

            Spacer(Modifier.height(22.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (canSave) Skerry.colors.cyan else Skerry.colors.cyan.copy(alpha = 0.4f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onSave)
                    .padding(15.dp),
                contentAlignment = Alignment.Center,
            ) {
                Txt(if (editHost != null) stringResource(Res.string.conn_save_changes) else stringResource(Res.string.conn_save_connection), color = Skerry.colors.ink, size = 16.sp, weight = FontWeight.Bold)
            }
    }
    // "New group" overlay is a sibling above the sheet (its own full-screen scrim), so it rises correctly above the keyboard.
    if (createGroupOpen) {
        MobileGroupCreateDialog(
            onDismiss = { createGroupOpen = false },
            onCreate = { name -> form.group = name.trim(); createGroupOpen = false },
        )
    }
}

/**
 * Segmented transport picker (SSH / Telnet / Serial) on the phone — parity with desktop
 * ProtocolPicker. Writes the type through [NewConnectionFormState.chooseConnectionType] (fills in
 * the default port/baud) and rebuilds the form.
 */
@Composable
private fun MobileSerialPortPicker(form: NewConnectionFormState) {
    val ports = remember { listSerialPorts() }
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

@Composable
private fun MobileProtocolPicker(form: NewConnectionFormState) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp)).background(Skerry.colors.bg).border(1.dp, Skerry.colors.cyan14, RoundedCornerShape(11.dp)).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        MobileProtocolSegment(stringResource(Res.string.conn_protocol_ssh), form.connectionType == ConnectionType.SSH, Modifier.weight(1f)) { form.chooseConnectionType(ConnectionType.SSH) }
        MobileProtocolSegment(stringResource(Res.string.conn_protocol_mosh), form.connectionType == ConnectionType.MOSH, Modifier.weight(1f)) { form.chooseConnectionType(ConnectionType.MOSH) }
        MobileProtocolSegment(stringResource(Res.string.conn_protocol_telnet), form.connectionType == ConnectionType.TELNET, Modifier.weight(1f)) { form.chooseConnectionType(ConnectionType.TELNET) }
        MobileProtocolSegment(stringResource(Res.string.conn_protocol_serial), form.connectionType == ConnectionType.SERIAL, Modifier.weight(1f)) { form.chooseConnectionType(ConnectionType.SERIAL) }
        MobileProtocolSegment(stringResource(Res.string.conn_protocol_vnc), form.connectionType == ConnectionType.VNC, Modifier.weight(1f)) { form.chooseConnectionType(ConnectionType.VNC) }
        // LOCAL is intentionally absent — the local shell isn't a user-created profile; it's launched
        // from the empty-tab placeholder and configured in Settings, not created here.
    }
}

@Composable
private fun MobileProtocolSegment(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
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
private fun MobileAuthPicker(form: NewConnectionFormState, allowKey: Boolean = true) {
    val credentials = LocalCredentials.current
    // VNC (allowKey = false) can only use a password secret: a key/certificate would silently map
    // to no auth at connect (toVncAuth), so those are not offered.
    val saved = (credentials?.credentials ?: emptyList())
        .filter { allowKey || it.secret is CredentialSecret.Password }
    var menuOpen by remember { mutableStateOf(false) }
    val selectedLabel = when (form.authMode) {
        AuthMode.ASK -> stringResource(Res.string.conn_auth_ask)
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
private fun MobileJumpHostPicker(form: NewConnectionFormState, allHosts: List<Host>, editingId: String?) {
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
                        MobileGroupOption(host.label, selected = form.jumpHostId == host.id) { form.jumpHostId = host.id; menuOpen = false }
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
private fun MobileKeepAlivePicker(form: NewConnectionFormState) {
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
 * The sheet's "Group" field: a dropdown select — "No group", the catalog's already-created groups
 * ([groupSuggestions]), and "New group…" which opens the creation dialog. The selected group is
 * stored in [NewConnectionFormState.group]; creating a new one just sets its name (the profile
 * creates the folder on save). No free-form entry in the field itself — only the list + explicit
 * creation, to avoid typo-duplicate groups on the phone.
 */
@Composable
private fun MobileGroupPicker(form: NewConnectionFormState, allHosts: List<Host>, onCreateGroup: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    val groups = remember(allHosts) { groupSuggestions(allHosts) }
    val hasGroup = form.group.isNotBlank()
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
                    Txt(if (hasGroup) form.group else stringResource(Res.string.conn_group_none), color = if (hasGroup) Skerry.colors.text else Skerry.colors.faint, size = 15.sp)
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
                    MobileGroupOption(stringResource(Res.string.conn_group_none), selected = !hasGroup) { form.group = ""; menuOpen = false }
                    groups.forEach { group ->
                        key(group) {
                            MobileGroupOption(group, selected = form.group == group) { form.group = group; menuOpen = false }
                        }
                    }
                    HLine(modifier = Modifier.padding(vertical = 4.dp))
                    MobileGroupOption(stringResource(Res.string.conn_group_new), selected = false, icon = "add") { menuOpen = false; onCreateGroup() }
                }
            },
        )
    }
}

/** Group select option row: optional icon + name + checkmark when selected. */
@Composable
private fun MobileGroupOption(title: String, selected: Boolean, icon: String? = null, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) Skerry.colors.cyan10 else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        if (icon != null) Sym(icon, size = 18.sp, color = Skerry.colors.cyanBright)
        Txt(title, color = if (selected) Skerry.colors.cyanBright else Skerry.colors.text, size = 14.sp, weight = if (selected) FontWeight.Medium else FontWeight.Normal, modifier = Modifier.weight(1f))
        if (selected) Sym("check", size = 17.sp, color = Skerry.colors.cyanBright)
    }
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
private fun AiPolicyPills(form: NewConnectionFormState) {
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
