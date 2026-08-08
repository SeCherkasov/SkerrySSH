package app.skerry.ui.vault

import app.skerry.shared.vault.DataKey
import app.skerry.shared.vault.MergeResult
import app.skerry.shared.vault.RecordType
import app.skerry.shared.vault.SyncMeta
import app.skerry.shared.vault.UnlockResult
import app.skerry.shared.vault.Vault
import app.skerry.shared.vault.VaultRecord

/**
 * Unlocked vault that accepts exactly one master password — everything the re-authentication tests
 * need and nothing else. Shared by [SecretCopyAuthorizerTest] and [SecretExportGateTest]: eighteen
 * identical overrides copied into both would drift the first time [Vault] grows a member.
 * (`VaultGateControllerTest` has its own, file-private fake — that one models a *locked* vault and
 * its unlock outcomes, which is a different thing to fake.)
 */
internal class FakeUnlockedVault(private val correct: String) : Vault {
    override fun verifyPassword(password: CharArray): Boolean = password.concatToString() == correct

    override fun exists(): Boolean = true
    override val isUnlocked: Boolean = true
    override fun create(password: CharArray) = Unit
    override fun unlock(password: CharArray): UnlockResult = UnlockResult.Success
    override fun unlockWithDataKey(dataKey: DataKey): UnlockResult = UnlockResult.Success
    override fun exportDataKey(): DataKey? = null
    override fun adoptDataKey(newDataKey: DataKey, password: CharArray): Boolean = false
    override fun lock() = Unit
    override fun reset() = Unit
    override fun records(): List<VaultRecord> = emptyList()
    override fun syncMeta(): SyncMeta? = null
    override fun mergeRemote(remote: List<VaultRecord>): MergeResult = MergeResult.EMPTY
    override fun openPayload(id: String): ByteArray? = null
    override fun put(id: String, type: RecordType, payload: ByteArray) = Unit
    override fun remove(id: String) = Unit
    override fun changePassword(oldPassword: CharArray, newPassword: CharArray): Boolean = true
}
