package app.skerry.ui.vault

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
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
import app.skerry.shared.vault.CredentialUsage
import app.skerry.shared.vault.SshCertificateInfo
import app.skerry.shared.vault.SshCertificateInspector
import app.skerry.shared.vault.SshKeyGenerator
import app.skerry.shared.vault.SshPublicKeyInfo
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.vault_any_principal
import app.skerry.ui.generated.resources.vault_badge_expired
import app.skerry.ui.generated.resources.vault_badge_file
import app.skerry.ui.generated.resources.vault_badge_file_missing
import app.skerry.ui.generated.resources.vault_cert_from_file_unreadable
import app.skerry.ui.generated.resources.vault_cert_key_id
import app.skerry.ui.generated.resources.vault_cert_unreadable
import app.skerry.ui.generated.resources.vault_copy_certificate
import app.skerry.ui.generated.resources.vault_copy_password
import app.skerry.ui.generated.resources.vault_copy_public_key
import app.skerry.ui.generated.resources.vault_delete
import app.skerry.ui.generated.resources.vault_export_key
import app.skerry.ui.generated.resources.vault_export_certificate
import app.skerry.ui.generated.resources.vault_key_unreadable
import app.skerry.ui.generated.resources.vault_label_cert_path
import app.skerry.ui.generated.resources.vault_label_key_path
import app.skerry.ui.generated.resources.vault_label_principals
import app.skerry.ui.generated.resources.vault_label_public_key
import app.skerry.ui.generated.resources.vault_label_serial
import app.skerry.ui.generated.resources.vault_label_signing_ca
import app.skerry.ui.generated.resources.vault_label_valid
import app.skerry.ui.generated.resources.vault_not_attached
import app.skerry.ui.generated.resources.vault_rename
import app.skerry.ui.generated.resources.vault_subtitle_certificate
import app.skerry.ui.generated.resources.vault_subtitle_certificate_typed
import app.skerry.ui.generated.resources.vault_subtitle_key_file
import app.skerry.ui.generated.resources.vault_subtitle_key_file_cert
import app.skerry.ui.generated.resources.vault_subtitle_password
import app.skerry.ui.generated.resources.vault_subtitle_private_key
import app.skerry.ui.generated.resources.vault_used_by
import app.skerry.ui.generated.resources.vault_used_by_one
import app.skerry.ui.generated.resources.vault_used_by_snippets
import app.skerry.ui.generated.resources.vault_used_by_snippets_one
import app.skerry.ui.host.rowLabel
import app.skerry.ui.known.shortFingerprint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.design.Badge
import app.skerry.ui.design.GhostButton
import app.skerry.ui.design.labelUppercase
import app.skerry.ui.app.LocalSecretFileReader
import app.skerry.ui.design.PrimaryButton
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.theme.Skerry

/** Square secret icon in the card/detail panel (cyan/moss tint for keys/certificates, neutral for passwords). */
@Composable
internal fun SecretIcon(icon: String, tinted: Boolean, color: Color, size: Int = 38) {
    Box(
        Modifier.size(size.dp).clip(RoundedCornerShape(9.dp)).background(if (tinted) color.copy(alpha = 0.12f) else Skerry.colors.overlayMed),
        contentAlignment = Alignment.Center,
    ) {
        Sym(icon, size = (size * 0.52f).sp, color = if (tinted) color else Skerry.colors.dim)
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
internal fun LiveSecretDetail(
    credential: Credential,
    generator: SshKeyGenerator?,
    inspector: SshCertificateInspector?,
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
) {
    val secret = credential.secret
    val keyExport = remember(credential) { privateKeyExport(credential) }
    val certExport = remember(credential) { certificateExport(credential) }
    val keyInfo = rememberKeyInfo(credential, generator)
    val certInfo = rememberCertInfo(credential, inspector)
    val keyFileState = (secret as? CredentialSecret.KeyFile)?.let { rememberKeyFileState(it, LocalSecretFileReader.current, inspector) }
    val subtitle = when (secret) {
        is CredentialSecret.Certificate -> certInfo?.keyTypeLabel?.let { stringResource(Res.string.vault_subtitle_certificate_typed, it) } ?: stringResource(Res.string.vault_subtitle_certificate)
        is CredentialSecret.PrivateKey -> keyInfo?.keyTypeLabel ?: stringResource(Res.string.vault_subtitle_private_key)
        is CredentialSecret.Password -> stringResource(Res.string.vault_subtitle_password)
        is CredentialSecret.KeyFile ->
            if (secret.certificateRef.isNullOrBlank()) stringResource(Res.string.vault_subtitle_key_file)
            else stringResource(Res.string.vault_subtitle_key_file_cert)
    }
    Column(Modifier.width(DETAIL_PANEL_WIDTH).fillMaxHeight().background(Skerry.colors.surface2).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 18.dp)) {
        DetailLabel(credential.label)
        SecretFactRows(
            typeLabel = subtitle,
            // A password has no fingerprint, and a key still being parsed has none yet — the row is
            // dropped rather than filled with a placeholder that reads like data.
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
                // The fingerprint itself is already a fact row above; what the panel adds here is the
                // key the user actually has to paste somewhere.
                Box(Modifier.fillMaxWidth().padding(bottom = 16.dp).clip(RoundedCornerShape(7.dp)).background(Skerry.colors.terminalBg).border(1.dp, Skerry.colors.cyan.copy(alpha = 0.1f), RoundedCornerShape(7.dp)).padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Txt(keyInfo?.publicKeyOpenSsh ?: stringResource(Res.string.vault_key_unreadable), color = Skerry.colors.dim, size = 10.5.sp, font = mono, lineHeight = 16.sp)
                }
            }
            is CredentialSecret.Password -> Unit
            is CredentialSecret.KeyFile -> KeyFileDetailBody(secret, keyFileState, mono)
        }
        UsedByHosts(hosts, snippetLabels, mono)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Copy is type-specific (what's copyable differs); rename is universal and edits only the
            // label (see onRename); delete/export are type-specific again.
            when (secret) {
                is CredentialSecret.Certificate ->
                    PrimaryButton(stringResource(Res.string.vault_copy_certificate), onClick = { onCopy(secret.certificate) }, icon = "content_copy", modifier = Modifier.fillMaxWidth())
                is CredentialSecret.PrivateKey ->
                    PrimaryButton(stringResource(Res.string.vault_copy_public_key), onClick = { keyInfo?.let { onCopy(it.publicKeyOpenSsh) } }, icon = "content_copy", modifier = Modifier.fillMaxWidth())
                // Password is sensitive: copying requires re-authentication (biometrics/master password,
                // see onCopyPassword) and goes through a platform-specific path (Android: sensitive clip +
                // auto-clear) rather than the plain clipboard used for cert/public key.
                is CredentialSecret.Password ->
                    PrimaryButton(stringResource(Res.string.vault_copy_password), onClick = { onCopyPassword(secret.password) }, icon = "content_copy", modifier = Modifier.fillMaxWidth())
                // Nothing to copy: the material is on disk, and the refs are already spelled out above.
                is CredentialSecret.KeyFile -> Unit
            }
            GhostButton(stringResource(Res.string.vault_rename), onClick = onRename, modifier = Modifier.fillMaxWidth())
            // Export writes the private key — the half of a key or a certificate that is otherwise
            // trapped in the vault; it is labelled for what it hands out, and the host
            // re-authenticates first. A certificate's public half gets its own button rather than a
            // second file from this one: one button, one file, one outcome to report.
            val deleteButton: @Composable (Modifier) -> Unit = { modifier ->
                GhostButton(stringResource(Res.string.vault_delete), onClick = onDelete, fg = Skerry.colors.sunset, border = Skerry.colors.sunset.copy(alpha = 0.3f), modifier = modifier)
            }
            when (secretActions(credential)) {
                SecretActions.KeyAndCertificate -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GhostButton(stringResource(Res.string.vault_export_key), onClick = { keyExport?.let(onExportKey) }, modifier = Modifier.weight(1f))
                        GhostButton(stringResource(Res.string.vault_export_certificate), onClick = { certExport?.let(onExportPublic) }, modifier = Modifier.weight(1f))
                    }
                    deleteButton(Modifier.fillMaxWidth())
                }
                SecretActions.KeyAndDelete -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GhostButton(stringResource(Res.string.vault_export_key), onClick = { keyExport?.let(onExportKey) }, modifier = Modifier.weight(1f))
                    deleteButton(Modifier.weight(1f))
                }
                SecretActions.DeleteOnly -> deleteButton(Modifier.fillMaxWidth())
            }
        }
        SecretSectionLabel(encryptionSectionTitle())
        SecretEncryptionRows(syncing)
        // Audit counts clipboard copies, and only a password ever leaves the vault that way: for a
        // key or a certificate the copy button hands over the public half, which is not a secret.
        // (An exported key is not a copy and is not counted — the log records clipboard events.)
        if (secret is CredentialSecret.Password) {
            SecretSectionLabel(auditSectionTitle())
            SecretAuditRows(usage)
        }
    }
}

/** "Used by · N hosts" block with host-name pills, for the secret detail panel. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun UsedByHosts(hosts: List<Host>, snippetLabels: List<String>, mono: FontFamily) {
    if (hosts.isEmpty() && snippetLabels.isEmpty()) {
        // Unused secret: just the hint, no noisy "used by 0 hosts" header.
        Txt(stringResource(Res.string.vault_not_attached), color = Skerry.colors.faint, size = 11.sp, modifier = Modifier.padding(bottom = 20.dp))
        return
    }
    // A single binding uses the singular form ("· 1 host"), otherwise the plural.
    if (hosts.isNotEmpty()) {
        DetailLabel(
            if (hosts.size == 1) stringResource(Res.string.vault_used_by_one)
            else stringResource(Res.string.vault_used_by, hosts.size),
        )
        FlowRow(Modifier.fillMaxWidth().padding(bottom = 20.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            hosts.forEach { HostPill(it.rowLabel(), mono) }
        }
    }
    // Snippets referencing this secret by name (${{vault:label}}) — a rename/delete breaks them,
    // so they must be visible next to the host bindings.
    if (snippetLabels.isNotEmpty()) {
        DetailLabel(
            if (snippetLabels.size == 1) stringResource(Res.string.vault_used_by_snippets_one)
            else stringResource(Res.string.vault_used_by_snippets, snippetLabels.size),
        )
        FlowRow(Modifier.fillMaxWidth().padding(bottom = 20.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            snippetLabels.forEach { HostPill(it, mono) }
        }
    }
}

/** Certificate detail panel body: the cert string itself, key id, principals, validity, serial, CA. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CertificateDetailBody(info: SshCertificateInfo?, mono: FontFamily) {
    DetailLabel(stringResource(Res.string.vault_subtitle_certificate))
    Box(Modifier.fillMaxWidth().padding(bottom = 16.dp).clip(RoundedCornerShape(7.dp)).background(Skerry.colors.terminalBg).border(1.dp, Skerry.colors.moss.copy(alpha = 0.12f), RoundedCornerShape(7.dp)).padding(horizontal = 12.dp, vertical = 10.dp)) {
        Txt(
            if (info != null) stringResource(Res.string.vault_cert_key_id, info.keyId) else stringResource(Res.string.vault_cert_unreadable),
            color = Skerry.colors.dim, size = 10.5.sp, font = mono, lineHeight = 16.sp,
        )
    }
    if (info == null) return
    DetailLabel(stringResource(Res.string.vault_label_principals))
    if (info.principals.isEmpty()) {
        Txt(stringResource(Res.string.vault_any_principal), color = Skerry.colors.faint, size = 11.sp, modifier = Modifier.padding(bottom = 16.dp))
    } else {
        FlowRow(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            info.principals.forEach { HostPill(it, mono) }
        }
    }
    DetailLabel(stringResource(Res.string.vault_label_valid))
    Row(Modifier.padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Txt("${info.validFrom} → ${info.validUntil}", color = Skerry.colors.textBright, size = 11.sp, font = mono)
        if (info.expired) Badge(stringResource(Res.string.vault_badge_expired), bg = Skerry.colors.sunset.copy(alpha = 0.16f), fg = Skerry.colors.sunset, radius = 3, size = 9.5.sp)
    }
    DetailLabel(stringResource(Res.string.vault_label_serial))
    Txt(info.serial, color = Skerry.colors.textBright, size = 11.sp, font = mono, modifier = Modifier.padding(bottom = 16.dp))
    DetailLabel(stringResource(Res.string.vault_label_signing_ca))
    Txt(info.caFingerprintSha256, color = Skerry.colors.textBright, size = 11.sp, font = mono, modifier = Modifier.padding(bottom = 16.dp))
}

// Dialogs: generate key, add password, import certificate, confirm delete.

/**
 * Badges for a file-backed secret in the list: the certificate's key type, whether it has expired,
 * and — loudest of the three — whether the files it points at can be read here at all. A credential
 * whose issuer hasn't run today looks identical to a working one until something says so.
 *
 * A null [state] (read still in flight, or a platform with no reader) shows nothing rather than
 * flashing a wrong verdict.
 */
@Composable
internal fun KeyFileBadges(state: KeyFileState?) {
    if (state == null) return
    val broken = !state.keyReadable || (state.certificateExpected && !state.certificateReadable)
    if (broken) {
        Badge(stringResource(Res.string.vault_badge_file_missing), bg = Skerry.colors.sunset.copy(alpha = 0.16f), fg = Skerry.colors.sunset, radius = 3, size = 9.5.sp)
        return
    }
    state.certificate?.keyTypeLabel?.let { Badge(it, bg = Skerry.colors.moss.copy(alpha = 0.16f), fg = Skerry.colors.moss, radius = 3, size = 9.5.sp) }
    if (state.certificate?.expired == true) {
        Badge(stringResource(Res.string.vault_badge_expired), bg = Skerry.colors.sunset.copy(alpha = 0.16f), fg = Skerry.colors.sunset, radius = 3, size = 9.5.sp)
    } else {
        Badge(stringResource(Res.string.vault_badge_file), bg = Skerry.colors.cyan.copy(alpha = 0.14f), fg = Skerry.colors.cyanBright, radius = 3, size = 9.5.sp)
    }
}

/**
 * Detail body for a file-backed secret: the refs themselves (that's the whole secret, as far as the
 * vault is concerned), whether each is readable here, and the certificate metadata parsed off disk —
 * the same [CertificateDetailBody] a vault-stored certificate gets, so both kinds read alike.
 */
@Composable
internal fun KeyFileDetailBody(secret: CredentialSecret.KeyFile, state: KeyFileState?, mono: FontFamily) {
    DetailLabel(stringResource(Res.string.vault_label_key_path))
    RefRow(secret.privateKeyRef, missing = state?.keyReadable == false, mono = mono)
    val certRef = state?.certificateRef ?: secret.certificateRef?.takeIf { it.isNotBlank() }
    if (certRef != null) {
        DetailLabel(stringResource(Res.string.vault_label_cert_path))
        RefRow(certRef, missing = state?.certificateReadable == false, mono = mono)
    }
    if (state?.certificateExpected == true && !state.certificateReadable) {
        Txt(stringResource(Res.string.vault_cert_from_file_unreadable), color = Skerry.colors.sunset, size = 11.sp, modifier = Modifier.padding(bottom = 16.dp))
    }
    state?.certificate?.let { CertificateDetailBody(it, mono) }
}

@Composable
internal fun DetailLabel(text: String) {
    Txt(labelUppercase(text), color = Skerry.colors.faint, size = 10.sp, weight = FontWeight.SemiBold, letterSpacing = 0.5.sp, modifier = Modifier.padding(bottom = 6.dp))
}

@Composable
internal fun HostPill(name: String, mono: FontFamily, dim: Boolean = false) {
    Box(Modifier.clip(RoundedCornerShape(20.dp)).background(Skerry.colors.overlaySoft).padding(horizontal = 9.dp, vertical = 3.dp)) {
        Txt(name, color = if (dim) Skerry.colors.dim else Skerry.colors.textBright, size = 11.sp, font = mono)
    }
}
