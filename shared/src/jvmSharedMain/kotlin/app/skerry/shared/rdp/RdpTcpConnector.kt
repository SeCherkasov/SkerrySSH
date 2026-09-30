package app.skerry.shared.rdp

import app.skerry.shared.io.causeChain
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * A negotiated, TLS-protected byte channel to an RDP server: everything the connection sequence
 * (MCS, capability exchange, session PDUs) is then spoken over. [selectedProtocol] decides what the
 * caller does next — [RdpSecurityProtocol.HYBRID] means CredSSP has to run before the RDP connection
 * sequence starts.
 *
 * [serverPublicKey] is the leaf certificate's DER SubjectPublicKeyInfo, kept because CredSSP binds
 * its exchange to exactly this key.
 */
class RdpConnection(
    private val socket: Socket,
    val selectedProtocol: Int,
    val negotiation: X224NegotiationResponse,
    val serverPublicKey: ByteArray,
) {
    private val input = DataInputStream(socket.getInputStream().buffered())
    private val output = BufferedOutputStream(socket.getOutputStream())
    private val writeLock = Mutex()
    private val closed = AtomicBoolean(false)

    /** Blocking pull source; [DataInputStream.readFully] is exactly the "N bytes or throw" contract. */
    val source = RdpSource { dst, offset, len -> input.readFully(dst, offset, len) }

    /**
     * Called under the write lock with each payload's size. The diagnostics byte counter hangs
     * here rather than around [sink]: its increment is a plain read-modify-write, and only inside
     * this lock is it serialised against the concurrent writers (the input actor, the read loop's
     * frame acknowledgements).
     */
    var onWrite: (Int) -> Unit = {}

    /** Serialized sink: input events, channel data and heartbeat PDUs share one socket. */
    val sink = RdpSink { bytes ->
        writeLock.withLock {
            output.write(bytes)
            output.flush()
            onWrite(bytes.size)
        }
    }

    /**
     * Drop the read timeout the connection was established under. A session is idle for as long as
     * the user is looking at a still desktop, so from here on a read that waits is not a read that
     * failed — the socket is closed on cancellation instead.
     */
    fun clearReadTimeout() {
        runCatching { socket.soTimeout = 0 }
    }

    /** Close the socket. Idempotent; unblocks a read parked in [source]. */
    fun close() {
        if (closed.compareAndSet(false, true)) runCatching { socket.close() }
    }
}

/**
 * Opens the socket and runs the connection-establishment step of MS-RDPBCGR: X.224 negotiation, then
 * the TLS upgrade the server selected. The result is an [RdpConnection] the protocol layers ride on.
 *
 * No expect/actual: `java.net.Socket` and `javax.net.ssl` behave identically on desktop and Android
 * (same reasoning as `VncTcpTransport`).
 */
class RdpTcpConnector(
    private val certificateVerifier: RdpCertificateVerifier,
    private val connectTimeoutMillis: Int = 15_000,
    /**
     * How long a single read may block while the connection is still being established. A blocking
     * socket read does not answer to coroutine cancellation, so without this a server that accepts
     * the connection and then goes quiet keeps a thread and a socket for the life of the process.
     * The session that follows clears it: an idle desktop is silent for as long as the user is.
     */
    private val negotiationTimeoutMillis: Int = 30_000,
    // Injectable for tests (a fake server socket); production uses a real Socket().
    private val openSocket: (host: String, port: Int) -> Socket = { host, port ->
        Socket().apply {
            connect(InetSocketAddress(host, port), connectTimeoutMillis)
            tcpNoDelay = true
        }
    },
    // Injectable for tests: the refusal it exists to recover from comes from BoringSSL, which the
    // desktop JVM the tests run on does not have.
    private val handshake: (SSLSocket) -> Unit = { it.startHandshake() },
) {
    /**
     * Negotiate [requestedProtocols] with the server at [host]:[port] and upgrade the socket to the
     * protocol it selected.
     *
     * @throws RdpNegotiationException the server refused every protocol we offered
     * @throws RdpCertificateRejectedException the verifier turned down the server's certificate
     * @throws RdpTlsException the TLS handshake failed for any other reason than the network
     * @throws RdpProtocolException the answer was malformed, or named a protocol we never offered
     */
    suspend fun connect(
        host: String,
        port: Int,
        requestedProtocols: Int = RdpSecurityProtocol.SSL or RdpSecurityProtocol.HYBRID,
        cookie: String? = null,
        loadBalanceInfo: String? = null,
        onStage: (RdpConnectStage) -> Unit = {},
    ): RdpConnection = withContext(Dispatchers.IO) {
        val first = TlsAttempt(rsaKeyExchange = false)
        try {
            attempt(host, port, requestedProtocols, cookie, loadBalanceInfo, onStage, first)
        } catch (e: RdpTlsException) {
            // Windows signs its own Remote Desktop certificate for key encipherment only, and
            // BoringSSL (Android) refuses such a key for an ECDHE suite, where it has to sign. The
            // handshake is dead by then, so the retry is a new connection, offering only the suites
            // that certificate was made for. JSSE never raises this, so the desktop never retries.
            if (!e.isKeyUsageRefusal()) throw e
            currentCoroutineContext().ensureActive()
            try {
                // A certificate the user already answered for in the refused handshake is not put to
                // them a second time within the same connect.
                val retry = TlsAttempt(rsaKeyExchange = true, approvedFingerprint = first.approved?.fingerprintSha256)
                attempt(host, port, requestedProtocols, cookie, loadBalanceInfo, onStage, retry)
            } catch (retry: Exception) {
                // The refusal is why the retry happened; a report that shows only the retry's
                // failure cannot tell a server without RSA suites from a retry that never ran.
                retry.addSuppressed(e)
                throw retry
            }
        }
    }

    @Suppress("LongParameterList")
    private suspend fun attempt(
        host: String,
        port: Int,
        requestedProtocols: Int,
        cookie: String?,
        loadBalanceInfo: String?,
        onStage: (RdpConnectStage) -> Unit,
        tls: TlsAttempt,
    ): RdpConnection {
        onStage(RdpConnectStage.Tcp)
        val socket = openSocket(host, port)
        socket.soTimeout = negotiationTimeoutMillis
        // Cancellation cannot interrupt the blocking reads below, but closing the socket under them
        // can; on success the connection owns the socket and this handler is gone by then.
        val closeOnCancel = currentCoroutineContext().job.invokeOnCompletion { cause ->
            if (cause != null) runCatching { socket.close() }
        }
        return try {
            onStage(RdpConnectStage.Negotiation)
            val plainSink = RdpSink { bytes ->
                socket.getOutputStream().apply {
                    write(bytes)
                    flush()
                }
            }
            val plainSource = RdpSource { dst, offset, len ->
                DataInputStream(socket.getInputStream()).readFully(dst, offset, len)
            }
            plainSink.write(X224.connectionRequest(requestedProtocols, cookie, loadBalanceInfo))
            val negotiation = X224.parseConnectionConfirm(Tpkt.readPacket(plainSource))
            val selected = negotiation.selectedProtocol
            if (selected == RdpSecurityProtocol.RDP) {
                // Standard RDP Security: RC4 over a plaintext socket, with a key exchange broken
                // beyond repair. We never offer it, so a server selecting it is either ancient or
                // downgrading us — either way the answer is no, not "connect anyway". To the user it
                // is the same refusal as a server that answers with SSL_NOT_ALLOWED_BY_SERVER.
                throw RdpNegotiationException(
                    reason = RdpNegotiationFailure.SSL_NOT_ALLOWED_BY_SERVER,
                    message = "server selected Standard RDP Security, which is not supported",
                )
            }
            if (selected and requestedProtocols == 0) {
                throw RdpProtocolException("server selected protocol $selected, which was not offered")
            }
            onStage(RdpConnectStage.Tls)
            val secure = upgradeToTls(socket, host, port, tls)
            RdpConnection(secure.socket, selected, negotiation, secure.publicKey)
        } catch (e: Throwable) {
            runCatching { socket.close() }
            throw e
        } finally {
            closeOnCancel.dispose()
        }
    }

    private class SecureSocket(val socket: SSLSocket, val publicKey: ByteArray)

    /** One TLS handshake of a connect: what it offers, and the certificate it saw approved. */
    private class TlsAttempt(val rsaKeyExchange: Boolean, val approvedFingerprint: String? = null) {
        var approved: RdpCertificateOffer? = null
    }

    /** Answers yes, without asking, for the one certificate approved earlier in the same connect. */
    private class AlreadyApproved(
        private val delegate: RdpCertificateVerifier,
        private val fingerprint: String,
    ) : RdpCertificateVerifier by delegate {
        override fun verify(offer: RdpCertificateOffer): Boolean =
            offer.fingerprintSha256 == fingerprint || delegate.verify(offer)
    }

    /**
     * Wrap [plain] in TLS, with [certificateVerifier] taking the trust decision from inside the
     * handshake. Refusing there rather than afterwards is the point: a client that trusts every
     * chain and only makes up its mind once the session is up has already finished a handshake with
     * a server it will not talk to, and any later slip leaks data over it.
     *
     * Trust on first use is committed the other side of [SSLSocket.startHandshake], though. The
     * verifier is asked when the server's certificate arrives, which is before the server has
     * proven it holds the matching key — recording there would let anyone able to answer the
     * connection register a certificate copied from elsewhere.
     */
    private fun upgradeToTls(plain: Socket, host: String, port: Int, tls: TlsAttempt): SecureSocket {
        val verifier = tls.approvedFingerprint?.let { AlreadyApproved(certificateVerifier, it) } ?: certificateVerifier
        val trust = RdpVerifyingTrustManager(verifier, platformTrustManager(), host, port)
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        val secure = context.socketFactory.createSocket(plain, host, port, true) as SSLSocket
        secure.useClientMode = true
        // Not left to the platform's defaults: Android's lag behind the desktop JVM's, and the floor
        // a remote-desktop session is protected by should not depend on which one is running it.
        secure.enabledProtocols = secure.supportedProtocols.filter { it in TLS_FLOOR }.toTypedArray()
        if (tls.rsaKeyExchange) offerRsaKeyExchangeOnly(secure)
        try {
            handshake(secure)
        } catch (e: SSLException) {
            runCatching { secure.close() }
            // Our own refusal surfaces here as a generic TLS failure; the caller needs the
            // certificate that was turned down, not the alert it produced. The alert stays as the
            // cause — a rejection and a broken handshake read the same way in a bug report.
            tls.approved = trust.accepted
            throw trust.rejected?.let { RdpCertificateRejectedException(it, cause = e) }
                ?: RdpTlsException("TLS handshake failed: ${e.message}", e)
        } catch (e: IOException) {
            runCatching { secure.close() }
            // A timeout or a reset during the handshake is the network's failure, not TLS's, and
            // stays the plain IOException it is.
            throw trust.rejected?.let { RdpCertificateRejectedException(it, cause = e) } ?: e
        }
        // Fails closed: null means the handshake completed without the trust manager being asked
        // at all — an anonymous suite, or a resumed session (impossible here, the context is new).
        val offer = trust.accepted
            ?: throw RdpProtocolException("TLS handshake produced no server certificate")
        if (tls.rsaKeyExchange) refuseNeedlessRsaKeyExchange(secure, offer)
        if (!certificateVerifier.remember(offer)) {
            // Another first-time connection to this host settled on a different certificate while
            // this handshake ran. One of the two is the one the host is now known by; this is not.
            runCatching { secure.close() }
            throw RdpCertificateRejectedException(offer)
        }
        return SecureSocket(secure, offer.publicKey)
    }

    /**
     * A deliberate step down — no forward secrecy — taken only for a certificate that allows nothing
     * else, and checked for that once the handshake shows the certificate.
     */
    private fun offerRsaKeyExchangeOnly(secure: SSLSocket) {
        // TLS 1.3 has no RSA key exchange; GCM where the platform has it, CBC only where it does not.
        val rsa = secure.supportedCipherSuites.filter { it.startsWith(RSA_KEY_EXCHANGE_AES) }
        val suites = rsa.filter { GCM in it }.ifEmpty { rsa }
        val protocols = secure.enabledProtocols.filter { it == TLS_12 }
        if (suites.isEmpty() || protocols.isEmpty()) {
            throw RdpTlsException(
                "no TLS 1.2 RSA key exchange to retry with",
                SSLException("platform offers no TLS_RSA_WITH_AES suite over TLS 1.2"),
            )
        }
        secure.enabledProtocols = protocols.toTypedArray()
        secure.enabledCipherSuites = suites.toTypedArray()
    }

    /**
     * BoringSSL would have taken a certificate that may sign for ECDHE, so the refusal that sent us
     * to the RSA retry was not this server's: whoever answered the first attempt wanted the weaker
     * exchange. Refused before it is remembered.
     */
    private fun refuseNeedlessRsaKeyExchange(secure: SSLSocket, offer: RdpCertificateOffer) {
        val refusal = try {
            if (encipherOnly(offer)) return
            RdpTlsException(
                "the RSA key exchange was retried for a certificate that did not need it",
                SSLException("server certificate allows digitalSignature"),
            )
        } catch (e: RdpTlsException) {
            e
        }
        runCatching { secure.close() }
        throw refusal
    }

    /** Whether the leaf's key usage forbids signing, the one case BoringSSL refuses for ECDHE. */
    private fun encipherOnly(offer: RdpCertificateOffer): Boolean {
        val leaf = try {
            CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(offer.derChain.first())) as X509Certificate
        } catch (e: CertificateException) {
            throw RdpTlsException("server certificate could not be parsed", e)
        }
        val usage = leaf.keyUsage ?: return false
        return !usage[DIGITAL_SIGNATURE] && usage.getOrElse(KEY_ENCIPHERMENT) { false }
    }

    private fun platformTrustManager(): X509TrustManager? =
        runCatching {
            TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                .apply { init(null as java.security.KeyStore?) }
                .trustManagers
                .filterIsInstance<X509TrustManager>()
                .firstOrNull()
        }.getOrNull()

    private companion object {
        /** The only TLS versions this client offers; anything older is not negotiable. */
        val TLS_FLOOR = setOf("TLSv1.2", "TLSv1.3")
        const val TLS_12 = "TLSv1.2"
        const val RSA_KEY_EXCHANGE_AES = "TLS_RSA_WITH_AES_"
        const val GCM = "_GCM_"

        /** Bit positions in [X509Certificate.getKeyUsage] (RFC 5280 4.2.1.3). */
        const val DIGITAL_SIGNATURE = 0
        const val KEY_ENCIPHERMENT = 2

        /** BoringSSL's name for a certificate whose key usage does not allow the negotiated suite. */
        const val KEY_USAGE_REFUSAL = "KEY_USAGE_BIT_INCORRECT"

        fun RdpTlsException.isKeyUsageRefusal(): Boolean =
            causeChain().any { it.message?.contains(KEY_USAGE_REFUSAL) == true }
    }
}
