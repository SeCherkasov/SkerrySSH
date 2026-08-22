package app.skerry.ui.vault

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.host.Host
import app.skerry.shared.vault.Credential
import app.skerry.shared.vault.CredentialSecret
import app.skerry.shared.vault.CredentialUsage
import app.skerry.shared.vault.SshCertificateInspector
import app.skerry.shared.vault.SshKeyGenerator
import app.skerry.ui.app.LocalSecurityLog
import app.skerry.ui.app.UiTags
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.help_button
import app.skerry.ui.generated.resources.vault_export_dismiss
import app.skerry.ui.generated.resources.vault_export_failed_message
import app.skerry.ui.generated.resources.vault_export_failed_title
import app.skerry.ui.generated.resources.vault_add_password
import app.skerry.ui.generated.resources.vault_badge_expired
import app.skerry.ui.generated.resources.vault_e2e_description
import app.skerry.ui.generated.resources.vault_e2e_encrypted
import app.skerry.ui.generated.resources.vault_empty_certificates_hint
import app.skerry.ui.generated.resources.vault_empty_certificates_title
import app.skerry.ui.generated.resources.vault_empty_passwords_hint
import app.skerry.ui.generated.resources.vault_empty_passwords_title
import app.skerry.ui.generated.resources.vault_empty_ssh_hint
import app.skerry.ui.generated.resources.vault_empty_ssh_title
import app.skerry.ui.generated.resources.vault_header_summary
import app.skerry.ui.generated.resources.vault_item_count
import app.skerry.ui.generated.resources.vault_title
import app.skerry.ui.generated.resources.vault_generate_key
import app.skerry.ui.generated.resources.vault_import_certificate
import app.skerry.ui.generated.resources.vault_link_key_file
import app.skerry.ui.generated.resources.vault_sidebar_header
import app.skerry.ui.host.rowLabel
import app.skerry.ui.identity.CredentialDraft
import app.skerry.ui.identity.CredentialKind
import app.skerry.ui.identity.CredentialManagerController
import app.skerry.ui.vault.SecretCopyAuthorizer
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
import app.skerry.ui.app.LocalSync
import app.skerry.ui.sync.SyncStatus
import app.skerry.ui.design.NoticeDialog
import app.skerry.ui.design.Badge
import app.skerry.ui.design.EmptyState
import app.skerry.ui.design.GhostButton
import app.skerry.ui.app.LocalCredentials
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.app.LocalHosts
import app.skerry.ui.app.LocalSnippets
import app.skerry.ui.app.LocalSecretFileReader
import app.skerry.ui.app.LocalSshCertificateInspector
import app.skerry.ui.app.LocalSshKeyGenerator
import app.skerry.ui.app.LocalVault
import app.skerry.ui.app.LocalVaultBiometrics
import app.skerry.ui.design.PrimaryButton
import app.skerry.ui.design.SIDEBAR_WIDTH
import app.skerry.ui.design.SectionHeader
import app.skerry.ui.design.SidebarSectionTitle
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.design.VLine
import app.skerry.ui.theme.Skerry

/**
 * Width of the secret detail panel — wide enough for a fingerprint and a wrapped public key to sit
 * next to their labels without the key block turning into a column of fragments.
 */
internal val DETAIL_PANEL_WIDTH = 400.dp

/**
 * Vault view. With a live keychain ([LocalCredentials]) renders the open vault's real data:
 * three keychain categories (SSH keys/Passwords/Certificates) — [Credential] secrets with a detail
 * panel (public key, fingerprint, which hosts use the secret directly via [Host.credentialId]),
 * key pair generation ([LocalSshKeyGenerator]), password add, certificate import
 * ([LocalSshCertificateInspector]), copy/export/delete.
 * Without a keychain controller (offscreen render/preview) renders the static [MockVaultView] mock.
 */
@Composable
fun VaultView() {
    when (val credentials = LocalCredentials.current) {
        null -> MockVaultView()
        else -> LiveVaultView(credentials)
    }
}

// Live path: vault keychain secrets + accounts + generate/add/import/delete.

@Composable
private fun LiveVaultView(credentials: CredentialManagerController) {
    val mono = LocalFonts.current.mono
    val hostsController = LocalHosts.current
    val hosts = hostsController?.hosts ?: emptyList()
    // Snippet library — "used by" must count `${{vault:name}}` references next to host bindings.
    val snippetList = LocalSnippets.current?.snippets?.map { it.snippet } ?: emptyList()
    val generator = LocalSshKeyGenerator.current
    val inspector = LocalSshCertificateInspector.current
    val scope = rememberCoroutineScope()
    val allCreds = credentials.credentials
    // Re-authentication before copying a password or exporting a key (no biometrics on desktop —
    // master password instead).
    val vault = LocalVault.current
    val biometrics = LocalVaultBiometrics.current
    val copyAuth = remember(vault, biometrics, scope) { SecretCopyAuthorizer(vault, biometrics, scope) }
    // A saved export is recorded in the usage trail and the security log (issue #221).
    val securityLog = LocalSecurityLog.current
    var exportFailed by remember { mutableStateOf(false) }

    var category by remember { mutableStateOf(VaultCategoryKind.SSH_KEYS) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var showGenerate by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    var showAddPassword by remember { mutableStateOf(false) }
    val secretFiles = LocalSecretFileReader.current
    var showImportCert by remember { mutableStateOf(false) }
    var showLinkKeyFile by remember { mutableStateOf(false) }
    var pendingEditCred by remember { mutableStateOf<Credential?>(null) }
    var pendingDeleteCred by remember { mutableStateOf<Credential?>(null) }

    val credItems = VaultPresentation.credentialsIn(category, allCreds)
    val selectedCred = credItems.firstOrNull { it.id == selectedId } ?: credItems.firstOrNull()
    // "Stored on server" is only true once an account exists; without sync the ciphertext never
    // leaves this device, and the panel has to say so.
    val syncing = LocalSync.current?.status?.collectAsState()?.value.let { it != null && it !is SyncStatus.Disabled }

    Box(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize()) {
            VaultSidebar(category, allCreds) { category = it; selectedId = null }
            VLine(Skerry.colors.line)
            Column(Modifier.weight(1f).fillMaxHeight().background(Skerry.colors.bg)) {
                VaultHeader(
                    category = category,
                    itemCount = allCreds.size,
                    canGenerate = generator != null,
                    canImportCert = inspector != null,
                    canLinkFile = secretFiles != null,
                    onGenerate = { showGenerate = true },
                    onAddPassword = { showAddPassword = true },
                    onImportCert = { showImportCert = true },
                    onLinkKeyFile = { showLinkKeyFile = true },
                    onHelp = { showHelp = true },
                )
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    if (credItems.isEmpty()) {
                        VaultEmptyCategory(category, Modifier.weight(1f).fillMaxHeight())
                    } else {
                        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                            credItems.forEach { credential ->
                                LiveSecretRow(
                                    credential = credential,
                                    selected = credential.id == selectedCred?.id,
                                    generator = generator,
                                    inspector = inspector,
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
                    selectedCred?.let { credential ->
                        VLine(Skerry.colors.line)
                        LiveSecretDetail(
                            credential = credential,
                            generator = generator,
                            inspector = inspector,
                            usage = credentials.usageOf(credential.id),
                            syncing = syncing,
                            hosts = VaultPresentation.hostsUsing(credential.id, hosts),
                            snippetLabels = VaultPresentation.snippetsUsing(credential.label, snippetList).map { it.label },
                            mono = mono,
                            onCopy = { copyTextToClipboard(it) },
                            onCopyPassword = { pwd ->
                                copyAuth.authorize { credentials.recordCopied(credential.id); copyPasswordToClipboard(pwd) }
                            },
                            // Private key material: the same re-authentication a password copy takes,
                            // for the same reason — an unlocked vault on an unattended screen. A
                            // cancelled Save-As stays silent; a failed write must not, or the user
                            // walks away believing they have a backup.
                            onExportKey = { export ->
                                exportPrivateKey(
                                    copyAuth, export, scope,
                                    onSaved = keyExportAudit(credentials, securityLog, credential.id),
                                ) { exportFailed = it.worthReporting }
                            },
                            // The certificate is public — no gate, like the Copy button next to it.
                            onExportPublic = { export ->
                                exportPublic(export, scope) { exportFailed = it.worthReporting }
                            },
                            onEdit = { pendingEditCred = credential },
                            onDelete = { pendingDeleteCred = credential },
                        )
                    }
                }
            }
        }
        if (showHelp) VaultHelpDialog(onDismiss = { showHelp = false })
        if (showGenerate && generator != null) {
            GenerateKeyDialog(
                onDismiss = { showGenerate = false },
                onCreate = { name, type ->
                    showGenerate = false
                    category = VaultCategoryKind.SSH_KEYS
                    // Generation (especially RSA-4096) is expensive — off the main thread to avoid UI jank;
                    // save touches Compose state, so we hop back to scope (main) to write it.
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
                    selectedId = credentials.save(
                        CredentialDraft(label = name, kind = CredentialKind.PASSWORD, password = password),
                    )
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
        pendingEditCred?.let { target ->
            // Editing touches only the name and the note; the id (which hosts reference) and the secret
            // stay put, and the change propagates to sync on its own (see CredentialManagerController.edit)
            // — except for a file-backed secret, whose whole record stays on this device (issue #174).
            EditSecretDialog(
                currentLabel = target.label,
                currentNote = target.note,
                onDismiss = { pendingEditCred = null },
                onConfirm = { newLabel, newNote ->
                    // Abort on a lock race: idle auto-lock can fire while the dialog is open, and vault
                    // CRUD throws once locked.
                    if (vault?.isUnlocked == true) credentials.edit(target.id, newLabel, newNote)
                    pendingEditCred = null
                },
            )
        }
        pendingDeleteCred?.let { victim ->
            // Deleting a keychain secret: hosts bound to it directly get unbound
            // (will prompt for a password on connect), then the secret itself is deleted.
            val bound = VaultPresentation.hostsUsing(victim.id, hosts)
            DeleteSecretDialog(
                label = victim.label,
                boundHostCount = bound.size,
                onDismiss = { pendingDeleteCred = null },
                onConfirm = {
                    // The cascade is only consistent with a live hostsController; otherwise hosts would keep
                    // referencing a deleted secret. Always present past the gate. Idle auto-lock can fire in
                    // the same frame as this click and vault CRUD throws once locked, so a vault known to be
                    // locked abandons the delete instead of throwing out of the click handler. `!= false`,
                    // not `== true`: no vault at all is the design/preview wiring, which must still run.
                    val hc = hostsController
                    if (hc != null && vault?.isUnlocked != false) {
                        hc.unbindCredential(victim.id)
                        credentials.delete(victim.id)
                        if (selectedId == victim.id) selectedId = null
                    }
                    pendingDeleteCred = null
                },
            )
        }
        if (exportFailed) {
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

// Left category sidebar (live counters) + header with the category action.

@Composable
private fun VaultSidebar(
    active: VaultCategoryKind,
    credentials: List<Credential>,
    onSelect: (VaultCategoryKind) -> Unit,
) {
    Column(Modifier.width(SIDEBAR_WIDTH).fillMaxHeight().background(Skerry.colors.surface2).padding(horizontal = 8.dp, vertical = 14.dp)) {
        SidebarSectionTitle(stringResource(Res.string.vault_sidebar_header), Modifier.padding(start = 10.dp, bottom = 10.dp))
        VaultPresentation.sidebarCategories.forEach { kind ->
            VaultCategoryRow(
                icon = kind.icon,
                label = kind.title(),
                count = VaultPresentation.count(kind, credentials).toString(),
                active = kind == active,
                onClick = { onSelect(kind) },
            )
        }
        Spacer(Modifier.weight(1f))
        Column(
            Modifier.clip(RoundedCornerShape(8.dp)).background(Skerry.colors.moss.copy(alpha = 0.06f)).border(1.dp, Skerry.colors.moss.copy(alpha = 0.16f), RoundedCornerShape(8.dp)).padding(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Sym("lock", size = 15.sp, color = Skerry.colors.moss)
                Txt(stringResource(Res.string.vault_e2e_encrypted), color = Skerry.colors.moss, size = 11.sp, weight = FontWeight.SemiBold)
            }
            Txt(stringResource(Res.string.vault_e2e_description), color = Skerry.colors.dim, size = 11.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
internal fun VaultCategoryRow(icon: String, label: String, count: String, active: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(if (active) Skerry.colors.cyan10 else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Sym(icon, size = 16.sp, color = if (active) Skerry.colors.cyanBright else Skerry.colors.dim)
        Txt(label, color = if (active) Skerry.colors.cyanBright else Skerry.colors.dim, size = 12.5.sp, modifier = Modifier.weight(1f))
        Txt(count, color = Skerry.colors.faint, size = 10.sp)
    }
}

@Composable
private fun VaultHeader(
    category: VaultCategoryKind,
    itemCount: Int,
    canGenerate: Boolean,
    canImportCert: Boolean,
    canLinkFile: Boolean,
    onGenerate: () -> Unit,
    onAddPassword: () -> Unit,
    onImportCert: () -> Unit,
    onLinkKeyFile: () -> Unit,
    onHelp: (() -> Unit)? = null,
) {
    SectionHeader(
        // The section names the whole keychain and how it is protected; which slice of it is on
        // screen is what the sidebar highlights.
        title = stringResource(Res.string.vault_title),
        subtitle = stringResource(
            Res.string.vault_header_summary,
            pluralStringResource(Res.plurals.vault_item_count, itemCount, itemCount),
        ),
        actions = {
            if (onHelp != null) {
                GhostButton(stringResource(Res.string.help_button), onClick = onHelp, icon = "help", modifier = Modifier.testTag(UiTags.HELP))
            }
            when (category) {
                // "Link file" sits in both key and certificate categories: which one a file-backed
                // secret lands in depends on whether it names a certificate.
                VaultCategoryKind.SSH_KEYS -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (canLinkFile) GhostButton(stringResource(Res.string.vault_link_key_file), onClick = onLinkKeyFile)
                    if (canGenerate) PrimaryButton(stringResource(Res.string.vault_generate_key), onClick = onGenerate, icon = "add")
                }
                VaultCategoryKind.PASSWORDS -> PrimaryButton(stringResource(Res.string.vault_add_password), onClick = onAddPassword, icon = "add")
                VaultCategoryKind.CERTIFICATES -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (canLinkFile) GhostButton(stringResource(Res.string.vault_link_key_file), onClick = onLinkKeyFile)
                    if (canImportCert) PrimaryButton(stringResource(Res.string.vault_import_certificate), onClick = onImportCert, icon = "add")
                }
            }
        },
    )
}

// Keychain secret card (key/password/certificate) + account card + empty states.

@Composable
private fun LiveSecretRow(
    credential: Credential,
    selected: Boolean,
    generator: SshKeyGenerator?,
    inspector: SshCertificateInspector?,
    usage: CredentialUsage?,
    usedBy: String?,
    mono: FontFamily,
    onClick: () -> Unit,
) {
    val secret = credential.secret
    val style = VaultPresentation.secretStyle(secret, Skerry.colors)
    val keyInfo = rememberKeyInfo(credential, generator)
    val certInfo = rememberCertInfo(credential, inspector)
    val fileState = (secret as? CredentialSecret.KeyFile)?.let { rememberKeyFileState(it, LocalSecretFileReader.current, inspector) }
    SecretRow(
        icon = style.icon,
        iconColor = style.color,
        tintedIcon = style.tinted,
        name = credential.label,
        meta = secretMetaLine(secret, keyInfo, certInfo, usage, usedBy),
        mono = mono,
        selected = selected,
        onClick = onClick,
        status = {
            when (secret) {
                is CredentialSecret.KeyFile -> KeyFileBadges(fileState)
                is CredentialSecret.Certificate ->
                    if (certInfo?.expired == true) {
                        Badge(stringResource(Res.string.vault_badge_expired), bg = Skerry.colors.sunset.copy(alpha = 0.16f), fg = Skerry.colors.sunset, radius = 3, size = 9.5.sp)
                    }
                is CredentialSecret.Password, is CredentialSecret.PrivateKey -> Unit
            }
        },
    )
}

@Composable
private fun VaultEmptyCategory(category: VaultCategoryKind, modifier: Modifier = Modifier) {
    val (title, hint) = when (category) {
        VaultCategoryKind.SSH_KEYS -> stringResource(Res.string.vault_empty_ssh_title) to stringResource(Res.string.vault_empty_ssh_hint)
        VaultCategoryKind.PASSWORDS -> stringResource(Res.string.vault_empty_passwords_title) to stringResource(Res.string.vault_empty_passwords_hint)
        VaultCategoryKind.CERTIFICATES -> stringResource(Res.string.vault_empty_certificates_title) to stringResource(Res.string.vault_empty_certificates_hint)
    }
    EmptyState(icon = category.icon, title = title, modifier = modifier, subtitle = hint.ifEmpty { null })
}
