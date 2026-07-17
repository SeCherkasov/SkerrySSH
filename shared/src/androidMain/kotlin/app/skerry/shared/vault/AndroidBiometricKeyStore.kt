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
 * `AndroidKeyStore` (TEE, StrongBox when available), gated by [AndroidxBiometricPrompt].
 * `setUserAuthenticationRequired(true)` with a short time-bound validity window
 * (`setUserAuthenticationParameters`) requires a live strong-biometric auth just before use. We do
 * NOT bind auth to a per-operation `CryptoObject`: some OEM ROMs (Xiaomi/MIUI/HyperOS) report success
 * with a null CryptoObject and never authorize the bound operation, so `doFinal()` always failed and
 * unlock silently bounced to the password screen (#23). `setInvalidatedByBiometricEnrollment(true)`
 * invalidates the key when a new fingerprint/face is enrolled — then `init` throws
 * [KeyPermanentlyInvalidatedException] and we return [BiometricResult.KeyInvalidated] (the
 * orchestrator resets biometrics).
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
        // Time-bound scheme: authenticate FIRST (no CryptoObject), then run the cipher within the
        // window the successful strong-biometric auth just opened (see authenticate / generateKey).
        when (authenticate(prompt)) {
            Auth.Success -> encrypt(alias, plaintext)
            Auth.Cancelled -> BiometricResult.Cancelled
            Auth.LockedOut -> BiometricResult.LockedOut
            Auth.Failed, Auth.NoActivity -> BiometricResult.Failed
        }
    }

    override suspend fun unwrap(
        alias: String,
        wrapped: ByteArray,
        prompt: BiometricPrompt,
    ): BiometricResult<ByteArray> = withContext(Dispatchers.Main) {
        when (authenticate(prompt)) {
            Auth.Success -> decrypt(alias, wrapped)
            Auth.Cancelled -> BiometricResult.Cancelled
            Auth.LockedOut -> BiometricResult.LockedOut
            Auth.Failed, Auth.NoActivity -> BiometricResult.Failed
        }
    }

    /** Encrypt after a successful auth (key is authorized within the time-bound window). IV(12) || GCM. */
    private fun encrypt(alias: String, plaintext: ByteArray): BiometricResult<ByteArray> = try {
        val key = loadKey(alias) ?: return BiometricResult.Failed
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
        BiometricResult.Success(cipher.iv + cipher.doFinal(plaintext))
    } catch (e: KeyPermanentlyInvalidatedException) {
        BiometricResult.KeyInvalidated // enrollment changed — orchestrator resets biometrics
    } catch (e: Exception) {
        BiometricResult.Failed
    }

    /** Decrypt IV(12) || GCM after a successful auth; a stale/OEM auth surfaces here as [Failed]. */
    private fun decrypt(alias: String, wrapped: ByteArray): BiometricResult<ByteArray> {
        if (wrapped.size <= IV_LENGTH) return BiometricResult.Failed
        val iv = wrapped.copyOfRange(0, IV_LENGTH)
        val ciphertext = wrapped.copyOfRange(IV_LENGTH, wrapped.size)
        return try {
            val key = loadKey(alias) ?: return BiometricResult.KeyInvalidated
            val cipher = Cipher.getInstance(TRANSFORMATION)
                .apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv)) }
            BiometricResult.Success(cipher.doFinal(ciphertext))
        } catch (e: KeyPermanentlyInvalidatedException) {
            BiometricResult.KeyInvalidated
        } catch (e: Exception) {
            BiometricResult.Failed // includes AEADBadTagException — tampered wrapper
        }
    }

    override fun deleteKey(alias: String) {
        runCatching { androidKeyStore().deleteEntry(alias) }
    }

    // --- internal ---

    private sealed interface Auth {
        data object Success : Auth
        data object Cancelled : Auth
        data object Failed : Auth
        data object LockedOut : Auth
        data object NoActivity : Auth
    }

    /**
     * Show the system prompt and await its outcome. Time-bound scheme (no `CryptoObject`): a
     * successful strong-biometric auth authorizes the `bioKey` for a short window (see [generateKey]),
     * so [encrypt]/[decrypt] run right after. Binding per operation via CryptoObject is deliberately
     * avoided — some OEM ROMs never populate it, breaking unlock (#23).
     */
    private suspend fun authenticate(prompt: BiometricPrompt): Auth =
        suspendCancellableCoroutine { cont ->
            val activity = activityProvider()
            if (activity == null) {
                cont.resume(Auth.NoActivity)
                return@suspendCancellableCoroutine
            }
            val callback = object : AndroidxBiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: AndroidxBiometricPrompt.AuthenticationResult) {
                    if (cont.isActive) cont.resume(Auth.Success)
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
            bioPrompt.authenticate(info) // no CryptoObject — time-bound key
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
            // Time-bound (not per-operation): a successful strong biometric authorizes the key for
            // AUTH_VALIDITY_SECONDS. Per-op CryptoObject binding is avoided — some OEM ROMs drop it
            // and never authorize the operation, so doFinal() always failed (#23).
            builder.setUserAuthenticationParameters(AUTH_VALIDITY_SECONDS, KeyProperties.AUTH_BIOMETRIC_STRONG)
        } else {
            // Pre-R time-bound API. Unlike the R+ overload it takes no authenticator type: Keystore
            // authorizes the key after ANY device auth (incl. device credential) within the window, so
            // "strong biometric only" is enforced by the app's BIOMETRIC_STRONG prompt, not Keystore.
            // Acceptable here: the key is only ever used synchronously right after our own prompt.
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(AUTH_VALIDITY_SECONDS)
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
        // Window (seconds) after a successful strong biometric during which the bioKey may be used
        // without re-auth. Short: just enough to run wrap/unwrap immediately after the prompt.
        const val AUTH_VALIDITY_SECONDS = 5
    }
}
