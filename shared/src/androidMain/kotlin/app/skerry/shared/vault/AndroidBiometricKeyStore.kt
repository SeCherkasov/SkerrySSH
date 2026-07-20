package app.skerry.shared.vault

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt as AndroidxBiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.coroutines.resume

/**
 * Android implementation of [BiometricKeyStore]: `bioKey` is a non-exportable AES-256-GCM key in
 * `AndroidKeyStore` (TEE, StrongBox when available), gated by biometrics via
 * [AndroidxBiometricPrompt] + `CryptoObject`. `setUserAuthenticationRequired(true)` requires live
 * biometric auth per operation; `setInvalidatedByBiometricEnrollment(true)` invalidates the key
 * when a new fingerprint/face is enrolled — then `init` throws [KeyPermanentlyInvalidatedException]
 * and we return [BiometricResult.KeyInvalidated] (the orchestrator resets biometrics).
 *
 * Key hardening follows [hardeningLadder]: OEM keystores exist that accept an auth-bound key and
 * then refuse every operation on it (#23). When that happens the operation resolves to
 * [BiometricResult.Unusable] and the orchestrator retries on a weaker rung; per-operation biometric
 * auth itself is never traded away, so a device where no rung works simply has no biometric unlock.
 *
 * The prompt is bound to a [FragmentActivity] (androidx.biometric requirement) and fetched lazily
 * via [activityProvider] — the store survives Activity recreation and grabs the current one only
 * at prompt time. Wrapper format: `IV(12) || GCM(ciphertext+tag)`. `wrap`/`unwrap` run on the main
 * thread (where the prompt lives); the caller is responsible for wiping the passed `plaintext`.
 */
class AndroidBiometricKeyStore(
    context: Context,
    private val activityProvider: () -> FragmentActivity?,
) : BiometricKeyStore {

    private val appContext = context.applicationContext

    override fun availability(): BiometricAvailability =
        when (BiometricManager.from(appContext).canAuthenticate(BIOMETRIC_STRONG)) {
            BiometricManager.BIOMETRIC_SUCCESS -> BiometricAvailability.Available
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricAvailability.NotEnrolled
            BiometricManager.BIOMETRIC_STATUS_UNKNOWN -> BiometricAvailability.NoHardware
            else -> BiometricAvailability.NoHardware // no hardware / HW unavailable / update needed
        }

    /**
     * StrongBox first, then TEE, then TEE without [KeyGenParameterSpec.Builder.setUnlockedDeviceRequired].
     * Both of the dropped properties are known to make OEM keystores accept an auth-bound key and then
     * refuse every operation on it (#23; KeePassDX #2298 reports the StrongBox variant as
     * "Invalid operation handle"). Per-operation biometric auth — the property that actually guards the
     * `dataKey` — is required on every rung.
     */
    override fun hardeningLadder(): List<BiometricKeyHardening> = listOf(
        BiometricKeyHardening.Strongest,
        BiometricKeyHardening.NoStrongBox,
        BiometricKeyHardening.Relaxed,
    )

    override suspend fun ensureKey(alias: String, hardening: BiometricKeyHardening): Boolean = withContext(Dispatchers.IO) {
        if (availability() != BiometricAvailability.Available) return@withContext false
        val keyStore = androidKeyStore()
        if (keyStore.containsAlias(alias)) return@withContext true
        generateKey(alias, hardening)
    }

    override suspend fun wrap(
        alias: String,
        plaintext: ByteArray,
        prompt: BiometricPrompt,
    ): BiometricResult<ByteArray> = withContext(Dispatchers.Main) {
        val cipher = try {
            Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.ENCRYPT_MODE, loadKey(alias) ?: return@withContext BiometricResult.Unusable)
            }
        } catch (e: KeyPermanentlyInvalidatedException) {
            return@withContext BiometricResult.KeyInvalidated
        } catch (e: Exception) {
            // Refusing to even start an operation on a key this store just created is the same
            // configuration evidence as refusing doFinal after auth (#23: HyperOS rejects TEE
            // auth-bound keys at init, KeePassDX #2298 sees "Invalid operation handle" here) —
            // Unusable, so enable() walks the ladder instead of aborting before the weaker rungs.
            return@withContext BiometricResult.Unusable
        }
        when (val auth = authenticate(cipher, prompt)) {
            is Auth.Success -> try {
                val sealed = auth.cipher.iv + auth.cipher.doFinal(plaintext)
                BiometricResult.Success(sealed)
            } catch (e: Exception) {
                // Auth passed, the enclave still refused the operation — see Auth.Unusable.
                BiometricResult.Unusable
            }
            Auth.Cancelled -> BiometricResult.Cancelled
            Auth.LockedOut -> BiometricResult.LockedOut
            Auth.Unusable -> BiometricResult.Unusable
            Auth.Failed, Auth.NoActivity -> BiometricResult.Failed
        }
    }

    override suspend fun unwrap(
        alias: String,
        wrapped: ByteArray,
        prompt: BiometricPrompt,
    ): BiometricResult<ByteArray> = withContext(Dispatchers.Main) {
        if (wrapped.size <= IV_LENGTH) return@withContext BiometricResult.Failed
        val iv = wrapped.copyOfRange(0, IV_LENGTH)
        val ciphertext = wrapped.copyOfRange(IV_LENGTH, wrapped.size)
        val cipher = try {
            Cipher.getInstance(TRANSFORMATION).apply {
                val key = loadKey(alias) ?: return@withContext BiometricResult.KeyInvalidated
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            }
        } catch (e: KeyPermanentlyInvalidatedException) {
            return@withContext BiometricResult.KeyInvalidated
        } catch (e: Exception) {
            // Same reasoning as in wrap(): an init refusal is the enclave rejecting the key, not a
            // sensor problem. At unlock time Unusable feeds the refusal streak instead of being
            // written off as a one-off failure forever.
            return@withContext BiometricResult.Unusable
        }
        when (val auth = authenticate(cipher, prompt)) {
            is Auth.Success -> try {
                BiometricResult.Success(auth.cipher.doFinal(ciphertext))
            } catch (e: AEADBadTagException) {
                // Either the wrapper genuinely doesn't match this key, or KeyMint answered
                // VERIFICATION_FAILED on finish() for a wrapper the key just produced (#23) — the
                // caller decides, since only it knows whether the ciphertext is fresh.
                BiometricResult.TagMismatch
            } catch (e: Exception) {
                // Anything else after a successful auth is the enclave refusing the authorized
                // operation (typically IllegalBlockSizeException caused by KeyStoreException
                // "Invalid operation handle") — see Auth.Unusable.
                BiometricResult.Unusable
            }
            Auth.Cancelled -> BiometricResult.Cancelled
            Auth.LockedOut -> BiometricResult.LockedOut
            Auth.Unusable -> BiometricResult.Unusable
            Auth.Failed, Auth.NoActivity -> BiometricResult.Failed
        }
    }

    override fun deleteKey(alias: String) {
        runCatching { androidKeyStore().deleteEntry(alias) }
    }

    // --- internal ---

    private sealed interface Auth {
        data class Success(val cipher: Cipher) : Auth
        data object Cancelled : Auth
        data object Failed : Auth
        data object LockedOut : Auth

        /** Auth reported success but no authorized cipher came back — the enclave can't serve us (#23). */
        data object Unusable : Auth
        data object NoActivity : Auth
    }

    /** Show the system prompt and await its outcome, binding auth to [cipher] via CryptoObject. */
    private suspend fun authenticate(cipher: Cipher, prompt: BiometricPrompt): Auth =
        suspendCancellableCoroutine { cont ->
            val activity = activityProvider()
            if (activity == null) {
                cont.resume(Auth.NoActivity)
                return@suspendCancellableCoroutine
            }
            val callback = object : AndroidxBiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: AndroidxBiometricPrompt.AuthenticationResult) {
                    if (!cont.isActive) return
                    // A null CryptoObject (reported on some OEM ROMs) means the prompt authorized
                    // nothing: reusing the unbound cipher would only fail at doFinal(). Report it as
                    // Unusable so enable() drops to a weaker key configuration instead of pretending.
                    val authedCipher = result.cryptoObject?.cipher
                    cont.resume(if (authedCipher != null) Auth.Success(authedCipher) else Auth.Unusable)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (!cont.isActive) return
                    cont.resume(
                        when (errorCode) {
                            AndroidxBiometricPrompt.ERROR_NEGATIVE_BUTTON,
                            AndroidxBiometricPrompt.ERROR_USER_CANCELED,
                            AndroidxBiometricPrompt.ERROR_CANCELED,
                            -> Auth.Cancelled
                            // Distinct from Failed so the UI can say "wait" instead of "didn't work".
                            AndroidxBiometricPrompt.ERROR_LOCKOUT,
                            AndroidxBiometricPrompt.ERROR_LOCKOUT_PERMANENT,
                            -> Auth.LockedOut
                            else -> Auth.Failed // hw error, timeout, etc. — soft fallback to password
                        },
                    )
                }
                // onAuthenticationFailed (fingerprint not recognized) is not terminal: prompt stays up.
            }
            val bioPrompt = AndroidxBiometricPrompt(activity, ContextCompat.getMainExecutor(appContext), callback)
            val info = AndroidxBiometricPrompt.PromptInfo.Builder()
                .setTitle(prompt.title)
                .apply { prompt.subtitle?.let { setSubtitle(it) } }
                .setNegativeButtonText(prompt.cancelLabel)
                .setAllowedAuthenticators(BIOMETRIC_STRONG)
                .build()
            bioPrompt.authenticate(info, AndroidxBiometricPrompt.CryptoObject(cipher))
            // Coroutine cancellation (e.g. the gate tore down the subtree) must dismiss the system
            // prompt, or it's left orphaned. cancelAuthentication -> onAuthenticationError(CANCELED),
            // which the cont.isActive guard already swallows.
            cont.invokeOnCancellation { bioPrompt.cancelAuthentication() }
        }

    private fun generateKey(alias: String, hardening: BiometricKeyHardening): Boolean = try {
        val builder = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (hardening != BiometricKeyHardening.Relaxed) builder.setUnlockedDeviceRequired(true)
            if (hardening == BiometricKeyHardening.Strongest) builder.setIsStrongBoxBacked(true)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // per-operation strong-biometric auth; default behavior for the auth key on <R
            builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        }
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
            init(builder.build())
            generateKey()
        }
        true
    } catch (e: StrongBoxUnavailableException) {
        // Device advertises no StrongBox — the ladder's next rung asks for exactly that, so just fail
        // this one instead of silently downgrading behind the caller's back.
        false
    } catch (e: Exception) {
        false
    }

    private fun loadKey(alias: String): SecretKey? = androidKeyStore().getKey(alias, null) as? SecretKey

    private fun androidKeyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val GCM_TAG_BITS = 128
    }
}
