package app.skerry.ui.sync

import app.skerry.shared.sync.AccountSummary
import app.skerry.shared.sync.DeviceInfo
import app.skerry.shared.sync.PairingResult
import app.skerry.shared.sync.PairingTicket
import app.skerry.shared.sync.RecordPage
import app.skerry.shared.sync.RemoteDevice
import app.skerry.shared.sync.RemoteRecord
import app.skerry.shared.sync.SyncClient
import app.skerry.shared.sync.SyncException
import app.skerry.shared.sync.SyncSession
import app.skerry.shared.sync.SyncSignal
import app.skerry.shared.vault.FileVault
import app.skerry.shared.vault.RecordType
import app.skerry.shared.vault.Vault
import app.skerry.shared.vault.VaultCrypto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import okio.FileSystem
import okio.Path.Companion.toPath
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Shared doubles for the reactivation/reconcile tests ([SyncCoordinatorReactivationTest] and
 * [SyncCoordinatorReconcileDebtTest]): a server that reactivates a revoked device, a vault whose clear
 * fails, and debt stores that refuse one direction of the write.
 */

/**
 * This device's own account after a server-side purge: `register` collides (account exists), `login`
 * reports [reactivated], the served wrap is the vault's OWN key (so the connect adopts nothing — isolating
 * the reactivation path), and the server no longer holds the purged record, so `pull` returns nothing.
 * `push` records exactly what the client sent.
 */
internal class ReactivatingClient(
    private val ownWrappedKey: ByteArray,
    reactivated: Boolean = true,
    /** Makes the reconcile's own first cycle fail, leaving the rebuild to a later sync. */
    private val failFirstPull: Boolean = false,
    /** How many key fetches serve an unopenable wrap — a connect that fails AFTER the login. */
    private val corruptWraps: Int = 0,
    /** The first key fetch dies on the network — a connect that THROWS after the login. */
    private val throwOnFirstFetch: Boolean = false,
) : SyncClient {
    val pushed = mutableListOf<RemoteRecord>()

    /** What each pull asked for — `0` is the full re-pull a reconcile's cursor reset forces. */
    val pulledSince = mutableListOf<Long>()
    private val pulls = AtomicInteger(0)

    // The reactivation is reported exactly once, like the server does it: the verify that reports it is the
    // one that clears the revocation, so every later login of this device is an ordinary one.
    private val revoked = AtomicBoolean(reactivated)
    private val fetches = AtomicInteger(0)

    override suspend fun register(accountId: String, authKey: ByteArray, wrappedDataKey: ByteArray, device: DeviceInfo): SyncSession =
        throw SyncException(SyncException.Kind.CONFLICT, "account exists")
    override suspend fun login(accountId: String, authKey: ByteArray, device: DeviceInfo): SyncSession =
        SyncSession(accountId, accessToken = "access", refreshToken = "refresh", reactivated = revoked.getAndSet(false))
    override suspend fun fetchWrappedDataKey(session: SyncSession): ByteArray {
        val n = fetches.getAndIncrement()
        if (throwOnFirstFetch && n == 0) throw SyncException(SyncException.Kind.NETWORK, "unreachable")
        return if (n < corruptWraps) ByteArray(ownWrappedKey.size) else ownWrappedKey.copyOf()
    }
    override suspend fun pull(session: SyncSession, since: Long): RecordPage {
        pulledSince += since
        // Not a SyncException(NETWORK): that one arms the backoff retry loop, and the tests want the next
        // cycle to be the one they trigger themselves.
        if (failFirstPull && pulls.getAndIncrement() == 0) error("pull unreachable")
        return RecordPage(emptyList(), 1)
    }
    override suspend fun push(session: SyncSession, records: List<RemoteRecord>): RecordPage {
        pushed += records
        return RecordPage(emptyList(), 1)
    }
    override fun changes(session: SyncSession): Flow<SyncSignal> = emptyFlow()

    // Unreachable on purpose: a health ping that comes up drives the coordinator's own self-heal
    // ([SyncCoordinator]'s init — no session, so `restoreSession`), which would race the connects these
    // tests drive and re-save the config from under the assertions. Reachability is covered elsewhere.
    override suspend fun ping(): Boolean = false
    override suspend fun close() {}
    override suspend fun listDevices(session: SyncSession): List<RemoteDevice> = emptyList()
    override suspend fun accountSummary(session: SyncSession): AccountSummary = error("unused")
    override suspend fun revokeDevice(session: SyncSession, deviceId: String): Boolean = false
    // The silent restore of a keep-connected device: a token exchange, with no `reactivated` in it.
    override suspend fun refresh(session: SyncSession): SyncSession =
        SyncSession(session.accountId, accessToken = "access2", refreshToken = "refresh2")
    // The rotation itself isn't what these tests are about: they need the activation that follows it, which
    // re-publishes the session WITHOUT reconciling. Accepts any current password.
    override suspend fun changePassword(accountId: String, currentAuthKey: ByteArray, newAuthKey: ByteArray, newWrappedDataKey: ByteArray, device: DeviceInfo): SyncSession =
        SyncSession(accountId, accessToken = "access2", refreshToken = "refresh2")
    override suspend fun startPairing(session: SyncSession, encryptedDataKey: ByteArray): PairingTicket = throw NotImplementedError()
    override suspend fun claimPairing(code: String, device: DeviceInfo): PairingResult = throw NotImplementedError()
}

/**
 * A real vault whose reconcile-time clear fails — what an auto-lock landing inside the connect does
 * (`clearRecords` requires an unlocked vault), leaving the debt standing with nothing cleared. [failures]
 * bounds how many clears fail, so a test can also drive the recovery: the lock is gone by the next connect
 * and the reconcile finally runs.
 */
internal class ClearFailingVault(private val delegate: Vault, private val failures: Int = Int.MAX_VALUE) : Vault by delegate {
    private val attempts = AtomicInteger(0)

    /** How many clears were tried — the observable "the reconcile ran again" for a retry that fails too. */
    val clearAttempts: Int get() = attempts.get()

    override fun clearRecords(types: Set<RecordType>) {
        if (attempts.getAndIncrement() < failures) error("vault is locked")
        delegate.clearRecords(types)
    }
}

/** Debt store that refuses exactly the write RECORDING a debt (a full disk). */
internal class DebtRaiseFailingStore(private val delegate: ReconcileDebtStore) : ReconcileDebtStore {
    /** Cleared once the test wants the disk to have room again. */
    @Volatile
    var refuse = true

    override fun load(): Set<ServerLink> = delegate.load()
    override fun save(debts: Set<ServerLink>) {
        if (refuse && debts.size > delegate.load().size) error("debt write failed")
        delegate.save(debts)
    }
}

/** Debt store that refuses exactly the write retiring a debt (a full disk). */
internal class DebtClearFailingStore(private val delegate: ReconcileDebtStore) : ReconcileDebtStore {
    @Volatile
    var refuseClear = true

    override fun load(): Set<ServerLink> = delegate.load()
    override fun save(debts: Set<ServerLink>) {
        if (refuseClear && debts.size < delegate.load().size) error("debt write failed")
        delegate.save(debts)
    }
}

/** Whether the store records a rebuild owed to [url]/[id]. */
internal fun ReconcileDebtStore.owes(url: String, id: String): Boolean = ServerLink(url, id) in load()

/** A fresh unlocked account vault under [password], with its own random dataKey. */
internal fun newAccountVault(crypto: VaultCrypto, password: String): Vault {
    val file = Files.createTempFile("skerry-reactivate", ".json").toString().toPath()
    FileSystem.SYSTEM.delete(file) // FileVault creates it
    return FileVault(file, crypto, deviceId = "devA", fileSystem = FileSystem.SYSTEM, now = { "2026-07-22T00:00:00Z" })
        .also { it.create(password.toCharArray()) }
}

/** The vault's own dataKey wrapped under the account (= vault) password, so a connect adopts nothing. */
internal fun wrapOwnKey(vault: Vault, crypto: VaultCrypto, password: String, account: String): ByteArray {
    val dk = vault.exportDataKey()!!
    return crypto.wrapDataKey(syncAccountKey(crypto, password, account), dk).also { dk.zeroize() }
}
