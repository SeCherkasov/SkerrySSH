package app.skerry.shared.ssh

import app.skerry.shared.agent.SshAgentOrigin
import app.skerry.shared.agent.SshAgentService
import app.skerry.shared.agent.SshjAgentForwarder
import java.io.IOException
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Security
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.bouncycastle.jce.provider.BouncyCastleProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import net.schmizz.sshj.userauth.UserAuthException

/**
 * Desktop implementation of [SshTransport] over sshj (JVM).
 *
 * [agent] is the built-in SSH agent; when a target asks for [SshTarget.forwardAgent] and an agent
 * is present, the session's shell gets agent forwarding. Null (the default) means no agent is
 * configured — probe/tunnel transports pass nothing, so "test connection" and background tunnels
 * never expose keys.
 */
class SshjTransport(
    private val hostKeyVerifier: HostKeyVerifier,
    private val agent: SshAgentService? = null,
) : SshTransport {

    override suspend fun connect(target: SshTarget, auth: SshAuth): SshConnection =
        withContext(Dispatchers.IO) {
            // Every client dialed so far (ProxyJump hops entry-point-first, the target's own client
            // last). On ANY failure the whole chain is closed here, in reverse dial order — the
            // per-step helpers below don't close anything themselves.
            val opened = mutableListOf<SSHClient>()
            try {
                connectChain(target, auth, opened)
            } catch (e: Exception) {
                opened.asReversed().forEach { runCatching { it.close() } }
                throw e
            }
        }

    private fun connectChain(target: SshTarget, auth: SshAuth, opened: MutableList<SSHClient>): SshConnection {
        ensureCryptoProvider()
        // Capture the cipher negotiated at KEX (client->server) via an algorithms verifier: in
        // sshj 0.40 it's called synchronously on the IO thread inside connect() (after
        // NEWKEYS, before return), while we read it after connect() — needs a thread-safe
        // publication, hence AtomicReference. The verifier always passes (true): we don't vet
        // ciphers here, only capture the name for the info panel; host-key checking is a
        // separate chain (addHostKeyVerifier). Hop clients don't get one — the info panel shows
        // the target session's cipher.
        val negotiatedCipher = AtomicReference<String?>(null)
        val client = dial(target.host, target.port, target.jump, opened) { c ->
            c.transport.addAlgorithmsVerifier { negotiated ->
                negotiatedCipher.set(negotiated.client2ServerCipherAlgorithm)
                true
            }
        }
        authenticate(client, target.username, auth, hop = false)

        // sshj returns the server ident without the prefix (`getServerVersion()` =
        // serverID.substring(8)); we restore the full `SSH-2.0-<software>` form for the status
        // bar. Read synchronously on the same IO thread after connect() — identification
        // exchange has already finished, no race. (A defunct `SSH-1.99-` server would show as
        // `SSH-2.0-` too — substring(8) is the same either way; cosmetic only.)
        val serverVersion = runCatching { client.transport.serverVersion }
            .getOrNull()?.takeIf { it.isNotBlank() }?.let { "SSH-2.0-$it" }
        // Agent forwarding is registered only after authentication: an unauthenticated server has no
        // business opening channels back to our keys. The origin is the address the user dialed, so
        // the activity list can name who asked (in memory only — see SshAgentService).
        val agentForwarder = agent?.takeIf { target.forwardAgent }?.let { service ->
            SshjAgentForwarder(client.connection, service, SshAgentOrigin.Session(target.host))
                .also { client.connection.attach(it) }
        }
        return SshjConnection(
            client,
            negotiatedCipher.get(),
            serverVersion,
            upstream = opened.dropLast(1),
            agentForwarder = agentForwarder,
        )
    }

    /**
     * Dial [host]:[port]: directly, or — with [jump] — through a recursively dialed and
     * authenticated hop chain via a direct-tcpip channel ([SSHClient.connectVia]), the ProxyJump
     * scheme. Every created client is registered in [opened] BEFORE its connect attempt, so the
     * caller's cleanup sees half-open clients too. [configure] runs before connecting (cipher
     * capture for the target client). Host keys are verified per hop under its own (host, port);
     * [hop] marks a ProxyJump hop so a rejection says whose key was refused (like the auth errors).
     */
    private fun dial(
        host: String,
        port: Int,
        jump: SshJump?,
        opened: MutableList<SSHClient>,
        hop: Boolean = false,
        configure: (SSHClient) -> Unit = {},
    ): SSHClient {
        val upstream = jump?.let { next ->
            dial(next.host, next.port, next.jump, opened, hop = true)
                .also { authenticate(it, next.username, next.auth, hop = true) }
        }
        val client = SSHClient()
        // TCP connect timeout: sshj's default is 0 = wait forever. Without this, "Test
        // connection" to a nonexistent/firewalled address hangs with no way to cancel from the
        // UI. (Protocol-level KEX/I-O timeout is separate, sshj default ~30s; round-trip ping
        // is its own thing.) For a connectVia hop the TCP dial happened upstream; the timeout is
        // harmless there.
        client.connectTimeout = CONNECT_TIMEOUT_MILLIS
        configure(client)
        val hostKeyRejected = installHostKeyVerifier(client)
        opened += client
        try {
            if (upstream == null) {
                client.connect(host, port)
            } else {
                client.connectVia(upstream.newDirectConnection(host, port))
            }
        } catch (e: IOException) {
            // Don't put the host address in the message text (logs/crash reporters): connect
            // metadata is sensitive in a zero-knowledge client. Diagnostic detail stays in the
            // cause (e).
            if (hostKeyRejected.get()) {
                throw SshHostKeyRejectedException(
                    if (hop) "Jump host key rejected by verifier" else "Host key rejected by verifier",
                )
            }
            throw SshConnectionException("Failed to establish connection", e)
        }
        return client
    }

    /**
     * Attach an adapter for our [hostKeyVerifier] to [client]. The returned flag is set on
     * rejection: verify() is called from sshj's IO thread, while the flag is read from the
     * coroutine after connect() — needs thread-safe visibility, hence AtomicBoolean.
     */
    private fun installHostKeyVerifier(client: SSHClient): AtomicBoolean {
        val hostKeyRejected = AtomicBoolean(false)
        client.addHostKeyVerifier(object : net.schmizz.sshj.transport.verification.HostKeyVerifier {
            override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
                val trusted = hostKeyVerifier.verify(
                    host = hostname,
                    port = port,
                    keyType = KeyType.fromKey(key).toString(),
                    fingerprint = opensshFingerprint(key),
                )
                if (!trusted) hostKeyRejected.set(true)
                return trusted
            }

            override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
        })
        return hostKeyRejected
    }

    /**
     * Authenticate an already-connected [client] per [auth]; throws on failure (closing is the
     * connect-level cleanup's job — see `opened` there). [hop] marks a ProxyJump hop so the user
     * can tell which side rejected the credentials (still no addresses/usernames in the text).
     */
    private fun authenticate(client: SSHClient, username: String, auth: SshAuth, hop: Boolean) {
        try {
            when (auth) {
                is SshAuth.Password -> client.authPassword(username, auth.secret)
                is SshAuth.PublicKey -> {
                    // The shared loader takes the PEM as key content (not a path) and covers every
                    // stored shape, PKCS#8 included ([loadSshKeyPair]).
                    client.authPublickey(username, keyProvider(loadAuthKey(auth.privateKeyPem, auth.passphrase)))
                }
                is SshAuth.Certificate -> {
                    // Cert auth: possession is proven by the private key from PEM, while the
                    // server is shown the certificate itself (public part = parsed *-cert.pub).
                    // sshj doesn't stitch these together from strings on its own (only from
                    // sibling files), so we build the KeyProvider by hand: private from PEM,
                    // public as Certificate, type as *_CERT.
                    val keys = loadAuthKey(auth.privateKeyPem, auth.passphrase)
                    client.authPublickey(username, certificateKeyProvider(keys, auth.certificate))
                }
            }
        } catch (e: UserAuthException) {
            // No username in the text: the message must not carry an identifier (logs/reports).
            throw SshAuthenticationException(
                if (hop) "Jump host rejected the credentials" else "Server rejected the credentials", e,
            )
        } catch (e: IOException) {
            throw SshConnectionException("Connection dropped during authentication", e)
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 10_000
    }
}

/** Once per process: registration of the full BouncyCastle provider (see [ensureCryptoProvider]). */
private val cryptoProviderLock = Any()

@Volatile
private var cryptoProviderReady = false

/**
 * sshj relies on full BouncyCastle. On Android, the default "BC" provider is the stripped-down
 * system BouncyCastle (class `com.android.org.bouncycastle…`), which lacks the ciphers and key
 * exchanges sshj needs — as a result `connect()` fails during KEX with a plain `IOException`
 * ("Failed to connect to host:port"). We swap "BC" for the full provider from bcprov, which is
 * bundled with sshj. No issue on desktop JVM — a guard on the presence of `android.os.Build` makes
 * the function a no-op there, so desktop behavior is unchanged. Idempotent.
 *
 * `internal` (not `private`): the same stripped-down system BouncyCastle breaks not only KEX on
 * connect but also private-key parsing (`SSHClient.loadKeys` in
 * [app.skerry.shared.vault.BouncyCastleSshKeyGenerator.inspect]), so the Vault section's key
 * generator/inspector registers the full provider via this same call.
 *
 * Under [synchronized] (not a lock-free `compareAndSet`): the `cryptoProviderReady` flag is raised
 * ONLY after the provider is actually registered. Otherwise a second thread (e.g. `inspect` from
 * the Vault tab racing `connect()`) could see the flag already set and start using the still
 * stripped-down "BC" in the window between setting the flag and `insertProviderAt`. Double-checking
 * the flag keeps the common path lock-free.
 */
internal fun ensureCryptoProvider() {
    if (cryptoProviderReady) return
    synchronized(cryptoProviderLock) {
        if (cryptoProviderReady) return
        // Explicitly install the full bcprov "BC" provider first on BOTH platforms — uniformly,
        // without relying on sshj's lazy self-registration:
        // - Android: the system "BC" is stripped down (com.android.org.bouncycastle) — missing
        //   ciphers/KEX, it must be replaced with the full bcprov.
        // - Desktop: a safety net — if "BC" is absent or isn't our bcprov, sshj.DefaultConfig
        //   .initCipherFactories would request a cipher through a nonexistent "BC" ->
        //   NoSuchProviderException (cause=null) -> NPE crashes SSHClient(); we install the
        //   provider ahead of time so this can't happen.
        // In both cases, install the full provider first if the current "BC" isn't our bcprov.
        val existing = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME)
        if (existing == null || existing.javaClass != BouncyCastleProvider::class.java) {
            Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
            Security.insertProviderAt(BouncyCastleProvider(), 1)
        }
        cryptoProviderReady = true
    }
}

/**
 * [KeyProvider] for certificate authentication: the private key comes from the already-loaded
 * [privateKeys] (PEM), while the public part is a `Certificate` object parsed from the
 * [certificate] string (sshj's `Buffer.readPublicKey` decoder returns exactly that for the cert
 * type). The type is taken from the string's first field (`ssh-…-cert-v01@openssh.com`) — that's
 * `*_CERT`, and sshj uses it to send the server the cert blob.
 */
/** sshj's view of an already-parsed pair: type comes from the public half (EC needs that). */
private fun keyProvider(pair: java.security.KeyPair): KeyProvider = object : KeyProvider {
    override fun getPrivate(): PrivateKey = pair.private
    override fun getPublic(): PublicKey = pair.public
    override fun getType(): KeyType = KeyType.fromKey(pair.public)
}

private fun certificateKeyProvider(privateKeys: java.security.KeyPair, certificate: String): KeyProvider {
    val fields = certificate.trim().split(Regex("\\s+"))
    // A malformed/truncated cert string (missing second field, invalid base64, garbage wire data)
    // must not escape as an unhandled IndexOutOfBounds/IllegalArgument past the auth handlers —
    // convert it to SshAuthenticationException (credentials could not be presented).
    val (certType, certKey) = runCatching {
        require(fields.size >= 2) { "expected format '<type> <base64> [comment]'" }
        KeyType.fromString(fields[0]) to Buffer.PlainBuffer(Base64.getDecoder().decode(fields[1])).readPublicKey()
    }.getOrElse { throw SshAuthenticationException("Failed to parse the stored SSH certificate", it) }
    return object : KeyProvider {
        override fun getPrivate(): PrivateKey = privateKeys.private
        override fun getPublic(): PublicKey = certKey
        override fun getType(): KeyType = certType
    }
}

/** Fingerprint in OpenSSH format: `SHA256:` + unpadded base64 of the key's wire encoding. */
private fun opensshFingerprint(key: PublicKey): String {
    val encoded = Buffer.PlainBuffer().putPublicKey(key).compactData
    val digest = MessageDigest.getInstance("SHA-256").digest(encoded)
    return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
}
