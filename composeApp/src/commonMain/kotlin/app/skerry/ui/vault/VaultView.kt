package app.skerry.ui.vault

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.host.Host
import app.skerry.shared.vault.Credential
import app.skerry.shared.vault.CredentialSecret
import app.skerry.shared.vault.SshCertificateInfo
import app.skerry.shared.vault.SshCertificateInspector
import app.skerry.shared.vault.SshKeyGenerator
import app.skerry.shared.vault.SshKeyType
import app.skerry.shared.vault.SshPublicKeyInfo
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.vault_add
import app.skerry.ui.generated.resources.vault_add_password
import app.skerry.ui.generated.resources.vault_any_principal
import app.skerry.ui.generated.resources.vault_badge_expired
import app.skerry.ui.generated.resources.vault_cancel
import app.skerry.ui.generated.resources.vault_cert_key_id
import app.skerry.ui.generated.resources.vault_cert_read_error
import app.skerry.ui.generated.resources.vault_cert_unreadable
import app.skerry.ui.generated.resources.vault_cert_valid_summary
import app.skerry.ui.generated.resources.vault_confirm_master_subtitle
import app.skerry.ui.generated.resources.vault_confirm_master_title
import app.skerry.ui.generated.resources.vault_copy
import app.skerry.ui.generated.resources.vault_copy_certificate
import app.skerry.ui.generated.resources.vault_copy_password
import app.skerry.ui.generated.resources.vault_copy_public_key
import app.skerry.ui.generated.resources.vault_delete
import app.skerry.ui.generated.resources.vault_delete_detail_bound
import app.skerry.ui.generated.resources.vault_delete_detail_none
import app.skerry.ui.generated.resources.vault_delete_title
import app.skerry.ui.generated.resources.vault_dialog_add_password_subtitle
import app.skerry.ui.generated.resources.vault_dialog_generate_subtitle
import app.skerry.ui.generated.resources.vault_dialog_generate_title
import app.skerry.ui.generated.resources.vault_dialog_import_subtitle
import app.skerry.ui.generated.resources.vault_e2e_description
import app.skerry.ui.generated.resources.vault_e2e_encrypted
import app.skerry.ui.generated.resources.vault_empty_certificates_hint
import app.skerry.ui.generated.resources.vault_empty_certificates_title
import app.skerry.ui.generated.resources.vault_empty_passwords_hint
import app.skerry.ui.generated.resources.vault_empty_passwords_title
import app.skerry.ui.generated.resources.vault_empty_ssh_hint
import app.skerry.ui.generated.resources.vault_empty_ssh_title
import app.skerry.ui.generated.resources.vault_export
import app.skerry.ui.generated.resources.vault_field_algorithm
import app.skerry.ui.generated.resources.vault_field_certificate
import app.skerry.ui.generated.resources.vault_field_master_password
import app.skerry.ui.generated.resources.vault_field_name
import app.skerry.ui.generated.resources.vault_field_passphrase
import app.skerry.ui.generated.resources.vault_field_password
import app.skerry.ui.generated.resources.vault_field_private_key_pem
import app.skerry.ui.generated.resources.vault_generate
import app.skerry.ui.generated.resources.vault_generate_key
import app.skerry.ui.generated.resources.vault_import
import app.skerry.ui.generated.resources.vault_import_certificate
import app.skerry.ui.generated.resources.vault_key_unreadable
import app.skerry.ui.generated.resources.vault_label_fingerprint
import app.skerry.ui.generated.resources.vault_label_principals
import app.skerry.ui.generated.resources.vault_label_public_key
import app.skerry.ui.generated.resources.vault_label_serial
import app.skerry.ui.generated.resources.vault_label_signing_ca
import app.skerry.ui.generated.resources.vault_label_valid
import app.skerry.ui.generated.resources.vault_meta_any_principal
import app.skerry.ui.generated.resources.vault_meta_certificate
import app.skerry.ui.generated.resources.vault_meta_password
import app.skerry.ui.generated.resources.vault_not_attached
import app.skerry.ui.generated.resources.vault_password_mismatch_retry
import app.skerry.ui.generated.resources.vault_placeholder_master_password
import app.skerry.ui.generated.resources.vault_placeholder_name_cert
import app.skerry.ui.generated.resources.vault_placeholder_name_key
import app.skerry.ui.generated.resources.vault_placeholder_name_password
import app.skerry.ui.generated.resources.vault_placeholder_optional
import app.skerry.ui.generated.resources.vault_placeholder_password
import app.skerry.ui.generated.resources.vault_sidebar_header
import app.skerry.ui.generated.resources.vault_subtitle_certificate
import app.skerry.ui.generated.resources.vault_subtitle_certificate_typed
import app.skerry.ui.generated.resources.vault_subtitle_password
import app.skerry.ui.generated.resources.vault_subtitle_private_key
import app.skerry.ui.generated.resources.vault_used_by
import app.skerry.ui.generated.resources.vault_used_by_one
import app.skerry.ui.generated.resources.vtail_meta_fingerprint
import app.skerry.ui.generated.resources.vtail_meta_principals
import app.skerry.ui.host.HostDraft
import app.skerry.ui.identity.CredentialDraft
import app.skerry.ui.identity.CredentialKind
import app.skerry.ui.identity.CredentialManagerController
import app.skerry.ui.known.shortFingerprint
import app.skerry.ui.nav.PlatformBackHandler
import app.skerry.ui.vault.SecretCopyAuthorizer
import app.skerry.ui.vault.VaultCategoryKind
import app.skerry.ui.vault.VaultPresentation
import app.skerry.ui.vault.title
import app.skerry.ui.vault.copyPasswordToClipboard
import app.skerry.ui.vault.copyTextToClipboard
import app.skerry.ui.vault.exportTextFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.design.Badge
import app.skerry.ui.design.Chip
import app.skerry.ui.design.D
import app.skerry.ui.design.GhostButton
import app.skerry.ui.design.HLine
import app.skerry.ui.app.LocalCredentials
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.labelUppercase
import app.skerry.ui.app.LocalHosts
import app.skerry.ui.app.LocalSshCertificateInspector
import app.skerry.ui.app.LocalSshKeyGenerator
import app.skerry.ui.app.LocalVault
import app.skerry.ui.app.LocalVaultBiometrics
import app.skerry.ui.design.PrimaryButton
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.design.VLine

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
    val generator = LocalSshKeyGenerator.current
    val inspector = LocalSshCertificateInspector.current
    val scope = rememberCoroutineScope()
    val allCreds = credentials.credentials
    // Re-authentication before copying a password (no biometrics on desktop — master password instead).
    val vault = LocalVault.current
    val biometrics = LocalVaultBiometrics.current
    val copyAuth = remember(vault, biometrics, scope) { SecretCopyAuthorizer(vault, biometrics, scope) }

    var category by remember { mutableStateOf(VaultCategoryKind.SSH_KEYS) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var showGenerate by remember { mutableStateOf(false) }
    var showAddPassword by remember { mutableStateOf(false) }
    var showImportCert by remember { mutableStateOf(false) }
    var pendingDeleteCred by remember { mutableStateOf<Credential?>(null) }

    val credItems = VaultPresentation.credentialsIn(category, allCreds)
    val selectedCred = credItems.firstOrNull { it.id == selectedId } ?: credItems.firstOrNull()

    Box(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize()) {
            VaultSidebar(category, allCreds) { category = it; selectedId = null }
            VLine(D.line)
            Column(Modifier.weight(1f).fillMaxHeight().background(D.bg)) {
                VaultHeader(
                    category = category,
                    canGenerate = generator != null,
                    canImportCert = inspector != null,
                    onGenerate = { showGenerate = true },
                    onAddPassword = { showAddPassword = true },
                    onImportCert = { showImportCert = true },
                )
                HLine()
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    if (credItems.isEmpty()) {
                        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                            VaultEmptyCategory(category)
                        }
                    } else {
                        Column(
                            Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            credItems.forEach { credential ->
                                LiveSecretCard(
                                    credential = credential,
                                    active = credential.id == selectedCred?.id,
                                    generator = generator,
                                    inspector = inspector,
                                    usedByCount = VaultPresentation.hostsUsing(credential.id, hosts).size,
                                    mono = mono,
                                    onClick = { selectedId = credential.id },
                                )
                            }
                        }
                    }
                    selectedCred?.let { credential ->
                        VLine(D.line)
                        LiveSecretDetail(
                            credential = credential,
                            generator = generator,
                            inspector = inspector,
                            hosts = VaultPresentation.hostsUsing(credential.id, hosts),
                            mono = mono,
                            onCopy = { copyTextToClipboard(it) },
                            onCopyPassword = { pwd -> copyAuth.authorize { copyPasswordToClipboard(pwd) } },
                            onExport = { name, content -> scope.launch { exportTextFile(name, content) } },
                            onDelete = { pendingDeleteCred = credential },
                        )
                    }
                }
            }
        }
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
                    // referencing a deleted secret. Always present past the gate; the guard protects against
                    // a lock race while the dialog is open (in which case the whole delete is aborted).
                    val hc = hostsController
                    if (hc != null) {
                        bound.forEach { host -> hc.save(host.unbindCredential()) }
                        credentials.delete(victim.id)
                        if (selectedId == victim.id) selectedId = null
                    }
                    pendingDeleteCred = null
                },
            )
        }
        if (copyAuth.passwordPromptVisible) {
            PasswordConfirmDialog(
                error = copyAuth.passwordError,
                busy = copyAuth.verifying,
                onDismiss = { copyAuth.dismiss() },
                onConfirm = { copyAuth.submitPassword(it) },
            )
        }
    }
}

internal fun Host.unbindCredential(): HostDraft =
    HostDraft(id = id, label = label, address = address, port = port, username = username, group = group, credentialId = null)

// Left category sidebar (live counters) + header with the category action.

@Composable
private fun VaultSidebar(
    active: VaultCategoryKind,
    credentials: List<Credential>,
    onSelect: (VaultCategoryKind) -> Unit,
) {
    Column(Modifier.width(222.dp).fillMaxHeight().background(D.surface2).padding(horizontal = 8.dp, vertical = 14.dp)) {
        Txt(stringResource(Res.string.vault_sidebar_header), color = D.faint, size = 11.sp, weight = FontWeight.SemiBold, letterSpacing = 0.6.sp, modifier = Modifier.padding(start = 10.dp, bottom = 10.dp))
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
            Modifier.clip(RoundedCornerShape(8.dp)).background(D.moss.copy(alpha = 0.06f)).border(1.dp, D.moss.copy(alpha = 0.16f), RoundedCornerShape(8.dp)).padding(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Sym("lock", size = 15.sp, color = D.moss)
                Txt(stringResource(Res.string.vault_e2e_encrypted), color = D.moss, size = 11.sp, weight = FontWeight.SemiBold)
            }
            Txt(stringResource(Res.string.vault_e2e_description), color = D.dim, size = 11.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun VaultCategoryRow(icon: String, label: String, count: String, active: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(if (active) D.cyan10 else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Sym(icon, size = 16.sp, color = if (active) D.cyanBright else D.dim)
        Txt(label, color = if (active) D.cyanBright else D.dim, size = 12.5.sp, modifier = Modifier.weight(1f))
        Txt(count, color = D.faint, size = 10.sp)
    }
}

@Composable
private fun VaultHeader(
    category: VaultCategoryKind,
    canGenerate: Boolean,
    canImportCert: Boolean,
    onGenerate: () -> Unit,
    onAddPassword: () -> Unit,
    onImportCert: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Txt(category.title(), color = D.text, size = 15.sp, weight = FontWeight.SemiBold)
        when (category) {
            VaultCategoryKind.SSH_KEYS -> if (canGenerate) PrimaryButton(stringResource(Res.string.vault_generate_key), onClick = onGenerate, icon = "add")
            VaultCategoryKind.PASSWORDS -> PrimaryButton(stringResource(Res.string.vault_add_password), onClick = onAddPassword, icon = "add")
            VaultCategoryKind.CERTIFICATES -> if (canImportCert) PrimaryButton(stringResource(Res.string.vault_import_certificate), onClick = onImportCert, icon = "add")
        }
    }
}

// Keychain secret card (key/password/certificate) + account card + empty states.

@Composable
private fun LiveSecretCard(
    credential: Credential,
    active: Boolean,
    generator: SshKeyGenerator?,
    inspector: SshCertificateInspector?,
    usedByCount: Int,
    mono: FontFamily,
    onClick: () -> Unit,
) {
    val border = if (active) D.cyan.copy(alpha = 0.18f) else D.cyan08
    val bg = if (active) D.cyan.copy(alpha = 0.04f) else Color.Transparent
    val usedBy = VaultPresentation.usedByLabel(usedByCount)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(bg).border(1.dp, border, RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (val secret = credential.secret) {
            is CredentialSecret.Certificate -> {
                val info = rememberCertInfo(credential, inspector)
                SecretIcon("workspace_premium", tinted = true, color = D.moss)
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Txt(credential.label, color = D.text, size = 13.5.sp, weight = FontWeight.SemiBold)
                        info?.keyTypeLabel?.let { Badge(it, bg = D.moss.copy(alpha = 0.16f), fg = D.moss, radius = 3, size = 9.5.sp) }
                        if (info?.expired == true) Badge(stringResource(Res.string.vault_badge_expired), bg = D.sunset.copy(alpha = 0.16f), fg = D.sunset, radius = 3, size = 9.5.sp)
                        AgentBadge(credential)
                    }
                    val meta = when {
                        info == null -> stringResource(Res.string.vault_meta_certificate, usedBy)
                        info.principals.isEmpty() -> stringResource(Res.string.vault_meta_any_principal, usedBy)
                        else -> stringResource(Res.string.vtail_meta_principals, info.principals.joinToString(", "), usedBy)
                    }
                    Txt(meta, color = D.dim, size = 11.sp, font = mono, modifier = Modifier.padding(top = 6.dp))
                }
            }
            is CredentialSecret.PrivateKey -> {
                val info = rememberKeyInfo(credential, generator)
                SecretIcon("key", tinted = true, color = D.cyanBright)
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Txt(credential.label, color = D.text, size = 13.5.sp, weight = FontWeight.SemiBold)
                        info?.keyTypeLabel?.let { Badge(it, bg = D.moss.copy(alpha = 0.16f), fg = D.moss, radius = 3, size = 9.5.sp) }
                        AgentBadge(credential)
                    }
                    val meta = if (info != null) {
                        stringResource(Res.string.vtail_meta_fingerprint, shortFingerprint(info.fingerprintSha256), usedBy)
                    } else {
                        usedBy
                    }
                    Txt(meta, color = D.dim, size = 11.sp, font = mono, modifier = Modifier.padding(top = 6.dp))
                }
            }
            is CredentialSecret.Password -> {
                SecretIcon("password", tinted = false, color = D.dim)
                Column(Modifier.weight(1f)) {
                    Txt(credential.label, color = D.text, size = 13.5.sp, weight = FontWeight.SemiBold)
                    Txt(stringResource(Res.string.vault_meta_password, usedBy), color = D.dim, size = 11.sp, font = mono, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
    }
}

/** Square secret icon in the card/detail panel (cyan/moss tint for keys/certificates, neutral for passwords). */
@Composable
internal fun SecretIcon(icon: String, tinted: Boolean, color: Color, size: Int = 38) {
    Box(
        Modifier.size(size.dp).clip(RoundedCornerShape(9.dp)).background(if (tinted) color.copy(alpha = 0.12f) else Color(0x0DFFFFFF)),
        contentAlignment = Alignment.Center,
    ) {
        Sym(icon, size = (size * 0.52f).sp, color = if (tinted) color else D.dim)
    }
}

@Composable
private fun VaultEmptyCategory(category: VaultCategoryKind) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Sym(category.icon, size = 26.sp, color = D.faint)
        val (title, hint) = when (category) {
            VaultCategoryKind.SSH_KEYS -> stringResource(Res.string.vault_empty_ssh_title) to stringResource(Res.string.vault_empty_ssh_hint)
            VaultCategoryKind.PASSWORDS -> stringResource(Res.string.vault_empty_passwords_title) to stringResource(Res.string.vault_empty_passwords_hint)
            VaultCategoryKind.CERTIFICATES -> stringResource(Res.string.vault_empty_certificates_title) to stringResource(Res.string.vault_empty_certificates_hint)
        }
        Txt(title, color = D.text, size = 13.sp, weight = FontWeight.SemiBold)
        if (hint.isNotEmpty()) Txt(hint, color = D.faint, size = 11.5.sp)
    }
}

/**
 * Public metadata of a private key (fingerprint/type/public string); null for password/certificate.
 * Parsing PEM in sshj (BER/DER + SHA-256 + BC registration) is expensive, especially on Android, so it's
 * computed on [Dispatchers.Default] via [produceState] rather than in `remember {}` on the composition
 * thread (which would drop frames rendering the key list). Value is `null` until ready — UI draws a
 * placeholder. Recompute keys are id+secret: recomputed when the record updates (same id, new secret).
 */
@Composable
internal fun rememberKeyInfo(credential: Credential, generator: SshKeyGenerator?): SshPublicKeyInfo? {
    val secret = credential.secret as? CredentialSecret.PrivateKey ?: return null
    return produceState<SshPublicKeyInfo?>(null, credential.id, credential.secret, generator) {
        value = withContext(Dispatchers.Default) { generator?.inspect(secret.privateKeyPem, secret.passphrase) }
    }.value
}

/**
 * Public certificate metadata (principals/validity/serial/CA); null if not a certificate, unreadable,
 * or still computing. Parsed on [Dispatchers.Default] via [produceState] (see [rememberKeyInfo]).
 */
@Composable
internal fun rememberCertInfo(credential: Credential, inspector: SshCertificateInspector?): SshCertificateInfo? {
    val secret = credential.secret as? CredentialSecret.Certificate ?: return null
    return produceState<SshCertificateInfo?>(null, credential.id, credential.secret, inspector) {
        value = withContext(Dispatchers.Default) { inspector?.inspect(secret.certificate) }
    }.value
}

// Detail panel for the selected keychain secret and the selected account.

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LiveSecretDetail(
    credential: Credential,
    generator: SshKeyGenerator?,
    inspector: SshCertificateInspector?,
    hosts: List<Host>,
    mono: FontFamily,
    onCopy: (String) -> Unit,
    onCopyPassword: (String) -> Unit,
    onExport: (name: String, content: String) -> Unit,
    onDelete: () -> Unit,
) {
    val secret = credential.secret
    val keyInfo = rememberKeyInfo(credential, generator)
    val certInfo = rememberCertInfo(credential, inspector)
    val (icon, color, tinted) = VaultPresentation.secretStyle(secret)
    val subtitle = when (secret) {
        is CredentialSecret.Certificate -> certInfo?.keyTypeLabel?.let { stringResource(Res.string.vault_subtitle_certificate_typed, it) } ?: stringResource(Res.string.vault_subtitle_certificate)
        is CredentialSecret.PrivateKey -> keyInfo?.keyTypeLabel ?: stringResource(Res.string.vault_subtitle_private_key)
        is CredentialSecret.Password -> stringResource(Res.string.vault_subtitle_password)
    }
    Column(Modifier.width(340.dp).fillMaxHeight().background(D.surface2).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 18.dp)) {
        Row(Modifier.padding(bottom = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
            SecretIcon(icon, tinted = tinted, color = color, size = 40)
            Column {
                Txt(credential.label, color = D.text, size = 14.sp, weight = FontWeight.SemiBold)
                Txt(subtitle, color = D.dim, size = 11.5.sp)
            }
        }
        when (secret) {
            is CredentialSecret.Certificate -> CertificateDetailBody(certInfo, mono)
            is CredentialSecret.PrivateKey -> {
                DetailLabel(stringResource(Res.string.vault_label_public_key))
                Box(Modifier.fillMaxWidth().padding(bottom = 16.dp).clip(RoundedCornerShape(7.dp)).background(D.terminalBg).border(1.dp, D.cyan.copy(alpha = 0.1f), RoundedCornerShape(7.dp)).padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Txt(keyInfo?.publicKeyOpenSsh ?: stringResource(Res.string.vault_key_unreadable), color = D.dim, size = 10.5.sp, font = mono, lineHeight = 16.sp)
                }
                DetailLabel(stringResource(Res.string.vault_label_fingerprint))
                Txt(keyInfo?.fingerprintSha256 ?: "—", color = D.textBright, size = 11.sp, font = mono, modifier = Modifier.padding(bottom = 16.dp))
            }
            is CredentialSecret.Password -> Unit
        }
        UsedByHosts(hosts, mono)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (secret) {
                is CredentialSecret.Certificate -> {
                    PrimaryButton(stringResource(Res.string.vault_copy_certificate), onClick = { onCopy(secret.certificate) }, icon = "content_copy", modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GhostButton(stringResource(Res.string.vault_export), onClick = { onExport("${credential.label}-cert.pub", secret.certificate) }, modifier = Modifier.weight(1f))
                        GhostButton(stringResource(Res.string.vault_delete), onClick = onDelete, fg = D.sunset, border = D.sunset.copy(alpha = 0.3f), modifier = Modifier.weight(1f))
                    }
                }
                is CredentialSecret.PrivateKey -> {
                    PrimaryButton(stringResource(Res.string.vault_copy_public_key), onClick = { keyInfo?.let { onCopy(it.publicKeyOpenSsh) } }, icon = "content_copy", modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GhostButton(stringResource(Res.string.vault_export), onClick = { keyInfo?.let { onExport("${credential.label}.pub", it.publicKeyOpenSsh) } }, modifier = Modifier.weight(1f))
                        GhostButton(stringResource(Res.string.vault_delete), onClick = onDelete, fg = D.sunset, border = D.sunset.copy(alpha = 0.3f), modifier = Modifier.weight(1f))
                    }
                }
                is CredentialSecret.Password -> {
                    // Password is sensitive: copying requires re-authentication (biometrics/master password,
                    // see onCopyPassword) and goes through a platform-specific path (Android: sensitive clip +
                    // auto-clear) rather than the plain clipboard used for cert/public key.
                    PrimaryButton(stringResource(Res.string.vault_copy_password), onClick = { onCopyPassword(secret.password) }, icon = "content_copy", modifier = Modifier.fillMaxWidth())
                    GhostButton(stringResource(Res.string.vault_delete), onClick = onDelete, fg = D.sunset, border = D.sunset.copy(alpha = 0.3f), modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

/** "Used by · N hosts" block with host-name pills, for the secret detail panel. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun UsedByHosts(hosts: List<Host>, mono: FontFamily) {
    // A single binding uses the singular form ("· 1 host"), otherwise the plural.
    DetailLabel(
        if (hosts.size == 1) stringResource(Res.string.vault_used_by_one)
        else stringResource(Res.string.vault_used_by, hosts.size),
    )
    if (hosts.isEmpty()) {
        Txt(stringResource(Res.string.vault_not_attached), color = D.faint, size = 11.sp, modifier = Modifier.padding(bottom = 20.dp))
    } else {
        FlowRow(Modifier.fillMaxWidth().padding(bottom = 20.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            hosts.forEach { HostPill(it.label, mono) }
        }
    }
}

/** Certificate detail panel body: the cert string itself, key id, principals, validity, serial, CA. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CertificateDetailBody(info: SshCertificateInfo?, mono: FontFamily) {
    DetailLabel(stringResource(Res.string.vault_subtitle_certificate))
    Box(Modifier.fillMaxWidth().padding(bottom = 16.dp).clip(RoundedCornerShape(7.dp)).background(D.terminalBg).border(1.dp, D.moss.copy(alpha = 0.12f), RoundedCornerShape(7.dp)).padding(horizontal = 12.dp, vertical = 10.dp)) {
        Txt(
            if (info != null) stringResource(Res.string.vault_cert_key_id, info.keyId) else stringResource(Res.string.vault_cert_unreadable),
            color = D.dim, size = 10.5.sp, font = mono, lineHeight = 16.sp,
        )
    }
    if (info == null) return
    DetailLabel(stringResource(Res.string.vault_label_principals))
    if (info.principals.isEmpty()) {
        Txt(stringResource(Res.string.vault_any_principal), color = D.faint, size = 11.sp, modifier = Modifier.padding(bottom = 16.dp))
    } else {
        FlowRow(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            info.principals.forEach { HostPill(it, mono) }
        }
    }
    DetailLabel(stringResource(Res.string.vault_label_valid))
    Row(Modifier.padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Txt("${info.validFrom} → ${info.validUntil}", color = D.textBright, size = 11.sp, font = mono)
        if (info.expired) Badge(stringResource(Res.string.vault_badge_expired), bg = D.sunset.copy(alpha = 0.16f), fg = D.sunset, radius = 3, size = 9.5.sp)
    }
    DetailLabel(stringResource(Res.string.vault_label_serial))
    Txt(info.serial, color = D.textBright, size = 11.sp, font = mono, modifier = Modifier.padding(bottom = 16.dp))
    DetailLabel(stringResource(Res.string.vault_label_signing_ca))
    Txt(info.caFingerprintSha256, color = D.textBright, size = 11.sp, font = mono, modifier = Modifier.padding(bottom = 16.dp))
}

// Dialogs: generate key, add password, import certificate, confirm delete.

@Composable
internal fun GenerateKeyDialog(onDismiss: () -> Unit, onCreate: (name: String, type: SshKeyType) -> Unit) {
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(SshKeyType.ED25519) }
    val valid = name.isNotBlank()
    VaultDialogScaffold(stringResource(Res.string.vault_dialog_generate_title), stringResource(Res.string.vault_dialog_generate_subtitle), onDismiss) {
        DialogField(stringResource(Res.string.vault_field_name), name, { name = it }, placeholder = stringResource(Res.string.vault_placeholder_name_key))
        Txt(stringResource(Res.string.vault_field_algorithm), color = D.faint, size = 10.5.sp, weight = FontWeight.SemiBold, letterSpacing = 0.6.sp, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SshKeyType.entries.forEach { option ->
                Chip(option.label, active = option == type, modifier = Modifier.clickable { type = option })
            }
        }
        DialogButtons(confirmLabel = stringResource(Res.string.vault_generate), confirmEnabled = valid, onDismiss = onDismiss, onConfirm = { onCreate(name.trim(), type) })
    }
}

@Composable
internal fun AddPasswordDialog(onDismiss: () -> Unit, onCreate: (name: String, password: String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val valid = name.isNotBlank() && password.isNotEmpty()
    VaultDialogScaffold(stringResource(Res.string.vault_add_password), stringResource(Res.string.vault_dialog_add_password_subtitle), onDismiss) {
        DialogField(stringResource(Res.string.vault_field_name), name, { name = it }, placeholder = stringResource(Res.string.vault_placeholder_name_password))
        Box(Modifier.padding(top = 16.dp)) {
            DialogField(stringResource(Res.string.vault_field_password), password, { password = it }, placeholder = stringResource(Res.string.vault_placeholder_password), password = true)
        }
        DialogButtons(confirmLabel = stringResource(Res.string.vault_add), confirmEnabled = valid, onDismiss = onDismiss, onConfirm = { onCreate(name.trim(), password) })
    }
}

@Composable
internal fun ImportCertificateDialog(
    inspector: SshCertificateInspector,
    onDismiss: () -> Unit,
    onCreate: (name: String, pem: String, certificate: String, passphrase: String?) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var pem by remember { mutableStateOf("") }
    var certificate by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    // Metadata is computed from the entered cert string — this doubles as validation that it's a valid certificate.
    val info = remember(certificate, inspector) { certificate.trim().takeIf { it.isNotEmpty() }?.let { inspector.inspect(it) } }
    val certInvalid = certificate.isNotBlank() && info == null
    val valid = name.isNotBlank() && pem.isNotBlank() && info != null

    VaultDialogScaffold(stringResource(Res.string.vault_import_certificate), stringResource(Res.string.vault_dialog_import_subtitle), onDismiss) {
        DialogField(stringResource(Res.string.vault_field_name), name, { name = it }, placeholder = stringResource(Res.string.vault_placeholder_name_cert))
        Box(Modifier.padding(top = 16.dp)) {
            DialogField(stringResource(Res.string.vault_field_private_key_pem), pem, { pem = it }, placeholder = "-----BEGIN OPENSSH PRIVATE KEY-----", singleLine = false, keyboardType = KeyboardType.Password)
        }
        Box(Modifier.padding(top = 16.dp)) {
            DialogField(stringResource(Res.string.vault_field_certificate), certificate, { certificate = it }, placeholder = "ssh-…-cert-v01@openssh.com …", singleLine = false)
        }
        Box(Modifier.padding(top = 16.dp)) {
            DialogField(stringResource(Res.string.vault_field_passphrase), passphrase, { passphrase = it }, placeholder = stringResource(Res.string.vault_placeholder_optional), password = true)
        }
        when {
            certInvalid -> Txt(stringResource(Res.string.vault_cert_read_error), color = D.sunset, size = 11.sp, modifier = Modifier.padding(top = 12.dp))
            info != null -> {
                val principalsPart = if (info.principals.isEmpty()) stringResource(Res.string.vault_any_principal) else info.principals.joinToString(", ")
                Txt(
                    stringResource(Res.string.vault_cert_valid_summary, info.keyTypeLabel, principalsPart, info.validUntil),
                    color = D.moss, size = 11.sp, modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
        DialogButtons(confirmLabel = stringResource(Res.string.vault_import), confirmEnabled = valid, onDismiss = onDismiss, onConfirm = { onCreate(name.trim(), pem.trim(), certificate.trim(), passphrase.ifBlank { null }) })
    }
}

/**
 * Master-password re-authentication before copying a password to the clipboard (shared by desktop and
 * mobile keychain — the no-biometrics path). On wrong input [error] shows an error and the form stays
 * open for another attempt. The field clears when the dialog is recreated.
 */
@Composable
internal fun PasswordConfirmDialog(error: Boolean, busy: Boolean, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var password by remember { mutableStateOf("") }
    VaultDialogScaffold(stringResource(Res.string.vault_confirm_master_title), stringResource(Res.string.vault_confirm_master_subtitle), onDismiss) {
        DialogField(stringResource(Res.string.vault_field_master_password), password, { password = it }, placeholder = stringResource(Res.string.vault_placeholder_master_password), password = true)
        if (error) Txt(stringResource(Res.string.vault_password_mismatch_retry), color = D.sunset, size = 11.sp, modifier = Modifier.padding(top = 12.dp))
        // confirmEnabled is disabled while verifying (Argon2id) — otherwise a double-tap would run it twice.
        DialogButtons(confirmLabel = stringResource(Res.string.vault_copy), confirmEnabled = password.isNotEmpty() && !busy, onDismiss = onDismiss, onConfirm = { onConfirm(password) })
    }
}

@Composable
internal fun DeleteSecretDialog(label: String, boundHostCount: Int, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    VaultDialogScaffold(stringResource(Res.string.vault_delete_title, label), null, onDismiss) {
        val detail = if (boundHostCount == 0) {
            stringResource(Res.string.vault_delete_detail_none)
        } else {
            stringResource(Res.string.vault_delete_detail_bound, boundHostCount)
        }
        Txt(detail, color = D.dim, size = 12.5.sp, lineHeight = 18.sp, modifier = Modifier.padding(bottom = 4.dp))
        Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.clip(RoundedCornerShape(7.dp)).clickable(onClick = onDismiss).padding(horizontal = 16.dp, vertical = 9.dp)) {
                Txt(stringResource(Res.string.vault_cancel), color = D.dim, size = 12.5.sp)
            }
            PrimaryButton(stringResource(Res.string.vault_delete), onClick = onConfirm, bg = D.sunset, fg = Color(0xFF1A0B07))
        }
    }
}

@Composable
private fun VaultDialogScaffold(title: String, subtitle: String?, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    // System back/gesture closes the dialog (same as tapping the scrim). Intercepts back before the
    // shell's navigation on Android (dispatcher LIFO); BackHandler without a dispatcher is a no-op on desktop.
    PlatformBackHandler(onBack = onDismiss)
    Box(
        // The dialog centers in the visible area; it ends up above the keyboard on its own — on mobile the
        // root `safeDrawing` shrinks the area above the IME, and `Center` centers within what's left (no-op on desktop).
        Modifier.fillMaxSize().background(Color(0xB3060E16)).clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .padding(20.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(D.surfaceDeep)
                .border(1.dp, D.cyan14, RoundedCornerShape(12.dp))
                // Absorbs the click on the card so it doesn't close the dialog (same as DesktopPasswordDialog).
                .clickable(onClick = {})
                // Scrolls the content: a tall dialog (certificate import — 4 fields) doesn't fit under the
                // on-screen keyboard; scrolling keeps the fields and buttons reachable. No-op on desktop.
                .verticalScroll(rememberScrollState())
                .padding(26.dp),
        ) {
            Txt(title, color = D.text, size = 16.sp, weight = FontWeight.SemiBold, letterSpacing = (-0.2).sp)
            if (subtitle != null) Txt(subtitle, color = D.dim, size = 12.5.sp, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
            else Spacer(Modifier.padding(top = 8.dp))
            content()
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DialogField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    password: Boolean = false,
    singleLine: Boolean = true,
    // Explicit keyboard type override: for visible but sensitive fields (PEM key) set
    // KeyboardType.Password — disables IME autocorrect/dictionary without visually masking input.
    keyboardType: KeyboardType? = null,
) {
    val ui = LocalFonts.current.ui
    val mono = LocalFonts.current.mono
    // Multi-line fields (PEM/certificate) use monospace so long blobs read like a file.
    val style = remember(ui, mono, singleLine) {
        TextStyle(color = D.text, fontSize = if (singleLine) 13.sp else 11.sp, fontFamily = if (singleLine) ui else mono)
    }
    // Auto-scroll to focus above the keyboard. The window in adjustResize mode (see AndroidManifest)
    // shrinks itself when the keyboard appears, so WindowInsets.ime is always 0 here — observing the inset
    // is useless, and a single bring-into-view would fire BEFORE the window resize and miss. Instead,
    // while the field is focused, it re-requests bring-into-view on EVERY frame for the first ~450ms
    // (keyboard/resize animation duration) — the field is guaranteed to end up above the keyboard
    // regardless of window mode. No keyboard on desktop: a couple of bringIntoView frames are harmless (no-op).
    val requester = remember { BringIntoViewRequester() }
    var focused by remember { mutableStateOf(false) }
    var fieldSize by remember { mutableStateOf(IntSize.Zero) }
    // Gap below the field during auto-scroll — focus surfaces above the keyboard with breathing room, not flush.
    val marginPx = with(LocalDensity.current) { 16.dp.toPx() }
    LaunchedEffect(focused) {
        if (!focused) return@LaunchedEffect
        val start = withFrameNanos { it }
        var now = start
        while (now - start < 450_000_000L) {
            val s = fieldSize
            requester.bringIntoView(
                if (s == IntSize.Zero) null
                else Rect(0f, 0f, s.width.toFloat(), s.height.toFloat() + marginPx),
            )
            now = withFrameNanos { it }
        }
    }
    Column {
        Txt(label, color = D.faint, size = 10.5.sp, weight = FontWeight.SemiBold, letterSpacing = 0.6.sp, modifier = Modifier.padding(bottom = 5.dp))
        // Capsule/padding live in decorationBox so a click anywhere in the field (incl. the empty area
        // below the caret in multi-line PEM/certificate fields) places the caret.
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
            singleLine = singleLine,
            textStyle = style,
            cursorBrush = SolidColor(D.cyan),
            visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            keyboardOptions = KeyboardOptions(imeAction = if (singleLine) ImeAction.Done else ImeAction.Default, keyboardType = keyboardType ?: if (password) KeyboardType.Password else KeyboardType.Text),
            keyboardActions = KeyboardActions(),
            decorationBox = { inner ->
                Box(
                    Modifier.fillMaxWidth().bringIntoViewRequester(requester).onSizeChanged { fieldSize = it }.clip(RoundedCornerShape(7.dp)).background(D.bg).border(1.dp, D.cyan14, RoundedCornerShape(7.dp))
                        .then(if (singleLine) Modifier else Modifier.heightIn(min = 72.dp, max = 132.dp))
                        .padding(horizontal = 11.dp, vertical = 10.dp)
                        .then(if (singleLine) Modifier else Modifier.verticalScroll(rememberScrollState())),
                ) {
                    if (value.isEmpty()) Txt(placeholder, color = D.faint, size = if (singleLine) 13.sp else 11.sp, font = if (singleLine) ui else mono)
                    inner()
                }
            },
        )
    }
}

@Composable
private fun DialogButtons(confirmLabel: String, confirmEnabled: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.clip(RoundedCornerShape(7.dp)).clickable(onClick = onDismiss).padding(horizontal = 16.dp, vertical = 9.dp)) {
            Txt(stringResource(Res.string.vault_cancel), color = D.dim, size = 12.5.sp)
        }
        PrimaryButton(confirmLabel, onClick = { if (confirmEnabled) onConfirm() }, bg = if (confirmEnabled) D.cyan else D.cyan.copy(alpha = 0.4f))
    }
}

// Mock path (offscreen render/preview): static categories/keys/details.

/** Vault view (mock): secret categories (sidebar) + SSH key list + key detail panel. */
@Composable
private fun MockVaultView() {
    val mono = LocalFonts.current.mono
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(222.dp).fillMaxHeight().background(D.surface2).padding(horizontal = 8.dp, vertical = 14.dp)) {
            Txt(stringResource(Res.string.vault_sidebar_header), color = D.faint, size = 11.sp, weight = FontWeight.SemiBold, letterSpacing = 0.6.sp, modifier = Modifier.padding(start = 10.dp, bottom = 10.dp))
            VaultCategory("key", "SSH keys", "4", active = true)
            VaultCategory("password", "Passwords", "12")
            VaultCategory("vpn_lock", "Certificates", "2")
            Spacer(Modifier.weight(1f))
            Column(
                Modifier.clip(RoundedCornerShape(8.dp)).background(D.moss.copy(alpha = 0.06f)).border(1.dp, D.moss.copy(alpha = 0.16f), RoundedCornerShape(8.dp)).padding(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Sym("lock", size = 15.sp, color = D.moss)
                    Txt(stringResource(Res.string.vault_e2e_encrypted), color = D.moss, size = 11.sp, weight = FontWeight.SemiBold)
                }
                Txt(stringResource(Res.string.vault_e2e_description), color = D.dim, size = 11.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
        VLine(D.line)
        Column(Modifier.weight(1f).fillMaxHeight().background(D.bg)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Txt("SSH keys", color = D.text, size = 15.sp, weight = FontWeight.SemiBold)
                PrimaryButton(stringResource(Res.string.vault_generate_key), onClick = {}, icon = "add")
            }
            HLine()
            Row(Modifier.weight(1f).fillMaxWidth()) {
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    KeyCard(
                        iconBg = D.cyan.copy(alpha = 0.12f), iconColor = D.cyanBright, icon = "key",
                        name = "id_ed25519", badges = listOf("ED25519" to false, "DEFAULT" to true),
                        meta = "SHA256:8c3F1a…Qz9pK · used by 6 hosts", mono = mono,
                        border = D.cyan.copy(alpha = 0.18f), bg = D.cyan.copy(alpha = 0.04f),
                        trailing = { CopyButton() },
                    )
                    KeyCard(
                        iconBg = Color(0x0DFFFFFF), iconColor = D.dim, icon = "key",
                        name = "id_rsa_legacy", badges = listOf("RSA-4096" to null),
                        meta = "SHA256:2dE7b…Lm4xR · used by 2 hosts", mono = mono,
                        border = D.cyan08, bg = Color.Transparent,
                        trailing = { CopyButton() },
                    )
                    KeyCard(
                        iconBg = D.sunset.copy(alpha = 0.12f), iconColor = D.sunset, icon = "warning",
                        name = "deploy_ci", badges = listOf("ROTATE SOON" to false), rotateBadge = true,
                        meta = "SHA256:9aB0c…Tn2wE · created 412 days ago", mono = mono,
                        border = D.sunset.copy(alpha = 0.25f), bg = D.sunset.copy(alpha = 0.04f),
                        trailing = {
                            Box(Modifier.clip(RoundedCornerShape(6.dp)).border(1.dp, D.sunset.copy(alpha = 0.4f), RoundedCornerShape(6.dp)).padding(horizontal = 12.dp, vertical = 7.dp)) {
                                Txt("Rotate", color = D.sunset, size = 11.5.sp, weight = FontWeight.SemiBold)
                            }
                        },
                    )
                }
                VLine(D.line)
                KeyDetail(mono)
            }
        }
    }
}

@Composable
private fun VaultCategory(icon: String, label: String, count: String, active: Boolean = false) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(if (active) D.cyan10 else Color.Transparent)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Sym(icon, size = 16.sp, color = if (active) D.cyanBright else D.dim)
        Txt(label, color = if (active) D.cyanBright else D.dim, size = 12.5.sp, modifier = Modifier.weight(1f))
        Txt(count, color = D.faint, size = 10.sp)
    }
}

@Composable
private fun KeyCard(
    iconBg: Color,
    iconColor: Color,
    icon: String,
    name: String,
    badges: List<Pair<String, Boolean?>>,
    meta: String,
    mono: FontFamily,
    border: Color,
    bg: Color,
    rotateBadge: Boolean = false,
    trailing: @Composable () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(bg).border(1.dp, border, RoundedCornerShape(10.dp)).padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(9.dp)).background(iconBg), contentAlignment = Alignment.Center) {
            Sym(icon, size = 20.sp, color = iconColor)
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Txt(name, color = D.text, size = 13.5.sp, weight = FontWeight.SemiBold)
                badges.forEach { (text, default) ->
                    when {
                        rotateBadge -> Badge(text, bg = D.sunset.copy(alpha = 0.16f), fg = D.sunset, radius = 3, size = 9.5.sp)
                        default == true -> Badge(text, bg = D.cyan14, fg = D.cyanBright, radius = 3, size = 9.5.sp)
                        default == false -> Badge(text, bg = D.moss.copy(alpha = 0.16f), fg = D.moss, radius = 3, size = 9.5.sp)
                        else -> Badge(text, bg = Color(0x0FFFFFFF), fg = D.dim, radius = 3, size = 9.5.sp)
                    }
                }
            }
            Txt(meta, color = D.dim, size = 11.sp, font = mono, modifier = Modifier.padding(top = 6.dp))
        }
        trailing()
    }
}

@Composable
private fun CopyButton() {
    Box(Modifier.size(30.dp).clip(RoundedCornerShape(6.dp)).border(1.dp, D.cyan14, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
        Sym("content_copy", size = 16.sp, color = D.dim)
    }
}

@Composable
private fun KeyDetail(mono: FontFamily) {
    Column(Modifier.width(340.dp).fillMaxHeight().background(D.surface2).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 18.dp)) {
        Row(Modifier.padding(bottom = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(9.dp)).background(D.cyan.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                Sym("key", size = 21.sp, color = D.cyanBright)
            }
            Column {
                Txt("id_ed25519", color = D.text, size = 14.sp, weight = FontWeight.SemiBold)
                Txt("ED25519 · 256-bit", color = D.dim, size = 11.5.sp)
            }
        }
        DetailLabel(stringResource(Res.string.vault_label_public_key))
        Box(Modifier.fillMaxWidth().padding(bottom = 16.dp).clip(RoundedCornerShape(7.dp)).background(D.terminalBg).border(1.dp, D.cyan.copy(alpha = 0.1f), RoundedCornerShape(7.dp)).padding(horizontal = 12.dp, vertical = 10.dp)) {
            Txt("ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIH8c3F1a2bQz9pK7mLwR0vNqz9pKmaya@skerry.dev", color = D.dim, size = 10.5.sp, font = mono, lineHeight = 16.sp)
        }
        DetailLabel(stringResource(Res.string.vault_label_fingerprint))
        Txt("SHA256:8c3F1a2bQz9pK7mLwR0vNqz9pK", color = D.textBright, size = 11.sp, font = mono, modifier = Modifier.padding(bottom = 16.dp))
        DetailLabel("Used by · 6 hosts")
        Row(Modifier.fillMaxWidth().padding(bottom = 20.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            HostPill("prod-web-01", mono)
            HostPill("prod-web-02", mono)
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 20.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            HostPill("db-master", mono)
            HostPill("homelab-pi", mono)
            HostPill("+2", mono, dim = true)
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton(stringResource(Res.string.vault_copy_public_key), onClick = {}, icon = "content_copy", modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostButton(stringResource(Res.string.vault_export), onClick = {}, modifier = Modifier.weight(1f))
                GhostButton(stringResource(Res.string.vault_delete), onClick = {}, fg = D.sunset, border = D.sunset.copy(alpha = 0.3f), modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
internal fun DetailLabel(text: String) {
    Txt(labelUppercase(text), color = D.faint, size = 10.sp, weight = FontWeight.SemiBold, letterSpacing = 0.5.sp, modifier = Modifier.padding(bottom = 6.dp))
}

@Composable
internal fun HostPill(name: String, mono: FontFamily, dim: Boolean = false) {
    Box(Modifier.clip(RoundedCornerShape(20.dp)).background(Color(0x0AFFFFFF)).padding(horizontal = 9.dp, vertical = 3.dp)) {
        Txt(name, color = if (dim) D.dim else D.textBright, size = 11.sp, font = mono)
    }
}
