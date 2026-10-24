package app.skerry.shared.securitykey

import app.skerry.shared.vault.GeneratedSshKey
import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Native USB CTAP backend; no library is loaded until the user requests a hardware operation. */
class DesktopSecurityKeyService internal constructor(private val loadLibrary: () -> LibFido2) : SecurityKeyService {
    constructor() : this(LibFido2::load)

    private val mutex = Mutex()
    override suspend fun generate(options: SecurityKeyGenerationOptions, prompter: SecurityKeyPrompter): GeneratedSshKey =
        operation(prompter, requirePin = true) { lib, dev, pin ->
            val cred = lib.fido_cred_new() ?: throw SecurityKeyException(SecurityKeyFailure.DeviceError)
            try {
                val hash = ByteArray(32).also(SecureRandom()::nextBytes)
                val user = ByteArray(32).also(SecureRandom()::nextBytes)
                checkFido(lib.fido_cred_set_type(cred, options.algorithm.cose))
                checkFido(lib.fido_cred_set_clientdata_hash(cred, hash, hash.size.toLong()))
                checkFido(lib.fido_cred_set_rp(cred, options.application, "Skerry SSH"))
                checkFido(lib.fido_cred_set_user(cred, user, user.size.toLong(), options.username.ifEmpty { "Skerry" }, "Skerry SSH", null))
                checkFido(lib.fido_cred_set_rk(cred, if (options.resident) 2 else 1))
                checkFido(lib.fido_cred_set_uv(cred, if (options.userVerification) 2 else 0))
                checkFido(lib.fido_dev_make_cred(dev, cred, pin))
                val verification = if (lib.fido_cred_x5c_len(cred) > 0) lib.fido_cred_verify(cred) else lib.fido_cred_verify_self(cred)
                if (verification != 0) throw SecurityKeyException(SecurityKeyFailure.VerificationFailed)
                var publicBytes = nativeBytes(lib.fido_cred_pubkey_ptr(cred), lib.fido_cred_pubkey_len(cred), 64)
                if (options.algorithm == SecurityKeyAlgorithm.ECDSA) publicBytes = byteArrayOf(4) + publicBytes
                encodeSecurityKey(options, publicBytes, nativeBytes(lib.fido_cred_id_ptr(cred), lib.fido_cred_id_len(cred), 4096))
            } finally { lib.fido_cred_free(PointerByReference(cred)) }
        }

    override suspend fun sign(request: SecurityKeySignRequest, prompter: SecurityKeyPrompter): SecurityKeyAssertion =
        operation(prompter, requirePin = request.flags and 4 != 0) { lib, dev, pin ->
            val assertion = lib.fido_assert_new() ?: throw SecurityKeyException(SecurityKeyFailure.DeviceError)
            try {
                checkFido(lib.fido_assert_set_rp(assertion, request.application))
                checkFido(lib.fido_assert_set_clientdata_hash(assertion, request.challenge, 32))
                checkFido(lib.fido_assert_allow_cred(assertion, request.keyHandle, request.keyHandle.size.toLong()))
                checkFido(lib.fido_assert_set_up(assertion, if (request.flags and 1 != 0) 2 else 1))
                checkFido(lib.fido_assert_set_uv(assertion, if (request.flags and 4 != 0 && pin == null) 2 else if (request.flags and 4 == 0) 1 else 0))
                checkFido(lib.fido_dev_get_assert(dev, assertion, pin))
                if (lib.fido_assert_count(assertion) != 1L) throw SecurityKeyException(SecurityKeyFailure.VerificationFailed)
                validateAssertion(request, SecurityKeyAssertion(
                    nativeBytes(lib.fido_assert_sig_ptr(assertion, 0), lib.fido_assert_sig_len(assertion, 0), 256),
                    lib.fido_assert_flags(assertion, 0).toInt() and 255,
                    lib.fido_assert_sigcount(assertion, 0).toLong() and 0xffffffffL,
                ))
            } finally { lib.fido_assert_free(PointerByReference(assertion)) }
        }

    private suspend fun <T> operation(prompter: SecurityKeyPrompter, requirePin: Boolean, block: (LibFido2, Pointer, Pointer?) -> T): T = mutex.withLock {
        withContext(Dispatchers.IO) {
            val lib = loadLibrary()
            val dev = openFirst(lib)
            try {
                checkFido(lib.fido_dev_set_timeout(dev, 90_000))
                val pin = if (requirePin && lib.fido_dev_has_pin(dev).toInt() != 0) {
                    prompter.requestPin(null) ?: throw SecurityKeyException(SecurityKeyFailure.Cancelled)
                } else null
                try {
                    if (pin != null && !isSecurityKeyPinValid(pin)) throw SecurityKeyException(SecurityKeyFailure.PinInvalid)
                    prompter.onTouch()
                    coroutineScope {
                        val work = async(Dispatchers.IO) {
                            val memory = pin?.let { chars ->
                                val bytes = Charsets.UTF_8.encode(java.nio.CharBuffer.wrap(chars))
                                Memory((bytes.remaining() + 1).toLong()).apply {
                                    clear(); val raw = ByteArray(bytes.remaining()); bytes.get(raw); write(0, raw, 0, raw.size); raw.fill(0)
                                    if (bytes.hasArray()) bytes.array().fill(0)
                                }
                            }
                            try { block(lib, dev, memory) } finally { memory?.clear(); memory?.close() }
                        }
                        try { work.await() } finally { if (!work.isCompleted) lib.fido_dev_cancel(dev) }
                    }
                } finally { pin?.fill('\u0000') }
            } finally { lib.fido_dev_close(dev); lib.fido_dev_free(PointerByReference(dev)) }
        }
    }

    private fun openDevice(lib: LibFido2, path: String): Pointer {
        if (path.startsWith("/dev/")) {
            val node = java.nio.file.Path.of(path)
            if (!java.nio.file.Files.isReadable(node) || !java.nio.file.Files.isWritable(node)) {
                throw SecurityKeyException(SecurityKeyFailure.PermissionDenied)
            }
        }
        val dev = lib.fido_dev_new() ?: throw SecurityKeyException(SecurityKeyFailure.DeviceError)
        try { checkFido(lib.fido_dev_open(dev, path)); return dev } catch (e: Exception) {
            lib.fido_dev_free(PointerByReference(dev)); throw e
        }
    }

    private fun openFirst(lib: LibFido2): Pointer {
        val info = lib.fido_dev_info_new(64) ?: throw SecurityKeyException(SecurityKeyFailure.DeviceError)
        try {
            Memory(8).use { count ->
                count.clear(); checkFido(lib.fido_dev_info_manifest(info, 64, count))
                val size = count.getLong(0)
                if (size !in 1..64) throw SecurityKeyException(SecurityKeyFailure.NoDevice)
                // Device paths come exclusively from libfido2 discovery, never from an imported key.
                val path = (0 until size).mapNotNull { index -> lib.fido_dev_info_path(lib.fido_dev_info_ptr(info, index)) }
                    .firstOrNull { !it.startsWith("windows://", ignoreCase = true) }
                    ?: throw SecurityKeyException(SecurityKeyFailure.NoDevice)
                return openDevice(lib, path)
            }
        } finally { lib.fido_dev_info_free(PointerByReference(info), 64) }
    }
}
private fun nativeBytes(pointer: Pointer?, length: Long, max: Int): ByteArray {
    if (pointer == null || length !in 1..max.toLong()) throw SecurityKeyException(SecurityKeyFailure.VerificationFailed)
    return pointer.getByteArray(0, length.toInt())
}
private fun checkFido(code: Int) {
    if (code == 0) return
    throw SecurityKeyException(when (code) {
        -1, -2 -> SecurityKeyFailure.Detached
        -10 -> SecurityKeyFailure.NoDevice
        -6 -> SecurityKeyFailure.VerificationFailed
        0x31 -> SecurityKeyFailure.PinInvalid
        0x32, 0x34 -> SecurityKeyFailure.PinBlocked
        0x35, 0x36 -> SecurityKeyFailure.PinRequired
        0x26 -> SecurityKeyFailure.UnsupportedAlgorithm
        0x2e -> SecurityKeyFailure.NoDevice
        0x2d -> SecurityKeyFailure.Cancelled
        0x05, 0x2f, 0x3a -> SecurityKeyFailure.Timeout
        else -> SecurityKeyFailure.DeviceError
    })
}
