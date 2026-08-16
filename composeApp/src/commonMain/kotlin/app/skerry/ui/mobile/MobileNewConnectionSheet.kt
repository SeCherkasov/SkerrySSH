package app.skerry.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.host.capNotes
import app.skerry.shared.ssh.ConnectionType
import app.skerry.shared.ssh.SshTarget
import app.skerry.shared.ssh.usesSshAuth
import app.skerry.shared.ssh.hasAiPolicy
import app.skerry.shared.ssh.isRdp
import app.skerry.shared.ssh.isVnc
import app.skerry.ui.host.HostSection
import app.skerry.ui.host.connectionTypesIn
import app.skerry.ui.host.NewConnectionFormState
import app.skerry.ui.secure.SecureScreen
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.conn_field_ai_policy_short
import app.skerry.ui.generated.resources.conn_field_audio
import app.skerry.ui.generated.resources.conn_field_authentication
import app.skerry.ui.generated.resources.conn_field_baud
import app.skerry.ui.generated.resources.conn_field_device
import app.skerry.ui.generated.resources.conn_field_group
import app.skerry.ui.generated.resources.conn_field_jump_host
import app.skerry.ui.generated.resources.conn_field_keep_alive
import app.skerry.ui.generated.resources.conn_field_host_address
import app.skerry.ui.generated.resources.conn_field_name
import app.skerry.ui.generated.resources.conn_field_port
import app.skerry.ui.generated.resources.conn_field_protocol
import app.skerry.ui.generated.resources.conn_field_notes
import app.skerry.ui.generated.resources.conn_field_tags
import app.skerry.ui.generated.resources.conn_notes_placeholder
import app.skerry.ui.generated.resources.conn_field_domain
import app.skerry.ui.generated.resources.conn_field_display
import app.skerry.ui.generated.resources.conn_field_image_quality
import app.skerry.ui.generated.resources.conn_field_username
import app.skerry.ui.generated.resources.conn_save_changes
import app.skerry.ui.generated.resources.conn_save_connection
import app.skerry.ui.generated.resources.conn_subtitle_mobile
import app.skerry.ui.generated.resources.conn_tag_add_placeholder
import app.skerry.ui.generated.resources.conn_duplicate_name
import app.skerry.ui.generated.resources.conn_title_edit
import app.skerry.ui.generated.resources.conn_title_new
import app.skerry.ui.generated.resources.conn_title_new_desktop
import app.skerry.ui.generated.resources.conn_title_edit_desktop
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.app.LocalAi
import app.skerry.ui.app.LocalCredentials
import app.skerry.ui.app.LocalFeatures
import app.skerry.ui.app.LocalHosts
import app.skerry.ui.app.LocalTestTransport
import app.skerry.ui.app.MobileDesignState
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.connection.ContainerBrowseController
import app.skerry.ui.connection.ContainerBrowseProblem
import app.skerry.ui.connection.JumpChainResolution
import app.skerry.ui.connection.resolveJumpChain
import app.skerry.ui.i18n.label
import app.skerry.ui.host.tagSuggestions
import app.skerry.ui.theme.Skerry
import androidx.compose.ui.platform.testTag
import app.skerry.ui.app.UiTags
import app.skerry.ui.generated.resources.shell_tip_close

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
    // Which catalog this sheet belongs to (the tab its FAB was tapped on, or the edited profile's
    // own section): it fixes the protocols offered and the header wording. Desktop parity.
    val section = state.sheetSection
    val form = remember(editHost, duplicateOf, section) {
        when {
            editHost != null -> NewConnectionFormState.fromHost(editHost)
            duplicateOf != null -> NewConnectionFormState.duplicateOf(duplicateOf, copyName.orEmpty())
            else -> NewConnectionFormState.forSection(section)
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
    // "Browse containers": a one-off listing over the read-only probe transport (no TOFU), null on
    // the preview path — the container name stays typeable either way.
    val probeTransport = LocalTestTransport.current
    val browseScope = rememberCoroutineScope()
    val browser = remember(probeTransport, browseScope) {
        probeTransport?.let { ContainerBrowseController(it, browseScope) }
    }
    // Closing the sheet (or changing the host/auth/runtime the listing belongs to) drops it: an
    // in-flight probe would otherwise keep a connection open until its own timeout.
    DisposableEffect(browser) { onDispose { browser?.reset() } }
    LaunchedEffect(
        form.address, form.username, form.port, form.authMode, form.existingCredentialId,
        form.password, form.privateKeyPem, form.passphrase, form.jumpHostId,
        form.containerRuntime, form.containerNamespace,
    ) {
        browser?.reset()
    }
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
                    color = Skerry.colors.text, size = 20.sp, weight = FontWeight.Bold,
                )
                Sym(
                    "close",
                    contentDescription = stringResource(Res.string.shell_tip_close),
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

            val namePlaceholder = if (section == HostSection.RemoteDesktops) "lab-desktop" else "prod-web-01"
            MobileFormField(stringResource(Res.string.conn_field_name)) { MobileFormInput(form.name, { form.name = it }, namePlaceholder) }
            Spacer(Modifier.height(14.dp))
            // A single-protocol section has nothing to pick (VNC is the only remote desktop today);
            // the picker returns on its own once RDP joins it.
            val protocols = remember(section) { connectionTypesIn(section) }
            if (protocols.size > 1) {
                MobileFormField(stringResource(Res.string.conn_field_protocol)) { MobileProtocolPicker(form, protocols) }
            }
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
                        MobileFormInput(form.port, { form.port = it }, "22", keyboardType = KeyboardType.Number, selectAllOnFocus = form.isDefaultPort)
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
            } else if (form.connectionType.isRdp) {
                // RDP: a Windows logon (name plus optional domain, stored as `DOMAIN\user`) and a
                // password. Desktop parity; no key auth, no jump host, no keep-alive.
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MobileFormField(stringResource(Res.string.conn_field_username), Modifier.weight(1f)) {
                        MobileFormInput(form.username, { form.username = it }, "Administrator")
                    }
                    MobileFormField(stringResource(Res.string.conn_field_port), Modifier.width(84.dp)) {
                        MobileFormInput(form.port, { form.port = it }, "3389", keyboardType = KeyboardType.Number, selectAllOnFocus = form.isDefaultPort)
                    }
                }
                Spacer(Modifier.height(14.dp))
                MobileFormField(stringResource(Res.string.conn_field_domain)) {
                    MobileFormInput(form.domain, { form.domain = it }, "CORP")
                }
                Spacer(Modifier.height(14.dp))
                MobileFormField(stringResource(Res.string.conn_field_authentication)) { MobileAuthPicker(form, allowKey = false) }
                Spacer(Modifier.height(14.dp))
                // The session's sound, played on this device (MS-RDPEA), and where to play it.
                MobileFormField(stringResource(Res.string.conn_field_audio)) {
                    app.skerry.ui.host.RdpAudioSection(form)
                    app.skerry.ui.host.RdpClipboardSection(form)
                }
                Spacer(Modifier.height(14.dp))
                MobileFormField(stringResource(Res.string.conn_field_image_quality)) {
                    app.skerry.ui.host.RdpQualitySection(form)
                }
                Spacer(Modifier.height(14.dp))
                MobileFormField(stringResource(Res.string.conn_field_display)) {
                    app.skerry.ui.host.RdpDisplaySection(form)
                }
                Spacer(Modifier.height(14.dp))
            } else if (form.connectionType.isVnc) {
                // VNC: a password (no username), plus the RFB port. No jump host / keep-alive.
                MobileFormField(stringResource(Res.string.conn_field_port), Modifier.width(120.dp)) {
                    MobileFormInput(form.port, { form.port = it }, "5900", keyboardType = KeyboardType.Number, selectAllOnFocus = form.isDefaultPort)
                }
                Spacer(Modifier.height(14.dp))
                MobileFormField(stringResource(Res.string.conn_field_authentication)) { MobileAuthPicker(form, allowKey = false) }
                Spacer(Modifier.height(14.dp))
            } else {
                // Telnet/Serial: no authentication; show only port/baud.
                MobileFormField(if (serial) stringResource(Res.string.conn_field_baud) else stringResource(Res.string.conn_field_port), Modifier.width(120.dp)) {
                    MobileFormInput(form.port, { form.port = it }, if (serial) "9600" else "23", keyboardType = KeyboardType.Number, selectAllOnFocus = form.isDefaultPort)
                }
                Spacer(Modifier.height(14.dp))
            }
            // Container profiles: what to enter on that host (desktop parity, same fields and probe).
            if (form.connectionType == ConnectionType.CONTAINER) {
                MobileContainerSection(
                    form = form,
                    browser = browser,
                    onBrowse = {
                        val auth = mobileFormAuth(form, credentials)
                        val jump = resolveJumpChain(
                            form.jumpHostId, editHost?.id,
                            findHost = { id -> hosts?.hosts?.firstOrNull { it.id == id } },
                            findCredential = { id -> credentials?.find(id) },
                        )
                        when {
                            auth == null || form.address.isBlank() || form.username.isBlank() || form.portOrNull == null ->
                                browser?.fail(ContainerBrowseProblem.IncompleteForm)
                            jump is JumpChainResolution.Unavailable ->
                                browser?.fail(ContainerBrowseProblem.ConnectionFailed)
                            else -> browser?.load(
                                SshTarget(
                                    form.address.trim(), form.portOrNull ?: 22, form.username.trim(),
                                    jump = (jump as JumpChainResolution.Resolved).jump,
                                ),
                                auth,
                                form.containerSpec(),
                            )
                        }
                    },
                )
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

            Spacer(Modifier.height(14.dp))
            // Free-form remark about the profile; surfaced in the host details screen (parity with
            // the desktop sidebar's hover tooltip, which a phone has no equivalent of).
            MobileFormField(stringResource(Res.string.conn_field_notes)) {
                MobileFormInput(
                    form.notes,
                    { form.notes = capNotes(it) },
                    stringResource(Res.string.conn_notes_placeholder),
                    singleLine = false,
                    minHeightDp = 80,
                )
            }

            // AI policy: not for a remote desktop (VNC and RDP have no shell for AI to act on).
            if (form.connectionType.hasAiPolicy && (LocalFeatures.current.ai || LocalAi.current != null)) {
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
                    .testTag(UiTags.FORM_SAVE)
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
