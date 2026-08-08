package app.skerry.ui.vault

import app.skerry.shared.io.safeFileStem
import app.skerry.shared.vault.Credential
import app.skerry.shared.vault.CredentialSecret
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * One file "Export" writes: the [fileName] offered in the Save-As dialog and the [content] written
 * into it.
 *
 * Split by sensitivity rather than carrying a boolean, because the two halves take different paths
 * out of the vault: [exportPublic] accepts a [Public] and nothing else, so a private key cannot be
 * handed to the ungated writer by mistake — that mistake, made once, is issue #218. The split stops
 * the accident, not a determined caller: `Public(name, pem)` still compiles, and what actually keeps
 * the two apart is that [privateKeyExport] and [certificateExport] are the only producers.
 *
 * `toString` redacts everything — the rule every secret-carrying type in the app follows
 * ([CredentialSecret], `CredentialDraft`): one `"$export"` in a log line would otherwise print a
 * whole private key. The file name goes too, because it is built from the vault label, and
 * `Credential.toString` redacts that for its own reason: `root@customer-bastion` is information
 * even without the key beside it.
 */
sealed interface SecretExport {
    val fileName: String
    val content: String

    /** Key material. Leaves the vault only through [exportPrivateKey], behind re-authentication. */
    data class PrivateKey(override val fileName: String, override val content: String) : SecretExport {
        override fun toString(): String = "SecretExport.PrivateKey(redacted)"
    }

    /** Public half (a certificate) — no gate, the same as the Copy button beside it. */
    data class Public(override val fileName: String, override val content: String) : SecretExport {
        override fun toString(): String = "SecretExport.Public(redacted)"
    }
}

/**
 * The private key of a keychain secret as a file, or `null` when there is none to write: a password
 * is not a file, and a file-backed secret already *is* one — its material never entered the vault.
 * This is the half a user cannot get out of the vault any other way, so it leaves only behind
 * re-authentication ([exportPrivateKey]).
 *
 * The PEM is written exactly as stored, encrypted or not. A key imported under a passphrase stays
 * encrypted in the file and the passphrase is not written next to it — it is the user's own.
 */
fun privateKeyExport(credential: Credential): SecretExport.PrivateKey? {
    val pem = when (val secret = credential.secret) {
        is CredentialSecret.PrivateKey -> secret.privateKeyPem
        is CredentialSecret.Certificate -> secret.privateKeyPem
        is CredentialSecret.Password, is CredentialSecret.KeyFile -> return null
    }
    // ".pem" over the OpenSSH convention of no extension at all: a Save-As dialog with no extension
    // is where a file goes missing, and both formats the vault holds (openssh-key-v1 and PKCS#1)
    // are PEM-armoured.
    return SecretExport.PrivateKey("${fileStem(credential)}.pem", pem)
}

/**
 * The CA-signed certificate as a file, or `null` for a secret that has none. Authenticating with a
 * certificate takes both files (`id` and `id-cert.pub`), so exporting the key alone would hand over
 * an unusable half — and on a phone there is nowhere to paste "Copy certificate" into a file.
 *
 * Deliberately a second button rather than a second file written by the same one: two Save-As
 * dialogs behind one authorization can end half-done, and "the export failed" would then be a lie
 * told over a private key already lying on disk. One button writes one file and reports on it.
 *
 * Public material, so no re-authentication — the same as the "Copy certificate" beside it.
 */
fun certificateExport(credential: Credential): SecretExport.Public? {
    val secret = credential.secret as? CredentialSecret.Certificate ?: return null
    return SecretExport.Public("${fileStem(credential)}-cert.pub", secret.certificate)
}

private fun fileStem(credential: Credential): String = safeFileStem(credential.label, fallback = "key")

/**
 * Which buttons the detail panel's bottom row carries. Decided here, once, because both screens draw
 * the same three layouts out of their own platform primitives — and a chain of ifs that got the
 * pairing right by accident would drift between them the first time someone edited one of the two.
 */
enum class SecretActions {
    /** Export key · Export certificate, with Delete on its own row below. */
    KeyAndCertificate,

    /** Export key · Delete. */
    KeyAndDelete,

    /** Delete only — a password or a file-backed secret has nothing to write out. */
    DeleteOnly,
}

/** The row layout for [credential]; see [SecretActions]. */
fun secretActions(credential: Credential): SecretActions = when {
    certificateExport(credential) != null -> SecretActions.KeyAndCertificate
    privateKeyExport(credential) != null -> SecretActions.KeyAndDelete
    else -> SecretActions.DeleteOnly
}

/**
 * The Export action for private key material, shared by the desktop panel and the mobile sheet:
 * re-authenticate, then write. The two must not drift — this is the gate issue #218 was missing, and
 * a screen calling [exportTextFile] directly would hand out a private key from an unattended
 * unlocked screen.
 *
 * [onOutcome] is told how it ended so the caller can surface a failure (see
 * [ExportOutcome.worthReporting]); it never runs when authorization is refused, which is the
 * behaviour `SecretExportGateTest` pins down. [write] is [exportTextFile] in the app and a fake in
 * tests — the only way to exercise this without a native file dialog.
 */
internal fun exportPrivateKey(
    auth: SecretCopyAuthorizer,
    export: SecretExport.PrivateKey,
    scope: CoroutineScope,
    write: suspend (SecretExport) -> ExportOutcome = { exportTextFile(it.fileName, it.content) },
    onOutcome: (ExportOutcome) -> Unit,
) {
    auth.authorize(SecretAccess.EXPORT) {
        scope.launch { onOutcome(guardedExport { write(export) }) }
    }
}

/** Writes public material (the certificate) — no gate, the same as copying it to the clipboard. */
internal fun exportPublic(
    export: SecretExport.Public,
    scope: CoroutineScope,
    write: suspend (SecretExport) -> ExportOutcome = { exportTextFile(it.fileName, it.content) },
    onOutcome: (ExportOutcome) -> Unit,
) {
    scope.launch { onOutcome(guardedExport { write(export) }) }
}


