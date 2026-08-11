package app.skerry.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.host.Host
import app.skerry.shared.vault.Credential
import app.skerry.shared.vault.CredentialSecret
import app.skerry.shared.vault.CredentialUsage
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.vault_add_password
import app.skerry.ui.generated.resources.vault_any_principal
import app.skerry.ui.generated.resources.vault_badge_expired
import app.skerry.ui.generated.resources.vault_copy_certificate
import app.skerry.ui.generated.resources.vault_copy_password
import app.skerry.ui.generated.resources.vault_copy_public_key
import app.skerry.ui.generated.resources.vault_delete
import app.skerry.ui.generated.resources.vault_empty_certificates_hint_short
import app.skerry.ui.generated.resources.vault_empty_certificates_title
import app.skerry.ui.generated.resources.vault_empty_passwords_hint
import app.skerry.ui.generated.resources.vault_empty_passwords_title
import app.skerry.ui.generated.resources.vault_empty_ssh_hint
import app.skerry.ui.generated.resources.vault_empty_ssh_title
import app.skerry.ui.generated.resources.vault_export_key
import app.skerry.ui.generated.resources.vault_export_certificate
import app.skerry.ui.generated.resources.vault_export_failed_title
import app.skerry.ui.generated.resources.vault_export_failed_message
import app.skerry.ui.generated.resources.vault_export_dismiss
import app.skerry.ui.generated.resources.vault_generate_key
import app.skerry.ui.generated.resources.vault_header_summary
import app.skerry.ui.generated.resources.vault_item_count
import app.skerry.ui.generated.resources.vault_import_certificate
import app.skerry.ui.generated.resources.vault_key_unreadable
import app.skerry.ui.generated.resources.vault_label_public_key
import app.skerry.ui.generated.resources.vault_link_key_file
import app.skerry.ui.generated.resources.vault_rename
import app.skerry.ui.generated.resources.vault_subtitle_certificate
import app.skerry.ui.generated.resources.vault_subtitle_certificate_typed
import app.skerry.ui.generated.resources.vault_subtitle_key_file
import app.skerry.ui.generated.resources.vault_subtitle_key_file_cert
import app.skerry.ui.generated.resources.vault_subtitle_password
import app.skerry.ui.generated.resources.vault_subtitle_private_key
import app.skerry.ui.generated.resources.vault_title
import app.skerry.ui.host.rowLabel
import app.skerry.ui.secure.SecureScreen
import app.skerry.ui.identity.CredentialDraft
import app.skerry.ui.identity.CredentialKind
import app.skerry.ui.identity.CredentialManagerController
import app.skerry.ui.known.shortFingerprint
import app.skerry.ui.vault.SecretCopyAuthorizer
import app.skerry.ui.vault.SecretExport
import app.skerry.ui.vault.privateKeyExport
import app.skerry.ui.vault.certificateExport
import app.skerry.ui.vault.SecretActions
import app.skerry.ui.vault.secretActions
import app.skerry.ui.vault.exportPrivateKey
import app.skerry.ui.vault.exportPublic
import app.skerry.ui.vault.VaultCategoryKind
import app.skerry.ui.vault.VaultPresentation
import app.skerry.ui.vault.title
import app.skerry.ui.vault.copyPasswordToClipboard
import app.skerry.ui.vault.copyTextToClipboard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.vault.AddPasswordDialog
import app.skerry.ui.design.Badge
import app.skerry.ui.vault.CertificateDetailBody
import app.skerry.ui.vault.DeleteSecretDialog
import app.skerry.ui.vault.DetailLabel
import app.skerry.ui.vault.GenerateKeyDialog
import app.skerry.ui.vault.ImportCertificateDialog
import app.skerry.ui.app.LocalCredentials
import app.skerry.ui.design.GhostButton
import app.skerry.ui.design.NoticeDialog
import app.skerry.ui.nav.PlatformBackHandler
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.app.LocalHosts
import app.skerry.ui.app.LocalSnippets
import app.skerry.ui.app.LocalSync
import app.skerry.ui.sync.SyncStatus
import app.skerry.ui.app.LocalSecretFileReader
import app.skerry.ui.vault.KeyFileBadges
import app.skerry.ui.vault.KeyFileDetailBody
import app.skerry.ui.vault.LinkKeyFileDialog
import app.skerry.ui.vault.rememberKeyFileState
import app.skerry.ui.app.LocalSshCertificateInspector
import app.skerry.ui.app.LocalSshKeyGenerator
import app.skerry.ui.app.LocalVault
import app.skerry.ui.app.LocalVaultBiometrics
import app.skerry.ui.app.MobileDesignState
import app.skerry.ui.vault.PasswordConfirmDialog
import app.skerry.ui.vault.RenameSecretDialog
import app.skerry.ui.design.PrimaryButton
import app.skerry.ui.vault.SecretIcon
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.vault.SecretAuditRows
import app.skerry.ui.vault.SecretEncryptionRows
import app.skerry.ui.vault.SecretFactRows
import app.skerry.ui.vault.SecretRow
import app.skerry.ui.vault.SecretSectionLabel
import app.skerry.ui.vault.UsedByHosts
import app.skerry.ui.vault.auditSectionTitle
import app.skerry.ui.vault.encryptionSectionTitle
import app.skerry.ui.vault.mockSecrets
import app.skerry.ui.vault.rememberCertInfo
import app.skerry.ui.vault.rememberKeyInfo
import app.skerry.ui.vault.secretMetaLine
import app.skerry.ui.vault.unbindCredential
import app.skerry.ui.theme.Skerry

/**
 * Vault push screen (More → Vault): three keychain categories (SSH keys/Passwords/Certificates) switched by pills, plus
 * key generation / add password / import certificate. Tapping a secret opens a detail sheet (public
 * key/fingerprint/principals, used-by hosts) with Copy/Export/Delete.
 *
 * Live path ([LocalCredentials] != null) renders the real unlocked keychain; preview/offscreen without
 * a keychain ([LocalCredentials] == null) is a static mock.
 */
@Composable
fun MobileVaultScreen(state: MobileDesignState) {
    when (val credentials = LocalCredentials.current) {
        null -> MobileVaultMock(onBack = state::pop)
        else -> MobileVaultLive(state, credentials)
    }
}

// Live path.

@Composable
private fun MobileVaultLive(state: MobileDesignState, credentials: CredentialManagerController) {
    val mono = LocalFonts.current.mono
    val generator = LocalSshKeyGenerator.current
    val inspector = LocalSshCertificateInspector.current
    val hostsController = LocalHosts.current
    val hosts = hostsController?.hosts ?: emptyList()
    // Snippet library — "used by" must count `${{vault:name}}` references next to host bindings.
    val snippetList = LocalSnippets.current?.snippets?.map { it.snippet } ?: emptyList()
    val scope = rememberCoroutineScope()
    val allCreds = credentials.credentials
    // Re-authentication before copying a password: biometrics if enabled, else the master password.
    val vault = LocalVault.current
    val biometrics = LocalVaultBiometrics.current
    val copyAuth = remember(vault, biometrics, scope) { SecretCopyAuthorizer(vault, biometrics, scope) }
    var exportFailed by remember { mutableStateOf(false) }

    var category by remember { mutableStateOf(VaultCategoryKind.SSH_KEYS) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var showGenerate by remember { mutableStateOf(false) }
    var showAddPassword by remember { mutableStateOf(false) }
    val secretFiles = LocalSecretFileReader.current
    var showImportCert by remember { mutableStateOf(false) }
    var showLinkKeyFile by remember { mutableStateOf(false) }
    var pendingRename by remember { mutableStateOf<Credential?>(null) }
    var pendingDelete by remember { mutableStateOf<Credential?>(null) }

    val credItems = VaultPresentation.credentialsIn(category, allCreds)
    val selectedCred = credItems.firstOrNull { it.id == selectedId }
    // Same rule as desktop: "stored on server" only holds once a sync account exists.
    val syncing = LocalSync.current?.status?.collectAsState()?.value.let { it != null && it !is SyncStatus.Disabled }

    // Any open overlay (create/delete dialogs + detail sheet) hides the tab bar: otherwise it floats
    // over the centered dialog and covers the bottom input fields above the keyboard. LaunchedEffect
    // writes the flag only on value change (not every list recomposition); DisposableEffect clears it
    // on leaving the tab so the tab bar isn't left hidden.
    val modalOpen = showGenerate || showAddPassword || showImportCert || showLinkKeyFile || pendingRename != null || pendingDelete != null ||
        selectedCred != null || copyAuth.passwordPromptVisible || exportFailed
    LaunchedEffect(modalOpen) { state.modalOverlay(modalOpen) }
    DisposableEffect(Unit) { onDispose { state.modalOverlay(false) } }

    // While the Vault tab is composed (including its dialogs and secret detail sheet) — protect the
    // window from screenshots and Recent Apps previews. Cleared automatically on leaving the tab.
    SecureScreen()

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(Skerry.colors.bg).verticalScroll(rememberScrollState())) {
            MobilePushHeader(stringResource(Res.string.vault_title), onBack = state::pop, plainBack = true)
            MobileVaultSummary(allCreds.size)
            MobileCategoryPills(category, allCreds) { category = it; selectedId = null }
            MobileVaultAction(
                category = category,
                canGenerate = generator != null,
                canImportCert = inspector != null,
                onGenerate = { showGenerate = true },
                onAddPassword = { showAddPassword = true },
                canLinkFile = secretFiles != null,
                onImportCert = { showImportCert = true },
                onLinkKeyFile = { showLinkKeyFile = true },
            )
            // 6dp on top of the row's own 12dp: the list lines up with the pills and the title above it.
            Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp).padding(top = 6.dp)) {
                if (credItems.isEmpty()) {
                    MobileVaultEmpty(category)
                } else {
                    credItems.forEach { credential ->
                        MobileSecretRow(
                            credential = credential,
                            usage = credentials.usageOf(credential.id),
                            usedBy = VaultPresentation.usedByLabel(
                                hostLabels = VaultPresentation.hostsUsing(credential.id, hosts).map { it.rowLabel() },
                                snippetCount = VaultPresentation.snippetsUsing(credential.label, snippetList).size,
                            ),
                            mono = mono,
                            onClick = { selectedId = credential.id },
                        )
                    }
                }
            }
            Spacer(Modifier.height(96.dp))
        }

        if (showGenerate && generator != null) {
            GenerateKeyDialog(
                onDismiss = { showGenerate = false },
                onCreate = { name, type ->
                    showGenerate = false
                    category = VaultCategoryKind.SSH_KEYS
                    // Generation (especially RSA-4096) is expensive — move it off the main thread; save touches state.
                    scope.launch {
                        val key = withContext(Dispatchers.Default) { generator.generate(type, comment = name) }
                        selectedId = credentials.save(
                            CredentialDraft(label = name, kind = CredentialKind.PRIVATE_KEY, privateKeyPem = key.privateKeyPem),
                        )
                    }
                },
            )
        }
        if (showAddPassword) {
            AddPasswordDialog(
                onDismiss = { showAddPassword = false },
                onCreate = { name, password ->
                    selectedId = credentials.save(CredentialDraft(label = name, kind = CredentialKind.PASSWORD, password = password))
                    category = VaultCategoryKind.PASSWORDS
                    showAddPassword = false
                },
            )
        }
        if (showImportCert && inspector != null) {
            ImportCertificateDialog(
                inspector = inspector,
                onDismiss = { showImportCert = false },
                onCreate = { name, pem, cert, passphrase ->
                    selectedId = credentials.save(
                        CredentialDraft(
                            label = name,
                            kind = CredentialKind.CERTIFICATE,
                            privateKeyPem = pem,
                            certificate = cert,
                            passphrase = passphrase ?: "",
                        ),
                    )
                    category = VaultCategoryKind.CERTIFICATES
                    showImportCert = false
                },
            )
        }
        if (showLinkKeyFile) {
            LinkKeyFileDialog(
                onDismiss = { showLinkKeyFile = false },
                onCreate = { name, keyRef, certRef, passphrase ->
                    selectedId = credentials.save(
                        CredentialDraft(
                            label = name,
                            kind = CredentialKind.KEY_FILE,
                            privateKeyRef = keyRef,
                            certificateRef = certRef ?: "",
                            passphrase = passphrase ?: "",
                        ),
                    )
                    category = if (certRef == null) VaultCategoryKind.SSH_KEYS else VaultCategoryKind.CERTIFICATES
                    showLinkKeyFile = false
                },
            )
        }
        pendingRename?.let { target ->
            // Rename edits only the label; the id (hosts reference it) and the secret stay put, and the
            // change propagates to sync on its own (see CredentialManagerController.rename).
            RenameSecretDialog(
                currentLabel = target.label,
                onDismiss = { pendingRename = null },
                onConfirm = { newLabel ->
                    // Abort on a lock race: idle auto-lock can fire while the dialog is open, and vault
                    // CRUD throws once locked. Mirrors the delete guard.
                    if (vault?.isUnlocked == true) credentials.rename(target.id, newLabel)
                    pendingRename = null
                },
            )
        }
        pendingDelete?.let { victim ->
            val bound = VaultPresentation.hostsUsing(victim.id, hosts)
            DeleteSecretDialog(
                label = victim.label,
                boundHostCount = bound.size,
                onDismiss = { pendingDelete = null },
                onConfirm = {
                    // The cascade is consistent only with a live hostsController (always present behind the
                    // gate): first unbind hosts so they don't reference the deleted secret, then delete it.
                    val hc = hostsController
                    if (hc != null) {
                        bound.forEach { host -> hc.save(host.unbindCredential()) }
                        credentials.delete(victim.id)
                        if (selectedId == victim.id) selectedId = null
                    }
                    pendingDelete = null
                },
            )
        }
        selectedCred?.let { credential ->
            MobileSecretDetailSheet(
                credential = credential,
                usage = credentials.usageOf(credential.id),
                syncing = syncing,
                hosts = VaultPresentation.hostsUsing(credential.id, hosts),
                snippetLabels = VaultPresentation.snippetsUsing(credential.label, snippetList).map { it.label },
                mono = mono,
                onCopy = { copyTextToClipboard(it) },
                onCopyPassword = { pwd ->
                    copyAuth.authorize { credentials.recordCopied(credential.id); copyPasswordToClipboard(pwd) }
                },
                // Private key material: re-authenticated like a password copy; see VaultView.
                onExportKey = { export -> exportPrivateKey(copyAuth, export, scope) { exportFailed = it.worthReporting } },
                onExportPublic = { export -> exportPublic(export, scope) { exportFailed = it.worthReporting } },
                // Close the detail sheet before showing a centered dialog (rename/delete): otherwise the
                // sheet, drawn on top, would cover it and leave only its edge visible.
                onRename = {
                    selectedId = null
                    pendingRename = credential
                },
                onDelete = {
                    selectedId = null
                    pendingDelete = credential
                },
                onDismiss = { selectedId = null },
            )
        }
        if (exportFailed) {
            // The sheet underneath registers its own back handler, so without this one Back would
            // close the sheet and leave the notice stranded over the tab bar.
            PlatformBackHandler(onBack = { exportFailed = false })
            NoticeDialog(
                title = stringResource(Res.string.vault_export_failed_title),
                message = stringResource(Res.string.vault_export_failed_message),
                buttonLabel = stringResource(Res.string.vault_export_dismiss),
                onDismiss = { exportFailed = false },
            )
        }
        if (copyAuth.passwordPromptVisible) {
            PasswordConfirmDialog(
                error = copyAuth.passwordError,
                busy = copyAuth.verifying,
                access = copyAuth.access,
                onDismiss = { copyAuth.dismiss() },
                onConfirm = { copyAuth.submitPassword(it) },
            )
        }
    }
}

// Header, categories, action.

/**
 * What the desktop header states, folded into one mobile line: how much the keychain holds and how it
 * is protected, with the live lock state on the right. Replaces the old static "everything here is
 * encrypted" banner — the same claim, but with a number and a deadline attached.
 */
@Composable
private fun MobileVaultSummary(itemCount: Int) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Two lines allowed: on a narrow screen the cipher/KDF names don't fit beside the badge on
        // one, and truncating them to "Argo…" would state nothing.
        Txt(
            stringResource(
                Res.string.vault_header_summary,
                pluralStringResource(Res.plurals.vault_item_count, itemCount, itemCount),
            ),
            modifier = Modifier.weight(1f),
            color = Skerry.colors.faint,
            size = 10.5.sp,
            lineHeight = 15.sp,
            font = LocalFonts.current.mono,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Keychain category switch pills with live counts. */
@Composable
private fun MobileCategoryPills(active: VaultCategoryKind, credentials: List<Credential>, onSelect: (VaultCategoryKind) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        VaultPresentation.sidebarCategories.forEach { kind ->
            val on = kind == active
            val count = VaultPresentation.count(kind, credentials)
            Row(
                Modifier.clip(RoundedCornerShape(20.dp))
                    .background(if (on) Skerry.colors.cyan.copy(alpha = 0.12f) else Skerry.colors.overlaySoft)
                    .border(1.dp, if (on) Skerry.colors.cyan.copy(alpha = 0.3f) else Color.Transparent, RoundedCornerShape(20.dp))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(kind) }
                    .padding(horizontal = 13.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Sym(kind.icon, size = 15.sp, color = if (on) Skerry.colors.cyanBright else Skerry.colors.dim)
                Txt(kind.title(), color = if (on) Skerry.colors.cyanBright else Skerry.colors.dim, size = 12.5.sp, weight = FontWeight.Medium)
                Txt(count.toString(), color = Skerry.colors.faint, size = 10.sp)
            }
        }
    }
}

/** Context action button per category — generate key / add password / import certificate. */
@Composable
private fun MobileVaultAction(
    category: VaultCategoryKind,
    canGenerate: Boolean,
    canImportCert: Boolean,
    canLinkFile: Boolean,
    onGenerate: () -> Unit,
    onAddPassword: () -> Unit,
    onImportCert: () -> Unit,
    onLinkKeyFile: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (category) {
            VaultCategoryKind.SSH_KEYS -> if (canGenerate) PrimaryButton(stringResource(Res.string.vault_generate_key), onClick = onGenerate, icon = "add", modifier = Modifier.fillMaxWidth())
            VaultCategoryKind.PASSWORDS -> PrimaryButton(stringResource(Res.string.vault_add_password), onClick = onAddPassword, icon = "add", modifier = Modifier.fillMaxWidth())
            VaultCategoryKind.CERTIFICATES -> if (canImportCert) PrimaryButton(stringResource(Res.string.vault_import_certificate), onClick = onImportCert, icon = "add", modifier = Modifier.fillMaxWidth())
        }
        // Same rule as desktop: a file-backed secret can be either kind, so the action shows in both.
        if (canLinkFile && category != VaultCategoryKind.PASSWORDS) {
            GhostButton(stringResource(Res.string.vault_link_key_file), onClick = onLinkKeyFile, modifier = Modifier.fillMaxWidth())
        }
    }
}

// Secret card.

/**
 * Keychain secret in the category list — the same dense row the desktop list draws ([SecretRow]), so
 * a key looks like the same object on both platforms. Selection isn't rendered here: on mobile a tap
 * opens the detail sheet rather than filling a panel beside the list.
 */
@Composable
private fun MobileSecretRow(
    credential: Credential,
    usage: CredentialUsage?,
    usedBy: String?,
    mono: FontFamily,
    onClick: () -> Unit,
) {
    val generator = LocalSshKeyGenerator.current
    val inspector = LocalSshCertificateInspector.current
    val secret = credential.secret
    // Compute metadata once per row (each helper is a separate produceState slot, so a repeat call
    // would parse the same secret twice). null for a mismatched type — no work.
    val keyInfo = rememberKeyInfo(credential, generator)
    val certInfo = rememberCertInfo(credential, inspector)
    val fileState = (secret as? CredentialSecret.KeyFile)?.let { rememberKeyFileState(it, LocalSecretFileReader.current, inspector) }
    val style = VaultPresentation.secretStyle(secret, Skerry.colors)
    SecretRow(
        icon = style.icon,
        iconColor = style.color,
        tintedIcon = style.tinted,
        name = credential.label,
        meta = secretMetaLine(secret, keyInfo, certInfo, usage, usedBy),
        mono = mono,
        selected = false,
        onClick = onClick,
        status = {
            when (secret) {
                is CredentialSecret.KeyFile -> KeyFileBadges(fileState)
                is CredentialSecret.Certificate ->
                    if (certInfo?.expired == true) {
                        Badge(stringResource(Res.string.vault_badge_expired), bg = Skerry.colors.sunset.copy(alpha = 0.16f), fg = Skerry.colors.sunset, size = 9.sp)
                    }
                is CredentialSecret.Password, is CredentialSecret.PrivateKey -> Unit
            }
        },
    )
}

@Composable
private fun MobileVaultEmpty(category: VaultCategoryKind) {
    Box(Modifier.fillMaxWidth().padding(top = 50.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Sym(category.icon, size = 26.sp, color = Skerry.colors.faint)
            val (title, hint) = when (category) {
                VaultCategoryKind.SSH_KEYS -> stringResource(Res.string.vault_empty_ssh_title) to stringResource(Res.string.vault_empty_ssh_hint)
                VaultCategoryKind.PASSWORDS -> stringResource(Res.string.vault_empty_passwords_title) to stringResource(Res.string.vault_empty_passwords_hint)
                VaultCategoryKind.CERTIFICATES -> stringResource(Res.string.vault_empty_certificates_title) to stringResource(Res.string.vault_empty_certificates_hint_short)
            }
            Txt(title, color = Skerry.colors.text, size = 13.sp, weight = FontWeight.SemiBold)
            Txt(hint, color = Skerry.colors.faint, size = 11.5.sp)
        }
    }
}

// Secret detail sheet.

/**
 * Bottom detail sheet for the selected secret: header (icon/name/subtype), public key + fingerprint
 * (key) or certificate body, used-by hosts, Copy/Export/Delete buttons. What the sheet *draws* is
 * public material only (public key/cert); the private key and the password leave the vault solely
 * through Copy/Export, each behind re-authentication.
 */
@Composable
private fun MobileSecretDetailSheet(
    credential: Credential,
    usage: CredentialUsage?,
    syncing: Boolean,
    hosts: List<Host>,
    snippetLabels: List<String>,
    mono: FontFamily,
    onCopy: (String) -> Unit,
    onCopyPassword: (String) -> Unit,
    onExportKey: (SecretExport.PrivateKey) -> Unit,
    onExportPublic: (SecretExport.Public) -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val keyExport = remember(credential) { privateKeyExport(credential) }
    val certExport = remember(credential) { certificateExport(credential) }
    val generator = LocalSshKeyGenerator.current
    val inspector = LocalSshCertificateInspector.current
    val secret = credential.secret
    val keyInfo = rememberKeyInfo(credential, generator)
    val certInfo = rememberCertInfo(credential, inspector)
    val keyFileState = (secret as? CredentialSecret.KeyFile)?.let { rememberKeyFileState(it, LocalSecretFileReader.current, inspector) }
    val (icon, color, tinted) = VaultPresentation.secretStyle(secret, Skerry.colors)
    val subtitle = when (secret) {
        is CredentialSecret.Certificate -> certInfo?.keyTypeLabel?.let { stringResource(Res.string.vault_subtitle_certificate_typed, it) } ?: stringResource(Res.string.vault_subtitle_certificate)
        is CredentialSecret.PrivateKey -> keyInfo?.keyTypeLabel ?: stringResource(Res.string.vault_subtitle_private_key)
        is CredentialSecret.Password -> stringResource(Res.string.vault_subtitle_password)
        is CredentialSecret.KeyFile ->
            if (secret.certificateRef.isNullOrBlank()) stringResource(Res.string.vault_subtitle_key_file)
            else stringResource(Res.string.vault_subtitle_key_file_cert)
    }
    // Full-screen scrim; a tap outside the sheet closes it. The sheet swallows clicks so it doesn't
    // close. It fits its content (a short password ⇒ short sheet) but not above 85% of the screen —
    // then content scrolls. A fixed height would inflate an empty sheet for a bodiless secret.
    MobileBottomSheet(onDismiss = onDismiss, maxHeightFraction = 0.85f) {
        // weight(fill = false): with short content the column hugs it; with long content it fills the
        // remaining sheet height and scrolls (rather than overflowing the edge).
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp)) {
                Row(Modifier.padding(bottom = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                    SecretIcon(icon, tinted = tinted, color = color, size = 40)
                    // No subtitle here: the Type fact row right below states it, and stating it twice
                    // 14dp apart is the noise this redesign removed from the rows.
                    Txt(credential.label, color = Skerry.colors.text, size = 15.sp, weight = FontWeight.SemiBold)
                }
                SecretFactRows(
                    typeLabel = subtitle,
                    fingerprint = keyInfo?.fingerprintSha256?.let { shortFingerprint(it) }
                        ?: keyFileState?.certificate?.caFingerprintSha256?.let { shortFingerprint(it) },
                    secret = secret,
                    usage = usage,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
                when (secret) {
                    is CredentialSecret.Certificate -> CertificateDetailBody(certInfo, mono)
                    is CredentialSecret.PrivateKey -> {
                        DetailLabel(stringResource(Res.string.vault_label_public_key))
                        // The fingerprint is a fact row above; here the sheet shows the key itself.
                        Box(Modifier.fillMaxWidth().padding(bottom = 16.dp).clip(RoundedCornerShape(7.dp)).background(Skerry.colors.terminalBg).border(1.dp, Skerry.colors.cyan.copy(alpha = 0.1f), RoundedCornerShape(7.dp)).padding(horizontal = 12.dp, vertical = 10.dp)) {
                            Txt(keyInfo?.publicKeyOpenSsh ?: stringResource(Res.string.vault_key_unreadable), color = Skerry.colors.dim, size = 10.5.sp, font = mono, lineHeight = 16.sp)
                        }
                    }
                    is CredentialSecret.Password -> Unit
                    is CredentialSecret.KeyFile -> KeyFileDetailBody(secret, keyFileState, mono)
                }
                UsedByHosts(hosts, snippetLabels, mono)
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // Copy is type-specific (what's copyable differs); rename is universal and edits only
                    // the label; export/delete are type-specific again.
                    when (secret) {
                        is CredentialSecret.Certificate ->
                            MobileSheetButton(stringResource(Res.string.vault_copy_certificate), onClick = { onCopy(secret.certificate) }, icon = "content_copy", modifier = Modifier.fillMaxWidth())
                        is CredentialSecret.PrivateKey ->
                            MobileSheetButton(stringResource(Res.string.vault_copy_public_key), onClick = { keyInfo?.let { onCopy(it.publicKeyOpenSsh) } }, icon = "content_copy", modifier = Modifier.fillMaxWidth())
                        // The password is sensitive: copying requires re-authentication (biometrics/master
                        // password, see onCopyPassword) and goes through the platform path (Android:
                        // sensitive clip + auto-clear), not the normal clipboard like cert/public key.
                        is CredentialSecret.Password ->
                            MobileSheetButton(stringResource(Res.string.vault_copy_password), onClick = { onCopyPassword(secret.password) }, icon = "content_copy", modifier = Modifier.fillMaxWidth())
                        // Nothing to copy: the material is on disk, and the refs are already spelled out above.
                        is CredentialSecret.KeyFile -> Unit
                    }
                    MobileSheetButton(stringResource(Res.string.vault_rename), onClick = onRename, filled = false, modifier = Modifier.fillMaxWidth())
                    // Export hands out the private key; see the desktop panel.
                    val deleteButton: @Composable (Modifier) -> Unit = { modifier ->
                        MobileSheetButton(stringResource(Res.string.vault_delete), onClick = onDelete, filled = false, danger = true, modifier = modifier)
                    }
                    when (secretActions(credential)) {
                        SecretActions.KeyAndCertificate -> {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                MobileSheetButton(stringResource(Res.string.vault_export_key), onClick = { keyExport?.let(onExportKey) }, filled = false, modifier = Modifier.weight(1f))
                                MobileSheetButton(stringResource(Res.string.vault_export_certificate), onClick = { certExport?.let(onExportPublic) }, filled = false, modifier = Modifier.weight(1f))
                            }
                            deleteButton(Modifier.fillMaxWidth())
                        }
                        SecretActions.KeyAndDelete -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            MobileSheetButton(stringResource(Res.string.vault_export_key), onClick = { keyExport?.let(onExportKey) }, filled = false, modifier = Modifier.weight(1f))
                            deleteButton(Modifier.weight(1f))
                        }
                        SecretActions.DeleteOnly -> deleteButton(Modifier.fillMaxWidth())
                    }
                }
                SecretSectionLabel(encryptionSectionTitle())
                SecretEncryptionRows(syncing)
                // Only a password is ever copied out of the vault; see the desktop panel.
                if (secret is CredentialSecret.Password) {
                    SecretSectionLabel(auditSectionTitle())
                    SecretAuditRows(usage)
                }
                Spacer(Modifier.height(8.dp))
            }
        }
}

// Preview (mock).

/** Static mock of the Vault screen (offscreen/preview without an unlocked keychain). */
@Composable
private fun MobileVaultMock(onBack: () -> Unit) {
    val mono = LocalFonts.current.mono
    Column(Modifier.fillMaxSize().background(Skerry.colors.bg).verticalScroll(rememberScrollState())) {
        MobilePushHeader(stringResource(Res.string.vault_title), onBack = onBack, plainBack = true)
        MobileVaultSummary(itemCount = mockSecrets().size)
        Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            mockSecrets().forEach { (credential, meta) ->
                val style = VaultPresentation.secretStyle(credential.secret, Skerry.colors)
                SecretRow(
                    icon = style.icon,
                    iconColor = style.color,
                    tintedIcon = style.tinted,
                    name = credential.label,
                    meta = meta,
                    mono = mono,
                    selected = false,
                    onClick = {},
                )
            }
        }
        Spacer(Modifier.height(96.dp))
    }
}
