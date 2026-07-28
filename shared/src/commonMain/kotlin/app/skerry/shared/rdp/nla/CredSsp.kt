package app.skerry.shared.rdp.nla

import app.skerry.shared.rdp.RdpAuthException
import app.skerry.shared.rdp.RdpProtocolException
import app.skerry.shared.rdp.RdpSink
import app.skerry.shared.rdp.RdpSource

/**
 * CredSSP (MS-CSSP) over an established TLS channel: runs NTLM inside TSRequest messages, binds the
 * result to the server's TLS public key, and only then hands over the user's password.
 *
 * The binding is the whole point of the protocol. A man in the middle can relay the NTLM exchange,
 * but it cannot produce the hash over *its* public key under a session key it does not have — so a
 * failed check here means the credentials are never sent, which is exactly the case NLA exists for.
 */
class CredSspClient(
    private val credentials: NtlmCredentials,
    private val crypto: NtlmCrypto,
    private val spn: String? = null,
    private val nowFileTime: () -> Long = { 0L },
) {
    /**
     * Authenticate over [source]/[sink], binding to [subjectPublicKeyInfo] (the DER
     * SubjectPublicKeyInfo of the server's TLS certificate).
     *
     * @throws RdpAuthException the server rejected the credentials or failed the binding check
     * @throws RdpProtocolException a malformed TSRequest
     */
    suspend fun authenticate(source: RdpSource, sink: RdpSink, subjectPublicKeyInfo: ByteArray) {
        val publicKey = Der.subjectPublicKey(subjectPublicKeyInfo)
        val ntlm = NtlmClient(credentials, crypto, spn, nowFileTime = nowFileTime)

        sink.write(TsRequest(version = CLIENT_VERSION, negoToken = ntlm.negotiate()).encode())

        val challengeRequest = TsRequest.read(source)
        challengeRequest.failIfError()
        val challenge = challengeRequest.negoToken
            ?: throw RdpProtocolException("CredSSP server sent no NTLM challenge")
        // Both sides drop to the lower of the two versions; the field decides how the key is bound.
        val version = minOf(CLIENT_VERSION, challengeRequest.version)
        if (version < MIN_SERVER_VERSION) {
            throw RdpAuthException("server offers CredSSP version $version, which predates public key binding")
        }

        val authenticated = ntlm.authenticate(challenge)
        val session = authenticated.session
        val nonce = if (version >= NONCE_VERSION) crypto.randomBytes(NONCE_SIZE) else null
        val binding = clientBinding(version, publicKey, nonce)

        sink.write(
            TsRequest(
                version = CLIENT_VERSION,
                negoToken = authenticated.message,
                pubKeyAuth = session.seal(binding),
                clientNonce = nonce,
            ).encode(),
        )

        val confirmation = TsRequest.read(source)
        confirmation.failIfError()
        val sealedAnswer = confirmation.pubKeyAuth
            ?: throw RdpAuthException("server did not answer the public key binding")
        val answer = try {
            session.unseal(sealedAnswer)
        } catch (e: RdpProtocolException) {
            // A signature failure here is not a parse problem: it means the peer that finished the
            // NTLM exchange is not the peer holding the TLS key.
            throw RdpAuthException("server failed the CredSSP binding check (${e.message})")
        }
        if (!answer.contentEquals(serverBinding(version, publicKey, nonce))) {
            throw RdpAuthException("server returned a public key that does not match the TLS session")
        }

        // Only now, with the channel proven to end at the server we authenticated to, does the
        // password leave this process.
        val tsCredentials = TsCredentials.password(credentials.domain, credentials.user, credentials.password)
        try {
            sink.write(TsRequest(version = CLIENT_VERSION, authInfo = session.seal(tsCredentials)).encode())
        } finally {
            tsCredentials.fill(0)
        }
    }

    private fun clientBinding(version: Int, publicKey: ByteArray, nonce: ByteArray?): ByteArray =
        if (version >= NONCE_VERSION && nonce != null) {
            crypto.sha256(magic(CLIENT_TO_SERVER_MAGIC) + nonce + publicKey)
        } else {
            publicKey
        }

    /**
     * What the server must answer with: the same hash under its own magic string in version 5+, or
     * — in the older versions — the public key with one added to its first byte, which is the
     * pre-nonce way of keeping the client's own message from being replayed back at it.
     */
    private fun serverBinding(version: Int, publicKey: ByteArray, nonce: ByteArray?): ByteArray =
        if (version >= NONCE_VERSION && nonce != null) {
            crypto.sha256(magic(SERVER_TO_CLIENT_MAGIC) + nonce + publicKey)
        } else {
            publicKey.copyOf().also { it[0] = (it[0] + 1).toByte() }
        }

    /** The well-known strings are hashed with their null terminator (MS-CSSP 3.1.5). */
    private fun magic(text: String): ByteArray = text.encodeToByteArray() + ByteArray(1)

    private fun TsRequest.failIfError() {
        val code = errorCode ?: return
        if (code == 0) return
        throw RdpAuthException(credSspErrorText(code))
    }

    private companion object {
        const val CLIENT_VERSION = 6
        const val NONCE_VERSION = 5

        /**
         * Versions below 2 predate the public key binding entirely; talking to one would mean
         * handing over the password on a channel nothing has tied to this server.
         */
        const val MIN_SERVER_VERSION = 2
        const val NONCE_SIZE = 32
        const val CLIENT_TO_SERVER_MAGIC = "CredSSP Client-To-Server Binding Hash"
        const val SERVER_TO_CLIENT_MAGIC = "CredSSP Server-To-Client Binding Hash"
    }
}

/**
 * Turn the NTSTATUS a CredSSP server reports into something a user can act on. Anything unmapped
 * keeps its hex code — the log is the place for a number, but a wrong password should not read
 * "0xC000006D".
 */
fun credSspErrorText(code: Int): String = when (code) {
    STATUS_LOGON_FAILURE -> "the user name or password is incorrect"
    STATUS_ACCOUNT_DISABLED -> "the account is disabled"
    STATUS_ACCOUNT_LOCKED_OUT -> "the account is locked out"
    STATUS_ACCOUNT_EXPIRED -> "the account has expired"
    STATUS_PASSWORD_EXPIRED, STATUS_PASSWORD_MUST_CHANGE -> "the password has expired and must be changed"
    STATUS_LOGON_TYPE_NOT_GRANTED -> "the account is not allowed to log on remotely"
    else -> "authentication failed (0x${code.toUInt().toString(16)})"
}

private const val STATUS_LOGON_FAILURE = 0xC000006D.toInt()
private const val STATUS_ACCOUNT_DISABLED = 0xC0000072.toInt()
private const val STATUS_ACCOUNT_LOCKED_OUT = 0xC0000234.toInt()
private const val STATUS_ACCOUNT_EXPIRED = 0xC0000193.toInt()
private const val STATUS_PASSWORD_EXPIRED = 0xC0000071.toInt()
private const val STATUS_PASSWORD_MUST_CHANGE = 0xC0000224.toInt()
private const val STATUS_LOGON_TYPE_NOT_GRANTED = 0xC000015B.toInt()
