package app.skerry.shared.securitykey

import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import java.io.Closeable
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Drives the real native adapter; only the authenticator and libfido2 ABI are faked. */
class NativeSecurityKeyGenerationPolicyTest {
    @Test
    fun requiredUvRejectsUpOnlyAttestationAfterPin() = runBlocking {
        for (certificate in listOf(false, true)) {
            FakeCredentialLibrary(attestationFlags = 0x01, certificate = certificate).use { fake ->
                val prompt = LocalPinPrompt()
                val service = DesktopSecurityKeyService { fake.lib }
                val failure = assertFailsWith<SecurityKeyException> {
                    service.generate(SecurityKeyGenerationOptions(userVerification = true), prompt)
                }
                assertEquals(SecurityKeyFailure.VerificationFailed, failure.reason)
                assertTrue(fake.usedPin, "the PIN-authenticated creation path must be exercised")
                assertTrue(fake.verified, "credential attestation must be checked before accepting the key")
                assertTrue(fake.deviceClosed && fake.credentialFreed, "a refused attestation must release native resources")
                assertContentEquals(CharArray(4), prompt.pin, "local PIN must be erased even when attestation fails")
            }
        }
    }

    @Test
    fun requiredUvAcceptsVerifiedAttestationAfterPin() = runBlocking {
        for (certificate in listOf(false, true)) {
            FakeCredentialLibrary(attestationFlags = 0x05, certificate = certificate).use { fake ->
                val prompt = LocalPinPrompt()
                val result = DesktopSecurityKeyService { fake.lib }.generate(
                    SecurityKeyGenerationOptions(userVerification = true), prompt,
                )
                assertEquals(5, result.info.securityKey?.flags)
                assertTrue(result.info.publicKeyOpenSsh.startsWith(SecurityKeyAlgorithm.ED25519.wireName))
                assertTrue(fake.usedPin && fake.verified)
                assertTrue(fake.deviceClosed && fake.credentialFreed)
                assertContentEquals(CharArray(4), prompt.pin)
            }
        }
    }

    @Test
    fun optionalUvAcceptsPresenceOnlyAttestation() = runBlocking {
        FakeCredentialLibrary(attestationFlags = 0x01, certificate = false).use { fake ->
            val result = DesktopSecurityKeyService { fake.lib }.generate(
                SecurityKeyGenerationOptions(userVerification = false), LocalPinPrompt(),
            )
            assertEquals(1, result.info.securityKey?.flags)
            assertTrue(fake.usedPin && fake.verified)
        }
    }
}

private class LocalPinPrompt : SecurityKeyPrompter {
    val pin = charArrayOf('1', '2', '3', '4')
    override suspend fun requestPin(retries: Int?): CharArray = pin
    override suspend fun onTouch() = Unit
}

/**
 * Models libfido2's documented credential policy: PIN suppresses the wire UV option,
 * but does not replace the credential's UV verification policy (cred.c). A valid PIN
 * alone cannot make an authenticator response bearing only UP satisfy required UV.
 */
private class FakeCredentialLibrary(private val attestationFlags: Int, private val certificate: Boolean) : Closeable {
    private val opaque = Pointer.createConstant(0x1000L)
    private val publicKey = Memory(32).apply { write(0, ByteArray(32) { (it + 1).toByte() }, 0, 32) }
    private val handle = Memory(16).apply { write(0, ByteArray(16) { 7 }, 0, 16) }
    private var uvPolicy = FIDO_OPT_OMIT
    var usedPin = false
        private set
    var verified = false
        private set
    var deviceClosed = false
        private set
    var credentialFreed = false
        private set

    val lib: LibFido2 = Proxy.newProxyInstance(
        LibFido2::class.java.classLoader, arrayOf(LibFido2::class.java),
    ) { proxy, method, args ->
        when (method.name) {
            "toString" -> "FakeCredentialLibrary"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.get(0)
            "fido_dev_info_new", "fido_dev_info_ptr", "fido_dev_new", "fido_cred_new" -> opaque
            "fido_dev_info_path" -> "test://hardware-token"
            "fido_dev_info_manifest" -> { (args!![2] as Pointer).setLong(0, 1L); 0 }
            "fido_dev_has_pin" -> 1.toByte()
            "fido_dev_open", "fido_dev_set_timeout", "fido_dev_cancel" -> 0
            "fido_cred_set_type", "fido_cred_set_clientdata_hash", "fido_cred_set_rp",
            "fido_cred_set_user", "fido_cred_set_rk" -> 0
            "fido_cred_set_uv" -> { uvPolicy = args!![1] as Int; 0 }
            "fido_dev_make_cred" -> { usedPin = args!![2] != null; 0 }
            "fido_cred_verify", "fido_cred_verify_self" -> {
                verified = true
                if (attestationFlags and 1 == 0 || uvPolicy == FIDO_OPT_TRUE && attestationFlags and 4 == 0) {
                    FIDO_ERR_INVALID_PARAM
                } else 0
            }
            "fido_cred_x5c_len" -> if (certificate) 1L else 0L
            "fido_cred_pubkey_ptr" -> publicKey
            "fido_cred_pubkey_len" -> publicKey.size()
            "fido_cred_id_ptr" -> handle
            "fido_cred_id_len" -> handle.size()
            "fido_dev_close" -> { deviceClosed = true; 0 }
            "fido_cred_free" -> { credentialFreed = true; (args!![0] as PointerByReference).value = null; null }
            "fido_dev_free", "fido_dev_info_free" -> { (args!![0] as PointerByReference).value = null; null }
            else -> error("Unexpected native ABI call: ${method.name}")
        }
    } as LibFido2

    override fun close() { publicKey.close(); handle.close() }

    companion object {
        private const val FIDO_OPT_OMIT = 0
        private const val FIDO_OPT_TRUE = 2
        // libfido2 cred.c returns INVALID_PARAM when required authenticator flags are missing.
        private const val FIDO_ERR_INVALID_PARAM = -5
    }
}
