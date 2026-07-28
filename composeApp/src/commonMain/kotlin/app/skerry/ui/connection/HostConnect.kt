package app.skerry.ui.connection

import app.skerry.shared.container.ContainerSpec
import app.skerry.shared.host.Host
import app.skerry.shared.ssh.ConnectionType
import app.skerry.shared.ssh.SshAuth
import app.skerry.shared.ssh.SshJump
import app.skerry.shared.ssh.SshTarget
import app.skerry.shared.vault.Credential
import app.skerry.shared.vault.CredentialSecret
import app.skerry.shared.vnc.VncAuth

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
        // Container profiles: what to exec into once the host's SSH leg is up (ignored by every
        // other transport, see [ContainerTransport]).
        container = container.takeIf { connectionType == ConnectionType.CONTAINER },
    )

/**
 * Session tab/title subtitle. `user@addr:port` for networked profiles; a local shell has no
 * host/user, so it shows the shell/command (blank → "local shell"); a container profile leads with
 * what it enters (`web · root@10.0.0.5`, namespaced pods as `ns/pod`) — the container is the point,
 * the host is where it runs.
 */
fun Host.connectionSubtitle(): String {
    val spec = container?.takeIf { connectionType == ConnectionType.CONTAINER && it.isComplete }
    return when {
        connectionType == ConnectionType.LOCAL -> address.ifBlank { "local shell" }
        spec != null -> "${containerLabel(spec)} · $username@$address"
        else -> "$username@$address:$port"
    }
}

/** Container/pod as shown in session chrome: `web`, or `ns/pod` when a namespace is set. */
private fun containerLabel(spec: ContainerSpec): String =
    if (spec.namespace.isNotBlank()) "${spec.namespace}/${spec.target}" else spec.target

/**
 * Keychain secret from the vault → SSH auth method. Password/key/certificate map one-to-one;
 * branches mirror the [CredentialSecret] model. A host references its secret by `credentialId` —
 * the caller resolves it to a [Credential] and calls this.
 */
fun Credential.toSshAuth(): SshAuth = when (val s = secret) {
    is CredentialSecret.Password -> SshAuth.Password(s.password)
    is CredentialSecret.PrivateKey -> SshAuth.PublicKey(s.privateKeyPem, s.passphrase)
    is CredentialSecret.Certificate -> SshAuth.Certificate(s.privateKeyPem, s.certificate, s.passphrase)
    // Refs travel as refs: the files behind them are read by the transport at connect time, so a
    // certificate the issuer rewrote a minute ago is the one presented.
    is CredentialSecret.KeyFile -> SshAuth.KeyFile(s.privateKeyRef, s.certificateRef, s.passphrase)
}

/**
 * Keychain secret → VNC auth. VNC authenticates with a password only (RFB VNC-Auth), so a stored
 * password maps to [VncAuth.Password]; a key/certificate secret is meaningless for VNC and falls
 * back to [VncAuth.None] (the server may still accept a no-auth connection).
 */
fun Credential.toVncAuth(): VncAuth = when (val s = secret) {
    is CredentialSecret.Password -> VncAuth.Password(s.password)
    else -> VncAuth.None
}

/**
 * Keychain secret → the RDP logon password. RDP authenticates a Windows user with a password, so a
 * stored password is the only secret shape that means anything here; a key or certificate belongs to
 * SSH and yields no password, which the caller turns into a prompt rather than a silent failure.
 */
fun Credential.toRdpPassword(): String? = (secret as? CredentialSecret.Password)?.password

/**
 * Cipher name for the compact info panel: drops the vendor suffix `@…` (`chacha20-poly1305@openssh.com`
 * → `chacha20-poly1305`) so the string fits. An empty/`null` string returns `null` (nothing to
 * show). The algorithm name itself is unchanged — the suffix is just an OpenSSH vendor marker.
 */
fun shortCipher(cipher: String?): String? =
    cipher?.trim()?.substringBefore('@')?.takeIf { it.isNotEmpty() }
