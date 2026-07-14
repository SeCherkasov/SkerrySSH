package app.skerry.ui.host

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import app.skerry.shared.host.Host
import app.skerry.shared.ssh.ConnectionType
import app.skerry.shared.ssh.SshAuth
import app.skerry.shared.ssh.SshTarget
import app.skerry.shared.ssh.usesSshAuth
import app.skerry.ui.connection.ConnectionTestController
import app.skerry.ui.connection.ConnectionTestStatus
import app.skerry.ui.connection.JumpChainResolution
import app.skerry.ui.connection.jumpHostCandidates
import app.skerry.ui.connection.jumpProblemText
import app.skerry.ui.connection.resolveJumpChain
import app.skerry.ui.connection.toSshAuth
import app.skerry.ui.design.DropdownField
import app.skerry.ui.host.AuthMode
import app.skerry.ui.host.NewConnectionFormState
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
import app.skerry.ui.generated.resources.conn_field_ai_policy
import app.skerry.ui.generated.resources.conn_field_authentication
import app.skerry.ui.generated.resources.conn_field_baud
import app.skerry.ui.generated.resources.conn_field_device
import app.skerry.ui.generated.resources.conn_field_group
import app.skerry.ui.generated.resources.conn_field_host_address
import app.skerry.ui.generated.resources.conn_field_jump_host
import app.skerry.ui.generated.resources.conn_field_keep_alive
import app.skerry.ui.generated.resources.conn_field_name
import app.skerry.ui.generated.resources.conn_field_port
import app.skerry.ui.generated.resources.conn_field_protocol
import app.skerry.ui.generated.resources.conn_field_tags
import app.skerry.ui.generated.resources.conn_field_username
import app.skerry.ui.generated.resources.conn_footer_encrypted
import app.skerry.ui.generated.resources.conn_group_new
import app.skerry.ui.generated.resources.conn_group_new_title
import app.skerry.ui.generated.resources.conn_group_none
import app.skerry.ui.generated.resources.conn_jump_none
import app.skerry.ui.generated.resources.conn_protocol_serial
import app.skerry.ui.generated.resources.conn_protocol_mosh
import app.skerry.ui.generated.resources.conn_protocol_ssh
import app.skerry.ui.generated.resources.conn_protocol_telnet
import app.skerry.ui.generated.resources.conn_telnet_plaintext_warning
import app.skerry.ui.generated.resources.conn_save
import app.skerry.ui.generated.resources.conn_save_changes
import app.skerry.ui.generated.resources.conn_subtitle_edit
import app.skerry.ui.generated.resources.conn_subtitle_new
import app.skerry.ui.generated.resources.conn_tag_add_placeholder
import app.skerry.ui.generated.resources.conn_test
import app.skerry.ui.generated.resources.conn_test_checking
import app.skerry.ui.generated.resources.conn_test_incomplete
import app.skerry.ui.generated.resources.conn_test_connected
import app.skerry.ui.generated.resources.conn_test_rtt_ms
import app.skerry.ui.generated.resources.conn_title_edit
import app.skerry.ui.generated.resources.conn_title_new
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.design.AnchoredDropdown
import app.skerry.ui.design.D
import app.skerry.ui.app.DesktopDesignState
import app.skerry.ui.design.GhostButton
import app.skerry.ui.design.HLine
import app.skerry.ui.design.IconBtn
import app.skerry.ui.app.LocalAi
import app.skerry.ui.app.LocalCredentials
import app.skerry.ui.app.LocalFeatures
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.ModalScrim
import app.skerry.ui.design.consumeClicks
import app.skerry.ui.app.LocalHosts
import app.skerry.ui.app.LocalTestTransport
import app.skerry.ui.ai.POLICY_OPTIONS
import app.skerry.ui.ai.PolicyOption
import app.skerry.ui.design.PrimaryButton
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.i18n.label
import app.skerry.ui.vault.title

/**
 * "New connection" / "Edit connection" modal: host profile form plus AI policy selection. With a
 * live [LocalHosts] (behind the vault gate), Save creates or (when [editHost] != null) updates the
 * profile via [app.skerry.ui.host.HostManagerController] and highlights it in the sidebar; without
 * it (mock/preview), Save just closes the modal. In edit mode the form is prefilled from
 * [editHost] ([NewConnectionFormState.fromHost]), and saving keeps its [Host.id]. Tags are editable
 * (pills plus inline input, wired to [NewConnectionFormState]).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NewConnectionModal(state: DesktopDesignState, editHost: Host? = null) {
    val hosts = LocalHosts.current
    // Already-created hosts, the suggestion source for the Group/Tags pickers (empty in mock/preview).
    val allHosts = hosts?.hosts ?: emptyList()
    val credentials = LocalCredentials.current
    // Keyed by editHost: opening the modal for editing (or switching target) rebuilds the form from the profile.
    val form = remember(editHost) { editHost?.let { NewConnectionFormState.fromHost(it) } ?: NewConnectionFormState() }
    // Guards repeated Save (Enter/double click) until the modal closes, otherwise a duplicate secret+host in the vault.
    // Keyed by editHost along with form: switching target resets the guard instead of sticking to the old one.
    var submitting by remember(editHost) { mutableStateOf(false) }
    // Uncommitted tag input (pill not created yet). Hoisted here so Save can commit it.
    var tagDraft by remember(editHost) { mutableStateOf("") }
    // "Test connection": a one-off connect without opening a session. Only with a live transport
    // (behind the vault gate); in mock/preview tester == null and the button is disabled.
    val transport = LocalTestTransport.current
    val testScope = rememberCoroutineScope()
    val tester = remember(transport, testScope) { transport?.let { ConnectionTestController(it, testScope) } }
    // On transport change (new tester) or modal close, cancel the old tester's in-flight check,
    // otherwise an orphaned connection probe would keep the network busy until its own timeout.
    DisposableEffect(tester) { onDispose { tester?.reset() } }
    // Whether auth is ready for testing, WITHOUT materializing the secret (that's only assembled in
    // onClick, so the password/key copy lives just for the connect, not the whole time the modal is open).
    val hasTestSecret = when (form.authMode) {
        AuthMode.NEW_PASSWORD -> form.password.isNotEmpty()
        AuthMode.NEW_KEY -> form.privateKeyPem.isNotBlank()
        AuthMode.EXISTING -> credentials?.credentials?.any { it.id == form.existingCredentialId } == true
        AuthMode.ASK -> false
    }
    // "Test connection" needs the SSH auth path (Telnet/Serial have no auth/cipher probe).
    // For Mosh it probes the SSH hop — the UDP leg is only exercised by a real connect.
    val canTest = tester != null && form.connectionType.usesSshAuth && hasTestSecret &&
        form.address.isNotBlank() && form.username.isNotBlank() && form.portOrNull != null
    // Editing connection/auth fields invalidates the previous test result, it's no longer relevant.
    LaunchedEffect(form.address, form.username, form.port, form.authMode, form.existingCredentialId, form.password, form.privateKeyPem, form.passphrase, form.jumpHostId) {
        tester?.reset()
    }
    // ProxyJump chain for "Test connection" — the probe must ride the same route as a real session,
    // so a broken chain fails the test with the same localized message as the connect dialogs.
    val testJump = resolveJumpChain(
        form.jumpHostId, editHost?.id,
        findHost = { id -> allHosts.firstOrNull { it.id == id } },
        findCredential = { id -> credentials?.find(id) },
    )
    val jumpErrorText = (testJump as? JumpChainResolution.Unavailable)?.let { jumpProblemText(it.problem) }
    // Shown as the test result when "Test" is clicked with an incomplete form (no username/host/secret):
    // the button stays tappable, so give feedback instead of silently doing nothing.
    val incompleteTestMessage = stringResource(Res.string.conn_test_incomplete)
    ModalScrim(onDismiss = state::closeModal) {
        Column(
            Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .padding(20.dp)
                .heightIn(max = 720.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(D.surfaceDeep)
                .border(1.dp, D.cyan14, RoundedCornerShape(12.dp))
                .consumeClicks(),
        ) {
            Box(Modifier.fillMaxWidth().padding(start = 26.dp, end = 26.dp, top = 22.dp, bottom = 14.dp)) {
                Column {
                    Txt(if (editHost != null) stringResource(Res.string.conn_title_edit) else stringResource(Res.string.conn_title_new), color = D.text, size = 18.sp, weight = FontWeight.SemiBold, letterSpacing = (-0.2).sp)
                    Txt(
                        if (editHost != null) stringResource(Res.string.conn_subtitle_edit)
                        else stringResource(Res.string.conn_subtitle_new),
                        color = D.dim, size = 12.5.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 6.dp),
                    )
                }
                IconBtn("close", onClick = state::closeModal, modifier = Modifier.align(Alignment.TopEnd))
            }
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(start = 26.dp, end = 26.dp, top = 6.dp, bottom = 22.dp)) {
                Field(stringResource(Res.string.conn_field_name)) { ModalTextField(form.name, { form.name = it }, "e.g. prod-web-01") }
                Spacer14()
                Field(stringResource(Res.string.conn_field_protocol)) { ProtocolPicker(form) }
                // Telnet has no transport encryption (unlike SSH/Mosh) — warn inline, mirroring the
                // insecure-URL notices on the Sync/AI forms. The transport itself is correct (no creds
                // auto-sent), this is a heads-up, not a block.
                if (form.connectionType == ConnectionType.TELNET) {
                    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Sym("warning", size = 14.sp, color = D.sunset)
                        Txt(stringResource(Res.string.conn_telnet_plaintext_warning), color = D.sunset, size = 11.5.sp, lineHeight = 15.sp)
                    }
                }
                Spacer14()
                val serial = form.connectionType == ConnectionType.SERIAL
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Field(if (serial) stringResource(Res.string.conn_field_device) else stringResource(Res.string.conn_field_host_address), Modifier.weight(1f)) {
                        ModalTextField(
                            form.address, { form.address = it },
                            if (serial) "/dev/ttyUSB0 or COM3" else "192.168.1.45 or example.com",
                            icon = if (serial) "usb" else "dns",
                        )
                    }
                    Field(if (serial) stringResource(Res.string.conn_field_baud) else stringResource(Res.string.conn_field_port), Modifier.width(110.dp)) {
                        ModalTextField(form.port, { form.port = it }, if (serial) "9600" else "22", keyboardType = KeyboardType.Number)
                    }
                }
                // Picker of discovered ports (desktop: jSerialComm, Android: USB-OTG): tapping fills
                // the Device field. Empty (no adapter / no USB Host) means no chips, manual input remains.
                if (serial) SerialPortPicker(form)
                // Auth follows the SSH path (SSH and Mosh): Telnet enters login/password in the
                // terminal itself, Serial has no auth at all.
                if (form.connectionType.usesSshAuth) {
                    Spacer14()
                    Field(stringResource(Res.string.conn_field_username)) { ModalTextField(form.username, { form.username = it }, "root or username", icon = "person") }
                    Spacer14()
                    Field(stringResource(Res.string.conn_field_authentication)) { AuthPicker(form) }
                }
                Spacer14()
                Field(stringResource(Res.string.conn_field_group)) { GroupPicker(form, allHosts) }
                if (form.connectionType.usesSshAuth) {
                    Spacer14()
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Field(stringResource(Res.string.conn_field_jump_host), Modifier.weight(1f)) { JumpHostPicker(form, allHosts, editHost?.id) }
                        // Keep-alive is SSH-only: Mosh heartbeats on its own every few seconds,
                        // so a per-profile cadence would be an inert knob.
                        if (form.connectionType == ConnectionType.SSH) {
                            Field(stringResource(Res.string.conn_field_keep_alive), Modifier.weight(1f)) { KeepAlivePicker(form) }
                        }
                    }
                }
                Spacer14()
                Field(stringResource(Res.string.conn_field_tags)) {
                    // Suggestions = other hosts' tags not yet added here, narrowed by the typed draft.
                    var tagFocused by remember(editHost) { mutableStateOf(false) }
                    val tagSugs = remember(allHosts, form.tags, tagDraft) { tagSuggestions(allHosts, form.tags, tagDraft) }
                    AnchoredDropdown(
                        expanded = tagFocused && tagSugs.isNotEmpty(),
                        onDismiss = { tagFocused = false },
                        focusable = false, // don't steal focus from the tag input field
                        trigger = {
                            FlowRow(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).background(D.bg).border(1.dp, D.cyan14, RoundedCornerShape(7.dp)).padding(horizontal = 10.dp, vertical = 7.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                form.tags.forEach { tag -> key(tag) { RemovableTagPill(tag) { form.removeTag(tag) } } }
                                TagInput(
                                    value = tagDraft,
                                    // A comma commits tag(s) immediately; a single tag commits on Enter (onCommit).
                                    onValueChange = { v -> if (v.contains(',')) { form.addTag(v); tagDraft = "" } else tagDraft = v },
                                    onCommit = { form.addTag(tagDraft); tagDraft = "" },
                                    onFocusChanged = { tagFocused = it },
                                )
                            }
                        },
                        menu = { width ->
                            SuggestionMenu(width) {
                                // Clicking a suggestion adds the tag; focus stays on the field so typing
                                // can continue, and the menu recomputes without the just-added tag.
                                tagSugs.forEach { tag -> key(tag) { SuggestionRow("#$tag") { form.addTag(tag); tagDraft = "" } } }
                            }
                        },
                    )
                }
                // AI policy selection is visible when AI is actually available (live controller or feature flag).
                // Written directly into the form -> host profile (Host.aiPolicy).
                if (LocalFeatures.current.ai || LocalAi.current != null) {
                    Spacer14()
                    Field(stringResource(Res.string.conn_field_ai_policy)) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            POLICY_OPTIONS.forEach { opt ->
                                PolicyRow(opt, selected = form.aiPolicy == opt.policy, onClick = { form.aiPolicy = opt.policy })
                            }
                        }
                    }
                }
            }
            HLine()
            Row(
                Modifier.fillMaxWidth().background(Color(0x26000000)).padding(horizontal = 26.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // Before the test runs, an encryption hint; otherwise the test status takes its place.
                    val status = tester?.status ?: ConnectionTestStatus.Idle
                    if (status == ConnectionTestStatus.Idle) {
                        Sym("shield_lock", size = 14.sp, color = D.moss)
                        Txt(stringResource(Res.string.conn_footer_encrypted), color = D.faint, size = 11.sp)
                    } else {
                        TestStatusLabel(status)
                    }
                }
                Box(Modifier.clip(RoundedCornerShape(7.dp)).clickable(onClick = state::closeModal).padding(horizontal = 16.dp, vertical = 9.dp)) {
                    Txt(stringResource(Res.string.conn_cancel), color = D.dim, size = 12.5.sp)
                }
                GhostButton(
                    stringResource(Res.string.conn_test),
                    onClick = {
                        // Secret is materialized here (only for the connect's duration), target from form fields.
                        val auth = when (form.authMode) {
                            AuthMode.NEW_PASSWORD -> form.password.takeIf { it.isNotEmpty() }?.let { SshAuth.Password(it) }
                            AuthMode.NEW_KEY -> form.privateKeyPem.takeIf { it.isNotBlank() }?.let { SshAuth.PublicKey(it, form.passphrase.ifBlank { null }) }
                            AuthMode.EXISTING -> credentials?.credentials?.firstOrNull { it.id == form.existingCredentialId }?.toSshAuth()
                            AuthMode.ASK -> null
                        }
                        if (canTest && auth != null) {
                            when (testJump) {
                                is JumpChainResolution.Unavailable -> tester.fail(jumpErrorText.orEmpty())
                                is JumpChainResolution.Resolved ->
                                    tester.test(SshTarget(form.address.trim(), form.portOrNull ?: 22, form.username.trim(), jump = testJump.jump), auth)
                            }
                        } else {
                            // Form isn't ready to dial (missing host/username/credentials): report it as a
                            // failure so the click isn't a silent no-op.
                            tester?.fail(incompleteTestMessage)
                        }
                    },
                    fg = if (canTest) D.text else D.faint,
                    border = if (canTest) D.lineStrong else D.line,
                )
                PrimaryButton(
                    if (editHost != null) stringResource(Res.string.conn_save_changes) else stringResource(Res.string.conn_save),
                    onClick = {
                        if (submitting) {
                            // repeated click before close, ignore
                        } else if (hosts == null) {
                            state.closeModal() // mock/preview: nowhere to save
                        } else if (form.canSave) {
                            // Commit any uncommitted tag input so it isn't lost on Save.
                            if (tagDraft.isNotBlank()) { form.addTag(tagDraft); tagDraft = "" }
                            // A new secret (password/key) is sealed into the keychain, its id attached
                            // directly to the host; ASK/mock path with no vault -> credentialId = null.
                            submitting = true
                            // Secret is created only with a live keychain, otherwise it would sit in the
                            // vault with no link to a host (orphan). credentials is always present behind
                            // the gate; this guard fails closed on desync. In edit mode an EXISTING
                            // attachment is returned as-is (secret isn't recreated).
                            val credentialId = form.resolveCredentialId(
                                saveCredential = { draft -> credentials?.save(draft) },
                            )
                            // editHost?.id != null means updating the existing profile in place.
                            state.selectHost(hosts.save(form.toDraft(id = editHost?.id, credentialId = credentialId)))
                            // Secret is already sealed in the vault, clear references to it from the form
                            // state to shrink the key/password's lifetime on the heap (a JVM String can't
                            // be zeroed in place, but the reference is dropped). Same trick as mobile's
                            // MobileNewConnectionSheet.
                            form.password = ""; form.privateKeyPem = ""; form.passphrase = ""
                            state.closeModal()
                        }
                    },
                    bg = if (hosts == null || form.canSave) D.cyan else D.cyan.copy(alpha = 0.4f),
                )
            }
        }
    }
}

@Composable
private fun Spacer14() = Box(Modifier.size(14.dp))

@Composable
private fun Field(label: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier) {
        Txt(label.uppercase(), color = D.faint, size = 10.5.sp, weight = FontWeight.SemiBold, letterSpacing = 0.6.sp, modifier = Modifier.padding(bottom = 5.dp))
        content()
    }
}

/**
 * Editable form text field (optional leading icon): layout style plus placeholder.
 * [masked] hides input (password/passphrase); [singleLine] = false plus [mono] plus [minHeightDp]
 * gives a multi-line monospace area for pasting a private key (PEM).
 */
@Composable
private fun ModalTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    icon: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    masked: Boolean = false,
    singleLine: Boolean = true,
    mono: Boolean = false,
    minHeightDp: Int? = null,
) {
    val fonts = LocalFonts.current
    val family = if (mono) fonts.mono else fonts.ui
    val fontSize = if (mono) 11.5.sp else 13.sp
    val textStyle = remember(family, fontSize) {
        TextStyle(color = D.text, fontSize = fontSize, fontFamily = family, lineHeight = if (mono) 16.sp else 18.sp)
    }
    // Border/icon live in decorationBox so a click anywhere on the field places the caret.
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = singleLine,
        textStyle = textStyle,
        cursorBrush = SolidColor(D.cyan),
        visualTransformation = if (masked) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (masked) KeyboardType.Password else keyboardType),
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .then(if (minHeightDp != null) Modifier.heightIn(min = minHeightDp.dp) else Modifier)
                    .clip(RoundedCornerShape(7.dp)).background(D.bg).border(1.dp, D.cyan14, RoundedCornerShape(7.dp))
                    .padding(horizontal = 11.dp, vertical = 9.dp),
                verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (icon != null) Sym(icon, size = 16.sp, color = D.faint)
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Txt(placeholder, color = D.faint, size = fontSize, font = if (mono) fonts.mono else null)
                    inner()
                }
            }
        },
    )
}

/**
 * Segmented transport picker (SSH / Telnet / Serial): writes [NewConnectionFormState.connectionType]
 * via [NewConnectionFormState.chooseConnectionType] (which substitutes the default port/speed). Changing
 * the type rebuilds the form (hides auth, changes the address/port labels).
 */
@Composable
private fun SerialPortPicker(form: NewConnectionFormState) {
    // Enumerated once when the form opens (cheap, no permission needed). Empty means nothing rendered.
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
                        .background(if (selected) D.cyan14 else D.bg)
                        .border(1.dp, if (selected) D.cyan else D.cyan14, RoundedCornerShape(7.dp))
                        .clickable { form.address = port.systemName }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Sym("usb", size = 14.sp, color = if (selected) D.cyanBright else D.faint)
                    Txt(port.description, color = if (selected) D.text else D.dim, size = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun ProtocolPicker(form: NewConnectionFormState) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).background(D.bg).border(1.dp, D.cyan14, RoundedCornerShape(7.dp)).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        ProtocolSegment(stringResource(Res.string.conn_protocol_ssh), "lan", form.connectionType == ConnectionType.SSH, Modifier.weight(1f)) { form.chooseConnectionType(ConnectionType.SSH) }
        ProtocolSegment(stringResource(Res.string.conn_protocol_mosh), "bolt", form.connectionType == ConnectionType.MOSH, Modifier.weight(1f)) { form.chooseConnectionType(ConnectionType.MOSH) }
        ProtocolSegment(stringResource(Res.string.conn_protocol_telnet), "terminal", form.connectionType == ConnectionType.TELNET, Modifier.weight(1f)) { form.chooseConnectionType(ConnectionType.TELNET) }
        ProtocolSegment(stringResource(Res.string.conn_protocol_serial), "cable", form.connectionType == ConnectionType.SERIAL, Modifier.weight(1f)) { form.chooseConnectionType(ConnectionType.SERIAL) }
    }
}

/** One pill of the segmented protocol picker: the active one sits on a cyan backing. */
@Composable
private fun ProtocolSegment(label: String, icon: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier.clip(RoundedCornerShape(5.dp)).background(if (selected) D.cyan10 else Color.Transparent).clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        Sym(icon, size = 15.sp, color = if (selected) D.cyanBright else D.faint)
        Txt(label, color = if (selected) D.cyanBright else D.dim, size = 12.5.sp, weight = if (selected) FontWeight.Medium else FontWeight.Normal)
    }
}

/**
 * Host auth selection: a working dropdown (Ask every time / new password / new key / already-saved
 * keychain secrets from the vault) plus inline fields for a new secret. The saved list comes from the
 * live [LocalCredentials] (behind the vault gate); in the mock path only the no-vault options remain.
 */
@Composable
private fun AuthPicker(form: NewConnectionFormState) {
    val credentials = LocalCredentials.current
    val saved = credentials?.credentials ?: emptyList()
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
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).background(D.bg).border(1.dp, D.cyan14, RoundedCornerShape(7.dp)).clickable { menuOpen = !menuOpen }.padding(horizontal = 11.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Txt(selectedLabel, color = D.text, size = 13.sp)
                    Sym(if (menuOpen) "expand_less" else "expand_more", size = 16.sp, color = D.faint)
                }
            },
            menu = { width ->
                // The menu floats ABOVE the form (Popup) rather than pushing it apart; width = trigger width, scrolls on overflow.
                Column(Modifier.width(width).clip(RoundedCornerShape(7.dp)).background(D.surfaceDeep).border(1.dp, D.cyan14, RoundedCornerShape(7.dp)).heightIn(max = 320.dp).verticalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
                    AuthOption("vpn_key_off", stringResource(Res.string.conn_auth_ask), stringResource(Res.string.conn_auth_ask_desc), form.authMode == AuthMode.ASK) {
                        form.authMode = AuthMode.ASK; menuOpen = false
                    }
                    AuthOption("password", stringResource(Res.string.conn_auth_password_option), stringResource(Res.string.conn_auth_password_desc), form.authMode == AuthMode.NEW_PASSWORD) {
                        form.authMode = AuthMode.NEW_PASSWORD; menuOpen = false
                    }
                    AuthOption("key", stringResource(Res.string.conn_auth_key_option), stringResource(Res.string.conn_auth_key_desc), form.authMode == AuthMode.NEW_KEY) {
                        form.authMode = AuthMode.NEW_KEY; menuOpen = false
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
private fun AuthOption(icon: String, title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(if (selected) D.cyan10 else Color.Transparent).clickable(onClick = onClick).padding(horizontal = 11.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Sym(icon, size = 16.sp, color = if (selected) D.cyanBright else D.dim)
        Column(Modifier.weight(1f)) {
            Txt(title, color = if (selected) D.cyanBright else D.text, size = 12.5.sp, weight = FontWeight.Medium)
            Txt(subtitle, color = D.faint, size = 10.5.sp)
        }
        if (selected) Sym("check", size = 15.sp, color = D.cyanBright)
    }
}

/**
 * "Jump host" field: "None — direct" plus eligible saved SSH profiles ([jumpHostCandidates] — no
 * self-reference, no cycle through the edited host). Stores only the id
 * ([NewConnectionFormState.jumpHostId]); the chain itself is resolved at connect time.
 */
@Composable
private fun JumpHostPicker(form: NewConnectionFormState, allHosts: List<Host>, editingId: String?) {
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
private fun KeepAlivePicker(form: NewConnectionFormState) {
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
private fun GroupPicker(form: NewConnectionFormState, allHosts: List<Host>) {
    var menuOpen by remember { mutableStateOf(false) }
    var createOpen by remember { mutableStateOf(false) }
    val groups = remember(allHosts) { groupSuggestions(allHosts) }
    val hasGroup = form.group.isNotBlank()
    AnchoredDropdown(
        expanded = menuOpen,
        onDismiss = { menuOpen = false },
        trigger = {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).background(D.bg).border(1.dp, D.cyan14, RoundedCornerShape(7.dp)).clickable { menuOpen = !menuOpen }.padding(horizontal = 11.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Txt(if (hasGroup) form.group else stringResource(Res.string.conn_group_none), color = if (hasGroup) D.text else D.faint, size = 13.sp)
                Sym(if (menuOpen) "expand_less" else "expand_more", size = 16.sp, color = D.faint)
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
        Modifier.fillMaxWidth().background(if (selected) D.cyan10 else Color.Transparent).clickable(onClick = onClick).padding(horizontal = 11.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        if (icon != null) Sym(icon, size = 15.sp, color = D.cyanBright)
        Txt(title, color = if (selected) D.cyanBright else D.text, size = 12.5.sp, weight = if (selected) FontWeight.Medium else FontWeight.Normal, modifier = Modifier.weight(1f))
        if (selected) Sym("check", size = 15.sp, color = D.cyanBright)
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
                    .background(D.surfaceDeep)
                    .border(1.dp, D.cyan14, RoundedCornerShape(12.dp))
                    .consumeClicks()
                    .padding(22.dp),
            ) {
                Txt(stringResource(Res.string.conn_group_new_title), color = D.text, size = 16.sp, weight = FontWeight.SemiBold)
                Spacer14()
                ModalTextField(name, { name = it }, "Production")
                Spacer14()
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                    Box(Modifier.clip(RoundedCornerShape(7.dp)).clickable(onClick = onDismiss).padding(horizontal = 16.dp, vertical = 9.dp)) {
                        Txt(stringResource(Res.string.conn_cancel), color = D.dim, size = 12.5.sp)
                    }
                    PrimaryButton(stringResource(Res.string.conn_create), onClick = { if (canCreate) onCreate(name) }, bg = if (canCreate) D.cyan else D.cyan.copy(alpha = 0.4f))
                }
            }
        }
    }
}

/** Container for suggestion dropdowns (group/tags): trigger width, scrolls on overflow, menu style. */
@Composable
private fun SuggestionMenu(width: Dp, content: @Composable () -> Unit) {
    Column(
        Modifier.width(width).clip(RoundedCornerShape(7.dp)).background(D.surfaceDeep).border(1.dp, D.cyan14, RoundedCornerShape(7.dp))
            .heightIn(max = 240.dp).verticalScroll(rememberScrollState()).padding(vertical = 4.dp),
    ) { content() }
}

/** One suggestion row in the dropdown list: a single label, click to select. */
@Composable
private fun SuggestionRow(label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 11.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Txt(label, color = D.text, size = 12.5.sp)
    }
}

/** Tag pill with a remove cross; [tag] is the canonical form, shown on screen with a `#` prefix. */
@Composable
private fun RemovableTagPill(tag: String, onRemove: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(20.dp)).background(D.cyan.copy(alpha = 0.12f)).padding(start = 9.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Txt("#$tag", color = D.cyanBright, size = 11.sp)
        Box(Modifier.clip(CircleShape).clickable(onClick = onRemove).padding(2.dp), contentAlignment = Alignment.Center) {
            Sym("close", size = 12.sp, color = D.cyanBright)
        }
    }
}

/** "Test connection" status in the modal footer: checking / success (with RTT) / failure with a reason. */
@Composable
private fun TestStatusLabel(status: ConnectionTestStatus) {
    when (status) {
        ConnectionTestStatus.Idle -> {}
        ConnectionTestStatus.Checking -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Sym("progress_activity", size = 14.sp, color = D.dim)
            Txt(stringResource(Res.string.conn_test_checking), color = D.dim, size = 11.5.sp)
        }
        is ConnectionTestStatus.Success -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Sym("check_circle", size = 14.sp, color = D.moss)
            // RTT goes on its own line: the footer slot is narrow and a single "Connected · N ms"
            // string used to wrap mid-unit.
            Column {
                Txt(stringResource(Res.string.conn_test_connected), color = D.moss, size = 11.5.sp)
                status.roundTripMillis?.let {
                    Txt(stringResource(Res.string.conn_test_rtt_ms, it), color = D.moss.copy(alpha = 0.75f), size = 10.5.sp)
                }
            }
        }
        is ConnectionTestStatus.Failure -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Sym("error", size = 14.sp, color = D.storm)
            Txt(status.message, color = D.storm, size = 11.5.sp)
        }
    }
}

/** Inline input for a new tag inside the Tags block: Enter ([onCommit]) or a comma commits the pill. */
@Composable
private fun TagInput(value: String, onValueChange: (String) -> Unit, onCommit: () -> Unit, onFocusChanged: ((Boolean) -> Unit)? = null) {
    val fonts = LocalFonts.current
    val textStyle = remember(fonts.ui) { TextStyle(color = D.text, fontSize = 12.5.sp, fontFamily = fonts.ui) }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = textStyle,
        cursorBrush = SolidColor(D.cyan),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onCommit() }),
        modifier = Modifier.widthIn(min = 72.dp).onFocusChanged { onFocusChanged?.invoke(it.isFocused) },
        decorationBox = { inner ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) Txt(stringResource(Res.string.conn_tag_add_placeholder), color = D.faint, size = 12.5.sp)
                inner()
            }
        },
    )
}

@Composable
private fun PolicyRow(opt: PolicyOption, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) D.cyan10 else Color.Transparent)
            .border(1.dp, if (selected) D.cyan else D.cyan06, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.padding(top = 2.dp).size(16.dp).clip(CircleShape).border(1.5.dp, if (selected) D.cyan else D.faint, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Box(Modifier.size(8.dp).clip(CircleShape).background(D.cyan))
        }
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Sym(opt.icon, size = 14.sp, color = D.dim)
                Txt(stringResource(opt.title), color = D.text, size = 13.sp, weight = FontWeight.Medium)
            }
            Txt(stringResource(opt.desc), color = D.dim, size = 11.5.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 2.dp))
        }
    }
}
