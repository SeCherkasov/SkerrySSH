package app.skerry.shared.rdp

import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.Dispatchers
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
) {
    /**
     * Negotiate [requestedProtocols] with the server at [host]:[port] and upgrade the socket to the
     * protocol it selected.
     *
     * @throws RdpNegotiationException the server refused every protocol we offered
     * @throws RdpCertificateRejectedException the verifier turned down the server's certificate
     * @throws RdpProtocolException the answer was malformed, or named a protocol we never offered
     */
    suspend fun connect(
        host: String,
        port: Int,
        requestedProtocols: Int = RdpSecurityProtocol.SSL or RdpSecurityProtocol.HYBRID,
        cookie: String? = null,
        loadBalanceInfo: String? = null,
    ): RdpConnection = withContext(Dispatchers.IO) {
        val socket = openSocket(host, port)
        socket.soTimeout = negotiationTimeoutMillis
        // Cancellation cannot interrupt the blocking reads below, but closing the socket under them
        // can; on success the connection owns the socket and this handler is gone by then.
        val closeOnCancel = coroutineContext.job.invokeOnCompletion { cause ->
            if (cause != null) runCatching { socket.close() }
        }
        try {
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
                // downgrading us — either way the answer is no, not "connect anyway".
                throw RdpProtocolException("server selected Standard RDP Security, which is not supported")
            }
            if (selected and requestedProtocols == 0) {
                throw RdpProtocolException("server selected protocol $selected, which was not offered")
            }
            val secure = upgradeToTls(socket, host, port)
            RdpConnection(secure.socket, selected, negotiation, secure.publicKey)
        } catch (e: Throwable) {
            runCatching { socket.close() }
            throw e
        } finally {
            closeOnCancel.dispose()
        }
    }

    private class SecureSocket(val socket: SSLSocket, val publicKey: ByteArray)

    /**
     * Wrap [plain] in TLS and put the server's certificate in front of [certificateVerifier].
     *
     * The decision is taken after the handshake completes rather than inside the trust manager: the
     * verifier's answer also depends on whether the platform trusted the chain and whether the name
     * matched, and the hostname check needs the finished session. Nothing of ours has been written
     * at that point, so a refusal costs the server a handshake and nothing else.
     */
    private fun upgradeToTls(plain: Socket, host: String, port: Int): SecureSocket {
        val trust = CapturingTrustManager(platformTrustManager())
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        val secure = context.socketFactory.createSocket(plain, host, port, true) as SSLSocket
        secure.useClientMode = true
        // Not left to the platform's defaults: Android's lag behind the desktop JVM's, and the floor
        // a remote-desktop session is protected by should not depend on which one is running it.
        secure.enabledProtocols = secure.supportedProtocols.filter { it in TLS_FLOOR }.toTypedArray()
        secure.startHandshake()

        val chain = trust.chain
            ?: throw RdpProtocolException("TLS handshake produced no server certificate")
        val leaf = chain.first()
        val offer = RdpCertificateOffer(
            host = host,
            port = port,
            fingerprintSha256 = fingerprintOf(leaf),
            subject = leaf.subjectX500Principal.name,
            issuer = leaf.issuerX500Principal.name,
            notBeforeMillis = leaf.notBefore.time,
            notAfterMillis = leaf.notAfter.time,
            trustedByPlatform = trust.platformTrusted,
            hostnameMatches = runCatching {
                HttpsURLConnection.getDefaultHostnameVerifier().verify(host, secure.session)
            }.getOrDefault(false),
            publicKey = leaf.publicKey.encoded,
            derChain = chain.map { it.encoded },
        )
        if (!certificateVerifier.verify(offer)) {
            runCatching { secure.close() }
            throw RdpCertificateRejectedException(offer)
        }
        return SecureSocket(secure, offer.publicKey)
    }

    private fun platformTrustManager(): X509TrustManager? =
        runCatching {
            TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                .apply { init(null as java.security.KeyStore?) }
                .trustManagers
                .filterIsInstance<X509TrustManager>()
                .firstOrNull()
        }.getOrNull()

    private fun fingerprintOf(certificate: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
            .joinToString(":") { byte -> (byte.toInt() and 0xFF).toString(16).padStart(2, '0').uppercase() }

    /**
     * Accepts every chain so the decision can be taken by [RdpCertificateVerifier], but records what
     * the platform's own trust store thought of it — "my enterprise CA issued this" and "the machine
     * signed it itself" are different answers, and the verifier is the one that gets to weigh them.
     */
    private class CapturingTrustManager(private val delegate: X509TrustManager?) : X509TrustManager {
        @Volatile
        var chain: Array<X509Certificate>? = null

        @Volatile
        var platformTrusted = false

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            this.chain = chain
            val platform = delegate
            platformTrusted = platform != null &&
                runCatching { platform.checkServerTrusted(chain, authType) }.isSuccess
        }

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private companion object {
        /** The only TLS versions this client offers; anything older is not negotiable. */
        val TLS_FLOOR = setOf("TLSv1.2", "TLSv1.3")
    }
}
