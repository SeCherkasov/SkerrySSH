package app.skerry.shared.securitykey

import app.skerry.shared.ssh.ensureCryptoProvider
import app.skerry.shared.vault.BouncyCastleSshKeyGenerator
import com.hierynomus.sshj.userauth.fido.SecurityKeyPrivateKey
import com.hierynomus.sshj.userauth.fido.SecurityKeySigningRequest
import java.nio.ByteBuffer
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SecurityKeyInteropTest {
    private val prompt = object : SecurityKeyPrompter {
        override suspend fun requestPin(retries: Int?): CharArray? = null
        override suspend fun onTouch() = Unit
    }
    @Test fun generatedStubsRoundTripBothAlgorithmsWithMetadata() {
        ensureCryptoProvider()
        for (algorithm in SecurityKeyAlgorithm.entries) {
            val pair = KeyPairGenerator.getInstance(if (algorithm == SecurityKeyAlgorithm.ED25519) "Ed25519" else "EC").apply {
                if (algorithm == SecurityKeyAlgorithm.ECDSA) initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
            }.generateKeyPair()
            val ordinary = Buffer.PlainBuffer().putPublicKey(pair.public).compactData
            val reader = Buffer.PlainBuffer(ordinary)
            reader.readString()
            if (algorithm == SecurityKeyAlgorithm.ECDSA) reader.readString()
            val key = encodeSecurityKey(SecurityKeyGenerationOptions(algorithm, resident = true, userVerification = true), reader.readBytes(), byteArrayOf(1, 2, 3))
            val inspected = assertNotNull(BouncyCastleSshKeyGenerator().inspect(key.privateKeyPem))
            assertEquals(key.info.fingerprintSha256, inspected.fingerprintSha256)
            assertEquals(SecurityKeyMetadata("ssh:", true, 5), inspected.securityKey)
            val loaded = SSHClient().use { it.loadKeys(key.privateKeyPem, null, null).private as SecurityKeyPrivateKey }
            assertTrue(loaded.keyHandle.contentEquals(byteArrayOf(1, 2, 3)))
            assertTrue(!key.toString().contains("BEGIN OPENSSH"))
        }
    }
    @Test fun encryptedOpenSshStubsImportBothAlgorithmsAndRejectWrongPassphrase() {
        val generator = BouncyCastleSshKeyGenerator()
        for (pem in listOf(ENCRYPTED_SK_ED25519, ENCRYPTED_SK_ECDSA)) {
            val info = assertNotNull(generator.inspect(pem, "test-only"))
            assertEquals(SecurityKeyMetadata("ssh:", false, 1), info.securityKey)
            kotlin.test.assertNull(generator.inspect(pem, "wrong"))
            kotlin.test.assertNull(generator.inspect(pem))
            val provider = loadSecurityKeyProvider(pem, "test-only", SecurityKeySignerContext(SecurityKeyAccess(null, prompt, { true }), "", AtomicReference(), abort = {}))
            assertTrue(provider.private is SecurityKeyPrivateKey)
        }
    }
    @Test fun disabledPolicyNeverCallsHardware() {
        val stub = stub()
        val error = assertFailsWith<SecurityKeyException> {
            loadSecurityKeyProvider(stub, null, SecurityKeySignerContext(SecurityKeyAccess(null, prompt, { false }), "", AtomicReference(), abort = {}))
        }
        assertEquals(SecurityKeyFailure.Disabled, error.reason)
    }
    @Test fun signerValidatesHardwareSignatureAgainstStoredKey() {
        ensureCryptoProvider()
        val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val raw = pair.public.encoded.takeLast(32).toByteArray()
        val stub = encodeSecurityKey(SecurityKeyGenerationOptions(), raw, byteArrayOf(1)).privateKeyPem
        val backend = object : SecurityKeyService {
            override suspend fun generate(options: SecurityKeyGenerationOptions, prompter: SecurityKeyPrompter) = error("unused")
            override suspend fun sign(request: SecurityKeySignRequest, prompter: SecurityKeyPrompter): SecurityKeyAssertion {
                val signed = MessageDigest.getInstance("SHA-256").digest(request.application.toByteArray()) + byteArrayOf(1) + ByteBuffer.allocate(4).putInt(9).array() + request.challenge
                return SecurityKeyAssertion(Signature.getInstance("Ed25519").run { initSign(pair.private); update(signed); sign() }, 1, 9)
            }
        }
        val keys = loadSecurityKeyProvider(stub, null, SecurityKeySignerContext(SecurityKeyAccess(backend, prompt, { true }), "", AtomicReference(), abort = {}))
        val key = keys.private as SecurityKeyPrivateKey
        val result = key.signer.sign(SecurityKeySigningRequest(key.keyTypeName, key.application, key.keyHandle, ByteArray(32), 1))
        assertEquals(9L, result.counter)
    }
    @Test fun cancelledRequestCancelsInFlightSignerAndCannotReturnLateSignature() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val backend = object : SecurityKeyService {
            override suspend fun generate(options: SecurityKeyGenerationOptions, prompter: SecurityKeyPrompter) = error("unused")
            override suspend fun sign(request: SecurityKeySignRequest, prompter: SecurityKeyPrompter): SecurityKeyAssertion {
                started.complete(Unit)
                try { kotlinx.coroutines.awaitCancellation() } finally { stopped.complete(Unit) }
            }
        }
        val job = async(Dispatchers.IO) {
            val keys = loadSecurityKeyProvider(stub(), null, SecurityKeySignerContext(SecurityKeyAccess(backend, prompt, { true }), "", AtomicReference(), coroutineContext, abort = {}))
            val key = keys.private as SecurityKeyPrivateKey
            key.signer.sign(SecurityKeySigningRequest(key.keyTypeName, key.application, key.keyHandle, ByteArray(32), 1))
        }
        started.await(); job.cancel(); job.join(); stopped.await()
        assertTrue(job.isCancelled)
    }
    private fun stub(): String {
        ensureCryptoProvider()
        val raw = KeyPairGenerator.getInstance("Ed25519").generateKeyPair().public.encoded.takeLast(32).toByteArray()
        return encodeSecurityKey(SecurityKeyGenerationOptions(), raw, byteArrayOf(1)).privateKeyPem
    }
}
