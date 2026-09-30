package app.skerry.ui.vault.keyring

import app.skerry.shared.vault.BiometricEnableResult
import app.skerry.shared.vault.BiometricPrompt
import app.skerry.shared.vault.SecurityEventType
import app.skerry.shared.vault.SecurityLog
import app.skerry.shared.vault.VaultBiometrics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * What keeps the trusted device of issue #398 true outside the gate: the experimental flag [allowed]
 * going off takes the trust with it, and a sync that replaced the vault key seals it again. Keyring
 * calls block, so both run on [scope], serialized. A re-seal re-reads the flag and yields to a disable
 * that lands while it runs ([VaultBiometrics.reseal]), so none brings back a trust the user turned off.
 */
internal class TrustedDeviceUpkeep(
    private val biometrics: VaultBiometrics,
    private val allowed: () -> Boolean,
    private val securityLog: SecurityLog,
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()

    /**
     * Start-up: a trust still on disk with the flag off (a crash between the two, a hand-edited prefs
     * file). The keyring delete is a D-Bus call, so it stays off the start-up thread.
     */
    fun dropIfDisallowed(): Job = scope.launch {
        mutex.withLock { if (!allowed() && biometrics.isEnabled()) drop() }
    }

    /**
     * The vault key was replaced while the vault is open. The old wrapper would now be refused, and the
     * keyring asks nobody, so the trust is sealed over the new key at once rather than offered again.
     * Whatever cannot be re-sealed is dropped — a wrapper of the old key opens nothing.
     */
    fun resealAfterKeyAdoption(): Job = scope.launch {
        mutex.withLock {
            if (!biometrics.isEnabled()) return@withLock
            if (!allowed()) return@withLock drop()
            when (resealed()) {
                // null: no trust to keep, or the user turned it off meanwhile and reseal() honoured that.
                BiometricEnableResult.Enabled, null -> Unit
                else -> drop()
            }
        }
    }

    /** A re-seal that throws (a disk refusing `vault.bio`) left a wrapper of the old key: dropped like any failure. */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun resealed(): BiometricEnableResult? = try {
        biometrics.reseal(PROMPT)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        BiometricEnableResult.Failed
    }

    private fun drop() {
        runCatching { biometrics.disable() }
        if (!biometrics.isEnabled()) runCatching { securityLog.record(SecurityEventType.TrustedDeviceDisabled) }
    }

    private companion object {
        /** No dialog behind the OS keyring; the prompt only fills the shared enable() signature. */
        val PROMPT = BiometricPrompt(title = "Skerry", cancelLabel = "")
    }
}
