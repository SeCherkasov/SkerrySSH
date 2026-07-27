package app.skerry.shared.ssh

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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import net.schmizz.sshj.userauth.UserAuthException
import net.schmizz.sshj.userauth.method.AuthKeyboardInteractive
import net.schmizz.sshj.userauth.method.AuthPassword
import net.schmizz.sshj.userauth.method.AuthPublickey
import net.schmizz.sshj.userauth.method.ChallengeResponseProvider
import net.schmizz.sshj.userauth.method.PasswordResponseProvider
import net.schmizz.sshj.userauth.password.PasswordUtils
import net.schmizz.sshj.userauth.password.Resource

/**
 * Desktop implementation of [SshTransport] over sshj (JVM).
 *
 * [keyboardInteractiveResponder] answers the server's keyboard-interactive challenges (2FA codes and
 * the like). Null — the default — means the method isn't offered at all, so a server that insists on
 * it fails authentication exactly as it did before the responder existed.
 */
class SshjTransport(
    private val hostKeyVerifier: HostKeyVerifier,
    private val keyboardInteractiveResponder: KeyboardInteractiveResponder? = null,
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
        authenticate(client, target.username, auth, hop = false, host = target.host, port = target.port)

        // sshj returns the server ident without the prefix (`getServerVersion()` =
        // serverID.substring(8)); we restore the full `SSH-2.0-<software>` form for the status
        // bar. Read synchronously on the same IO thread after connect() — identification
        // exchange has already finished, no race. (A defunct `SSH-1.99-` server would show as
        // `SSH-2.0-` too — substring(8) is the same either way; cosmetic only.)
        val serverVersion = runCatching { client.transport.serverVersion }
            .getOrNull()?.takeIf { it.isNotBlank() }?.let { "SSH-2.0-$it" }
        return SshjConnection(
            client,
            negotiatedCipher.get(),
            serverVersion,
            upstream = opened.dropLast(1),
            // Non-null for a container profile: the interactive channel runs that command on the
            // PTY instead of the login shell (see [SshTarget.shellCommand]).
            shellCommand = target.shellCommand,
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
                .also { authenticate(it, next.username, next.auth, hop = true, host = next.host, port = next.port) }
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
    private fun authenticate(
        client: SSHClient,
        username: String,
        auth: SshAuth,
        hop: Boolean,
        host: String,
        port: Int,
    ) {
        // Held so the failure below can tell "the user waved the prompt away" apart from "the server
        // said no" — sshj reports both as the same UserAuthException.
        var prompts: ResponderChallengeProvider? = null
        try {
            // One ordered list rather than a single call: a server configured with
            // `AuthenticationMethods publickey,keyboard-interactive` answers the first method with
            // partial success, and sshj then moves on to the next one in the list (SSHClient.auth).
            // That is what makes two-factor login work; with a single method it would stop at the
            // partial success and report failure.
            val methods = buildList {
                when (auth) {
                    is SshAuth.Password -> {
                        add(AuthPassword(PasswordUtils.createOneOff(auth.secret.toCharArray())))
                        // Without a responder, keep sshj's own fallback (what authPassword did here
                        // before): it answers password-shaped prompts from the saved secret, which is
                        // how PAM setups that only re-ask for the password work. With a responder,
                        // ours supersedes it — it answers those same prompts from the secret and asks
                        // the user about everything else.
                        //
                        // Its own finder, not the one above: createOneOff hands the password over
                        // exactly once and blanks the array afterwards, so a shared finder would leave
                        // the fallback answering with nothing the moment AuthPassword had run — which
                        // is precisely the case this fallback exists for.
                        if (keyboardInteractiveResponder == null) {
                            add(
                                AuthKeyboardInteractive(
                                    PasswordResponseProvider(PasswordUtils.createOneOff(auth.secret.toCharArray())),
                                ),
                            )
                        }
                    }
                    is SshAuth.PublicKey -> {
                        // loadKeys treats the strings as key content (not a path); passphrase is a
                        // one-off PasswordFinder. sshj detects the format (OpenSSH/PKCS) itself.
                        val pwdf = auth.passphrase?.let { PasswordUtils.createOneOff(it.toCharArray()) }
                        val keys = client.loadKeys(auth.privateKeyPem, null, pwdf)
                        add(AuthPublickey(keys))
                    }
                    is SshAuth.Certificate -> {
                        // Cert auth: possession is proven by the private key from PEM, while the
                        // server is shown the certificate itself (public part = parsed *-cert.pub).
                        // sshj doesn't stitch these together from strings on its own (only from
                        // sibling files), so we build the KeyProvider by hand: private from PEM,
                        // public as Certificate, type as *_CERT.
                        val pwdf = auth.passphrase?.let { PasswordUtils.createOneOff(it.toCharArray()) }
                        val keys = client.loadKeys(auth.privateKeyPem, null, pwdf)
                        add(AuthPublickey(certificateKeyProvider(keys, auth.certificate)))
                    }
                    // Nothing to offer up front: the whole exchange is the server asking and the
                    // user answering, added below.
                    SshAuth.Interactive -> Unit
                }
                keyboardInteractiveResponder?.let { responder ->
                    val provider = ResponderChallengeProvider(
                        responder = responder,
                        hop = hop,
                        knownPassword = (auth as? SshAuth.Password)?.secret,
                        endpoint = "$username@$host:$port",
                    )
                    prompts = provider
                    add(AuthKeyboardInteractive(provider))
                }
            }
            client.auth(username, methods)
        } catch (e: UserAuthException) {
            // No username in the text: the message must not carry an identifier (logs/reports).
            // A prompt the user waved away (or let expire) is reported as itself: otherwise the one
            // failure the user caused reads as "your credentials are wrong", and they go hunting for
            // a problem with the password instead of simply answering the next prompt.
            throw SshAuthenticationException(
                when (prompts?.outcome) {
                    ResponderChallengeProvider.Outcome.Dismissed -> "Two-factor prompt was dismissed"
                    ResponderChallengeProvider.Outcome.TimedOut -> "Two-factor prompt timed out"
                    ResponderChallengeProvider.Outcome.Flooded -> "Server asked for too many answers at once"
                    else -> if (hop) "Jump host rejected the credentials" else "Server rejected the credentials"
                },
                e,
            )
        } catch (e: IOException) {
            throw SshConnectionException("Connection dropped during authentication", e)
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 10_000
    }
}

/**
 * Adapts sshj's synchronous [ChallengeResponseProvider] to our suspend [KeyboardInteractiveResponder].
 *
 * sshj hands over one prompt at a time (`AuthKeyboardInteractive` loops over the request's prompts
 * calling [getResponse] for each), so each prompt becomes its own single-prompt challenge — the
 * contract keeps a list because RFC 4256 allows several per round and a future transport may deliver
 * them together.
 *
 * The call happens on sshj's reader thread and blocks it until the user answers, which is what makes
 * the connection wait; [KEYBOARD_INTERACTIVE_TIMEOUT_MILLIS] keeps an unanswered prompt from pinning
 * that thread forever. Dismissal (or timeout) is remembered: the remaining prompts of the round are
 * answered with nothing and the method is not retried, so a cancelled prompt fails authentication
 * instead of re-opening.
 *
 * [knownPassword] short-circuits password-shaped prompts with the secret we already hold, using
 * sshj's own pattern — the user is asked only for what the vault can't answer, i.e. the second
 * factor itself.
 */
private class ResponderChallengeProvider(
    private val responder: KeyboardInteractiveResponder,
    private val hop: Boolean,
    private val knownPassword: String?,
    private val endpoint: String,
) : ChallengeResponseProvider {

    /** Why the exchange stopped, for the failure message. */
    enum class Outcome { Answering, Dismissed, TimedOut, Flooded }

    @Volatile
    var outcome: Outcome = Outcome.Answering
        private set

    private var name: String = ""
    private var instruction: String = ""
    private var rounds: Int = 0
    private var promptsThisRound: Int = 0

    private val stopped: Boolean get() = outcome != Outcome.Answering

    override fun init(resource: Resource<*>, name: String, instruction: String) {
        this.name = name
        this.instruction = instruction
        rounds++
        promptsThisRound = 0
    }

    override fun getResponse(prompt: String, echo: Boolean): CharArray {
        if (stopped) return CharArray(0)
        // One request may carry any number of prompts, each costing the user a dialog; a server that
        // sends hundreds isn't authenticating anyone, so stop answering rather than march the user
        // through them.
        if (++promptsThisRound > KEYBOARD_INTERACTIVE_MAX_PROMPTS_PER_ROUND) {
            outcome = Outcome.Flooded
            return CharArray(0)
        }
        if (!echo && knownPassword != null &&
            PasswordResponseProvider.DEFAULT_PROMPT_PATTERN.matcher(prompt).matches()
        ) {
            return knownPassword.toCharArray()
        }
        val challenge = KeyboardInteractiveChallenge(
            name = name,
            instruction = instruction,
            prompts = listOf(KeyboardInteractivePrompt(prompt, echo)),
            hop = hop,
            endpoint = endpoint,
        )
        // The deadline the user experiences lives in the responder, which starts counting when the
        // prompt is actually on screen; this one only catches a responder that never returns at all.
        // Any other failure out of the responder is treated as a dismissal rather than thrown: it
        // would escape through sshj's auth loop as an unhandled type, bypassing the exception
        // translation above.
        val answers = runCatching {
            runBlocking {
                withTimeoutOrNull(KEYBOARD_INTERACTIVE_RESPONDER_BACKSTOP_MILLIS) { responder.respond(challenge) }
            }
        }.getOrNull()
        if (answers == null) {
            outcome = Outcome.Dismissed
            return CharArray(0)
        }
        return answers.firstOrNull().orEmpty().toCharArray()
    }

    override fun shouldRetry(): Boolean = !stopped && rounds < KEYBOARD_INTERACTIVE_MAX_ROUNDS

    /** No submethod hint: let the server pick whatever it has configured, like OpenSSH does. */
    override fun getSubmethods(): List<String> = emptyList()
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
private fun certificateKeyProvider(privateKeys: KeyProvider, certificate: String): KeyProvider {
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
