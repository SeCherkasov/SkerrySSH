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

    override suspend fun ensureKey(alias: String): Boolean = withContext(Dispatchers.IO) {
        if (availability() != BiometricAvailability.Available) return@withContext false
        val keyStore = androidKeyStore()
        if (keyStore.containsAlias(alias)) return@withContext true
        generateKey(alias, strongBox = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
    }

    override suspend fun wrap(
        alias: String,
        plaintext: ByteArray,
        prompt: BiometricPrompt,
    ): BiometricResult<ByteArray> = withContext(Dispatchers.Main) {
        val cipher = try {
            Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.ENCRYPT_MODE, loadKey(alias) ?: return@withContext BiometricResult.Failed)
            }
        } catch (e: KeyPermanentlyInvalidatedException) {
            return@withContext BiometricResult.KeyInvalidated
        } catch (e: Exception) {
            return@withContext BiometricResult.Failed
        }
        when (val auth = authenticate(cipher, prompt)) {
            is Auth.Success -> try {
                val sealed = auth.cipher.iv + auth.cipher.doFinal(plaintext)
                BiometricResult.Success(sealed)
            } catch (e: Exception) {
                BiometricResult.Failed
            }
            Auth.Cancelled -> BiometricResult.Cancelled
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
            return@withContext BiometricResult.Failed
        }
        when (val auth = authenticate(cipher, prompt)) {
            is Auth.Success -> try {
                BiometricResult.Success(auth.cipher.doFinal(ciphertext))
            } catch (e: Exception) {
                BiometricResult.Failed // includes AEADBadTagException — tampered wrapper
            }
            Auth.Cancelled -> BiometricResult.Cancelled
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
                    val authedCipher = result.cryptoObject?.cipher
                    cont.resume(if (authedCipher != null) Auth.Success(authedCipher) else Auth.Failed)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (!cont.isActive) return
                    cont.resume(
                        when (errorCode) {
                            AndroidxBiometricPrompt.ERROR_NEGATIVE_BUTTON,
                            AndroidxBiometricPrompt.ERROR_USER_CANCELED,
                            AndroidxBiometricPrompt.ERROR_CANCELED,
                            -> Auth.Cancelled
                            else -> Auth.Failed // lockout, hw error, etc. — soft fallback to password
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

    private fun generateKey(alias: String, strongBox: Boolean): Boolean = try {
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
            builder.setUnlockedDeviceRequired(true)
            if (strongBox) builder.setIsStrongBoxBacked(true)
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
        // A StrongBox-less retry can also throw (full keystore, TEE bug) — must not fail enable().
        if (strongBox) runCatching { generateKey(alias, strongBox = false) }.getOrDefault(false) else false
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
