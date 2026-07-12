package app.skerry.ui.connection

import app.skerry.shared.host.Host
import app.skerry.shared.ssh.SshAuth
import app.skerry.shared.ssh.SshJump
import app.skerry.shared.ssh.SshTarget
import app.skerry.shared.vault.Credential
import app.skerry.shared.vault.CredentialSecret

/**
 * Pure helpers wiring a saved host profile to a live session. Kept separate from UI so the
 * desktop design layer and the mobile screen build [SshTarget]/[SshAuth] and labels the same way
 * (DRY), covered by shared tests without Compose.
 */

/**
 * Host profile → connection address ([SshTarget]); [Host.connectionType] picks the transport.
 * [jump] is the resolved ProxyJump chain ([resolveJumpChain]) when the profile has one — the
 * caller resolves it (needs the host/credential stores) and must NOT pass `null` for a profile
 * with [Host.jumpHostId] set (that would silently connect direct).
 */
fun Host.toTarget(jump: SshJump? = null): SshTarget =
    SshTarget(
        host = address, port = port, username = username, connectionType = connectionType,
        jump = jump, keepAliveSeconds = keepAliveSeconds,
    )

/** `user@addr:port` string — the session's tab/title label. */
fun Host.connectionSubtitle(): String = "$username@$address:$port"

/**
 * Keychain secret from the vault → SSH auth method. Password/key/certificate map one-to-one;
 * branches mirror the [CredentialSecret] model. A host references its secret by `credentialId` —
 * the caller resolves it to a [Credential] and calls this.
 */
fun Credential.toSshAuth(): SshAuth = when (val s = secret) {
    is CredentialSecret.Password -> SshAuth.Password(s.password)
    is CredentialSecret.PrivateKey -> SshAuth.PublicKey(s.privateKeyPem, s.passphrase)
    is CredentialSecret.Certificate -> SshAuth.Certificate(s.privateKeyPem, s.certificate, s.passphrase)
}

/**
 * Cipher name for the compact info panel: drops the vendor suffix `@…` (`chacha20-poly1305@openssh.com`
 * → `chacha20-poly1305`) so the string fits. An empty/`null` string returns `null` (nothing to
 * show). The algorithm name itself is unchanged — the suffix is just an OpenSSH vendor marker.
 */
fun shortCipher(cipher: String?): String? =
    cipher?.trim()?.substringBefore('@')?.takeIf { it.isNotEmpty() }
