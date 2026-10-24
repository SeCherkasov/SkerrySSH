package app.skerry.shared.securitykey

import app.skerry.shared.vault.GeneratedSshKey

/** Public metadata only. The credential handle stays inside the encrypted private-key record. */
data class SecurityKeyMetadata(val application: String, val resident: Boolean, val flags: Int)

enum class SecurityKeyAlgorithm(val wireName: String, val cose: Int) {
    ED25519("sk-ssh-ed25519@openssh.com", -8),
    ECDSA("sk-ecdsa-sha2-nistp256@openssh.com", -7),
}

data class SecurityKeyGenerationOptions(
    val algorithm: SecurityKeyAlgorithm = SecurityKeyAlgorithm.ED25519,
    val application: String = "ssh:",
    val resident: Boolean = false,
    val userVerification: Boolean = false,
    val comment: String = "",
    val username: String = "",
) {
    init {
        require(application.startsWith("ssh:") && application.length in 4..255 && application.none { it.code < 32 })
        require(username.length <= 256 && username.none { it.code < 32 })
        require(comment.length <= 256 && comment.none { it.code < 32 })
    }
    val flags: Int get() = 1 or (if (userVerification) 4 else 0)
}

/** PIN ownership transfers to the service, which erases the array after the hardware operation. */
enum class SecurityKeyOperation { Generate, Sign }
data class SecurityKeyRequestContext(val endpoint: String = "", val fingerprint: String = "", val application: String, val operation: SecurityKeyOperation)

interface SecurityKeyPrompter {
    fun forRequest(context: SecurityKeyRequestContext): SecurityKeyPrompter = this
    suspend fun requestPin(retries: Int? = null): CharArray?
    suspend fun onTouch()
}

enum class SecurityKeyFailure {
    Disabled, Unavailable, NoDevice, PermissionDenied, Detached, Cancelled, Timeout,
    PinRequired, PinInvalid, PinBlocked, UnsupportedAlgorithm, InvalidKey, VerificationFailed, DeviceError,
}
class SecurityKeyException(val reason: SecurityKeyFailure) : Exception("Security key: ${reason.name}")

interface SecurityKeyService {
    suspend fun generate(options: SecurityKeyGenerationOptions, prompter: SecurityKeyPrompter): GeneratedSshKey
    suspend fun sign(request: SecurityKeySignRequest, prompter: SecurityKeyPrompter): SecurityKeyAssertion
}
class SecurityKeySignRequest(
    val algorithm: SecurityKeyAlgorithm,
    val application: String,
    val keyHandle: ByteArray,
    val challenge: ByteArray,
    val flags: Int,
) {
    init {
        require(application.startsWith("ssh:") && application.length in 4..255 && application.none { it.code < 32 })
        require(keyHandle.size in 1..4096 && challenge.size == 32 && flags and 5 == flags)
    }
    override fun toString() = "SecurityKeySignRequest(redacted)"
}
class SecurityKeyAssertion(val signature: ByteArray, val flags: Int, val counter: Long) {
    override fun toString() = "SecurityKeyAssertion(redacted)"
}

internal fun validateAssertion(request: SecurityKeySignRequest, assertion: SecurityKeyAssertion): SecurityKeyAssertion {
    val invalidSignature = assertion.signature.size !in 8..256 ||
        (request.algorithm == SecurityKeyAlgorithm.ED25519 && assertion.signature.size != 64)
    if (assertion.flags and request.flags != request.flags || assertion.counter !in 0..0xffffffffL || invalidSignature) throw SecurityKeyException(SecurityKeyFailure.VerificationFailed)
    return assertion
}
