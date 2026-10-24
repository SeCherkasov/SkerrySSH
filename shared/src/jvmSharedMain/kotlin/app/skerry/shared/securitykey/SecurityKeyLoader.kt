package app.skerry.shared.securitykey

import com.hierynomus.sshj.userauth.fido.SecurityKeyPrivateKey
import com.hierynomus.sshj.userauth.fido.SecurityKeyPublicKey
import com.hierynomus.sshj.userauth.fido.SecurityKeySignatureData
import com.hierynomus.sshj.userauth.fido.SecurityKeySigningRequest
import com.hierynomus.sshj.userauth.keyprovider.OpenSSHKeyV1KeyFile
import java.io.IOException
import java.security.MessageDigest
import java.security.Signature
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import net.schmizz.sshj.userauth.password.PasswordUtils

internal class SecurityKeyAccess(val service: SecurityKeyService?, val prompter: SecurityKeyPrompter?, val enabled: () -> Boolean)

/** Each context belongs to one SSH authentication attempt, never to a global signer. */
internal class SecurityKeySignerContext(
    private val access: SecurityKeyAccess,
    private val endpoint: String,
    private val failure: AtomicReference<SecurityKeyException?>,
    private val requestContext: CoroutineContext = EmptyCoroutineContext,
    private val abort: () -> Unit,
) {
    fun requireEnabled() {
        if (!access.enabled()) throw SecurityKeyException(SecurityKeyFailure.Disabled)
    }
    fun sign(provider: OpenSSHKeyV1KeyFile, request: SecurityKeySigningRequest): SecurityKeySignatureData = try {
        produce(provider, request)
    } catch (e: CancellationException) {
        abort(); throw e
    } catch (e: Exception) {
        val safe = e as? SecurityKeyException ?: SecurityKeyException(SecurityKeyFailure.DeviceError)
        failure.set(safe)
        abort()
        throw IOException("Security key operation failed", safe)
    }
    private fun produce(provider: OpenSSHKeyV1KeyFile, request: SecurityKeySigningRequest): SecurityKeySignatureData {
        requireEnabled()
        val backend = access.service ?: throw SecurityKeyException(SecurityKeyFailure.Unavailable)
        val key = provider.public as? SecurityKeyPublicKey ?: throw SecurityKeyException(SecurityKeyFailure.InvalidKey)
        val stored = provider.private as? SecurityKeyPrivateKey ?: throw SecurityKeyException(SecurityKeyFailure.InvalidKey)
        if (request.application != key.application || !MessageDigest.isEqual(request.keyHandle, stored.keyHandle)) {
            throw SecurityKeyException(SecurityKeyFailure.InvalidKey)
        }
        val algorithm = securityKeyAlgorithm(request.keyTypeName, SecurityKeyFailure.UnsupportedAlgorithm)
        val input = SecurityKeySignRequest(algorithm, request.application, request.keyHandle, request.challenge, request.minFlags.toInt() and 5)
        val assertion = runBlocking(requestContext) { backend.sign(input, prompt(key)) }
        requestContext.ensureActive()
        requireEnabled()
        validateAssertion(input, assertion)
        verifySignature(key, input, assertion)
        return SecurityKeySignatureData(assertion.flags.toByte(), assertion.counter, assertion.signature)
    }
    private fun prompt(key: SecurityKeyPublicKey): SecurityKeyPrompter = access.prompter?.forRequest(
        SecurityKeyRequestContext(endpoint, app.skerry.shared.ssh.opensshFingerprint(key), key.application, SecurityKeyOperation.Sign),
    ) ?: throw SecurityKeyException(SecurityKeyFailure.Cancelled)
}

internal fun loadSecurityKeyProvider(pem: String, passphrase: String?, context: SecurityKeySignerContext): OpenSSHKeyV1KeyFile {
    val provider = OpenSSHKeyV1KeyFile()
    provider.setSecurityKeySigner { context.sign(provider, it) }
    if (pem.length > 262144 || !app.skerry.shared.vault.kdfCostAcceptable(pem)) throw SecurityKeyException(SecurityKeyFailure.InvalidKey)
    try { validateSecurityKeyEnvelope(pem) } catch (_: IllegalArgumentException) { throw SecurityKeyException(SecurityKeyFailure.InvalidKey) }
    provider.init(pem, null, passphrase?.let { PasswordUtils.createOneOff(it.toCharArray()) })
    val private = try { provider.private } catch (_: IOException) { throw SecurityKeyException(SecurityKeyFailure.InvalidKey) }
    if (private is SecurityKeyPrivateKey) {
        context.requireEnabled()
        SecurityKeySignRequest(securityKeyAlgorithm(private.keyTypeName, SecurityKeyFailure.InvalidKey), private.application,
            private.keyHandle, ByteArray(32), private.flags.toInt() and 5)
    }
    return provider
}
private fun securityKeyAlgorithm(name: String, failure: SecurityKeyFailure): SecurityKeyAlgorithm =
    SecurityKeyAlgorithm.entries.firstOrNull { it.wireName == name } ?: throw SecurityKeyException(failure)

private fun verifySignature(key: SecurityKeyPublicKey, input: SecurityKeySignRequest, assertion: SecurityKeyAssertion) {
    val algorithm = if (input.algorithm == SecurityKeyAlgorithm.ED25519) "Ed25519" else "SHA256withECDSA"
    val verifier = Signature.getInstance(algorithm)
    verifier.initVerify(key.delegate)
    verifier.update(securityKeySignedData(input, assertion))
    if (!verifier.verify(assertion.signature)) throw SecurityKeyException(SecurityKeyFailure.VerificationFailed)
}
