package app.skerry.ui.host

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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.host.Host
import app.skerry.shared.host.capNotes
import app.skerry.shared.ssh.ConnectionType
import app.skerry.shared.ssh.SshTarget
import app.skerry.shared.ssh.usesSshAuth
import app.skerry.shared.ssh.isRdp
import app.skerry.shared.ssh.hasAiPolicy
import app.skerry.shared.ssh.hasConnectionTest
import app.skerry.shared.ssh.isVnc
import app.skerry.ui.connection.ContainerBrowseController
import app.skerry.ui.connection.ContainerBrowseProblem
import app.skerry.ui.connection.ConnectionTestController
import app.skerry.ui.connection.ConnectionTestProblem
import app.skerry.ui.connection.ConnectionTestStatus
import app.skerry.ui.connection.JumpChainResolution
import app.skerry.ui.connection.resolveJumpChain
import app.skerry.ui.host.AuthMode
import app.skerry.ui.host.NewConnectionFormState
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.shell_tip_close
import app.skerry.ui.generated.resources.conn_cancel
import app.skerry.ui.generated.resources.conn_duplicate_name
import app.skerry.ui.generated.resources.conn_field_ai_policy
import app.skerry.ui.generated.resources.conn_field_audio
import app.skerry.ui.generated.resources.conn_field_clipboard
import app.skerry.ui.generated.resources.conn_field_image_quality
import app.skerry.ui.generated.resources.conn_field_authentication
import app.skerry.ui.generated.resources.conn_field_baud
import app.skerry.ui.generated.resources.conn_field_device
import app.skerry.ui.generated.resources.conn_field_group
import app.skerry.ui.generated.resources.conn_field_host_address
import app.skerry.ui.generated.resources.conn_field_jump_host
import app.skerry.ui.generated.resources.conn_field_keep_alive
import app.skerry.ui.generated.resources.conn_field_name
import app.skerry.ui.generated.resources.conn_field_notes
import app.skerry.ui.generated.resources.conn_field_port
import app.skerry.ui.generated.resources.conn_field_protocol
import app.skerry.ui.generated.resources.conn_field_tags
import app.skerry.ui.generated.resources.conn_field_domain
import app.skerry.ui.generated.resources.conn_field_username
import app.skerry.ui.generated.resources.conn_footer_encrypted
import app.skerry.ui.generated.resources.conn_notes_placeholder
import app.skerry.ui.generated.resources.conn_telnet_plaintext_warning
import app.skerry.ui.generated.resources.conn_vnc_plaintext_warning
import app.skerry.ui.generated.resources.conn_save
import app.skerry.ui.generated.resources.conn_save_changes
import app.skerry.ui.generated.resources.conn_subtitle_edit
import app.skerry.ui.generated.resources.conn_subtitle_new
import app.skerry.ui.generated.resources.conn_test
import app.skerry.ui.generated.resources.conn_title_edit
import app.skerry.ui.generated.resources.conn_title_new
import app.skerry.ui.generated.resources.conn_title_new_desktop
import app.skerry.ui.generated.resources.conn_title_edit_desktop
import app.skerry.ui.generated.resources.conn_subtitle_new_desktop
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.design.AnchoredDropdown
import app.skerry.ui.design.CancelButton
import app.skerry.ui.app.DesktopDesignState
import app.skerry.ui.design.GhostButton
import app.skerry.ui.design.HLine
import app.skerry.ui.design.IconBtn
import app.skerry.ui.app.LocalAi
import app.skerry.ui.app.LocalCredentials
import app.skerry.ui.app.LocalFeatures
import app.skerry.ui.design.ModalScrim
import app.skerry.ui.design.consumeClicks
import app.skerry.ui.app.LocalHosts
import app.skerry.ui.app.LocalTestTransport
import app.skerry.ui.ai.POLICY_OPTIONS
import app.skerry.ui.design.PrimaryButton
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.design.modalBody
import app.skerry.ui.i18n.label
import app.skerry.ui.theme.Skerry
import androidx.compose.ui.platform.testTag
import app.skerry.ui.app.UiTags

/**
 * "New connection" / "Edit connection" modal: host profile form plus AI policy selection. With a
 * live [LocalHosts] (behind the vault gate), Save creates or (when [editHost] != null) updates the
 * profile via [app.skerry.ui.host.HostManagerController] and highlights it in the sidebar; without
 * it (mock/preview), Save just closes the modal. In edit mode the form is prefilled from
 * [editHost] ([NewConnectionFormState.fromHost]), and saving keeps its [Host.id]. Tags are editable
 * (pills plus inline input, wired to [NewConnectionFormState]).
 *
 * [duplicateOf] opens the modal in "New connection" mode prefilled as a copy of that host
 * ([NewConnectionFormState.duplicateOf]): same profile and shared secret under a "… Copy" name;
 * saving creates a new record (id = null).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NewConnectionModal(state: DesktopDesignState, editHost: Host? = null, duplicateOf: Host? = null) {
    val hosts = LocalHosts.current
    // Already-created hosts, the suggestion source for the Group/Tags pickers (empty in mock/preview).
    val allHosts = hosts?.hosts ?: emptyList()
    val credentials = LocalCredentials.current
    val copyName = duplicateOf?.let { stringResource(Res.string.conn_duplicate_name, it.label) }
    // Keyed by editHost/duplicateOf: opening the modal for editing or duplicating (or switching target)
    // rebuilds the form from the profile.
    // Which catalog this form belongs to (the list its button was pressed in, or the edited
    // profile's own section): it fixes the protocols offered and the wording of the header.
    val section = state.modalSection
    val form = remember(editHost, duplicateOf, section) {
        when {
            editHost != null -> NewConnectionFormState.fromHost(editHost)
            duplicateOf != null -> NewConnectionFormState.duplicateOf(duplicateOf, copyName.orEmpty())
            else -> NewConnectionFormState.forSection(section)
        }
    }
    // Guards repeated Save (Enter/double click) until the modal closes, otherwise a duplicate secret+host in the vault.
    // Keyed by editHost along with form: switching target resets the guard instead of sticking to the old one.
    var submitting by remember(editHost, duplicateOf) { mutableStateOf(false) }
    // Uncommitted tag input (pill not created yet). Hoisted here so Save can commit it.
    var tagDraft by remember(editHost, duplicateOf) { mutableStateOf("") }
    // "Test connection": a one-off connect without opening a session. Only with a live transport
    // (behind the vault gate); in mock/preview tester == null and the button is disabled.
    val transport = LocalTestTransport.current
    val testScope = rememberCoroutineScope()
    // The probe is an SSH connect, so the controller exists only where there is one to make: a remote
    // desktop, Telnet, Serial or a local shell has no test, and with no controller there is no status
    // to leave stale under a button the form doesn't draw.
    val showsTest = form.connectionType.hasConnectionTest
    val tester = remember(transport, testScope, showsTest) {
        if (showsTest) transport?.let { ConnectionTestController(it, testScope) } else null
    }
    // On transport change (new tester) or modal close, cancel the old tester's in-flight check,
    // otherwise an orphaned connection probe would keep the network busy until its own timeout.
    DisposableEffect(tester) { onDispose { tester?.reset() } }
    // "Browse containers" rides the same probe transport as the test (read-only host-key verifier).
    val browser = remember(transport, testScope) { transport?.let { ContainerBrowseController(it, testScope) } }
    DisposableEffect(browser) { onDispose { browser?.reset() } }
    // Whether auth is ready for testing, WITHOUT materializing the secret (that's only assembled in
    // onClick, so the password/key copy lives just for the connect, not the whole time the modal is open).
    val hasTestSecret = when (form.authMode) {
        AuthMode.NEW_PASSWORD -> form.password.isNotEmpty()
        AuthMode.NEW_KEY -> form.privateKeyPem.isNotBlank()
        AuthMode.EXISTING -> credentials?.credentials?.any { it.id == form.existingCredentialId } == true
        AuthMode.ASK -> false
        // Testable: the probe connects and answers whatever the server asks, same as a real session.
        AuthMode.INTERACTIVE -> true
    }
    // Whether the button can fire: the form still has to name a host, a user and a secret. For Mosh
    // the probe rides the SSH hop — the UDP leg is only exercised by a real connect.
    val canTest = tester != null && hasTestSecret &&
        form.address.isNotBlank() && form.username.isNotBlank() && form.portOrNull != null
    // Editing connection/auth fields invalidates the previous test result, it's no longer relevant.
    // A listing belongs to one host/runtime/namespace too — the same edits make it stale.
    LaunchedEffect(form.address, form.username, form.port, form.authMode, form.existingCredentialId, form.password, form.privateKeyPem, form.passphrase, form.jumpHostId) {
        tester?.reset()
        browser?.reset()
    }
    LaunchedEffect(form.containerRuntime, form.containerNamespace) { browser?.reset() }
    // ProxyJump chain for "Test connection" — the probe must ride the same route as a real session,
    // so a broken chain fails the test with the same localized message as the connect dialogs.
    val testJump = resolveJumpChain(
        form.jumpHostId, editHost?.id,
        findHost = { id -> allHosts.firstOrNull { it.id == id } },
        findCredential = { id -> credentials?.find(id) },
    )
    ModalScrim(onDismiss = state::closeModal) {
        Column(
            Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .padding(20.dp)
                .heightIn(max = 720.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Skerry.colors.surfaceDeep)
                .border(1.dp, Skerry.colors.cyan14, RoundedCornerShape(12.dp))
                .consumeClicks(),
        ) {
            Box(Modifier.fillMaxWidth().padding(start = 26.dp, end = 26.dp, top = 22.dp, bottom = 14.dp)) {
                Column {
                    val remote = section == HostSection.RemoteDesktops
                    Txt(
                        stringResource(
                            when {
                                editHost != null && remote -> Res.string.conn_title_edit_desktop
                                editHost != null -> Res.string.conn_title_edit
                                remote -> Res.string.conn_title_new_desktop
                                else -> Res.string.conn_title_new
                            },
                        ),
                        color = Skerry.colors.text, size = 18.sp, weight = FontWeight.SemiBold, letterSpacing = (-0.2).sp,
                    )
                    Txt(
                        if (editHost != null) stringResource(Res.string.conn_subtitle_edit)
                        else if (remote) stringResource(Res.string.conn_subtitle_new_desktop)
                        else stringResource(Res.string.conn_subtitle_new),
                        color = Skerry.colors.dim, size = 12.5.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 6.dp),
                    )
                }
                IconBtn("close", label = stringResource(Res.string.shell_tip_close), onClick = state::closeModal, modifier = Modifier.align(Alignment.TopEnd))
            }
            Column(modalBody().padding(start = 26.dp, end = 26.dp, top = 6.dp, bottom = 22.dp)) {
                val namePlaceholder = if (section == HostSection.RemoteDesktops) "e.g. lab-desktop" else "e.g. prod-web-01"
                Field(stringResource(Res.string.conn_field_name)) { ModalTextField(form.name, { form.name = it }, namePlaceholder) }
                Spacer14()
                // A section with a single protocol has nothing to pick: VNC is the only remote
                // desktop today, and a one-segment switch would just be chrome. It comes back on
                // its own once RDP lands beside it.
                val protocols = remember(section) { connectionTypesIn(section) }
                if (protocols.size > 1) {
                    Field(stringResource(Res.string.conn_field_protocol)) { ProtocolPicker(form, protocols) }
                }
                // Telnet has no transport encryption (unlike SSH/Mosh) — warn inline, mirroring the
                // insecure-URL notices on the Sync/AI forms. The transport itself is correct (no creds
                // auto-sent), this is a heads-up, not a block.
                if (form.connectionType == ConnectionType.TELNET) {
                    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Sym("warning", size = 14.sp, color = Skerry.colors.sunset)
                        Txt(stringResource(Res.string.conn_telnet_plaintext_warning), color = Skerry.colors.sunset, size = 11.5.sp, lineHeight = 15.sp)
                    }
                }
                // VNC/RFB has no transport encryption either — same heads-up as Telnet.
                if (form.connectionType == ConnectionType.VNC) {
                    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Sym("warning", size = 14.sp, color = Skerry.colors.sunset)
                        Txt(stringResource(Res.string.conn_vnc_plaintext_warning), color = Skerry.colors.sunset, size = 11.5.sp, lineHeight = 15.sp)
                    }
                }
                Spacer14()
                val serial = form.connectionType == ConnectionType.SERIAL
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Field(if (serial) stringResource(Res.string.conn_field_device) else stringResource(Res.string.conn_field_host_address), Modifier.weight(1f)) {
                        if (serial) {
                            SerialDeviceField(form)
                        } else {
                            ModalTextField(form.address, { form.address = it }, "192.168.1.45 or example.com", icon = "dns")
                        }
                    }
                    Field(if (serial) stringResource(Res.string.conn_field_baud) else stringResource(Res.string.conn_field_port), Modifier.width(110.dp)) {
                        // Still the transport's own default (22 / 23 / 3389 / 5900 / 9600 baud) —
                        // select it on focus so typing replaces it. A port the user set stays put.
                        ModalTextField(
                            form.port, { form.port = it }, if (serial) "9600" else "22",
                            keyboardType = KeyboardType.Number,
                            selectAllOnFocus = form.isDefaultPort,
                        )
                    }
                }
                // Auth follows the SSH path (SSH and Mosh): Telnet enters login/password in the
                // terminal itself, Serial has no auth at all.
                if (form.connectionType.usesSshAuth) {
                    Spacer14()
                    Field(stringResource(Res.string.conn_field_username)) { ModalTextField(form.username, { form.username = it }, "root or username", icon = "person") }
                    Spacer14()
                    Field(stringResource(Res.string.conn_field_authentication)) { AuthPicker(form) }
                }
                // VNC authenticates with a password only — no username, no private key (allowKey = false).
                if (form.connectionType.isVnc) {
                    Spacer14()
                    Field(stringResource(Res.string.conn_field_authentication)) { AuthPicker(form, allowKey = false) }
                }
                // RDP logs on as a Windows user: a name, an optional domain (stored as `DOMAIN\user`)
                // and a password. No private key — RDP has no key authentication.
                if (form.connectionType.isRdp) {
                    Spacer14()
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Field(stringResource(Res.string.conn_field_username), Modifier.weight(1f)) {
                            ModalTextField(form.username, { form.username = it }, "Administrator", icon = "person")
                        }
                        Field(stringResource(Res.string.conn_field_domain), Modifier.weight(1f)) {
                            ModalTextField(form.domain, { form.domain = it }, "CORP", icon = "domain")
                        }
                    }
                    Spacer14()
                    Field(stringResource(Res.string.conn_field_authentication)) { AuthPicker(form, allowKey = false) }
                    Spacer14()
                    // The session's sound, played on this machine (MS-RDPEA), and where to play it.
                    Field(stringResource(Res.string.conn_field_audio)) { RdpAudioSection(form) }
                    Field(stringResource(Res.string.conn_field_clipboard)) { RdpClipboardSection(form) }
                    Spacer14()
                    Field(stringResource(Res.string.conn_field_image_quality)) { RdpQualitySection(form) }
                }
                // Container profiles: which container/pod on that host to enter. Sits after auth
                // because "Browse" dials the host with exactly these credentials.
                if (form.connectionType == ConnectionType.CONTAINER) {
                    ContainerSection(
                        form = form,
                        browser = browser,
                        onBrowse = {
                            val auth = formSshAuth(form, credentials)
                            val jump = (testJump as? JumpChainResolution.Resolved)?.jump
                            when {
                                auth == null || form.address.isBlank() || form.username.isBlank() || form.portOrNull == null ->
                                    browser?.fail(ContainerBrowseProblem.IncompleteForm)
                                // An unresolvable jump chain can't be probed through; the precise
                                // reason is what "Test connection" is for.
                                testJump is JumpChainResolution.Unavailable ->
                                    browser?.fail(ContainerBrowseProblem.ConnectionFailed)
                                else -> browser?.load(
                                    SshTarget(form.address.trim(), form.portOrNull ?: 22, form.username.trim(), jump = jump),
                                    auth,
                                    form.containerSpec(),
                                )
                            }
                        },
                    )
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
                    var tagFocused by remember(editHost, duplicateOf) { mutableStateOf(false) }
                    val tagFocus = remember { FocusRequester() }
                    val tagSugs = remember(allHosts, form.tags, tagDraft) { tagSuggestions(allHosts, form.tags, tagDraft) }
                    AnchoredDropdown(
                        expanded = tagFocused && tagSugs.isNotEmpty(),
                        onDismiss = { tagFocused = false },
                        focusable = false, // don't steal focus from the tag input field
                        trigger = {
                            FlowRow(
                                // Tapping anywhere in the capsule (padding, gaps between pills) focuses the input.
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).background(Skerry.colors.bg).border(1.dp, Skerry.colors.cyan14, RoundedCornerShape(7.dp))
                                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { tagFocus.requestFocus() }
                                    .padding(horizontal = 10.dp, vertical = 7.dp),
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
                                    modifier = Modifier.focusRequester(tagFocus),
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
                Spacer14()
                // Free-form remark about the profile; shown as a hover tooltip on the sidebar row.
                Field(stringResource(Res.string.conn_field_notes)) {
                    ModalTextField(
                        form.notes,
                        { form.notes = capNotes(it) },
                        stringResource(Res.string.conn_notes_placeholder),
                        singleLine = false,
                        minHeightDp = 64,
                    )
                }
                // AI policy selection is visible when AI is actually available (live controller or feature flag).
                // Written directly into the form -> host profile (Host.aiPolicy). Not for a remote
                // desktop: VNC and RDP have no shell/terminal for AI to act on (see [hasAiPolicy]).
                if (form.connectionType.hasAiPolicy && (LocalFeatures.current.ai || LocalAi.current != null)) {
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
                Modifier.fillMaxWidth().background(Skerry.colors.shade15).padding(horizontal = 26.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // Before the test runs, an encryption hint; otherwise the test status takes its place.
                    val status = tester?.status ?: ConnectionTestStatus.Idle
                    if (status == ConnectionTestStatus.Idle) {
                        Sym("shield_lock", size = 14.sp, color = Skerry.colors.moss)
                        Txt(stringResource(Res.string.conn_footer_encrypted), color = Skerry.colors.faint, size = 11.sp)
                    } else {
                        TestStatusLabel(status)
                    }
                }
                CancelButton(stringResource(Res.string.conn_cancel), onClick = state::closeModal, modifier = Modifier.testTag(UiTags.FORM_CANCEL))
                if (showsTest) {
                    GhostButton(
                        stringResource(Res.string.conn_test),
                        onClick = {
                            // Secret is materialized here (only for the connect's duration), target from form fields.
                            val auth = formSshAuth(form, credentials)
                            if (canTest && auth != null) {
                                when (testJump) {
                                    is JumpChainResolution.Unavailable -> tester.fail(ConnectionTestProblem.Jump(testJump.problem))
                                    is JumpChainResolution.Resolved ->
                                        tester.test(SshTarget(form.address.trim(), form.portOrNull ?: 22, form.username.trim(), jump = testJump.jump), auth)
                                }
                            } else {
                                // Form isn't ready to dial (missing host/username/credentials): report it as a
                                // failure so the click isn't a silent no-op.
                                tester?.fail(ConnectionTestProblem.IncompleteForm)
                            }
                        },
                        fg = if (canTest) Skerry.colors.text else Skerry.colors.faint,
                        border = if (canTest) Skerry.colors.lineStrong else Skerry.colors.line,
                    )
                }
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
                    enabled = hosts == null || form.canSave,
                    modifier = Modifier.testTag(UiTags.FORM_SAVE),
                )
            }
        }
    }
}
