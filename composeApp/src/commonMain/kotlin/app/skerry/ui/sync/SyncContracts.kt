package app.skerry.ui.sync

import app.skerry.shared.sync.SyncClient
import app.skerry.shared.sync.SyncEngine
import app.skerry.shared.sync.SyncOutcome
import app.skerry.shared.sync.SyncSession
import kotlinx.coroutines.cancel

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
     * The reactivation reconcile marker as versions up to 0.2.1 wrote it, when the intent lived on the
     * saved link itself. Read from an older config file and migrated into [ReconcileDebtStore] once, at
     * startup ([SyncCoordinator]'s init); never written again. A config holds ONE link, so a connect to
     * another server saved a config without the previous link's marker and the debt died with the
     * process — issue #170.
     */
    val legacyPendingReconcile: Boolean = false,
)

/**
 * One server link — what a reactivation reconcile debt belongs to. A LINK, not an account id: the same
 * user-chosen id names different accounts on a home and a work instance, and rebuilding the wrong one
 * throws away records nobody purged.
 */
data class ServerLink(val serverUrl: String, val accountId: String)

/**
 * Durable set of links this device owes a reactivation rebuild to — records dropped and re-pulled from
 * the server before it may push again.
 *
 * Separate from [SyncConfigStore] because the debt outlives the link it was learned on: the server
 * reports `reactivated` exactly once (the SRP verify that reports it is what clears the revocation), the
 * connect that heard it may fail before it reaches a session, and the config holds a single link — so a
 * connect to another server, or a [SyncCoordinator.disconnect], must not take the debt with it.
 */
interface ReconcileDebtStore {
    /**
     * The debts recorded on this device. Best-effort like [SyncConfigStore.load]: an unreadable store
     * yields an empty set. That direction is deliberate — the alternative (refuse to sync until the file
     * reads) is a dead end the user cannot resolve, since nothing but a reconcile retires a debt.
     */
    fun load(): Set<ServerLink>

    /** Replace the recorded debts. Throws if the write is refused — the caller must not go on as if it landed. */
    fun save(debts: Set<ServerLink>)
}

class InMemoryReconcileDebtStore : ReconcileDebtStore {
    private var debts: Set<ServerLink> = emptySet()
    override fun load(): Set<ServerLink> = debts
    override fun save(debts: Set<ServerLink>) { this.debts = debts }
}

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

/**
 * Outcome of a web-access change ([SyncCoordinator.setWebPassword], [SyncCoordinator.clearWebPassword]).
 * A discrete action result like [AccountPasswordChange], not a [SyncStatus] transition: this is one
 * settings card's action, and its failure must not overwrite the state of the sync session itself.
 */
sealed interface WebAccessChange {
    data object Success : WebAccessChange

    /**
     * No live session. The web password is account-level and travels over the app's own token, so
     * there is nothing to change while this device is merely linked or offline.
     */
    data object NotConnected : WebAccessChange

    /** The server refused or was unreachable ([reason] is localized in the UI; [detail] optional). */
    data class Failed(val reason: SyncFailureReason, val detail: String? = null) : WebAccessChange
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
    AccountKeyNotAdopted,  // the account's wrap didn't open under the account password — we'd sync unreadable records
    SaveSettingsFailed,    // sync settings didn't save (detail: cause)
    SyncFailed,            // sync cycle failure (detail: cause)
    RevokeFailed,          // device revoke failed (detail: cause)
    Rejected,              // the server refused on purpose (closed registration, blocked account id) — detail: its message
    // The instance refuses new accounts AND didn't accept the sign-in: either there is no such
    // account, or its password was rotated elsewhere and this vault still holds the old one. The two
    // are indistinguishable by design (see the anti-enumeration login), so both are named — detail:
    // the server's registration refusal.
    RegistrationRefusedSignInFailed,
    TooManyRequests,       // the server's rate limiter turned the request away — retrying later works
    ServerError,           // the server (or the proxy in front of it) is broken or restarting
    // This device still owes the reactivation rebuild ([ReconcileDebtStore]) and its vault was
    // never cleared, so every sync cycle is refused rather than push records the account purged. Redoing
    // the reconcile takes a reconnect, a keep-connected restore, or the unlock of a vault that was locked
    // when the clear ran — hence a named reason and not the silent Configured that reads as "vault locked".
    ReconcileRequired,
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
