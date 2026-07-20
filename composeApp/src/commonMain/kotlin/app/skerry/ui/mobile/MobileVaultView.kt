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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.host.Host
import app.skerry.shared.vault.Credential
import app.skerry.shared.vault.CredentialSecret
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.vault_add_password
import app.skerry.ui.generated.resources.vault_badge_expired
import app.skerry.ui.generated.resources.vault_banner_encrypted
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
import app.skerry.ui.generated.resources.vault_export
import app.skerry.ui.generated.resources.vault_generate_key
import app.skerry.ui.generated.resources.vault_import_certificate
import app.skerry.ui.generated.resources.vault_key_unreadable
import app.skerry.ui.generated.resources.vault_label_fingerprint
import app.skerry.ui.generated.resources.vault_label_public_key
import app.skerry.ui.generated.resources.vault_meta_any_principal
import app.skerry.ui.generated.resources.vault_meta_certificate
import app.skerry.ui.generated.resources.vault_meta_password
import app.skerry.ui.generated.resources.vault_subtitle_certificate
import app.skerry.ui.generated.resources.vault_subtitle_certificate_typed
import app.skerry.ui.generated.resources.vault_subtitle_password
import app.skerry.ui.generated.resources.vault_subtitle_private_key
import app.skerry.ui.generated.resources.vault_title
import app.skerry.ui.generated.resources.vtail_meta_fingerprint
import app.skerry.ui.generated.resources.vtail_meta_principals
import app.skerry.ui.secure.SecureScreen
import app.skerry.ui.identity.CredentialDraft
import app.skerry.ui.identity.CredentialKind
import app.skerry.ui.identity.CredentialManagerController
import app.skerry.ui.known.shortFingerprint
import app.skerry.ui.vault.SecretCopyAuthorizer
import app.skerry.ui.vault.VaultCategoryKind
import app.skerry.ui.vault.VaultPresentation
import app.skerry.ui.vault.title
import app.skerry.ui.vault.copyPasswordToClipboard
import app.skerry.ui.vault.copyTextToClipboard
import app.skerry.ui.vault.exportTextFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.vault.AddPasswordDialog
import app.skerry.ui.design.Badge
import app.skerry.ui.vault.CertificateDetailBody
import app.skerry.ui.design.D
import app.skerry.ui.vault.DeleteSecretDialog
import app.skerry.ui.vault.DetailLabel
import app.skerry.ui.vault.GenerateKeyDialog
import app.skerry.ui.vault.ImportCertificateDialog
import app.skerry.ui.app.LocalCredentials
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.app.LocalHosts
import app.skerry.ui.app.LocalSshCertificateInspector
import app.skerry.ui.app.LocalSshKeyGenerator
import app.skerry.ui.app.LocalVault
import app.skerry.ui.app.LocalVaultBiometrics
import app.skerry.ui.app.MobileDesignState
import app.skerry.ui.vault.PasswordConfirmDialog
import app.skerry.ui.design.PrimaryButton
import app.skerry.ui.vault.SecretIcon
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.vault.UsedByHosts
import app.skerry.ui.vault.rememberCertInfo
import app.skerry.ui.vault.rememberKeyInfo
import app.skerry.ui.vault.unbindCredential

/**
 * Vault root tab: three keychain categories (SSH keys/Passwords/Certificates) switched by pills, plus
 * key generation / add password / import certificate. Tapping a secret opens a detail sheet (public
 * key/fingerprint/principals, used-by hosts) with Copy/Export/Delete.
 *
 * Live path ([LocalCredentials] != null) renders the real unlocked keychain; preview/offscreen without
 * a keychain ([LocalCredentials] == null) is a static mock.
 */
@Composable
fun MobileVaultScreen(state: MobileDesignState) {
    when (val credentials = LocalCredentials.current) {
        null -> MobileVaultMock()
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
    val scope = rememberCoroutineScope()
    val allCreds = credentials.credentials
    // Re-authentication before copying a password: biometrics if enabled, else the master password.
    val vault = LocalVault.current
    val biometrics = LocalVaultBiometrics.current
    val copyAuth = remember(vault, biometrics, scope) { SecretCopyAuthorizer(vault, biometrics, scope) }

    var category by remember { mutableStateOf(VaultCategoryKind.SSH_KEYS) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var showGenerate by remember { mutableStateOf(false) }
    var showAddPassword by remember { mutableStateOf(false) }
    var showImportCert by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Credential?>(null) }

    val credItems = VaultPresentation.credentialsIn(category, allCreds)
    val selectedCred = credItems.firstOrNull { it.id == selectedId }

    // Any open overlay (create/delete dialogs + detail sheet) hides the tab bar: otherwise it floats
    // over the centered dialog and covers the bottom input fields above the keyboard. LaunchedEffect
    // writes the flag only on value change (not every list recomposition); DisposableEffect clears it
    // on leaving the tab so the tab bar isn't left hidden.
    val modalOpen = showGenerate || showAddPassword || showImportCert || pendingDelete != null ||
        selectedCred != null || copyAuth.passwordPromptVisible
    LaunchedEffect(modalOpen) { state.modalOverlay(modalOpen) }
    DisposableEffect(Unit) { onDispose { state.modalOverlay(false) } }

    // While the Vault tab is composed (including its dialogs and secret detail sheet) — protect the
    // window from screenshots and Recent Apps previews. Cleared automatically on leaving the tab.
    SecureScreen()

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(D.bg).verticalScroll(rememberScrollState())) {
            Box(Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 6.dp, bottom = 10.dp)) {
                MobileScreenTitle(stringResource(Res.string.vault_title))
            }
            MobileVaultBanner()
            MobileCategoryPills(category, allCreds) { category = it; selectedId = null }
            MobileVaultAction(
                category = category,
                canGenerate = generator != null,
                canImportCert = inspector != null,
                onGenerate = { showGenerate = true },
                onAddPassword = { showAddPassword = true },
                onImportCert = { showImportCert = true },
            )
            Column(
                Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 6.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                if (credItems.isEmpty()) {
                    MobileVaultEmpty(category)
                } else {
                    credItems.forEach { credential ->
                        MobileSecretCard(
                            credential = credential,
                            usedByCount = VaultPresentation.hostsUsing(credential.id, hosts).size,
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
                hosts = VaultPresentation.hostsUsing(credential.id, hosts),
                mono = mono,
                onCopy = { copyTextToClipboard(it) },
                onCopyPassword = { pwd -> copyAuth.authorize { copyPasswordToClipboard(pwd) } },
                onExport = { name, content -> scope.launch { exportTextFile(name, content) } },
                // Close the detail sheet before showing the confirm dialog: otherwise the sheet (drawn
                // on top) would cover the centered DeleteSecretDialog, leaving only its edge visible.
                onDelete = {
                    selectedId = null
                    pendingDelete = credential
                },
                onDismiss = { selectedId = null },
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

// Header, categories, action.

@Composable
private fun MobileVaultBanner() {
    Row(
        Modifier.padding(horizontal = 22.dp).padding(bottom = 12.dp)
            .clip(RoundedCornerShape(12.dp)).background(D.moss.copy(alpha = 0.06f))
            .border(1.dp, D.moss.copy(alpha = 0.18f), RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Sym("lock", size = 19.sp, color = D.moss)
        Txt(stringResource(Res.string.vault_banner_encrypted), color = D.dim, size = 12.sp)
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
                    .background(if (on) D.cyan.copy(alpha = 0.12f) else Color(0x0AFFFFFF))
                    .border(1.dp, if (on) D.cyan.copy(alpha = 0.3f) else Color.Transparent, RoundedCornerShape(20.dp))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(kind) }
                    .padding(horizontal = 13.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Sym(kind.icon, size = 15.sp, color = if (on) D.cyanBright else D.dim)
                Txt(kind.title(), color = if (on) D.cyanBright else D.dim, size = 12.5.sp, weight = FontWeight.Medium)
                Txt(count.toString(), color = D.faint, size = 10.sp)
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
    onGenerate: () -> Unit,
    onAddPassword: () -> Unit,
    onImportCert: () -> Unit,
) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp)) {
        when (category) {
            VaultCategoryKind.SSH_KEYS -> if (canGenerate) PrimaryButton(stringResource(Res.string.vault_generate_key), onClick = onGenerate, icon = "add", modifier = Modifier.fillMaxWidth())
            VaultCategoryKind.PASSWORDS -> PrimaryButton(stringResource(Res.string.vault_add_password), onClick = onAddPassword, icon = "add", modifier = Modifier.fillMaxWidth())
            VaultCategoryKind.CERTIFICATES -> if (canImportCert) PrimaryButton(stringResource(Res.string.vault_import_certificate), onClick = onImportCert, icon = "add", modifier = Modifier.fillMaxWidth())
        }
    }
}

// Secret card.

/** Keychain secret card (key/password/certificate) in the category list. */
@Composable
private fun MobileSecretCard(credential: Credential, usedByCount: Int, mono: FontFamily, onClick: () -> Unit) {
    val generator = LocalSshKeyGenerator.current
    val inspector = LocalSshCertificateInspector.current
    val usedBy = VaultPresentation.usedByLabel(usedByCount)
    // Compute metadata once per card (each helper is a separate produceState slot, so a repeat call
    // would parse the same secret twice). null for a mismatched type — no work.
    val keyInfo = rememberKeyInfo(credential, generator)
    val certInfo = rememberCertInfo(credential, inspector)
    val (icon, iconColor) = VaultPresentation.secretStyle(credential.secret)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(D.card)
            .border(1.dp, D.cyan.copy(alpha = 0.1f), RoundedCornerShape(14.dp))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        Box(
            Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(iconColor.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) { Sym(icon, size = 20.sp, color = iconColor) }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Txt(credential.label, color = D.text, size = 14.5.sp, weight = FontWeight.SemiBold)
                when (credential.secret) {
                    is CredentialSecret.PrivateKey ->
                        keyInfo?.keyTypeLabel?.let { Badge(it, bg = D.moss.copy(alpha = 0.16f), fg = D.moss, size = 9.sp) }
                    is CredentialSecret.Certificate -> {
                        certInfo?.keyTypeLabel?.let { Badge(it, bg = D.moss.copy(alpha = 0.16f), fg = D.moss, size = 9.sp) }
                        if (certInfo?.expired == true) Badge(stringResource(Res.string.vault_badge_expired), bg = D.sunset.copy(alpha = 0.16f), fg = D.sunset, size = 9.sp)
                    }
                    is CredentialSecret.Password -> Unit
                }
            }
            val meta = when (credential.secret) {
                is CredentialSecret.PrivateKey ->
                    if (keyInfo != null) {
                        stringResource(Res.string.vtail_meta_fingerprint, shortFingerprint(keyInfo.fingerprintSha256), usedBy)
                    } else {
                        usedBy
                    }
                is CredentialSecret.Certificate -> when {
                    certInfo == null -> stringResource(Res.string.vault_meta_certificate, usedBy)
                    certInfo.principals.isEmpty() -> stringResource(Res.string.vault_meta_any_principal, usedBy)
                    else -> stringResource(Res.string.vtail_meta_principals, certInfo.principals.joinToString(", "), usedBy)
                }
                is CredentialSecret.Password -> stringResource(Res.string.vault_meta_password, usedBy)
            }
            Txt(meta, color = D.dim, size = 10.5.sp, font = mono, modifier = Modifier.padding(top = 3.dp))
        }
        Sym("chevron_right", size = 20.sp, color = D.faint)
    }
}

@Composable
private fun MobileVaultEmpty(category: VaultCategoryKind) {
    Box(Modifier.fillMaxWidth().padding(top = 50.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Sym(category.icon, size = 26.sp, color = D.faint)
            val (title, hint) = when (category) {
                VaultCategoryKind.SSH_KEYS -> stringResource(Res.string.vault_empty_ssh_title) to stringResource(Res.string.vault_empty_ssh_hint)
                VaultCategoryKind.PASSWORDS -> stringResource(Res.string.vault_empty_passwords_title) to stringResource(Res.string.vault_empty_passwords_hint)
                VaultCategoryKind.CERTIFICATES -> stringResource(Res.string.vault_empty_certificates_title) to stringResource(Res.string.vault_empty_certificates_hint_short)
            }
            Txt(title, color = D.text, size = 13.sp, weight = FontWeight.SemiBold)
            Txt(hint, color = D.faint, size = 11.5.sp)
        }
    }
}

// Secret detail sheet.

/**
 * Bottom detail sheet for the selected secret: header (icon/name/subtype), public key + fingerprint
 * (key) or certificate body, used-by hosts, Copy/Export/Delete buttons. Only public material (public
 * key/cert) is exposed; the private key/password only via Copy/Export on an explicit user action.
 */
@Composable
private fun MobileSecretDetailSheet(
    credential: Credential,
    hosts: List<Host>,
    mono: FontFamily,
    onCopy: (String) -> Unit,
    onCopyPassword: (String) -> Unit,
    onExport: (name: String, content: String) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val generator = LocalSshKeyGenerator.current
    val inspector = LocalSshCertificateInspector.current
    val secret = credential.secret
    val keyInfo = rememberKeyInfo(credential, generator)
    val certInfo = rememberCertInfo(credential, inspector)
    val (icon, color, tinted) = VaultPresentation.secretStyle(secret)
    val subtitle = when (secret) {
        is CredentialSecret.Certificate -> certInfo?.keyTypeLabel?.let { stringResource(Res.string.vault_subtitle_certificate_typed, it) } ?: stringResource(Res.string.vault_subtitle_certificate)
        is CredentialSecret.PrivateKey -> keyInfo?.keyTypeLabel ?: stringResource(Res.string.vault_subtitle_private_key)
        is CredentialSecret.Password -> stringResource(Res.string.vault_subtitle_password)
    }
    // Full-screen scrim; a tap outside the sheet closes it. The sheet swallows clicks so it doesn't
    // close. It fits its content (a short password ⇒ short sheet) but not above 85% of the screen —
    // then content scrolls. A fixed height would inflate an empty sheet for a bodiless secret.
    MobileBottomSheet(onDismiss = onDismiss, maxHeightFraction = 0.85f) {
        // weight(fill = false): with short content the column hugs it; with long content it fills the
        // remaining sheet height and scrolls (rather than overflowing the edge).
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp)) {
                Row(Modifier.padding(bottom = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                    SecretIcon(icon, tinted = tinted, color = color, size = 40)
                    Column {
                        Txt(credential.label, color = D.text, size = 15.sp, weight = FontWeight.SemiBold)
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
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    when (secret) {
                        is CredentialSecret.Certificate -> {
                            MobileSheetButton(stringResource(Res.string.vault_copy_certificate), onClick = { onCopy(secret.certificate) }, icon = "content_copy", modifier = Modifier.fillMaxWidth())
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                MobileSheetButton(stringResource(Res.string.vault_export), onClick = { onExport("${credential.label}-cert.pub", secret.certificate) }, filled = false, modifier = Modifier.weight(1f))
                                MobileSheetButton(stringResource(Res.string.vault_delete), onClick = onDelete, filled = false, danger = true, modifier = Modifier.weight(1f))
                            }
                        }
                        is CredentialSecret.PrivateKey -> {
                            MobileSheetButton(stringResource(Res.string.vault_copy_public_key), onClick = { keyInfo?.let { onCopy(it.publicKeyOpenSsh) } }, icon = "content_copy", modifier = Modifier.fillMaxWidth())
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                MobileSheetButton(stringResource(Res.string.vault_export), onClick = { keyInfo?.let { onExport("${credential.label}.pub", it.publicKeyOpenSsh) } }, filled = false, modifier = Modifier.weight(1f))
                                MobileSheetButton(stringResource(Res.string.vault_delete), onClick = onDelete, filled = false, danger = true, modifier = Modifier.weight(1f))
                            }
                        }
                        is CredentialSecret.Password -> {
                            // The password is sensitive: copying requires re-authentication
                            // (biometrics/master password, see onCopyPassword) and goes through the
                            // platform path (Android: sensitive clip + auto-clear), not the normal
                            // clipboard like cert/public key.
                            MobileSheetButton(stringResource(Res.string.vault_copy_password), onClick = { onCopyPassword(secret.password) }, icon = "content_copy", modifier = Modifier.fillMaxWidth())
                            MobileSheetButton(stringResource(Res.string.vault_delete), onClick = onDelete, filled = false, danger = true, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
}

// Preview (mock).

/** Static mock of the Vault tab (offscreen/preview without an unlocked keychain). */
@Composable
private fun MobileVaultMock() {
    val mono = LocalFonts.current.mono
    Column(Modifier.fillMaxSize().background(D.bg).verticalScroll(rememberScrollState())) {
        Box(Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 6.dp, bottom = 10.dp)) {
            MobileScreenTitle(stringResource(Res.string.vault_title))
        }
        MobileVaultBanner()
        Txt(
            "SSH KEYS",
            color = D.faint, size = 12.sp, weight = FontWeight.SemiBold, letterSpacing = 0.6.sp,
            modifier = Modifier.padding(start = 22.dp, end = 22.dp, bottom = 4.dp),
        )
        Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 8.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            MockKeyShell(D.cyanBright, D.cyan.copy(alpha = 0.05f), D.cyan.copy(alpha = 0.16f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Txt("id_ed25519", color = D.text, size = 14.5.sp, weight = FontWeight.SemiBold)
                    Badge("DEFAULT", bg = D.cyan.copy(alpha = 0.14f), fg = D.cyanBright, size = 9.sp)
                }
                Txt("SHA256:8c3F1a…Qz9pK", color = D.dim, size = 10.5.sp, font = mono, modifier = Modifier.padding(top = 3.dp))
            }
            MockKeyShell(D.dim, D.card, D.cyan.copy(alpha = 0.08f)) {
                Txt("id_rsa_legacy", color = D.text, size = 14.5.sp, weight = FontWeight.SemiBold)
                Txt("SHA256:2dE7b…Lm4xR", color = D.dim, size = 10.5.sp, font = mono, modifier = Modifier.padding(top = 3.dp))
            }
            MockKeyShell(D.sunset, D.sunset.copy(alpha = 0.05f), D.sunset.copy(alpha = 0.22f), icon = "warning") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Txt("deploy_ci", color = D.text, size = 14.5.sp, weight = FontWeight.SemiBold)
                    Badge("ROTATE", bg = D.sunset.copy(alpha = 0.16f), fg = D.sunset, size = 9.sp)
                }
                Txt("created 412 days ago", color = D.dim, size = 10.5.sp, font = mono, modifier = Modifier.padding(top = 3.dp))
            }
        }
        Spacer(Modifier.height(96.dp))
    }
}

@Composable
private fun MockKeyShell(iconColor: Color, cardBg: Color, cardBorder: Color, icon: String = "key", content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(cardBg)
            .border(1.dp, cardBorder, RoundedCornerShape(14.dp)).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(iconColor.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
            Sym(icon, size = 20.sp, color = iconColor)
        }
        Column(Modifier.weight(1f)) { content() }
    }
}
