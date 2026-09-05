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
import app.skerry.ui.design.untrustedLabel
import app.skerry.ui.terminal.SudoPasswordOffer

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
    // Sanitized like the catalog's own caption ([app.skerry.ui.host.rowSubtitle]): for a profile a
    // team member shared, every field spliced in here is that member's text. The local-shell
    // fallback reads the sanitized path, not the raw one — a path made only of format characters is
    // blank once drawn, and the caption would otherwise go empty instead of naming the shell.
    return when {
        connectionType == ConnectionType.LOCAL -> untrustedLabel(address).ifBlank { "local shell" }
        spec != null -> untrustedLabel("${containerLabel(spec)} · $username@$address")
        else -> untrustedLabel("$username@$address:$port")
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
 * The sudo offer for a session authenticating as [target] with [auth] (issue #360), or `null` when
 * there is nothing to offer: [enabled] is off, the profile does not authenticate with a password —
 * a key-based session has no secret a prompt could be answered with — or it carries no account name
 * for a prompt to be matched against.
 *
 * Built from the credential the connection is actually using rather than from a vault lookup, which
 * is what makes "only the credential belonging to the current host/user" true by construction: what
 * may be offered back is exactly what got in.
 */
fun sudoOfferFor(target: SshTarget, auth: SshAuth, enabled: Boolean): SudoPasswordOffer? {
    if (!enabled) return null
    // A container profile execs into an image once the host's SSH leg is up, so the shell on screen
    // is not the account that authenticated: a container with a same-named user printing a sudo
    // prompt would be handed the host's password, and an image is a far weaker trust boundary than
    // the host running it. Nesting the user does by hand (an inner ssh, su, docker exec) cannot be
    // detected from here - which is why the hint names the account and host the password belongs to.
    if (target.connectionType == ConnectionType.CONTAINER) return null
    val password = (auth as? SshAuth.Password)?.secret?.takeIf { it.isNotEmpty() } ?: return null
    val username = target.username.trim().takeIf { it.isNotEmpty() } ?: return null
    return SudoPasswordOffer(username, untrustedLabel("$username@${target.host}"), password)
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
