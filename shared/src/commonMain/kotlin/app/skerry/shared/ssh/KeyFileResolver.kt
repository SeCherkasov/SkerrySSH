package app.skerry.shared.ssh

import app.skerry.shared.vault.SecretFileReader
import app.skerry.shared.vault.SecretFileResult
import app.skerry.shared.vault.SshCertificateInspector

/**
 * Expands [SshAuth.KeyFile] into a concrete authentication method by reading the referenced files.
 * Runs immediately before authentication (inside the transport, on its IO dispatcher), so a
 * certificate an external issuer rewrote a second ago is the one presented.
 *
 * Certificate selection follows OpenSSH: an explicit [SshAuth.KeyFile.certificateRef] is used as
 * given, and a blank one means "look for the `<key>-cert.pub` sibling", falling back to plain
 * public-key auth when there is none. The sibling probe only applies to path-shaped refs — a
 * `content://` document Uri has no siblings to guess at.
 *
 * Failures throw [SshAuthenticationException] with the ref named: a file-backed credential breaks
 * for pedestrian reasons (issuer not run today, key moved, Uri grant revoked), and "authentication
 * failed" would send the user looking at the server instead of at their own disk. A ref is the
 * user's own input — their own on another of their devices, once sync has carried the credential —
 * never someone else's, since team sharing strips credential bindings. That is why naming it is
 * acceptable where a host address wouldn't be; the text still stays out of any log.
 *
 * [inspector] is optional; when present, a certificate that *parses* and has expired is refused
 * here instead of at the server. One that doesn't parse is passed through — an inspector that
 * doesn't recognise a newer key type must not block a certificate the server would accept.
 */
class KeyFileResolver(
    private val files: SecretFileReader,
    private val inspector: SshCertificateInspector? = null,
) {

    /** [auth] as-is unless it is a [SshAuth.KeyFile], which is expanded by reading its files. */
    fun resolve(auth: SshAuth): SshAuth {
        if (auth !is SshAuth.KeyFile) return auth
        val privateKey = require(auth.privateKeyRef, KEY)
        val explicit = auth.certificateRef?.takeIf { it.isNotBlank() }
        val certificate = when {
            explicit != null -> require(explicit, CERT)
            else -> keyFileSiblingRef(auth.privateKeyRef)?.let { sibling ->
                // An absent sibling is the ordinary plain-key case; a sibling that is *there* but
                // unreadable is not. Falling back silently would answer a cert-only server with the
                // wrong method and leave nothing pointing at the file that actually broke.
                when (val result = files.read(sibling)) {
                    is SecretFileResult.Ok -> result.text
                    SecretFileResult.NotFound -> null
                    else -> require(sibling, CERT)
                }
            }
        }
        return when (certificate) {
            null -> SshAuth.PublicKey(privateKey, auth.passphrase)
            else -> {
                inspector?.inspect(certificate)?.let { info ->
                    if (info.expired) {
                        throw SshAuthenticationException(
                            "Certificate expired on ${info.validUntil} (${explicit ?: keyFileSiblingRef(auth.privateKeyRef)}) — issue a new one",
                        )
                    }
                }
                SshAuth.Certificate(privateKey, certificate, auth.passphrase)
            }
        }
    }

    /** File content at [ref], or [SshAuthenticationException] naming what went wrong with it. */
    private fun require(ref: String, what: String): String = when (val result = files.read(ref)) {
        is SecretFileResult.Ok -> result.text
        SecretFileResult.NotFound -> fail("$what file not found: $ref")
        SecretFileResult.Denied -> fail("$what file access denied: $ref")
        SecretFileResult.TooLarge -> fail("$what file is too large to be a key: $ref")
        SecretFileResult.Unsupported ->
            fail("$what file was picked on another device and can't be opened here: $ref")
        is SecretFileResult.Failed -> fail("$what file could not be read: $ref${result.detail?.let { " ($it)" }.orEmpty()}")
    }

    private fun fail(message: String): Nothing = throw SshAuthenticationException(message)

    private companion object {
        const val KEY = "Private key"
        const val CERT = "Certificate"
    }
}

/**
 * The certificate OpenSSH would look for next to a key: `<ref>-cert.pub`. Null for a ref carrying a
 * URI scheme — appending a suffix to an opaque Uri would only ask the provider for a document that
 * cannot exist. Shared with the vault UI so the form and the connection agree on which file a blank
 * certificate ref means.
 */
fun keyFileSiblingRef(ref: String): String? =
    ref.trim().takeIf { it.isNotEmpty() && !SCHEME.containsMatchIn(it) }?.let { "$it-cert.pub" }

private val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.\\-]*://")
