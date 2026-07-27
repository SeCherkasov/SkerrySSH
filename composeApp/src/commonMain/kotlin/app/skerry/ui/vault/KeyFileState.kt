package app.skerry.ui.vault

import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import app.skerry.shared.ssh.keyFileSiblingRef
import app.skerry.shared.vault.CredentialSecret
import app.skerry.shared.vault.SecretFileReader
import app.skerry.shared.vault.SecretFileResult
import app.skerry.shared.vault.SshCertificateInfo
import app.skerry.shared.vault.SshCertificateInspector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What the vault UI can say about a file-backed secret *on this device*: whether the referenced
 * files are readable here and what the certificate behind them contains. Everything is derived by
 * reading the same refs a connection would, so the list can show "file missing" before the user
 * finds out the hard way.
 *
 * [certificateRef] is the certificate that actually applies — the explicit ref, or the
 * `<key>-cert.pub` sibling when one exists — and is null when the key authenticates on its own.
 * [certificateExpected] separates "the user named a certificate" from "we looked for a sibling":
 * only the first makes an unreadable file a broken credential.
 */
data class KeyFileState(
    val keyReadable: Boolean,
    val certificateRef: String?,
    val certificateExpected: Boolean,
    val certificateReadable: Boolean,
    val certificate: SshCertificateInfo?,
)

/**
 * Reads the refs of [secret] through [files] and parses the certificate with [inspector]. Pure of
 * Compose (blocking I/O on small files) so it can be tested and called off the main thread.
 */
internal fun inspectKeyFile(
    secret: CredentialSecret.KeyFile,
    files: SecretFileReader,
    inspector: SshCertificateInspector?,
): KeyFileState {
    // Probe, not read: the list needs "is it there", not the key itself.
    val keyReadable = files.probe(secret.privateKeyRef)
    val explicit = secret.certificateRef?.takeIf { it.isNotBlank() }
    val ref = explicit ?: keyFileSiblingRef(secret.privateKeyRef)
    val read = ref?.let { files.read(it) }
    val certificate = (read as? SecretFileResult.Ok)?.text
    // Mirrors the connection ([app.skerry.shared.ssh.KeyFileResolver]): a sibling that isn't there
    // applies to nothing, but one that exists and can't be read is a broken credential, not a
    // plain-key one — and must be flagged even though the user never named it.
    val siblingBroken = explicit == null && read != null && read !is SecretFileResult.Ok &&
        read != SecretFileResult.NotFound
    return KeyFileState(
        keyReadable = keyReadable,
        certificateRef = ref?.takeIf { explicit != null || certificate != null || siblingBroken },
        certificateExpected = explicit != null || siblingBroken,
        certificateReadable = certificate != null,
        certificate = certificate?.let { inspector?.inspect(it) },
    )
}

/**
 * [inspectKeyFile] as Compose state: null while the read is in flight or when the platform has no
 * reader. Runs on [Dispatchers.Default] (the same choice as the other vault inspections — commonMain
 * has no IO dispatcher, and these files are a few kilobytes).
 */
@Composable
internal fun rememberKeyFileState(
    secret: CredentialSecret.KeyFile,
    files: SecretFileReader?,
    inspector: SshCertificateInspector?,
): KeyFileState? {
    if (files == null) return null
    return produceState<KeyFileState?>(null, secret, files, inspector) {
        value = withContext(Dispatchers.Default) { inspectKeyFile(secret, files, inspector) }
    }.value
}
