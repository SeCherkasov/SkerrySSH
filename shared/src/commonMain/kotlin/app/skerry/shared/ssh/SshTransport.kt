package app.skerry.shared.ssh

import app.skerry.shared.container.ContainerSpec
import kotlinx.coroutines.flow.Flow

/**
 * Transport contract for the SSH core. Platform implementations are supplied externally: sshj (JVM)
 * on desktop, own implementation on mobile later.
 */
interface SshTransport {
    /**
     * @throws SshConnectionException network error or transport dropped
     * @throws SshHostKeyRejectedException host key rejected by [HostKeyVerifier]
     * @throws SshAuthenticationException server rejected the credentials
     */
    suspend fun connect(target: SshTarget, auth: SshAuth): SshConnection
}

/**
 * Connection target. [connectionType] selects the transport ([RoutingTransport] dispatches on it):
 * for [ConnectionType.SSH] all fields matter; [ConnectionType.MOSH] reads them the same way (they
 * describe the SSH hop that bootstraps `mosh-server`); for [ConnectionType.TELNET] only [host]/[port] are
 * used ([username]/auth ignored); for [ConnectionType.SERIAL] [host] carries the device name
 * (`/dev/ttyUSB0`, `COM3`) and [port] the baud rate. Default [ConnectionType.SSH] preserves the
 * behavior of prior call sites that built a target without specifying a type.
 *
 * [jump] is an optional ProxyJump hop: the transport first connects (and authenticates) to the
 * hop, then reaches this target through a direct-tcpip channel over it. SSH-only; `null` means a
 * direct connection. Carried inside the target so the whole session/reconnect/tunnel stack routes
 * through the jump without extra plumbing (the controller drops the target together with the auth,
 * so the hop's secret doesn't outlive the session's own).
 *
 * [keepAliveSeconds] is the keep-alive cadence for sessions to this target (0 = none): the session
 * layer sends a keepalive request every N seconds while connected (see
 * [SshConnection.measureRoundTrip]). Carried inside the target (like [jump]) so auto-reconnect
 * keeps the cadence with no extra plumbing. Default 0 preserves prior call sites: ad-hoc/probe
 * targets spawn no background traffic unless asked to.
 *
 * [container] is what a [ConnectionType.CONTAINER] target execs into; the other fields describe the
 * host running the container CLI. [app.skerry.shared.container.ContainerTransport] turns it into
 * [shellCommand] and dials the host as plain SSH — no other transport reads it.
 *
 * [shellCommand] replaces the login shell on the interactive channel with this argv (quoted by the
 * transport, see [app.skerry.shared.container.shellCommandLine]): the PTY is allocated as usual, but
 * the server runs this command in it. `null` (the default) means the account's login shell, i.e.
 * every plain SSH/Mosh/Telnet/Serial/local session.
 */
data class SshTarget(
    val host: String,
    val port: Int = 22,
    val username: String,
    val connectionType: ConnectionType = ConnectionType.SSH,
    val jump: SshJump? = null,
    val keepAliveSeconds: Int = 0,
    val container: ContainerSpec? = null,
    val shellCommand: List<String>? = null,
)

/**
 * One resolved ProxyJump hop: where to connect, as whom, and with which secret. [jump] is the next
 * hop *before* this one (multi-hop chain, resolved outermost-first like OpenSSH `-J a,b`): to reach
 * the target through `b` which is itself behind `a`, the target's hop is `b` with `b.jump = a`.
 * Host key verification runs for every hop under its own (host, port).
 */
data class SshJump(
    val host: String,
    val port: Int = 22,
    val username: String,
    val auth: SshAuth,
    val jump: SshJump? = null,
)

sealed interface SshAuth {
    // Secret as String: not zeroed on JVM; switching to a wipeable buffer is a separate step.
    data class Password(val secret: String) : SshAuth {
        override fun toString(): String = "Password(redacted)"
    }

    /**
     * Private key authentication: [privateKeyPem] is PEM content (OpenSSH/PKCS), [passphrase]
     * decrypts the key (null for a passphrase-less key). Secret comes from the vault
     * ([app.skerry.shared.vault.CredentialSecret.PrivateKey]).
     */
    data class PublicKey(val privateKeyPem: String, val passphrase: String? = null) : SshAuth {
        override fun toString(): String = "PublicKey(redacted)"
    }

    /**
     * SSH certificate authentication: the client presents [certificate] (a CA-issued `*-cert.pub`
     * string) and proves possession of the private key [privateKeyPem] (PEM, [passphrase] decrypts
     * it if needed). Secret comes from the vault
     * ([app.skerry.shared.vault.CredentialSecret.Certificate]).
     */
    data class Certificate(
        val privateKeyPem: String,
        val certificate: String,
        val passphrase: String? = null,
    ) : SshAuth {
        override fun toString(): String = "Certificate(redacted)"
    }

    /**
     * Key and (optional) certificate to be read from where they live at connect time rather than
     * carried in the vault — the form short-lived CA certificates take, since an external issuer
     * rewrites the file every few hours. Comes from
     * [app.skerry.shared.vault.CredentialSecret.KeyFile] and is expanded by [KeyFileResolver] into
     * [PublicKey] or [Certificate] just before authentication, so the file read is as late as
     * possible.
     *
     * [privateKeyRef]/[certificateRef] are locations (path or `content://` Uri), not secrets, but
     * [passphrase] is one — `toString` redacts the lot, like its siblings.
     */
    data class KeyFile(
        val privateKeyRef: String,
        val certificateRef: String? = null,
        val passphrase: String? = null,
    ) : SshAuth {
        override fun toString(): String = "KeyFile(redacted)"
    }

    /**
     * No stored secret: the server drives the exchange and the user answers it
     * ([KeyboardInteractiveResponder]) — a TOTP-only login, a push confirmation, an SMS token.
     *
     * Distinct from an empty [Password] on purpose: a password attempt would be offered and refused
     * first, which costs a failed authentication in the server's log and, under fail2ban, a ban after
     * a couple of connects.
     */
    data object Interactive : SshAuth
}

/**
 * What a server presented as its host key, and for whom. [fingerprint] uses OpenSSH format
 * (`SHA256:` + unpadded base64) and [keyType] is the algorithm identifier (`ssh-ed25519`,
 * `rsa-sha2-512`, …) — both describe what was actually offered, so for a certificate they describe
 * the certificate blob (`ssh-ed25519-cert-v01@openssh.com`), not the key inside it.
 *
 * [certificate] is non-null when the server offered a CA-signed host certificate; [bareKey] strips
 * it down to the key inside, which is what trust-on-first-use has to remember (a certificate is
 * re-issued on a schedule, the key under it is not).
 */
data class HostKeyOffer(
    val host: String,
    val port: Int,
    val keyType: String,
    val fingerprint: String,
    val certificate: OfferedHostCertificate? = null,
) {
    /** This offer as if the server had presented the plain key inside the certificate. */
    fun bareKey(): HostKeyOffer = certificate?.let {
        copy(keyType = it.keyType, fingerprint = it.fingerprint, certificate = null)
    } ?: this
}

/**
 * The fields of an offered OpenSSH host certificate that a trust decision needs. [keyType] and
 * [fingerprint] describe the key inside the certificate; [caKeyType]/[caFingerprint] the key that
 * signed it. [principals] are the names the certificate was issued for (empty means "any host",
 * per PROTOCOL.certkeys) and may be patterns. Validity is in epoch seconds.
 *
 * [hostCertificate] is `false` for a *user* certificate — the same CA usually issues both, and
 * nothing else in the exchange distinguishes them.
 *
 * [caSignatureVerified] states that the transport checked the CA's signature over this certificate.
 * The trust decision lives in `commonMain`, which has no crypto; a verifier must refuse a
 * certificate whose signature nobody vouched for rather than assume it was checked.
 */
data class OfferedHostCertificate(
    val keyType: String,
    val fingerprint: String,
    val caKeyType: String,
    val caFingerprint: String,
    val principals: List<String>,
    val validAfterEpochSeconds: Long,
    val validBeforeEpochSeconds: Long,
    val hostCertificate: Boolean,
    val caSignatureVerified: Boolean,
    val keyId: String = "",
    val serial: String = "",
    val criticalOptions: List<String> = emptyList(),
)

/** Trust decision for a host key. See [HostKeyOffer]. */
fun interface HostKeyVerifier {
    fun verify(offer: HostKeyOffer): Boolean
}

/** Trust decision for a plain (non-certificate) host key — the shorthand used by tests and probes. */
fun HostKeyVerifier.verify(host: String, port: Int, keyType: String, fingerprint: String): Boolean =
    verify(HostKeyOffer(host, port, keyType, fingerprint))

/**
 * One prompt of a keyboard-interactive exchange (RFC 4256). [text] is the server's wording verbatim
 * ("Verification code:", "Duo passcode:"); [echo] false marks a secret the UI must mask.
 */
data class KeyboardInteractivePrompt(val text: String, val echo: Boolean)

/**
 * One round of a keyboard-interactive exchange the user has to answer. [name] and [instruction] are
 * the server's own headings (either may be empty). [hop] marks a challenge coming from a ProxyJump
 * hop rather than the target, so the UI can say whose code is being asked for.
 *
 * The server may ask several times in a row (a wrong code, then another attempt); each round is a
 * separate challenge. Prompts are a list because the protocol allows several per round, though the
 * sshj transport surfaces them one at a time — see `ResponderChallengeProvider`.
 */
data class KeyboardInteractiveChallenge(
    val name: String,
    val instruction: String,
    val prompts: List<KeyboardInteractivePrompt>,
    val hop: Boolean = false,
    /**
     * Who is asking, as `user@host:port`. Shown in the prompt so the user can tell which machine
     * wants the code — without it, a server's own wording is the only thing on screen, and any host
     * you dial could pose as another. Empty when the transport doesn't report it.
     *
     * Display-only: this carries a host address, so it must not be logged or put into exception
     * text, per the connect-metadata rule the transport's error messages already follow.
     */
    val endpoint: String = "",
)

/**
 * Answers keyboard-interactive challenges, i.e. supplies what only the user has — a TOTP code, an
 * SMS token, a push confirmation. Supplied to the transport like [HostKeyVerifier] is; a transport
 * without one simply doesn't offer the method, which is the behavior of every release before this.
 *
 * Called off the UI thread from inside `connect`, and the connection waits for the answer, so an
 * implementation must not block indefinitely — the transport applies its own timeout.
 */
fun interface KeyboardInteractiveResponder {
    /**
     * @return answers in [KeyboardInteractiveChallenge.prompts] order, or null to abort
     *   authentication (the user dismissed the prompt).
     */
    suspend fun respond(challenge: KeyboardInteractiveChallenge): List<String>?
}

/**
 * How many keyboard-interactive rounds we answer before giving up. A server that keeps rejecting
 * (or keeps asking) must not turn into an endless sequence of prompts; OpenSSH's own default of
 * three attempts is the familiar number.
 */
const val KEYBOARD_INTERACTIVE_MAX_ROUNDS: Int = 3

/**
 * How many prompts we answer within one round. The protocol lets the server put an arbitrary number
 * of prompts in a single request, and each one costs the user a dialog; past a handful it isn't an
 * authentication exchange any more, it's a machine wearing the user down.
 */
const val KEYBOARD_INTERACTIVE_MAX_PROMPTS_PER_ROUND: Int = 8

/**
 * How long a shown challenge waits for the user before it counts as dismissed. Timed from the moment
 * the prompt is actually presented, not from when the server asked — a challenge queued behind
 * another connection's prompt must not spend its budget waiting its turn.
 */
const val KEYBOARD_INTERACTIVE_TIMEOUT_MILLIS: Long = 120_000

/**
 * Backstop for a responder that never returns at all (a UI that dropped the prompt on the floor).
 * Deliberately far longer than [KEYBOARD_INTERACTIVE_TIMEOUT_MILLIS], which is the deadline users
 * actually experience: this one only exists so a broken implementation can't pin the transport
 * thread for the lifetime of the process.
 */
const val KEYBOARD_INTERACTIVE_RESPONDER_BACKSTOP_MILLIS: Long = 600_000

interface SshConnection {
    val isConnected: Boolean

    /**
     * Symmetric cipher negotiated at connection setup (client→server direction) in SSH notation
     * (`chacha20-poly1305@openssh.com`, `aes256-gcm@openssh.com`, `aes256-ctr`, …), or `null` if the
     * transport doesn't report it. Static for the connection's lifetime. Default `null`
     * (fakes/tests); real transports override.
     */
    val cipher: String? get() = null

    /**
     * Server identification string (remote ident) in full form `SSH-2.0-<software>`, e.g.
     * `SSH-2.0-OpenSSH_8.9p1`, or `null` if the transport doesn't report it. Static for the
     * connection's lifetime. Default `null` (fakes/tests); real transports override.
     */
    val serverVersion: String? get() = null

    /**
     * Measure round-trip time to the server (ms): sends a keep-alive request and waits for a
     * response, returning `null` if the connection is dead or no reply arrives in a reasonable time.
     * Each call is one round-trip (and incidentally keeps the connection alive). Default `null`
     * (fakes/tests); real transports override. Polling cadence is up to the caller (UI poller).
     */
    suspend fun measureRoundTrip(): Long? = null

    /** One-shot exec channel for non-interactive commands. */
    suspend fun exec(command: String): ExecResult

    /**
     * Interactive shell with a PTY.
     * @throws SshConnectionException channel failed to open
     */
    suspend fun openShell(size: PtySize = PtySize(), term: String = "xterm-256color"): ShellChannel

    /**
     * Open an SFTP subsystem over this connection. Each call is a separate channel; close via
     * [app.skerry.shared.sftp.SftpClient.close]. The connection stays open.
     * @throws SshConnectionException SFTP subsystem failed to open
     */
    suspend fun openSftp(): app.skerry.shared.sftp.SftpClient

    /**
     * Start a local port forward (`-L`) over this connection. The listener lives until
     * [PortForward.close] is called; the connection stays open. See [LocalForwardSpec].
     * @throws PortForwardException listener failed to start (port in use) or channel broke
     */
    suspend fun forwardLocal(spec: LocalForwardSpec): PortForward

    /**
     * Start a remote port forward (`-R`) over this connection. The server listens on its side until
     * [PortForward.close] is called; the connection stays open. See [RemoteForwardSpec].
     * @throws PortForwardException server rejected the request or channel broke
     */
    suspend fun forwardRemote(spec: RemoteForwardSpec): PortForward

    /**
     * Start a dynamic forward (`-D`) over this connection: a SOCKS5 proxy runs on our machine, and
     * each client supplies its own destination address. The listener lives until [PortForward.close]
     * is called; the connection stays open. See [DynamicForwardSpec].
     * @throws PortForwardException listener failed to start (port in use)
     */
    suspend fun forwardDynamic(spec: DynamicForwardSpec): PortForward

    suspend fun disconnect()
}

/** PTY size; pixel dimensions are optional (0 means not reported). */
data class PtySize(
    val cols: Int = 80,
    val rows: Int = 24,
    val widthPx: Int = 0,
    val heightPx: Int = 0,
)

interface ShellChannel {
    val isOpen: Boolean

    /**
     * Total bytes written to the PTY (input/reports) and read from the PTY (output) over the
     * channel's lifetime. Monotonically increasing. Used for the status bar throughput indicator
     * (delta/period computed by `ThroughputController` in the UI layer; not linked here since
     * `shared` doesn't know about UI). Default `0` (fakes/tests).
     */
    val bytesUp: Long get() = 0L
    val bytesDown: Long get() = 0L

    /**
     * Raw PTY output (stdout and stderr merged, as in a real terminal). Cold flow with a single
     * allowed collector: a second collect throws [IllegalStateException]. Completes on channel EOF.
     */
    val output: Flow<ByteArray>

    /**
     * After [output] completes: true if the channel reached a clean EOF (the server closed the shell
     * itself, e.g. via `exit`), false if [output] ended due to a transport error or [close] was
     * called. Undefined before [output] completes. Default false (fakes/tests that don't report the
     * close reason). Used to distinguish a clean exit (→ close the session) from a drop (→
     * auto-reconnect); see [app.skerry.shared.terminal.TerminalState.Closed].
     */
    val endedWithEof: Boolean get() = false

    /**
     * Whether the server's echo is currently suppressed (password entry / line-mode): when true, the
     * upper layer does not track the typed line or write it into autocomplete history, so secrets
     * don't linger in memory/suggestions. Default `false` (echo on): the SSH transport doesn't report
     * termios state, so it's always `false` here (residual risk for in-session passwords); Telnet
     * overrides based on the negotiated ECHO option.
     */
    val echoSuppressed: Boolean get() = false

    /** @throws SshConnectionException channel closed or transport dropped */
    suspend fun write(data: ByteArray)

    suspend fun resize(size: PtySize)

    suspend fun close()
}

data class ExecResult(
    /** null if the server closed the channel without a status. */
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
)

open class SshException(message: String, cause: Throwable? = null) : Exception(message, cause)

class SshConnectionException(message: String, cause: Throwable? = null) : SshException(message, cause)

class SshHostKeyRejectedException(message: String, cause: Throwable? = null) : SshException(message, cause)

class SshAuthenticationException(message: String, cause: Throwable? = null) : SshException(message, cause)
