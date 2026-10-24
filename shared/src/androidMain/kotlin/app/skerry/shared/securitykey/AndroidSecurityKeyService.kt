package app.skerry.shared.securitykey

import android.app.Activity
import app.skerry.shared.vault.GeneratedSshKey
import com.yubico.yubikit.core.application.CommandState
import com.yubico.yubikit.core.YubiKeyConnection
import com.yubico.yubikit.core.fido.FidoConnection
import com.yubico.yubikit.core.smartcard.SmartCardConnection
import com.yubico.yubikit.fido.ctap.Ctap2Session
import com.yubico.yubikit.fido.ctap.ClientPin
import com.yubico.yubikit.fido.ctap.PinUvAuthProtocolV1
import com.yubico.yubikit.fido.webauthn.AuthenticatorData
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Job
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** CTAP2 over YubiKit USB HID (with Android permission) and NFC ISO-DEP reader discovery. */
class AndroidSecurityKeyService(private val activity: Activity) : SecurityKeyService {
    private val mutex = Mutex()
    override suspend fun generate(options: SecurityKeyGenerationOptions, prompter: SecurityKeyPrompter): GeneratedSshKey =
        operation(prompter) { session, state ->
            val hash = ByteArray(32).also(SecureRandom()::nextBytes)
            val auth = pinAuth(session, PinRequest(options.application, hash, ClientPin.PIN_PERMISSION_MC, true), prompter)
            try {
                val data = session.makeCredential(
                    hash, mapOf("id" to options.application, "name" to "Skerry SSH"),
                    mapOf("id" to ByteArray(32).also(SecureRandom()::nextBytes), "name" to options.username.ifEmpty { "Skerry" }, "displayName" to "Skerry SSH"),
                    listOf(mapOf("type" to "public-key", "alg" to options.algorithm.cose)), null, null,
                    mapOf("rk" to options.resident, "uv" to (options.userVerification && auth == null)),
                    auth, if (auth != null) 1 else null, null, state,
                )
                val parsed = parseAuth(data.authenticatorData, options.application, options.flags)
                val credential = parsed.attestedCredentialData ?: throw SecurityKeyException(SecurityKeyFailure.VerificationFailed)
                val cose = credential.cosePublicKey
                if ((cose[3] as? Number)?.toInt() != options.algorithm.cose) throw SecurityKeyException(SecurityKeyFailure.UnsupportedAlgorithm)
                val x = cose[-2] as? ByteArray ?: throw SecurityKeyException(SecurityKeyFailure.VerificationFailed)
                val public = if (options.algorithm == SecurityKeyAlgorithm.ED25519) x else byteArrayOf(4) + x +
                    (cose[-3] as? ByteArray ?: throw SecurityKeyException(SecurityKeyFailure.VerificationFailed))
                encodeSecurityKey(options, public, credential.credentialId)
            } finally { auth?.fill(0) }
        }

    override suspend fun sign(request: SecurityKeySignRequest, prompter: SecurityKeyPrompter): SecurityKeyAssertion =
        operation(prompter) { session, state ->
            val auth = pinAuth(session, PinRequest(request.application, request.challenge, ClientPin.PIN_PERMISSION_GA, request.flags and 4 != 0), prompter)
            try {
                val result = session.getAssertions(
                    request.application, request.challenge,
                    listOf(mapOf("type" to "public-key", "id" to request.keyHandle)), null,
                    mapOf("up" to (request.flags and 1 != 0), "uv" to (request.flags and 4 != 0 && auth == null)),
                    auth, if (auth != null) 1 else null, state,
                ).singleOrNull() ?: throw SecurityKeyException(SecurityKeyFailure.VerificationFailed)
                val id = result.credential?.get("id") as? ByteArray
                if (id != null && !MessageDigest.isEqual(id, request.keyHandle)) throw SecurityKeyException(SecurityKeyFailure.VerificationFailed)
                val parsed = parseAuth(result.authenticatorData, request.application, request.flags)
                validateAssertion(request, SecurityKeyAssertion(result.signature, parsed.flags.toInt() and 255, parsed.signCount.toLong() and 0xffffffffL))
            } finally { auth?.fill(0) }
        }

    private suspend fun pinAuth(session: Ctap2Session, request: PinRequest, prompter: SecurityKeyPrompter): ByteArray? {
        if (!request.required || session.cachedInfo.options["clientPin"] != true) return null
        val protocol = PinUvAuthProtocolV1()
        val client = ClientPin(session, protocol)
        val pin = prompter.requestPin(client.pinRetries.count) ?: throw SecurityKeyException(SecurityKeyFailure.Cancelled)
        val token = try {
            if (!isSecurityKeyPinValid(pin)) throw SecurityKeyException(SecurityKeyFailure.PinInvalid)
            client.getPinToken(pin, request.permission, request.application)
        } finally { pin.fill('\u0000') }
        return try { protocol.authenticate(token, request.hash) } finally { token.fill(0) }
    }

    private suspend fun <T> operation(prompter: SecurityKeyPrompter, action: suspend (Ctap2Session, CommandState) -> T): T = mutex.withLock {
        val failure = AtomicReference<SecurityKeyFailure?>()
        withTimeoutOrNull(90_000) {
            val discovery = AndroidSecurityKeyDiscovery(activity, currentCoroutineContext()[Job]!!)
            try {
                discovery.start()
                prompter.onTouch()
                val open = discovery.await()
                withContext(Dispatchers.IO) { execute(discovery, open, action) }
            } catch (e: CancellationException) { throw e
            } catch (e: SecurityKeyException) { throw e
            } catch (_: java.io.IOException) { throw SecurityKeyException(SecurityKeyFailure.Detached)
            } catch (e: com.yubico.yubikit.core.fido.CtapException) { throw ctapFailure(e)
            } catch (_: Exception) { throw SecurityKeyException(SecurityKeyFailure.DeviceError)
            } finally {
                failure.set(discovery.lastFailure)
                discovery.close()
            }
        }?.getOrThrow() ?: throw SecurityKeyException(failure.get() ?: SecurityKeyFailure.Timeout)
    }

    private suspend fun <T> execute(
        discovery: AndroidSecurityKeyDiscovery,
        open: () -> YubiKeyConnection,
        action: suspend (Ctap2Session, CommandState) -> T,
    ): Result<T> {
        val raw = open()
        discovery.publish(raw)
        val outcome = try {
            withOwnedSecurityKeyConnection(raw) { connection ->
                val session = when (connection) {
                    is FidoConnection -> Ctap2Session(connection)
                    is SmartCardConnection -> Ctap2Session(connection)
                    else -> throw SecurityKeyException(SecurityKeyFailure.Unavailable)
                }
                session.use { action(it, CommandState()) }
            }
        } finally { discovery.clear(raw) }
        return Result.success(outcome)
    }

}
private fun parseAuth(bytes: ByteArray, application: String, flags: Int): AuthenticatorData {
    if (bytes.size !in 37..16384) throw SecurityKeyException(SecurityKeyFailure.VerificationFailed)
    val parsed = AuthenticatorData.parseFrom(ByteBuffer.wrap(bytes))
    if (!MessageDigest.isEqual(parsed.rpIdHash, MessageDigest.getInstance("SHA-256").digest(application.toByteArray())) ||
        parsed.flags.toInt() and flags != flags) throw SecurityKeyException(SecurityKeyFailure.VerificationFailed)
    return parsed
}

private class PinRequest(val application: String, val hash: ByteArray, val permission: Int, val required: Boolean)

private fun ctapFailure(error: com.yubico.yubikit.core.fido.CtapException): SecurityKeyException =
    SecurityKeyException(when (error.ctapError.toInt() and 255) {
        0x31 -> SecurityKeyFailure.PinInvalid
        0x32, 0x34 -> SecurityKeyFailure.PinBlocked
        0x35, 0x36 -> SecurityKeyFailure.PinRequired
        0x26 -> SecurityKeyFailure.UnsupportedAlgorithm
        0x2d -> SecurityKeyFailure.Cancelled
        0x05, 0x2f, 0x3a -> SecurityKeyFailure.Timeout
        0x2e -> SecurityKeyFailure.NoDevice
        else -> SecurityKeyFailure.DeviceError
    })
