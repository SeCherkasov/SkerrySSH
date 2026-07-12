package app.skerry.shared.ssh

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
 */
data class SshTarget(
    val host: String,
    val port: Int = 22,
    val username: String,
    val connectionType: ConnectionType = ConnectionType.SSH,
    val jump: SshJump? = null,
    val keepAliveSeconds: Int = 0,
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
}

/**
 * Trust decision for a host key. Fingerprint uses OpenSSH format (`SHA256:` + unpadded base64),
 * keyType is the algorithm identifier (`ssh-ed25519`, `rsa-sha2-512`, …).
 */
fun interface HostKeyVerifier {
    fun verify(host: String, port: Int, keyType: String, fingerprint: String): Boolean
}

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

class SshHostKeyRejectedException(message: String) : SshException(message)

class SshAuthenticationException(message: String, cause: Throwable? = null) : SshException(message, cause)
