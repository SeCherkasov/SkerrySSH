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
import app.skerry.shared.vault.RecordType
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
    /**
     * Durable "this device must rebuild from the server before pushing" marker for the revoked→reactivated
     * flow. The server clears revocation on the SRP verify that reports `reactivated`, so it never reports
     * it again; persisting the intent here (set before the vault is cleared, cleared only after the first
     * sync succeeds) means an interrupted reconcile is retried on the next connect/restore instead of
     * silently resurrecting a purged record. Default `false`; older config files without the key load as `false`.
     */
    val pendingReconcile: Boolean = false,
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
     * The typed password is a valid password for an EXISTING account, but not this device's vault
     * password. Joining that account re-keys the local vault to the account password — i.e. this device
     * will start unlocking with the account password (issue #28). We don't do it silently: the UI must
     * confirm ([SyncCoordinator.confirmPasswordReplace]) or cancel ([SyncCoordinator.cancelPasswordReplace]).
     * Server/account are echoed for the dialog copy.
     */
    data class NeedsPasswordReplaceConfirm(val serverUrl: String, val accountId: String) : SyncStatus

    /**
     * Failure: [reason] is a typed cause (localized in the UI layer), [detail] an optional technical
     * detail (exception message) for cases where it aids diagnosis; the UI appends it after the
     * localized text.
     */
    data class Failed(val reason: SyncFailureReason, val detail: String? = null) : SyncStatus
}

/**
 * Outcome of [SyncCoordinator.changeAccountPassword] (issue #32). A discrete action result, not a
 * [SyncStatus] transition: the caller (a dialog) shows the message inline; the localized text lives
 * in the UI layer.
 */
sealed interface AccountPasswordChange {
    data object Success : AccountPasswordChange

    /** The typed current password doesn't unlock this vault (caught locally, no round-trip). */
    data object WrongCurrentPassword : AccountPasswordChange

    /** Sync isn't configured on this device — there's no account password to rotate. */
    data object NotConfigured : AccountPasswordChange

    /**
     * The server rotated the password, but re-wrapping the local vault under it failed. The account
     * is now on the new password; this device must reconnect with it (the #28 path heals the local
     * wrap). Distinct from [Failed] so the UI can tell the user exactly this.
     */
    data object LocalRewrapFailed : AccountPasswordChange

    /** The rotation failed before anything changed ([reason] is localized in the UI; [detail] optional). */
    data class Failed(val reason: SyncFailureReason, val detail: String? = null) : AccountPasswordChange
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
    VaultRekeyFailed,      // vault couldn't be re-wrapped under the account password
    AccountKeyNotAdopted,  // the account key didn't open, so the confirmed replace didn't happen
    SaveSettingsFailed,    // sync settings didn't save (detail: cause)
    SyncFailed,            // sync cycle failure (detail: cause)
    RevokeFailed,          // device revoke failed (detail: cause)
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

    /**
     * Connect paused on [SyncStatus.NeedsPasswordReplaceConfirm]: the params to re-run the connect once the
     * user confirms replacing the vault unlock password. Holds an owned password copy — wiped on confirm
     * (handed to the re-run, wiped in its finally), cancel, a superseding connect, and [close].
     */
    private class PendingReplace(
        val serverUrl: String,
        val accountId: String,
        val password: CharArray,
        val keepConnected: Boolean,
    )

    // Set under [opMutex] in doConnect; read/cleared from the UI thread (confirm/cancel/connect) and [close].
    // @Volatile for cross-thread visibility (doConnect runs on [scope]); the status flips to
    // NeedsPasswordReplaceConfirm only after the stash, and the UI acts on that status, so confirm/cancel
    // always observe a set value.
    @Volatile
    private var pendingReplace: PendingReplace? = null

    /** Stash the pending replace, wiping any superseded one's password first. */
    private fun stashPendingReplace(next: PendingReplace) {
        pendingReplace?.password?.fill(' ')
        pendingReplace = next
    }

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

    // Set by [pauseForLock], cleared by [resumeAfterUnlock]: which of the two the coordinator should end
    // up obeying when they queue up behind a long operation holding [opMutex], regardless of the order
    // their coroutines get dispatched in.
    @Volatile
    private var lockPaused = false

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
        pendingReplace?.password?.fill(' ')
        pendingReplace = null
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
    fun connect(serverUrl: String, accountId: String, masterPassword: CharArray, keepConnected: Boolean = false) {
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
        // A fresh connect supersedes any pending vault-password-replace confirmation: wipe its kept password.
        pendingReplace?.let { it.password.fill(' '); pendingReplace = null }
        // Copy synchronously and wipe the original before launch: the coroutine starts on
        // Dispatchers.Default not immediately, and the caller may wipe the array first — otherwise
        // deriveMasterKey would get an empty password (TOCTOU). The copy is owned by [doConnect] and
        // wiped in its finally.
        val owned = masterPassword.copyOf()
        masterPassword.fill(' ')
        scope.launch { opMutex.withLock { doConnect(serverUrl, accountId, owned, keepConnected) } }
    }

    // Under [opMutex] (see connect): session activation must not race with disconnect.
    private suspend fun doConnect(serverUrl: String, accountId: String, masterPassword: CharArray, keepConnected: Boolean, allowPasswordReplace: Boolean = false) {
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

            // The account (remote) password is the single source of truth: every device shares one
            // account dataKey wrapped under it, so a synced device MUST unlock with the account password.
            // [matchesVault] — is the typed password THIS vault's own unlock password? — decides the flow
            // and whether the unlock password changes (issue #28). verifyPassword runs Argon2id over the
            // vault salt; a rare user-initiated connect, so the extra derivation is acceptable.
            val matchesVault = vault.verifyPassword(masterPassword.copyOf())
            var adoptedKey = false
            // Set when the server reports this device was revoked and this login reactivated it: the vault
            // may still hold records the server purged while we were locked out, so we must re-mirror the
            // server before the first push (see [activateSession]'s clearLocalRecords).
            var reactivated = false
            val newSession: SyncSession = if (matchesVault) {
                // Establish the account under the vault's own password: register a new one (publishes our
                // dataKey), or on CONFLICT log into our existing account and adopt its key. Adopting here
                // never changes the unlock password (same password, at most a new dataKey → full re-pull).
                try {
                    syncClient.register(accountId, ak, crypto.wrapDataKey(mk, dataKey), device)
                } catch (e: SyncException) {
                    if (e.kind != SyncException.Kind.CONFLICT) throw e
                    val s = syncClient.login(accountId, ak, device)
                    reactivated = s.reactivated
                    // Same password on both sides: nothing to re-wrap, only a possible key change.
                    adoptedKey = adoptAccountDataKey(syncClient, s, mk, masterPassword.copyOf()) == KeyAdoption.Adopted
                    s
                }
            } else if (!allowPasswordReplace) {
                // Typed password isn't this vault's. Never register — creating an account under a non-vault
                // password permanently splits the local unlock password from the account password (issue
                // #28's root cause). The only valid intent is joining an EXISTING account under its own
                // password, which re-keys this vault to that password. Log in to verify it's a real account
                // password (don't prompt on a wrong one), then pause for the user to confirm the change.
                val s = try {
                    syncClient.login(accountId, ak, device)
                } catch (e: SyncException) {
                    // The server hides "no such account" behind a wrong-password shape — both surface as
                    // Unauthorized (the UI hint tells the user to use their vault password).
                    if (e.kind == SyncException.Kind.UNAUTHORIZED || e.kind == SyncException.Kind.NOT_FOUND) {
                        runCatching { syncClient.close() }
                        _status.value = SyncStatus.Failed(SyncFailureReason.Unauthorized)
                        return
                    }
                    throw e
                }
                // Verified only — close this client; the confirmed re-run opens its own. Stash the connect
                // params + password and ask the UI to confirm (finally still wipes mk/authKey/dataKey/password).
                runCatching { syncClient.close() }
                stashPendingReplace(PendingReplace(serverUrl, accountId, masterPassword.copyOf(), keepConnected))
                _status.value = SyncStatus.NeedsPasswordReplaceConfirm(serverUrl, accountId)
                return
            } else {
                // Confirmed ([confirmPasswordReplace]): log into the existing account and adopt its key,
                // re-keying this vault to the account password (the intended, consented password change).
                val s = syncClient.login(accountId, ak, device)
                reactivated = s.reactivated
                when (adoptAccountDataKey(syncClient, s, mk, masterPassword.copyOf())) {
                    KeyAdoption.Adopted -> adoptedKey = true
                    // The account key already IS ours (registered here, then the vault password was changed
                    // locally): adoptDataKey keeps the meta in that case, so the unlock password would stay
                    // the local one and diverge from the account — re-wrap it explicitly instead.
                    KeyAdoption.AlreadyOurs -> if (!vault.rewrapUnder(masterPassword.copyOf())) {
                        runCatching { syncClient.close() }
                        _status.value = SyncStatus.Failed(SyncFailureReason.VaultRekeyFailed)
                        return
                    }
                    // The replace the user confirmed did NOT happen (the account wrap didn't open). Connecting
                    // now would claim a password change that isn't there, on records we can't decrypt.
                    KeyAdoption.Undecryptable -> {
                        runCatching { syncClient.close() }
                        _status.value = SyncStatus.Failed(SyncFailureReason.AccountKeyNotAdopted)
                        return
                    }
                }
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
            // A reactivated (formerly revoked) device forces a full re-pull like an adopted key, and first
            // discards its pre-revocation records so a stale live copy can't re-push a server-purged record.
            // The server reports `reactivated` only once (it clears revocation on this verify), so we also
            // honor a durable pendingReconcile from a previously interrupted reconcile — otherwise a crash
            // mid-reconcile would drop the signal and let the stale records push on the next connect.
            val pendingReconcile = configStore.load()?.takeIf { it.accountId == accountId }?.pendingReconcile == true
            val mustReconcile = reactivated || pendingReconcile
            activateSession(
                syncClient,
                newSession,
                SyncConfig(serverUrl, accountId, deviceId, keepConnected, sealed),
                resetCursor = adoptedKey || mustReconcile,
                clearLocalRecords = mustReconcile,
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
     * publish client/session, optionally drop pre-revocation records ([clearLocalRecords] — a reactivated
     * device rebuilds from the server), reset the cursor ([resetCursor] — full re-pull cases: adopted
     * account key, freshly paired device, reactivation), save the link, point the health ping at its
     * server, and start the initial sync + live subscriptions (watch/push).
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
        clearLocalRecords: Boolean = false,
    ) {
        // Publish under [syncMutex]: the 401 recovery ([refreshSessionLocked]) rotates/nulls the
        // session and rewrites the config while holding syncMutex only — without sharing the lock, a
        // refresh in flight (a suspended network call) would resolve after this activation and clobber
        // the just-published session and config (including the pendingReconcile marker saved below).
        // Lock order holds: we're under opMutex, syncMutex nests inside it.
        val superseded: SyncClient?
        syncMutex.withLock {
            superseded = client?.takeIf { it !== syncClient }
            client = syncClient
            session = newSession
        }
        // Reactivation reconcile: drop the local records BEFORE resetting the cursor and running the first
        // sync, so the following full re-pull rebuilds them from the server snapshot and the subsequent
        // push can't resurrect a record the server purged while this device was revoked.
        //
        // The drop set is EVERY sync-capable type, NOT the currently-enabled ones: the push after the
        // reconciling pull filters by the SERVER's "what syncs" settings (the pull applies them), which can
        // differ from this device's stale local settings. If we gated the clear by the local settings, a
        // type disabled locally but enabled on the server would keep its stale record through the clear and
        // then be pushed once the pull flips the filter on — resurrecting the purged record. Clearing by the
        // maximal (everything-on) filter covers any server settings; only never-synced, device-local types
        // (terminal history) are kept.
        if (clearLocalRecords) {
            // Persist the reconcile intent BEFORE mutating the vault: the server clears revocation on the
            // verify that reported `reactivated` and never reports it again, so a crash between here and the
            // first successful sync would otherwise lose the signal and let the stale records push. The
            // marker is cleared only after runSync succeeds below, so an interrupted reconcile is retried.
            configStore.save(config.copy(pendingReconcile = true))
            val syncCapable = SyncSettings(syncHosts = true, syncSnippets = true)
            vault.clearRecords(RecordType.entries.filter { syncCapable.shouldSync(it) }.toSet())
            syncState.setCursor(config.accountId, 0) // reactivation always full-pulls to rebuild from the server
        } else {
            if (resetCursor) syncState.setCursor(config.accountId, 0)
            configStore.save(config)
        }
        health.setTarget(config.serverUrl)
        runSync()
        // The reconcile is complete only once the first full re-pull actually succeeded (status Online);
        // until then keep the marker so an interrupted or failed reconcile is redone on the next connect.
        if (clearLocalRecords && _status.value is SyncStatus.Online) {
            configStore.save(config.copy(pendingReconcile = false))
        }
        startWatch()
        startLocalPush()
        // Reconnecting over a live session (switching accounts, a confirmed password replace) leaves the
        // previous Ktor client with its socket pool: only disconnect used to close one. Closed last, once
        // startWatch/startLocalPush have cancelled and joined the subscriptions that were using it, and
        // under syncMutex so it can't be closed out from under an in-flight sync.
        if (superseded != null) syncMutex.withLock { runCatching { superseded.close() } }
        // The vault may have locked while this operation held opMutex: [pauseForLock] then ran first,
        // found no session to stop and left the flag for us. Undo the live half right here — otherwise
        // the subscriptions just published would outlive the lock with nothing left to cancel them.
        if (lockPaused) {
            stopSubscriptions()
            _status.value = SyncStatus.Configured(config.serverUrl, config.accountId)
        }
    }

    /**
     * Adopt the account dataKey on the incoming device: fetch the wrap, unwrap it with the master key,
     * and persistently adopt the key into the local vault ([Vault.adoptDataKey] — re-wrap under
     * [password] + rewrite the file) so records from other devices decrypt across restarts, without
     * re-login. If the wrap doesn't unwrap (different password) — keep the local key. adoptDataKey
     * wipes [password] and consumes [accountDataKey]. Returns `true` if the key was adopted (changed) —
     * the caller forces a full re-pull on that.
     *
     * Whether this changes the vault UNLOCK password is decided by the caller ([doConnect]) via
     * `matchesVault`, not here: re-wrapping under a [password] equal to the current one keeps the unlock
     * password; a different one (only reached after the user confirmed, issue #28) changes it.
     */
    private suspend fun adoptAccountDataKey(syncClient: SyncClient, s: SyncSession, masterKey: MasterKey, password: CharArray): KeyAdoption {
        val wrapped = syncClient.fetchWrappedDataKey(s)
        val accountDataKey = crypto.unwrapDataKey(masterKey, wrapped)
        if (accountDataKey == null) {
            password.fill(' ')
            return KeyAdoption.Undecryptable
        }
        // Key changed → biometrics is wrapped under the old key and would yield the wrong dataKey on
        // fingerprint unlock: ask the platform to reset it (runCatching — a biometrics failure must not
        // fail the connection). If biometrics was enabled, raise the flag so the UI prompts to
        // re-enroll under the new key.
        val adopted = vault.adoptDataKey(accountDataKey, password)
        if (adopted) {
            if (runCatching { onDataKeyAdopted() }.getOrDefault(false)) _biometricResetNeeded.value = true
        }
        return if (adopted) KeyAdoption.Adopted else KeyAdoption.AlreadyOurs
    }

    /** Clear the re-enroll prompt (the user re-enrolled or dismissed it). */
    fun acknowledgeBiometricReset() {
        _biometricResetNeeded.value = false
    }

    /** Outcome of [adoptAccountDataKey]: the account key replaced ours, already was ours, or didn't open. */
    private enum class KeyAdoption { Adopted, AlreadyOurs, Undecryptable }

    /**
     * Confirm replacing this device's vault unlock password with the sync password (status
     * [SyncStatus.NeedsPasswordReplaceConfirm]): re-run the paused connect, this time allowed to adopt the
     * differing account key. The password kept from the paused connect is handed to the re-run and wiped in
     * its finally. No-op if nothing is pending (stale tap / already resolved).
     */
    fun confirmPasswordReplace() {
        val pending = pendingReplace ?: return
        pendingReplace = null
        _status.value = SyncStatus.Busy
        scope.launch {
            opMutex.withLock {
                doConnect(pending.serverUrl, pending.accountId, pending.password, pending.keepConnected, allowPasswordReplace = true)
            }
        }
    }

    /**
     * Decline replacing the vault unlock password (status [SyncStatus.NeedsPasswordReplaceConfirm]): wipe the
     * kept password and return to the prior state (Configured if a link is saved, else Disabled). The account
     * is untouched and the local vault keeps its current password. No-op if nothing is pending.
     */
    fun cancelPasswordReplace() {
        val pending = pendingReplace ?: return
        pendingReplace = null
        pending.password.fill(' ')
        _status.value = configStore.load()?.let { SyncStatus.Configured(it.serverUrl, it.accountId) } ?: SyncStatus.Disabled
    }

    /**
     * Change the account (sync) password — the single source of truth shared by all devices (issue
     * #32). Atomically on the server: the SRP verifier and the wrapped account dataKey are swapped to
     * the new password's, and every other device is revoked (they re-authenticate with the new
     * password). Locally the vault is re-wrapped under the new password so this device keeps
     * unlocking; the dataKey is unchanged (only its wrap), so biometrics — which wraps the dataKey —
     * stays valid and needs no reset.
     *
     * Server-first ordering is the atomic commit point: if this device dies after the server rotates
     * but before the local re-wrap ([AccountPasswordChange.LocalRewrapFailed]), the account is on the
     * new password and this device heals on its next reconnect via the confirmed-replace path (#28).
     *
     * Requires sync configured ([isConfigured]) and an unlocked vault. Owns copies of [current]/[next]
     * and wipes them (and all derived key material) before returning.
     */
    suspend fun changeAccountPassword(current: CharArray, next: CharArray): AccountPasswordChange {
        val cfg = savedConfig
        if (cfg == null) {
            current.fill(' '); next.fill(' ')
            return AccountPasswordChange.NotConfigured
        }
        // Under opMutex like connect: activating the fresh session must not race with a disconnect or
        // a concurrent connect.
        return try {
            opMutex.withLock { doChangeAccountPassword(cfg, current, next) }
        } finally {
            // If cancelled while waiting for opMutex (dialog dismissed mid-submit), doChangeAccountPassword
            // never ran and never wiped these — wipe here too. Idempotent on the normal path (it already
            // filled them with spaces).
            current.fill(' '); next.fill(' ')
        }
    }

    // Under [opMutex] (see changeAccountPassword).
    private suspend fun doChangeAccountPassword(cfg: SyncConfig, current: CharArray, next: CharArray): AccountPasswordChange {
        // Local current-password check first: catch a typo without a round-trip (and the vault must be
        // unlocked to export the dataKey for the new wrap anyway).
        if (!vault.verifyPassword(current.copyOf())) {
            current.fill(' '); next.fill(' ')
            return AccountPasswordChange.WrongCurrentPassword
        }
        val dataKey = vault.exportDataKey()
        if (dataKey == null) {
            current.fill(' '); next.fill(' ')
            return AccountPasswordChange.Failed(SyncFailureReason.VaultLocked)
        }
        var curMasterKey: MasterKey? = null
        var newMasterKey: MasterKey? = null
        var curAuthKey: ByteArray? = null
        var newAuthKey: ByteArray? = null
        // A client we opened but haven't handed to [activateSession] yet — close it on any early exit
        // so its Ktor pool/sockets don't leak.
        var openedClient: SyncClient? = null
        try {
            val syncSalt = crypto.deriveSyncSalt(cfg.accountId)
            val cmk = crypto.deriveMasterKey(current, syncSalt).also { curMasterKey = it }
            val cak = crypto.deriveAuthKey(cmk).also { curAuthKey = it }
            val nmk = crypto.deriveMasterKey(next, syncSalt).also { newMasterKey = it }
            val nak = crypto.deriveAuthKey(nmk).also { newAuthKey = it }
            val newWrapped = crypto.wrapDataKey(nmk, dataKey)
            val device = DeviceInfo(cfg.deviceId, deviceName, platformName)
            val syncClient = clientFactory(cfg.serverUrl).also { openedClient = it }

            // keep-connected: drop the auto-restore token for the rotation window. If this device dies
            // after the server commits but before the local re-wrap, [restoreSession] must NOT silently
            // bring it back Online under the OLD password on the next launch (the account moved to the
            // new one — issue #32 divergence, and the leaked old password would keep unlocking it). The
            // new token is re-sealed on success; the old one is restored only on errors that provably
            // precede the server commit.
            val hadAutoRestore = cfg.keepConnected && cfg.sealedRefreshToken != null
            if (hadAutoRestore) configStore.save(cfg.copy(sealedRefreshToken = null))

            val newSession = try {
                syncClient.changePassword(cfg.accountId, cak, nak, newWrapped, device)
            } catch (e: SyncException) {
                // UNAUTHORIZED/NOT_FOUND come from the SRP verify gate, before any DB write — nothing
                // rotated, so it's safe to restore the auto-restore token (a wrong-password typo mustn't
                // cost the user their "keep connected"). NETWORK/PROTOCOL are ambiguous (the server may
                // have committed): leave it cleared and let the user reconnect with the new password.
                if (hadAutoRestore && (e.kind == SyncException.Kind.UNAUTHORIZED || e.kind == SyncException.Kind.NOT_FOUND)) {
                    configStore.save(cfg)
                }
                return when (e.kind) {
                    // A wrong current password (the server's SRP proof failed) or missing account.
                    SyncException.Kind.UNAUTHORIZED, SyncException.Kind.NOT_FOUND ->
                        AccountPasswordChange.Failed(SyncFailureReason.Unauthorized)
                    SyncException.Kind.NETWORK -> AccountPasswordChange.Failed(SyncFailureReason.Network, e.message)
                    SyncException.Kind.PROTOCOL -> AccountPasswordChange.Failed(SyncFailureReason.Protocol, e.message)
                    else -> AccountPasswordChange.Failed(SyncFailureReason.ConnectFailed, e.message)
                }
            }

            // Server rotated. Re-wrap the LOCAL vault under the new password so this device keeps
            // unlocking with it. The dataKey is unchanged, so this doesn't touch biometrics.
            if (!vault.changePassword(current.copyOf(), next.copyOf())) {
                // The account is on the new password but the local re-wrap failed (disk error). Don't
                // report success: this device must reconnect with the new password, and the #28
                // confirmed-replace path re-wraps the local vault then.
                return AccountPasswordChange.LocalRewrapFailed
            }

            // keep-connected: re-seal the fresh refresh token under the (unchanged) dataKey.
            val sealed = if (cfg.keepConnected) {
                vault.exportDataKey()?.let { dk -> try { tokens.seal(dk, newSession.refreshToken) } finally { dk.zeroize() } }
            } else null
            openedClient = null // ownership passes to activateSession (it closes any superseded client)
            activateSession(syncClient, newSession, cfg.copy(sealedRefreshToken = sealed), resetCursor = false)
            return AccountPasswordChange.Success
        } catch (e: CancellationException) {
            throw e // don't swallow cancellation — it would break structured concurrency
        } catch (e: Exception) {
            return AccountPasswordChange.Failed(SyncFailureReason.ConnectFailed, e.message)
        } finally {
            current.fill(' '); next.fill(' ')
            curMasterKey?.zeroize(); newMasterKey?.zeroize()
            curAuthKey?.fill(0); newAuthKey?.fill(0)
            dataKey.zeroize()
            runCatching { openedClient?.close() } // opened but not activated (error/rewrap-failed path)
        }
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
            runSyncAttempt(c, s)
        } catch (e: CancellationException) {
            throw e // don't swallow cancellation — it would break structured concurrency
        } catch (e: SyncException) {
            // 401 mid-session = the short-TTL access token expired (15 min by default) while the
            // session stayed alive — the norm on mobile, where the process survives in background far
            // past the TTL. The refresh token in [session] is still valid: rotate and retry once
            // instead of surfacing Failed(Unauthorized), which the UI renders as "logged out".
            if (e.kind == SyncException.Kind.UNAUTHORIZED) {
                // The vault locked while this sync was in flight (pauseForLock doesn't track a manual
                // syncNow, only the subscriptions): don't recover into a state the pause just parked —
                // the retry would throw inside the locked vault and overwrite Configured with a failure.
                if (lockPaused) {
                    configStore.load()?.let { _status.value = SyncStatus.Configured(it.serverUrl, it.accountId) }
                    return
                }
                when (val r = refreshSessionLocked(c, s)) {
                    RefreshResult.Refreshed -> {
                        val fresh = session ?: return
                        try {
                            runSyncAttempt(c, fresh)
                        } catch (e2: CancellationException) {
                            throw e2
                        } catch (e2: SyncException) {
                            _status.value = syncFailure(e2)
                        } catch (e2: Exception) {
                            _status.value = SyncStatus.Failed(SyncFailureReason.SyncFailed, e2.message)
                        }
                    }
                    // Refresh itself was rejected: refreshSessionLocked already tore the dead session
                    // down and parked the status on Configured (password reauth).
                    RefreshResult.Dead -> {}
                    // Session vanished or was replaced (disconnect/connect raced): its owner drives the
                    // status, and retrying the NEW session over the OLD client would send its bearer
                    // token to a possibly different server.
                    RefreshResult.StandDown -> {}
                    // The refresh attempt failed for a non-auth reason: report THAT cause, not the
                    // original 401 — "Unauthorized" for a network blip during refresh would be exactly
                    // the bogus "logged out" rendering this recovery exists to remove. The session
                    // stays; the next sync retries the whole recovery.
                    is RefreshResult.Failed ->
                        _status.value = (r.cause as? SyncException)?.let { syncFailure(it) }
                            ?: SyncStatus.Failed(SyncFailureReason.SyncFailed, r.cause.message)
                }
            } else {
                _status.value = syncFailure(e)
            }
        } catch (e: Exception) {
            // Unexpected (serialization, OOM, engine bug) — otherwise it would go silently to the
            // SupervisorJob and the status stuck on Busy (eternal spinner). syncNow/restoreSession call this.
            _status.value = SyncStatus.Failed(SyncFailureReason.SyncFailed, e.message)
        }
    }

    // One sync attempt + the Online bookkeeping; exceptions propagate to runSyncLocked.
    private suspend fun runSyncAttempt(c: SyncClient, s: SyncSession) {
        val outcome = engineFactory(c).sync(s)
        _status.value = SyncStatus.Online(s.accountId, outcome.pushed, outcome.pulled)
        // Pulled records from the server → refresh list managers, else synced data isn't visible until reopen.
        if (outcome.pulled > 0) {
            refreshSyncSettings() // another device may have changed "what to sync"
            runCatching { onSynced() }
        }
    }

    /** What [refreshSessionLocked] did with the expired session. */
    private sealed interface RefreshResult {
        /** Tokens rotated (or already rotated by the other recovery path): retry the failed call. */
        data object Refreshed : RefreshResult

        /** Refresh token rejected: session torn down, status parked on Configured (password reauth). */
        data object Dead : RefreshResult

        /** Session vanished or was replaced while we ran: its new owner drives status — do nothing. */
        data object StandDown : RefreshResult

        /** The refresh attempt itself failed (network/protocol); session kept for a later retry. */
        data class Failed(val cause: Exception) : RefreshResult
    }

    /**
     * Rotate the tokens of the live session via its refresh token ([SyncClient.refresh]) after a 401.
     * Call with [syncMutex] held — every [session]/config writer shares it: [disconnect]'s null and
     * [activateSession]'s publish take syncMutex too, so a suspended refresh can't clobber a session
     * or config another path just wrote. [stale] is the session the caller failed with; if [session]
     * moved on since (a parallel connect, or the other of the two 401-recovery paths — sync and
     * watch — won the race), nothing is rotated ([RefreshResult.StandDown]). The identity is
     * re-checked after the suspending refresh as defense-in-depth for any future writer that
     * bypasses the mutex.
     *
     * A refresh rejected as UNAUTHORIZED/NOT_FOUND means the refresh token itself is dead (device
     * revoked, 30-day expiry, account gone): tear the session down — cancel the live subscriptions
     * (no join: the watch loop may be this very caller and exits on the nulled session by itself),
     * close the client (disconnect's "no live client" hygiene), drop the known-dead sealed token so
     * cold starts stop burning a refresh round trip on it — and park on [SyncStatus.Configured]: the
     * link survives and the user reconnects with the password, mirroring [restoreSession]'s fallback.
     */
    private suspend fun refreshSessionLocked(c: SyncClient, stale: SyncSession): RefreshResult {
        val current = session ?: return RefreshResult.StandDown
        if (current !== stale) return RefreshResult.Refreshed
        val fresh = try {
            c.refresh(stale)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SyncException) {
            if (e.kind == SyncException.Kind.UNAUTHORIZED || e.kind == SyncException.Kind.NOT_FOUND) {
                if (session !== stale) return RefreshResult.StandDown
                session = null
                watchJob?.cancel() // cancel only — join/replace stay under opMutex (activateSession/disconnect)
                pushJob?.cancel()
                runCatching { c.close() }
                client = null
                val cfg = configStore.load()
                if (cfg?.sealedRefreshToken != null) {
                    runCatching { configStore.save(cfg.copy(sealedRefreshToken = null)) }
                }
                _status.value = cfg?.let { SyncStatus.Configured(it.serverUrl, it.accountId) }
                    ?: SyncStatus.Disabled
                return RefreshResult.Dead
            }
            return RefreshResult.Failed(e)
        } catch (e: Exception) {
            return RefreshResult.Failed(e)
        }
        if (session !== stale) return RefreshResult.StandDown
        session = fresh
        resealRefreshToken(fresh)
        return RefreshResult.Refreshed
    }

    /**
     * keep-connected: re-seal the rotated refresh token into the config, or the next cold-start
     * [restoreSession] would run on a stale (eventually 30-day-expired) one. Best-effort: a locked
     * vault or a config write failure must not fail the recovery — the old sealed token stays valid
     * until its own expiry (the server's refresh tokens are stateless, not single-use).
     */
    private fun resealRefreshToken(fresh: SyncSession) {
        val cfg = configStore.load() ?: return
        if (!cfg.keepConnected) return
        val dk = vault.exportDataKey() ?: return
        try {
            runCatching { configStore.save(cfg.copy(sealedRefreshToken = tokens.seal(dk, fresh.refreshToken))) }
        } finally {
            dk.zeroize()
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
        if (session == null) return
        // cancel + join the old subscription before starting a new one (reconnect without disconnect):
        // otherwise the old collect could run runSync with the new session under the old cursor.
        watchJob?.cancel()
        watchJob?.join()
        watchJob = scope.launch {
            var backoff = WATCH_RETRY_MIN_MS
            while (true) {
                // Re-read the live session on every attempt: a mid-session token rotation (the 401
                // recovery in runSyncLocked, or the refresh below) must reach the next WS handshake —
                // a captured copy would reconnect with the dead access token forever, live-pull
                // silently gone while the status stays Online. A nulled session (dead refresh token,
                // disconnect is a cancel and never reaches here) ends the loop.
                val s = session ?: break
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
                    // WS dropped. A handshake 401 (access token expired while the socket was down)
                    // surfaces as an untyped Ktor error, indistinguishable from a network blip — so
                    // rotate the tokens best-effort before retrying. Cheap: rate-limited by the
                    // backoff, a no-op when another path already rotated, and a healthy-token rotate
                    // is harmless (server refresh tokens are stateless, the old one stays valid). A
                    // dead refresh token nulls the session — the re-read above ends the loop.
                    syncMutex.withLock { refreshSessionLocked(c, s) }
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
                // First cancel both live subscriptions and wait for them to stop, so a finishing runSync
                // can't write Online after the Disabled set below (status stuck on Online, client==null).
                stopSubscriptions()
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
     * Suspend live sync for the duration of a vault lock (from `onBeforeLock`, so it covers the manual
     * lock and both automatic ones): stop the WS live-pull and the local-push subscription. Everything
     * needed to come back — the client, the session, the saved link — is kept, so [resumeAfterUnlock]
     * doesn't ask for a password. This is NOT [disconnect]: that one erases the link.
     *
     * Without it both subscriptions keep running against a vault that can no longer decrypt: every WS
     * signal drives a [runSync] that throws inside the vault, the status parks on Failed, and the WS
     * retry loop keeps reconnecting on a 60s backoff for nothing. The health ping deliberately keeps
     * running (see [ServerHealthMonitor]) — reachability doesn't depend on the vault.
     */
    fun pauseForLock() {
        // Set synchronously, before the launch: [resumeAfterUnlock] clears it, so an unlock that beats
        // this coroutine to opMutex (a long connect holding the lock across lock+unlock) cancels the
        // pause instead of tearing down the session the resume just kept.
        lockPaused = true
        // No early return on a null session: a connect in flight holds opMutex and would publish its
        // subscriptions after the lock, with nothing left to stop them. Queueing behind it stops them.
        scope.launch {
            opMutex.withLock {
                if (!lockPaused || session == null) return@withLock
                stopSubscriptions()
                // The link is intact, only the live half is gone: Configured is exactly that state.
                configStore.load()?.let { _status.value = SyncStatus.Configured(it.serverUrl, it.accountId) }
            }
        }
    }

    /**
     * Bring sync back after the vault is unlocked. A session paused by [pauseForLock] is still in
     * memory — resubscribe and pull what was missed; otherwise (cold start) fall back to
     * [restoreSession]. Called from `onVaultUnlocked` on both platforms.
     */
    fun resumeAfterUnlock() {
        lockPaused = false
        if (session == null) {
            restoreSession()
            return
        }
        scope.launch {
            opMutex.withLock {
                // A disconnect or a fresh connect may have run while we waited for the mutex; a
                // reconnect has already restarted the subscriptions itself.
                if (lockPaused || session == null || watchJob != null) return@withLock
                runSync()
                startWatch()
                startLocalPush()
            }
        }
    }

    /**
     * Cancel and await both live subscriptions. cancel() only signals — a collect might be mid-[runSync],
     * and without the join its finishing cycle would write a status after the caller set its own.
     * Under [opMutex] like every other write to these fields.
     */
    private suspend fun stopSubscriptions() {
        watchJob?.cancel()
        pushJob?.cancel()
        watchJob?.join()
        pushJob?.join()
        watchJob = null
        pushJob = null
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
                    // A reconcile interrupted before it finished (pendingReconcile) must still run on this
                    // silent restore — refresh carries no `reactivated` signal, so the durable marker is the
                    // only thing that redoes it before the first push.
                    val reconcile = cfg.pendingReconcile
                    // refresh rotates the token — re-save it sealed under the dataKey (inside activation).
                    activateSession(
                        syncClient,
                        newSession,
                        cfg.copy(sealedRefreshToken = tokens.seal(dataKey, newSession.refreshToken)),
                        resetCursor = reconcile,
                        clearLocalRecords = reconcile,
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
