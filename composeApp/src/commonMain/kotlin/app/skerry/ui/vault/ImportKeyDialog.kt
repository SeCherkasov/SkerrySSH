package app.skerry.ui.vault

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.vault.SshCertificateInspector
import app.skerry.shared.vault.SshKeyGenerator
import app.skerry.shared.vault.SshPublicKeyInfo
import app.skerry.ui.design.StatusAnnouncer
import app.skerry.ui.identity.CredentialDraft
import app.skerry.ui.identity.CredentialKind
import app.skerry.ui.design.Txt
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.vault_any_principal
import app.skerry.ui.generated.resources.vault_cert_key_mismatch
import app.skerry.ui.generated.resources.vault_cert_read_error
import app.skerry.ui.generated.resources.vault_cert_valid_summary
import app.skerry.ui.generated.resources.vault_dialog_import_key_subtitle
import app.skerry.ui.generated.resources.vault_dialog_import_subtitle
import app.skerry.ui.generated.resources.vault_field_certificate
import app.skerry.ui.generated.resources.vault_field_name
import app.skerry.ui.generated.resources.vault_field_passphrase
import app.skerry.ui.generated.resources.vault_field_private_key_pem
import app.skerry.ui.generated.resources.vault_import
import app.skerry.ui.generated.resources.vault_import_certificate
import app.skerry.ui.generated.resources.vault_import_key
import app.skerry.ui.generated.resources.vault_key_read_error
import app.skerry.ui.generated.resources.vault_key_readable
import app.skerry.ui.generated.resources.vault_key_valid_summary
import app.skerry.ui.generated.resources.vault_placeholder_name_cert
import app.skerry.ui.generated.resources.vault_placeholder_name_key
import app.skerry.ui.generated.resources.vault_placeholder_optional
import app.skerry.ui.theme.Skerry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

/** Where the import was opened from: from Certificates it is a certificate import and needs one. */
internal enum class ImportKeyMode { KEY, CERTIFICATE }

/**
 * Imports a pasted private key, with its certificate when there is one. The key is read with
 * [generator] — the same sshj loader a connection uses — so a key that would fail at login never
 * reaches the vault, and an encrypted one is checked against the passphrase that gets stored with it.
 * A certificate issued for another key is refused too, when [inspector] can tell. [inspector] null
 * leaves the certificate field out, so [ImportKeyMode.CERTIFICATE] requires one.
 */
@Composable
internal fun ImportKeyDialog(
    mode: ImportKeyMode,
    generator: SshKeyGenerator,
    inspector: SshCertificateInspector?,
    onDismiss: () -> Unit,
    onCreate: (name: String, pem: String, certificate: String?, passphrase: String?) -> Unit,
) {
    require(mode == ImportKeyMode.KEY || inspector != null) { "a certificate import needs an inspector" }
    var name by remember { mutableStateOf("") }
    var pem by remember { mutableStateOf("") }
    var certificate by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    val keyPem = pem.trim()
    val keyPassphrase = passphrase.ifBlank { null }
    val cert = certificate.trim()

    val keyRead = rememberKeyRead(generator, keyPem, keyPassphrase)
    val keyInfo = keyRead?.info
    val keyUnreadable = keyRead != null && keyRead.info == null
    val certInfo = remember(cert, inspector) { cert.takeIf { it.isNotEmpty() }?.let { inspector?.inspect(it) } }
    val certInvalid = cert.isNotEmpty() && certInfo == null
    val certFor = certInfo?.keyFingerprintSha256
    val certMismatch = keyInfo != null && certFor != null && certFor != keyInfo.fingerprintSha256
    val certOk = if (cert.isEmpty()) mode == ImportKeyMode.KEY else certInfo != null && !certMismatch
    val valid = name.isNotBlank() && keyInfo != null && certOk

    val keyError = if (keyUnreadable) stringResource(Res.string.vault_key_read_error) else null
    val certError = when {
        certInvalid -> stringResource(Res.string.vault_cert_read_error)
        certMismatch -> stringResource(Res.string.vault_cert_key_mismatch)
        else -> null
    }
    // Held while a read is pending, so a pause in typing doesn't make the screen reader repeat itself.
    val verdict = listOfNotNull(
        keyError ?: keyInfo?.let { stringResource(Res.string.vault_key_readable, it.keyTypeLabel) },
        certError,
    ).joinToString(" ")
    val pending = keyPem.isNotEmpty() && keyRead == null
    var announced by remember { mutableStateOf("") }
    LaunchedEffect(verdict, pending) { if (!pending) announced = verdict }
    val certificateMode = mode == ImportKeyMode.CERTIFICATE
    VaultDialogScaffold(
        stringResource(if (certificateMode) Res.string.vault_import_certificate else Res.string.vault_import_key),
        stringResource(if (certificateMode) Res.string.vault_dialog_import_subtitle else Res.string.vault_dialog_import_key_subtitle),
        onDismiss,
    ) {
        StatusAnnouncer(announced)
        DialogField(
            stringResource(Res.string.vault_field_name), name, { name = it },
            placeholder = stringResource(if (certificateMode) Res.string.vault_placeholder_name_cert else Res.string.vault_placeholder_name_key),
        )
        Box(Modifier.padding(top = 16.dp)) {
            DialogField(stringResource(Res.string.vault_field_private_key_pem), pem, { pem = it }, placeholder = "-----BEGIN OPENSSH PRIVATE KEY-----", singleLine = false, keyboardType = KeyboardType.Password)
        }
        Box(Modifier.padding(top = 16.dp)) {
            DialogField(stringResource(Res.string.vault_field_passphrase), passphrase, { passphrase = it }, placeholder = stringResource(Res.string.vault_placeholder_optional), password = true)
        }
        when {
            keyError != null -> Txt(keyError, color = Skerry.colors.sunset, size = 11.sp, modifier = Modifier.padding(top = 12.dp))
            keyInfo != null -> Txt(
                stringResource(Res.string.vault_key_valid_summary, keyInfo.keyTypeLabel, keyInfo.fingerprintSha256),
                color = Skerry.colors.moss, size = 11.sp, modifier = Modifier.padding(top = 12.dp),
            )
        }
        if (inspector != null) {
            Box(Modifier.padding(top = 16.dp)) {
                DialogField(
                    stringResource(Res.string.vault_field_certificate), certificate, { certificate = it },
                    placeholder = if (certificateMode) "ssh-…-cert-v01@openssh.com …" else stringResource(Res.string.vault_placeholder_optional),
                    singleLine = false,
                )
            }
            when {
                certError != null -> Txt(certError, color = Skerry.colors.sunset, size = 11.sp, modifier = Modifier.padding(top = 12.dp))
                certInfo != null -> {
                    val principalsPart = if (certInfo.principals.isEmpty()) stringResource(Res.string.vault_any_principal) else certInfo.principals.joinToString(", ")
                    Txt(
                        stringResource(Res.string.vault_cert_valid_summary, certInfo.keyTypeLabel, principalsPart, certInfo.validUntil),
                        color = Skerry.colors.moss, size = 11.sp, modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        }
        DialogButtons(
            confirmLabel = stringResource(Res.string.vault_import),
            confirmEnabled = valid,
            onDismiss = onDismiss,
            onConfirm = { onCreate(name.trim(), keyPem, cert.ifEmpty { null }, keyPassphrase) },
        )
    }
}

/** What [ImportKeyDialog] hands back as a secret: a certificate makes it one, a bare key stays a key. */
internal fun importedKeyDraft(name: String, pem: String, certificate: String?, passphrase: String?): CredentialDraft =
    CredentialDraft(
        label = name,
        kind = if (certificate == null) CredentialKind.PRIVATE_KEY else CredentialKind.CERTIFICATE,
        privateKeyPem = pem,
        certificate = certificate.orEmpty(),
        passphrase = passphrase.orEmpty(),
    )

/** The vault category an imported secret lands in, so the list shows what was just added. */
internal fun CredentialDraft.importCategory(): VaultCategoryKind =
    if (kind == CredentialKind.CERTIFICATE) VaultCategoryKind.CERTIFICATES else VaultCategoryKind.SSH_KEYS

/** The outcome of reading one exact key and passphrase; [info] null means sshj could not load it. */
private class KeyRead(val pem: String, val passphrase: String?, val info: SshPublicKeyInfo?)

/**
 * Reads the key off the UI thread: an encrypted OpenSSH key runs bcrypt, once per keystroke of the
 * passphrase without the pause (its cost is capped by the generator, see `kdfCostAcceptable`).
 * Returns null while blank or still reading — a verdict is only handed back for the exact [pem] and
 * [passphrase] on screen, so a stale "readable" can never enable the import of text that changed
 * since.
 */
@Composable
private fun rememberKeyRead(generator: SshKeyGenerator, pem: String, passphrase: String?): KeyRead? {
    val read by produceState<KeyRead?>(null, generator, pem, passphrase) {
        if (pem.isEmpty()) return@produceState
        value = withContext(keyReadLane) {
            delay(KEY_READ_PAUSE_MS)
            KeyRead(pem, passphrase, generator.inspect(pem, passphrase))
        }
    }
    return read?.takeIf { it.pem == pem && it.passphrase == passphrase }
}

private const val KEY_READ_PAUSE_MS = 250L

/**
 * One key read at a time: a read in flight can't be interrupted, so edits made meanwhile queue behind
 * it and are cancelled while still waiting instead of each taking a thread of their own.
 */
@OptIn(ExperimentalCoroutinesApi::class)
private val keyReadLane = Dispatchers.Default.limitedParallelism(1)

private val PUBLIC_KEY_LINE = Regex("""^(ssh|ecdsa|sk)-[A-Za-z0-9@.-]+\s+AAAA""")

/**
 * Whether a value typed into a key-file *path* is really the key — a PEM block, or a public key or
 * certificate line (issue #396). Such a value is never a usable path; it belongs in the import.
 */
internal fun looksLikeKeyMaterial(ref: String): Boolean {
    val text = ref.trim()
    return "-----BEGIN" in text || PUBLIC_KEY_LINE.containsMatchIn(text)
}
