package app.skerry.ui.sync

import app.skerry.shared.platformName
import app.skerry.shared.sync.DeviceInfo
import app.skerry.shared.sync.RemoteDevice
import app.skerry.shared.sync.SyncClient
import app.skerry.shared.sync.SyncEngine
import app.skerry.shared.sync.SyncException
import app.skerry.shared.sync.SyncOutcome
import app.skerry.shared.sync.SyncSession
import app.skerry.shared.sync.SyncSignal
import app.skerry.shared.team.TeamClient
import app.skerry.shared.sync.SyncStateStore
import app.skerry.shared.sync.InMemorySyncStateStore
import app.skerry.shared.sync.SyncSettings
import app.skerry.shared.sync.SyncSettingsStore
import app.skerry.shared.vault.DataKey
import app.skerry.shared.vault.MasterKey
import app.skerry.shared.vault.UnlockResult
import app.skerry.shared.vault.Vault
import app.skerry.shared.vault.VaultCrypto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException

/** Where the app persists sync config (server URL, accountId, deviceId) across launches. */
interface SyncConfigStore {
    fun load(): SyncConfig?
    fun save(config: SyncConfig)
    fun clear()
}

/**
 * Saved server link. By default no tokens are stored (re-auth by password). If the user enabled
 * "keep connected" ([keepConnected]), the refresh token is stored but sealed under the vault dataKey
 * ([sealedRefreshToken], ciphertext hex): useless without unlocking the vault, so stealing the config
 * file grants no data access (zero-knowledge).
 */
data class SyncConfig(
    val serverUrl: String,
    val accountId: String,
    val deviceId: String,
    val keepConnected: Boolean = false,
    val sealedRefreshToken: String? = null,
)

class InMemorySyncConfigStore : SyncConfigStore {
    private var config: SyncConfig? = null
    override fun load(): SyncConfig? = config
    override fun save(config: SyncConfig) { this.config = config }
    override fun clear() { config = null }
}

/**
 * What a logged-in device shows for quick pairing: [payload] is the [PairingPayload] string for the
 * QR/code, [expiresAt] is the pairing-session expiry (epoch ms) for the UI countdown.
 */
class PairingOffer(val payload: String, val expiresAt: Long)

/** UI-visible sync connection state. */
sealed interface SyncStatus {
    /** Sync not configured on this device (no saved link). */
    data object Disabled : SyncStatus
    data object Busy : SyncStatus

    /**
     * Server link exists (survived a restart) but there's no active session — tokens aren't persisted
     * (zero-knowledge, design §4). Master password re-entry is needed; server/account are known.
     */
    data class Configured(val serverUrl: String, val accountId: String) : SyncStatus
    data class Online(val accountId: String, val lastPushed: Int, val lastPulled: Int) : SyncStatus

    /**
     * Failure: [reason] is a typed cause (localized in the UI layer), [detail] an optional technical
     * detail (exception message) for cases where it aids diagnosis; the UI appends it after the
     * localized text.
     */
    data class Failed(val reason: SyncFailureReason, val detail: String? = null) : SyncStatus
}

/** [SyncStatus.Failed] causes — one value per user-facing situation (en+ru strings in the UI). */
enum class SyncFailureReason {
    VaultLocked,
    Unauthorized,          // wrong master password or account
    AccountNotFound,
    AccountExists,
    PairingCodeExpired,
    Network,               // no connection to the server (detail: cause)
    Protocol,              // protocol error (detail: cause)
    ConnectFailed,         // unexpected connection failure (detail: cause)
    PairingCodeMalformed,  // string doesn't look like a pairing code
    PairingCodeInvalid,
    WrongDevicePassword,
    LocalVaultCorrupted,
    PairingFailed,         // other pairing failures (no detail: don't expose crypto/Ktor internals)
    SaveSettingsFailed,    // sync settings didn't save (detail: cause)
    SyncFailed,            // sync cycle failure (detail: cause)
    RevokeFailed,          // device revoke failed (detail: cause)
    Forbidden,             // server rejected (registration closed, invite code invalid, etc.)
}

/**
 * Sync server availability from a periodic health probe ([SyncClient.ping] → `GET /healthz`),
 * independent of vault state or session. Feeds the "server up and reachable" indicator on the main
 * desktop/mobile screens. [UNKNOWN] means sync isn't configured (nothing to ping) or the first check
 * hasn't run yet; the indicator hides in that state so it doesn't linger for non-sync users.
 */
enum class ServerReachable { UNKNOWN, REACHABLE, UNREACHABLE }

/**
 * One sync cycle (pull/merge/push) — an abstraction over [SyncEngine.sync] for test injection:
 * [SyncEngine] is final and needs a live network, so the coordinator factory hands back this function
 * rather than the engine (see `engineFactory` in [SyncCoordinator]).
 */
fun interface SyncRunner {
    suspend fun sync(session: SyncSession): SyncOutcome
}

/**
 * App-level glue for self-hosted sync: ties [SyncClient], [VaultCrypto]
 * and the local [Vault] into register/login/sync operations for the UI. Zero-knowledge — master
 * password and dataKey never leave the device; only the SRP verifier and ciphertext go to the server.
 *
 * The masterKey derivation salt comes from accountId ([VaultCrypto.deriveSyncSalt]) — design §1 — so
 * another device can log in with one master password. Requires an unlocked vault (dataKey is needed
 * for the server wrap). [clientFactory] builds the network client for a URL (platform implementation,
 * KtorSyncClient on JVM/Android).
 */
class SyncCoordinator(
    private val clientFactory: (serverUrl: String) -> SyncClient,
    private val crypto: VaultCrypto,
    private val vault: Vault,
    private val configStore: SyncConfigStore = InMemorySyncConfigStore(),
    private val syncState: SyncStateStore = InMemorySyncStateStore(),
    private val deviceIdProvider: () -> String = { randomDeviceId(crypto) },
    private val deviceName: String = "Skerry device",
    /**
     * Called when login adopts an account dataKey different from the local one ([Vault.adoptDataKey]
     * returned true), i.e. the vault dataKey changed. The biometric artifact (`vault.bio`) is wrapped
     * under the old key and would now yield the wrong key on fingerprint unlock, so the platform
     * resets biometrics (the user re-enables it under the new key). A silent re-wrap is impossible —
     * it needs a system fingerprint prompt. No-op on a device without biometrics.
     *
     * Returns `true` if biometrics was enabled and had to be reset — then the coordinator raises
     * [biometricResetNeeded] and the UI prompts to re-enroll (outside onboarding, biometrics is
     * enabled before connect, so the reset would otherwise be silent). During onboarding there's no
     * biometrics yet — the callback returns `false` and the flag stays down.
     */
    private val onDataKeyAdopted: () -> Boolean = { false },
    /**
     * Called after a successful sync when something was pulled from the server ([SyncOutcome.pulled] >
     * 0). List managers (hosts/snippets/tunnels/known-hosts) hold records in memory and don't see what
     * sync wrote to the vault directly — without this callback synced data doesn't appear on screen
     * until a reopen. The platform wires a manager reload here (on the main thread).
     */
    private val onSynced: () -> Unit = {},
    /**
     * Factory for one sync cycle over the active client — injection point for [runSync] tests (see
     * [SyncRunner]). `null` (prod) — the real [SyncEngine] over vault/cursor/settings.
     */
    engineFactory: ((SyncClient) -> SyncRunner)? = null,
) {
    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.Disabled)
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    /**
     * Raised when connecting adopted an account key and thereby reset enabled biometrics
     * ([onDataKeyAdopted] returned true). The UI shows a re-enroll prompt and clears the flag via
     * [acknowledgeBiometricReset]. Outside onboarding this is the only signal — otherwise the user
     * would silently lose fast unlock.
     */
    private val _biometricResetNeeded = MutableStateFlow(false)
    val biometricResetNeeded: StateFlow<Boolean> = _biometricResetNeeded.asStateFlow()

    // "What to sync" (account level) — stored as a SETTINGS record in the vault, synced by the same
    // sync (see [SyncSettings]). Read lazily from the vault: on a locked vault the store returns default.
    private val settingsStore = SyncSettingsStore(vault)

    private val engineFactory: (SyncClient) -> SyncRunner = engineFactory
        ?: { c -> SyncRunner { s -> SyncEngine(c, vault, syncState, settings = { settingsStore.load() }).sync(s) } }

    /** Sealing the refresh token under the vault dataKey for "keep connected" (see [SealedTokenCodec]). */
    private val tokens = SealedTokenCodec(crypto)

    /**
     * Current "what to sync" for the UI (WHAT SYNCS section). Refreshed from the vault via
     * [refreshSyncSettings] (call after unlock and when showing the screen) and automatically after
     * each successful sync that pulled records (another device may have changed it). [setSyncSettings]
     * writes it to the vault — the change goes to the server via the same live-push as other edits.
     */
    private val _syncSettings = MutableStateFlow(SyncSettings())
    val syncSettings: StateFlow<SyncSettings> = _syncSettings.asStateFlow()

    // @Volatile: written/read from independent coroutines on [scope] (Dispatchers.Default thread pool):
    // activateSession sets, disconnect nulls, startWatch/startLocalPush/runSync read. Without volatile a
    // write on one thread isn't guaranteed visible to a read on another (JMM) — e.g. disconnect sets
    // client=null while startWatch sees a stale non-null and starts a watch on a dead client.
    @Volatile
    private var client: SyncClient? = null
    @Volatile
    private var session: SyncSession? = null

    /**
     * TeamsCoordinator hook: team WS signals ([SyncSignal.Team]/[SyncSignal.Membership]) from the
     * shared `/sync` socket arrive here. Volatile for the same reason as [client]; called from the
     * watch coroutine.
     */
    @Volatile
    var onTeamSignal: ((SyncSignal) -> Unit)? = null

    /** Live session for team operations; null when sync isn't connected. */
    fun currentSession(): SyncSession? = session

    /** Team API of the current client; null when sync isn't connected (or transport lacks Teams). */
    fun currentTeamClient(): TeamClient? = client as? TeamClient

    // Own scope: network operations must not depend on a composable's lifecycle. On mobile the form
    // recomposes on [status]: as soon as connect() sets Busy the form leaves composition, and if the
    // launch used its rememberCoroutineScope the operation would cancel mid-flight. Launching here
    // avoids that; Argon2id (heavy) also runs off the main thread on Dispatchers.Default.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Server availability via health ping — a dedicated poller with its own client (see
    // [ServerHealthMonitor]); lives for the coordinator's lifetime, holds UNKNOWN while target is null.
    private val health = ServerHealthMonitor(clientFactory, scope, initialTarget = configStore.load()?.serverUrl)

    /**
     * Server availability via health ping (see [ServerReachable]). Updated by the [health] poller
     * independently of the session — the indicator is honest even with a locked vault.
     */
    val serverReachable: StateFlow<ServerReachable> get() = health.reachable

    // Subscription to server change notifications (WS `/sync`): while alive, every remote change
    // arrives as a push signal and pulls the delta, without a manual "Sync". One per session; a new
    // connect and disconnect cancel it. null = live-pull inactive (manual sync only). cancel/join/new
    // job replacement only under [opMutex] (activateSession/disconnect).
    @Volatile
    private var watchJob: Job? = null

    // Subscription to local vault changes ([Vault.localChanges]): an edit/add/delete on this device,
    // debounced, triggers a sync (push) so the change flies to the server itself, and from there via a
    // WS signal to other devices (live-sync). One per session; cancelled in disconnect and replaced on
    // reconnect — also strictly under [opMutex].
    @Volatile
    private var pushJob: Job? = null

    // Serializes all sync cycles: launched by activateSession, manual syncNow, WS live-pull (watchJob),
    // and auto-push of local edits (pushJob) — on Dispatchers.Default they'd otherwise run in parallel
    // and race on the cursor ([syncState]) and [_status] (two engines read cursor=N, both write
    // cursor=M, status reflects "whoever finished last"). LWW and the vault lock would protect data,
    // but cursor/status would desync. One sync at a time.
    private val syncMutex = Mutex()

    // Serializes session-lifecycle operations: doConnect/doClaimPairing/restoreSession (assigning
    // client/session + restarting watch/push) and disconnect (stopping them + closing the client).
    // Without it a disconnect slipping between the network register and publishing client would leave a
    // live Ktor client and a running watch after "disconnect". Lock order invariant: opMutex first,
    // syncMutex only inside it (runSync/disconnect) — otherwise deadlock.
    private val opMutex = Mutex()

    // Serializes startPairing: a double-tap on "Link a device" must not spawn multiple live pairing
    // sessions on the server — each is independently valid until TTL and widens the attack window.
    // tryLock: a second concurrent call returns null immediately (the UI just won't show a second code).
    private val pairMutex = Mutex()

    init {
        // Restore the link after a restart: no session/tokens in memory, but show the saved
        // server/account as Configured — the UI offers "reconnect" with one password, no retyping.
        // Disconnect erases the config → back to Disabled.
        configStore.load()?.let { _status.value = SyncStatus.Configured(it.serverUrl, it.accountId) }
    }

    val isConfigured: Boolean get() = configStore.load() != null

    /** Saved link (to prefill the reconnect form with server/account). */
    val savedConfig: SyncConfig? get() = configStore.load()

    /**
     * Stop the coordinator's background work (health poller, watch/push, in-flight operations) —
     * process/test teardown. Does not touch the saved link (that's [disconnect]).
     */
    fun close() {
        scope.cancel()
    }

    /**
     * Connect the device to an account with the master password — one action instead of separate
     * "register"/"login" (no "account already exists" vs "no account" dead ends): try to register a new
     * account, on collision (`CONFLICT`) log into the existing one. Fire-and-forget launch,
     * progress/result via [status]. [keepConnected] stores the refresh token (sealed under the dataKey)
     * for silent restore after a restart.
     *
     * Register makes the local dataKey the account key; logging into an existing account instead adopts
     * the account key (see [doConnect]).
     */
    fun connect(serverUrl: String, accountId: String, masterPassword: CharArray, keepConnected: Boolean = false, inviteCode: String? = null) {
        // Guard against a double launch: a repeat click while the previous connect/claim is in flight
        // would spawn a second Ktor client (pool/socket leak) and a status race. Calls come from UI
        // handlers on the main thread, so check-then-set without CAS is enough (like panel busy flags).
        if (_status.value == SyncStatus.Busy) {
            masterPassword.fill(' ')
            return
        }
        // Set Busy synchronously, before launch: the onboarding form disables "Skip" on Busy. If we set
        // Busy only in doConnect's first line, a dispatch window would remain where the status is still
        // Disabled and Skip is active: proceeding to biometric enroll, the user would wrap it under a key
        // that connect then replaces (account-key adoption race).
        _status.value = SyncStatus.Busy
        // Copy synchronously and wipe the original before launch: the coroutine starts on
        // Dispatchers.Default not immediately, and the caller may wipe the array first — otherwise
        // deriveMasterKey would get an empty password (TOCTOU). The copy is owned by [doConnect] and
        // wiped in its finally.
        val owned = masterPassword.copyOf()
        masterPassword.fill(' ')
        scope.launch { opMutex.withLock { doConnect(serverUrl, accountId, owned, keepConnected, inviteCode) } }
    }

    // Under [opMutex] (see connect): session activation must not race with disconnect.
    private suspend fun doConnect(serverUrl: String, accountId: String, masterPassword: CharArray, keepConnected: Boolean, inviteCode: String?) {
        _status.value = SyncStatus.Busy
        val dataKey = vault.exportDataKey()
        if (dataKey == null) {
            _status.value = SyncStatus.Failed(SyncFailureReason.VaultLocked)
            masterPassword.fill(' ')
            return
        }
        // Keep key material in outer vars to wipe it in finally (zero-knowledge: masterKey is the
        // Argon2id output, authKey is SRP material; no reason to hold them in heap until GC).
        var masterKey: MasterKey? = null
        var authKey: ByteArray? = null
        try {
            // Argon2id inside try: heavy and may throw (up to OutOfMemoryError) — otherwise the password
            // wouldn't be wiped (finally) and the status would be stuck on Busy forever.
            val mk = crypto.deriveMasterKey(masterPassword, crypto.deriveSyncSalt(accountId)).also { masterKey = it }
            val ak = crypto.deriveAuthKey(mk).also { authKey = it }
            val deviceId = configStore.load()?.takeIf { it.accountId == accountId }?.deviceId ?: deviceIdProvider()
            val device = DeviceInfo(deviceId, deviceName, platformName)
            val syncClient = clientFactory(serverUrl)

            // Register-or-login: a new account publishes our dataKey; an existing one — log in and
            // adopt its dataKey, else other records won't decrypt. CONFLICT = account already exists.
            var adoptedKey = false
            val newSession = try {
                syncClient.register(accountId, ak, crypto.wrapDataKey(mk, dataKey), device)
            } catch (e: SyncException) {
                if (e.kind != SyncException.Kind.CONFLICT) throw e
                val s = syncClient.login(accountId, ak, device)
                adoptedKey = adoptAccountDataKey(syncClient, s, mk, masterPassword.copyOf())
                s
            }

            // keep-connected: seal the refresh token under the current vault dataKey (adopting the
            // account key above may have changed it) — otherwise restoreSession can't open it.
            val sealed = if (keepConnected) {
                vault.exportDataKey()?.let { dk -> try { tokens.seal(dk, newSession.refreshToken) } finally { dk.zeroize() } }
            } else null
            // Full re-pull (reset cursor to 0) only when the dataKey changed, i.e. login adopted a
            // different account key (adoptedKey). After a vault reset/recreate the local vault is empty
            // and/or under a new key while the saved cursor ([SyncStateStore]) is from the last session
            // — without the reset, `pull since tip` would skip server records (pulled==0 ⇒ no onSynced).
            // Recreate always yields a new random dataKey, so adoptedKey catches it. A normal reconnect
            // with the same key (adoptedKey=false) is incremental: otherwise every connect would force a
            // full re-pull of all history — extra load and a rebroadcast amplifier for old tombstones.
            // The cursor is now persistent ([FileSyncStateStore]), so a process restart also continues
            // incrementally; the reset path is doubly guarded — cursor reset in [disconnect]
            // (onVaultReset) and adoptedKey.
            activateSession(
                syncClient,
                newSession,
                SyncConfig(serverUrl, accountId, deviceId, keepConnected, sealed),
                resetCursor = adoptedKey,
            )
        } catch (e: CancellationException) {
            throw e // don't swallow cancellation — it would break structured concurrency
        } catch (e: SyncException) {
            _status.value = syncFailure(e)
        } catch (e: Exception) {
            // Unexpected (e.g. vault.unlockWithDataKey threw I/O while adopting the key) — otherwise the
            // exception would go silently to the SupervisorJob and the status stuck on Busy forever.
            _status.value = SyncStatus.Failed(SyncFailureReason.ConnectFailed, e.message)
        } finally {
            // Wipe all derived key material and the password (zero-knowledge): masterKey/authKey are
            // subkeys, dataKey a copy from exportDataKey (the live key stays with the vault). Idempotent.
            masterPassword.fill(' ')
            masterKey?.zeroize()
            authKey?.fill(0)
            dataKey.zeroize()
        }
    }

    /**
     * Shared tail of activating a session (from [doConnect]/[doClaimPairing]/[restoreSession]):
     * publish client/session, optionally reset the cursor ([resetCursor] — full re-pull cases: adopted
     * account key, freshly paired device), save the link, point the health ping at its server, and
     * start the initial sync + live subscriptions (watch/push).
     *
     * Call only under [opMutex]: assigning client/session and cancel/join/replacing subscriptions race
     * with [disconnect] — the mutex serializes activation and teardown as a whole. For [restoreSession]
     * setting the health target is a no-op (URL unchanged; StateFlow dedups equal values).
     */
    private suspend fun activateSession(
        syncClient: SyncClient,
        newSession: SyncSession,
        config: SyncConfig,
        resetCursor: Boolean,
    ) {
        client = syncClient
        session = newSession
        if (resetCursor) syncState.setCursor(config.accountId, 0)
        configStore.save(config)
        health.setTarget(config.serverUrl)
        runSync()
        startWatch()
        startLocalPush()
    }

    /**
     * Adopt the account dataKey on the incoming device: fetch the wrap, unwrap it with the master key,
     * and persistently adopt the key into the local vault ([Vault.adoptDataKey] — re-wrap under
     * [password] + rewrite the file) so records from other devices decrypt across restarts, without
     * re-login. If the wrap doesn't unwrap (different password) — keep the local key. adoptDataKey
     * wipes [password] and consumes [accountDataKey]. Returns `true` if the key was adopted (changed) —
     * the caller forces a full re-pull on that.
     */
    private suspend fun adoptAccountDataKey(syncClient: SyncClient, s: SyncSession, masterKey: MasterKey, password: CharArray): Boolean {
        val wrapped = syncClient.fetchWrappedDataKey(s)
        val accountDataKey = crypto.unwrapDataKey(masterKey, wrapped)
        if (accountDataKey == null) {
            password.fill(' ')
            return false
        }
        // Key changed → biometrics is wrapped under the old key and would yield the wrong dataKey on
        // fingerprint unlock: ask the platform to reset it (runCatching — a biometrics failure must not
        // fail the connection). If biometrics was enabled, raise the flag so the UI prompts to
        // re-enroll under the new key.
        val adopted = vault.adoptDataKey(accountDataKey, password)
        if (adopted) {
            if (runCatching { onDataKeyAdopted() }.getOrDefault(false)) _biometricResetNeeded.value = true
        }
        return adopted
    }

    /** Clear the re-enroll prompt (the user re-enrolled or dismissed it). */
    fun acknowledgeBiometricReset() {
        _biometricResetNeeded.value = false
    }

    /**
     * Start quick pairing on the logged-in device (variant B): generate a one-time transferKey, seal
     * the live dataKey with it, and hand the envelope to the server ([SyncClient.startPairing]); return
     * a [PairingOffer] — the QR/code string ([PairingPayload]) and expiry. The transferKey travels only
     * in the QR, only the envelope goes to the server, so the server ciphertext is useless without the
     * QR. Requires an active session and unlocked vault; `null` on failure (status → [SyncStatus.Failed]).
     * suspend — called from a UI coroutine (short POST); transferKey/dataKey copy are wiped in finally.
     */
    suspend fun startPairing(): PairingOffer? {
        val c = client ?: return null
        val s = session ?: return null
        val cfg = configStore.load() ?: return null
        // tryLock serializes pairing: a repeat call before the previous one finishes returns null.
        if (!pairMutex.tryLock()) return null
        // Don't touch global _status: pairing starts when the device is already Online, and a one-off
        // POST failure must not drop the status to Failed (that would collapse the whole Online section
        // along with the pairing card itself). Signal errors only via a null return — the UI shows them locally.
        val dataKey = vault.exportDataKey()
        if (dataKey == null) {
            pairMutex.unlock()
            return null
        }
        val transferKey = crypto.newTransferKey()
        return try {
            val envelope = crypto.sealDataKeyForTransfer(dataKey, transferKey)
            val ticket = c.startPairing(s, envelope)
            // encode() copies transferKey into a base64 string before finally, where the raw array is wiped.
            PairingOffer(PairingPayload(cfg.serverUrl, ticket.code, transferKey).encode(), ticket.expiresAt)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } finally {
            dataKey.zeroize()
            transferKey.fill(0)
            pairMutex.unlock()
        }
    }

    /**
     * Complete quick pairing on the new device (variant B): take the string from QR/manual entry,
     * claim the session by code ([SyncClient.claimPairing]), unwrap the account dataKey with the
     * transferKey (bypassing the server), and write the local vault under [localPassword] — this
     * password unlocks the device thereafter, no account master password needed. If the vault doesn't
     * exist yet (onboarding join), create it with this password; if it exists but is locked, unlock it.
     * Fire-and-forget launch, progress/result via [status]; activation tail shared with [doConnect]
     * ([activateSession]).
     */
    fun claimPairing(payload: String, localPassword: CharArray, keepConnected: Boolean = false) {
        // Guard against a double launch — as in [connect] (a double submit would leak a Ktor client).
        if (_status.value == SyncStatus.Busy) {
            localPassword.fill(' ')
            return
        }
        // Busy synchronously (like connect): the onboarding form disables "Skip"/double-submit on Busy.
        _status.value = SyncStatus.Busy
        // Copy and wipe the original before launch (TOCTOU): the coroutine doesn't start immediately, and
        // the caller could wipe the array before vault.create/unlock reads it. The copy is owned by doClaimPairing.
        val owned = localPassword.copyOf()
        localPassword.fill(' ')
        scope.launch { opMutex.withLock { doClaimPairing(payload, owned, keepConnected) } }
    }

    // Under [opMutex] (see claimPairing): session activation must not race with disconnect.
    private suspend fun doClaimPairing(payload: String, localPassword: CharArray, keepConnected: Boolean) {
        _status.value = SyncStatus.Busy
        val parsed = PairingPayload.decode(payload)
        if (parsed == null) {
            _status.value = SyncStatus.Failed(SyncFailureReason.PairingCodeMalformed)
            localPassword.fill(' ')
            return
        }
        // Keep the unwrapped account key in an outer var to wipe in finally until adoptDataKey takes
        // ownership (null the ref after a successful adopt — else we'd wipe the live key).
        var accountDataKey: DataKey? = null
        // A client we opened but haven't made the active [client] yet: on an error before assignment it
        // must be closed (Ktor pool/sockets/dispatcher), else it leaks for the whole process.
        var openedClient: SyncClient? = null
        try {
            val syncClient = clientFactory(parsed.serverUrl).also { openedClient = it }
            val deviceId = deviceIdProvider() // a new device for the account — always a fresh id
            val device = DeviceInfo(deviceId, deviceName, platformName)
            val result = syncClient.claimPairing(parsed.code, device)

            val decoded = crypto.openTransferredDataKey(parsed.transferKey, result.encryptedDataKey)
            if (decoded == null) {
                // transferKey didn't fit the envelope — a corrupt/tampered code. claimPairing already
                // burned the one-time code on the server; a retry won't help: have the user re-pair.
                _status.value = SyncStatus.Failed(SyncFailureReason.PairingCodeInvalid)
                return
            }
            accountDataKey = decoded

            // Bring the local vault to an unlocked state under [localPassword], then adopt the account
            // key (re-wrap under this password + rewrite the file). Existing local records under the old
            // key become unreadable — synced ones return via the full re-pull below.
            if (!vault.exists()) {
                vault.create(localPassword.copyOf())
            } else if (!vault.isUnlocked) {
                when (vault.unlock(localPassword.copyOf())) {
                    UnlockResult.Success -> {}
                    UnlockResult.WrongPassword -> {
                        _status.value = SyncStatus.Failed(SyncFailureReason.WrongDevicePassword)
                        return
                    }
                    UnlockResult.Corrupted -> {
                        _status.value = SyncStatus.Failed(SyncFailureReason.LocalVaultCorrupted)
                        return
                    }
                }
            }
            val adopted = vault.adoptDataKey(decoded, localPassword.copyOf())
            // adoptDataKey takes ownership of the key only when adopted (true). If it rejected the key
            // (false — matched the current one; practically impossible for a new device) — ownership
            // stays with us, keep the ref so finally wipes it. Otherwise null it.
            if (adopted) accountDataKey = null
            if (adopted && runCatching { onDataKeyAdopted() }.getOrDefault(false)) {
                _biometricResetNeeded.value = true
            }

            val sealed = if (keepConnected) {
                vault.exportDataKey()?.let { dk -> try { tokens.seal(dk, result.session.refreshToken) } finally { dk.zeroize() } }
            } else null
            // Client ownership passes to the [client] field on the first activation assignment.
            openedClient = null
            // New device: no local records — full re-pull of the account's whole history
            // (resetCursor, like adoptedKey in doConnect).
            activateSession(
                syncClient,
                result.session,
                SyncConfig(parsed.serverUrl, result.accountId, deviceId, keepConnected, sealed),
                resetCursor = true,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: SyncException) {
            _status.value = syncFailure(e)
        } catch (e: Exception) {
            // No e.message: it can leak crypto/Ktor internals to the UI.
            _status.value = SyncStatus.Failed(SyncFailureReason.PairingFailed)
        } finally {
            localPassword.fill(' ')
            accountDataKey?.zeroize() // if adopt wasn't reached (or the key was rejected) — wipe the unwrapped key
            parsed.transferKey.fill(0)
            runCatching { openedClient?.close() } // opened but not made active — close it
        }
    }

    /**
     * Reread "what to sync" from the vault into [syncSettings]. On a locked vault the store returns
     * default. Called automatically after a sync that pulled, and manually when showing the settings
     * screen (after unlock the vault value is available, whereas the flow may have started at default on
     * a locked start). Vault read (disk + AEAD) is moved off the UI thread to [scope] — ANR risk.
     */
    fun refreshSyncSettings() {
        scope.launch { _syncSettings.value = settingsStore.load() }
    }

    /**
     * Save "what to sync" (account level). [syncSettings] is updated immediately (optimistically, for a
     * responsive toggle); the vault write runs on [scope] (disk/atomicWrite off the UI thread). Settings
     * are a normal vault record, so [Vault.put] emits localChanges and live-push sends it to the server
     * (and from there to other devices).
     *
     * Re-enable backfill: when enabling a previously disabled type, reset the cursor to 0 before saving —
     * while the type was OFF the cursor passed the serverSeq of others' records of that type, and without
     * a reset re-enabling wouldn't pull them. The full re-pull is idempotent ([Vault.mergeRemote] by LWW),
     * so extra duplicates are harmless.
     */
    fun setSyncSettings(settings: SyncSettings) {
        val previous = _syncSettings.value
        _syncSettings.value = settings
        scope.launch {
            try {
                val reEnabled = (settings.syncHosts && !previous.syncHosts) ||
                    (settings.syncSnippets && !previous.syncSnippets)
                if (reEnabled) configStore.load()?.let { syncState.setCursor(it.accountId, 0) }
                // save after the cursor reset: its localChanges wakes pushJob→runSync, which must see the
                // already-reset cursor and do a full re-pull (debounce gives enough of a gap).
                settingsStore.save(settings)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // vault save failed (vault locked, disk full) — otherwise the error would go silently to
                // the SupervisorJob and the optimistically-toggled switch would "snap back" on the next
                // refreshSyncSettings without explanation. Roll the UI back to previous and signal.
                _syncSettings.value = previous
                _status.value = SyncStatus.Failed(SyncFailureReason.SaveSettingsFailed, e.message)
            }
        }
    }

    /**
     * Recovery full re-pull: reset the account cursor to 0 and run a cycle — the server returns all
     * records again, merge (LWW) is idempotent. Needed when a record is irrecoverably lost to delta
     * sync: an old client that didn't know a type (e.g. TEAM before Teams existed) silently skipped it
     * while advancing the cursor, and other devices' re-pushes don't raise seq, so the delta never
     * brings it again. No-op if not connected.
     */
    fun recoverFullPull() {
        val s = session ?: return
        if (client == null) return
        syncState.setCursor(s.accountId, 0)
        syncNow()
    }

    /** Run one sync cycle (pull/merge/push). No-op if not connected. */
    fun syncNow() {
        if (client == null || session == null) return
        scope.launch {
            syncMutex.withLock {
                // Busy only under the mutex and after the client check: setting the status before
                // acquiring the lock let a disconnect slip into that gap and leave runSync's early
                // return without writing status — an eternal Busy spinner.
                val c = client ?: return@withLock
                val s = session ?: return@withLock
                _status.value = SyncStatus.Busy
                runSyncLocked(c, s)
            }
        }
    }

    // Under [syncMutex]: parallel calls (watchJob/pushJob/syncNow/connect) are serialized so cursor and
    // status don't race. withLock is a cancellation point, so a cancel from disconnect/reconnect
    // releases the lock cleanly (CancellationException propagates).
    private suspend fun runSync() = syncMutex.withLock {
        val c = client ?: return@withLock
        val s = session ?: return@withLock
        runSyncLocked(c, s)
    }

    // Body of one sync cycle; called only with [syncMutex] already held (runSync/syncNow).
    private suspend fun runSyncLocked(c: SyncClient, s: SyncSession) {
        try {
            val outcome = engineFactory(c).sync(s)
            _status.value = SyncStatus.Online(s.accountId, outcome.pushed, outcome.pulled)
            // Pulled records from the server → refresh list managers, else synced data isn't visible until reopen.
            if (outcome.pulled > 0) {
                refreshSyncSettings() // another device may have changed "what to sync"
                runCatching { onSynced() }
            }
        } catch (e: CancellationException) {
            throw e // don't swallow cancellation — it would break structured concurrency
        } catch (e: SyncException) {
            _status.value = syncFailure(e)
        } catch (e: Exception) {
            // Unexpected (serialization, OOM, engine bug) — otherwise it would go silently to the
            // SupervisorJob and the status stuck on Busy (eternal spinner). syncNow/restoreSession call this.
            _status.value = SyncStatus.Failed(SyncFailureReason.SyncFailed, e.message)
        }
    }

    /**
     * Whether a WS signal with cursor [remoteCursor] should trigger a delta pull. `true` only when the
     * server advanced past our saved cursor (= remote changes appeared). An equal/behind cursor is an
     * echo of our own push with nothing to pull: suppress it to avoid a push→WS→push loop. `internal`
     * (not private) — a hook for the unit test [SyncCoordinatorWatchGuardTest].
     */
    internal fun signalAdvancesCursor(accountId: String, remoteCursor: Long): Boolean =
        remoteCursor > syncState.cursor(accountId)

    /**
     * Subscribe to server change notifications (WS `/sync`) and pull the delta on each signal —
     * realtime live-pull instead of a manual "Sync". Cancels the previous subscription (reconnect);
     * cancel/join/replace are atomic w.r.t. [disconnect] — called only under [opMutex]. Best-effort: a
     * WS drop/error doesn't kill live-pull forever — reconnect with exponential backoff
     * ([WATCH_RETRY_MIN_MS]…[WATCH_RETRY_MAX_MS]) until the coroutine is cancelled (disconnect/reconnect).
     * Without this a transient network dip would silently kill live-pull while the status stayed Online.
     * Don't drop the status to Failed — a connectivity blink shouldn't kill a working Online; manual
     * [syncNow] is always available. [runSync] per signal runs sequentially (collect isn't parallel) and
     * doesn't blink Busy.
     */
    private suspend fun startWatch() {
        val c = client ?: return
        val s = session ?: return
        // cancel + join the old subscription before starting a new one (reconnect without disconnect):
        // otherwise the old collect could run runSync with the new session under the old cursor.
        watchJob?.cancel()
        watchJob?.join()
        watchJob = scope.launch {
            var backoff = WATCH_RETRY_MIN_MS
            while (true) {
                try {
                    c.changes(s).collect { signal ->
                        backoff = WATCH_RETRY_MIN_MS // a live signal — reset the delay to the minimum
                        when (signal) {
                            is SyncSignal.Account ->
                                // Pull the delta only if the server advanced past our cursor. Otherwise
                                // our own push, returning as a WS signal with an already-known cursor,
                                // would trigger a redundant sync — the second push→WS→push loop breaker
                                // (defense-in-depth to the server guard).
                                if (signalAdvancesCursor(s.accountId, signal.cursor)) runSync()
                            // Team signals are TeamsCoordinator's job (its own cursor guard is there).
                            is SyncSignal.Team, SyncSignal.Membership -> onTeamSignal?.invoke(signal)
                        }
                    }
                    // collect finished without an exception = server closed the stream cleanly; reconnect below.
                } catch (e: CancellationException) {
                    throw e // don't swallow cancellation (disconnect/reconnect)
                } catch (e: Exception) {
                    // WS dropped (network/server/expired token) — don't give up, wait backoff and reconnect.
                }
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(WATCH_RETRY_MAX_MS)
            }
        }
    }

    /**
     * Subscribe to local vault changes and auto-push them (live-sync): an edit/add/delete triggers a
     * sync, no manual "Sync". Debounce [PUSH_DEBOUNCE_MS] coalesces a burst of quick edits (bulk import,
     * rename with autosave) into one sync. Cancels the previous subscription (reconnect); like
     * [startWatch], called only under [opMutex]. [runSync] does pull+push: pull→merge doesn't emit
     * localChanges, so incoming records don't spawn a new push — no loop.
     */
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    private suspend fun startLocalPush() {
        if (client == null || session == null) return
        pushJob?.cancel()
        pushJob?.join() // like startWatch: wait for the old subscription to stop before starting a new one
        pushJob = scope.launch {
            vault.localChanges
                .debounce(PUSH_DEBOUNCE_MS)
                .collect { if (client != null && session != null) runSync() }
        }
    }

    suspend fun listDevices(): List<RemoteDevice> {
        val c = client ?: return emptyList()
        val s = session ?: return emptyList()
        // Not runCatching: it would swallow CancellationException and break structured concurrency.
        return try {
            c.listDevices(s)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Revoke another device by [deviceId] (Settings → Account). No-op without an active session.
     * Returns `true` if the server confirmed the revoke — the UI rereads the device list on that. The
     * UI doesn't offer revoking the current device (that's [disconnect]).
     */
    suspend fun revokeDevice(deviceId: String): Boolean {
        val c = client ?: return false
        val s = session ?: return false
        return try {
            c.revokeDevice(s, deviceId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Revoke is a security action (reaction to a lost device): a silent false is indistinguishable
            // in the UI from "no such device", so the user doesn't know if it revoked. Signal the error via
            // status so the Sync section shows the failure, and return false (list isn't reread).
            _status.value = SyncStatus.Failed(SyncFailureReason.RevokeFailed, e.message)
            false
        }
    }

    /** Disable sync on this device: forget the session and the saved link. */
    fun disconnect() {
        // Under [opMutex]: teardown must not race with activation (connect/claim/restore) — otherwise a
        // disconnect slipping between the network register and publishing client would leave a live Ktor
        // client and a running watch "after disconnect". withLock waits for activation to finish, then
        // tears down its result entirely (invariant: no live client after disconnect).
        scope.launch {
            opMutex.withLock {
                // First cancel both live subscriptions and wait for them to stop: cancel() only sends a
                // signal, and their collect might be mid-runSync() — without join its finishing runSync
                // would write Online after the Disabled set below (status stuck on Online with client==null).
                watchJob?.cancel()
                pushJob?.cancel()
                watchJob?.join()
                pushJob?.join()
                watchJob = null
                pushJob = null
                // close() and nulling client/session under syncMutex: manual syncNow() launches runSync()
                // and isn't tracked/cancelled anywhere, so without the lock disconnect could close the Ktor
                // client during its own in-flight request. withLock waits for the current runSync; the next
                // one after nulling sees client==null and returns early.
                syncMutex.withLock {
                    // runCatching: a close() failure (I/O at teardown) must not leave the link/cursor in
                    // place — otherwise disconnect would silently fail and the status stick.
                    runCatching { client?.close() }
                    client = null
                    session = null
                }
                // Forget the sync cursor too: the next connect (to this or another account in the same
                // process) must do a full re-pull, not continue from the last session's tip. runCatching: a
                // cursor write failure (disk full) must not leave configStore/healthTarget/status in a
                // half-detached state ("Disconnect ran but the device is linked again").
                runCatching { configStore.load()?.let { syncState.setCursor(it.accountId, 0) } }
                configStore.clear()
                health.setTarget(null) // detached — stop the health ping (poller closes the client, status → UNKNOWN)
                _status.value = SyncStatus.Disabled
            }
        }
    }

    /**
     * Silently restore the session after a restart if "keep connected" is on: decrypt the saved refresh
     * token under the dataKey and refresh the session via the server. Call after the vault is unlocked
     * (dataKey needed) — usually from `onVaultUnlocked`. Vault locked / no token → no-op (stays
     * [SyncStatus.Configured]); token expired / no connection → fall back to Configured (link not
     * erased, user reconnects by password). Already connected → no-op.
     */
    fun restoreSession() {
        val cfg = configStore.load() ?: return
        if (!cfg.keepConnected || cfg.sealedRefreshToken == null || session != null) return
        scope.launch {
            opMutex.withLock {
                // Recheck under the lock: a parallel connect/claim may have activated the session while
                // we waited for opMutex — then there's nothing to restore.
                if (session != null) return@withLock
                val dataKey = vault.exportDataKey() ?: return@withLock
                _status.value = SyncStatus.Busy
                try {
                    val refreshToken = tokens.open(dataKey, cfg.sealedRefreshToken)
                    if (refreshToken == null) {
                        _status.value = SyncStatus.Configured(cfg.serverUrl, cfg.accountId)
                        return@withLock
                    }
                    val syncClient = clientFactory(cfg.serverUrl)
                    val newSession = syncClient.refresh(SyncSession(cfg.accountId, "", refreshToken))
                    // refresh rotates the token — re-save it sealed under the dataKey (inside activation).
                    activateSession(
                        syncClient,
                        newSession,
                        cfg.copy(sealedRefreshToken = tokens.seal(dataKey, newSession.refreshToken)),
                        resetCursor = false,
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Any restore failure (expired token, no connection, other) — fall back to Configured,
                    // don't erase the link (reconnect by password). Catch Exception, not just SyncException:
                    // otherwise something unexpected would stick on Busy (eternal spinner).
                    _status.value = SyncStatus.Configured(cfg.serverUrl, cfg.accountId)
                } finally {
                    dataKey.zeroize() // dataKey copy — wipe it, the live key stays with the vault
                }
            }
        }
    }

    /** [SyncException] → typed [SyncStatus.Failed] (texts in the UI layer, en+ru). */
    private fun syncFailure(e: SyncException): SyncStatus.Failed = when (e.kind) {
        SyncException.Kind.UNAUTHORIZED -> SyncStatus.Failed(SyncFailureReason.Unauthorized)
        SyncException.Kind.NOT_FOUND -> SyncStatus.Failed(SyncFailureReason.AccountNotFound)
        SyncException.Kind.CONFLICT -> SyncStatus.Failed(SyncFailureReason.AccountExists)
        SyncException.Kind.GONE -> SyncStatus.Failed(SyncFailureReason.PairingCodeExpired)
        SyncException.Kind.NETWORK -> SyncStatus.Failed(SyncFailureReason.Network, e.message)
        SyncException.Kind.PROTOCOL -> SyncStatus.Failed(SyncFailureReason.Protocol, e.message)
        SyncException.Kind.FORBIDDEN -> SyncStatus.Failed(SyncFailureReason.Forbidden, e.message)
    }
}

/**
 * Debounce for auto-pushing local edits: a burst of quick changes (bulk import, rename autosaving each
 * keystroke) coalesces into one sync. Small enough that an edit reaches other devices in ~a second, big
 * enough not to push on every keystroke.
 */
private const val PUSH_DEBOUNCE_MS = 1500L

/**
 * Reconnect backoff for the live-pull WS subscription ([SyncCoordinator.startWatch]): after a drop wait
 * [WATCH_RETRY_MIN_MS] and double up to a ceiling of [WATCH_RETRY_MAX_MS]. The minimum is small so a
 * short network dip recovers fast; the ceiling caps retry frequency during a long server outage (or dead
 * token) — ~once a minute, no battery drain. A live signal resets the delay to the minimum.
 */
private const val WATCH_RETRY_MIN_MS = 1_000L
private const val WATCH_RETRY_MAX_MS = 60_000L

/**
 * Cryptographically random 128-bit deviceId as hex. 16 bytes from the libsodium CSPRNG via
 * [VaultCrypto.newSalt] (not `kotlin.random.Random`, which isn't crypto-grade): deviceId isn't a
 * secret, but it's part of the biometric key alias and the LWW tie-break, so predictability is undesirable.
 */
private fun randomDeviceId(crypto: VaultCrypto): String = crypto.newSalt().toHex()
