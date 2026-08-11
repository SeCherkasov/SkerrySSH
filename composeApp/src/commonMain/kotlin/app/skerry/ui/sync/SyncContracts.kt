package app.skerry.ui.sync

import app.skerry.shared.share.SessionShareClient
import app.skerry.shared.sync.SyncClient
import app.skerry.shared.sync.SyncEngine
import app.skerry.shared.sync.SyncOutcome
import app.skerry.shared.sync.SyncSession
import app.skerry.shared.team.TeamClient
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
 * One server link — what a reactivation reconcile debt and the delta cursor belong to. A LINK, not an
 * account id: the same user-chosen id names different accounts on a home and a work instance, and
 * rebuilding the wrong one throws away records nobody purged, while sharing a cursor between them skips
 * everything below the other's tip.
 *
 * Identity is over the CANONICAL url ([canonicalServerUrl]), not the string as typed. `disconnect` erases
 * the saved link, so the next connect is typed by hand with nothing to prefill it: with a raw-string
 * identity, `https://work.test`, `https://Work.test` and `https://work.test:443` are three links for one
 * server — and a rebuild owed under one spelling is not owed under another, which is exactly how a device
 * pushes back what the account purged (issue #243).
 */
class ServerLink(serverUrl: String, val accountId: String) {

    /** The typed url reduced to its canonical spelling — what this link is compared and filed under. */
    val serverUrl: String = canonicalServerUrl(serverUrl)

    /**
     * Key for per-link state kept in a store that takes a plain string ([app.skerry.shared.sync.SyncStateStore]).
     *
     * The url's length precedes it, so where one half ends is stated rather than inferred from a separator:
     * the account id is not always this user's own typing — a device that joins by pairing learns it from
     * the server's answer — and a key that rests on a character being impossible is only as sound as that
     * assumption. With the length there, no pair of halves can be read as another pair whatever either half
     * holds, and no legacy key (a bare account id) can collide with one, which is what makes the cursor a
     * fresh one after the update rather than a wrongly inherited one.
     */
    val cursorKey: String get() = "${serverUrl.length}\u0000$serverUrl\u0000$accountId"

    override fun equals(other: Any?): Boolean =
        this === other || (other is ServerLink && other.serverUrl == serverUrl && other.accountId == accountId)

    override fun hashCode(): Int = 31 * serverUrl.hashCode() + accountId.hashCode()

    override fun toString(): String = "ServerLink($serverUrl, $accountId)"
}

/**
 * The spelling two typings of the same server both reduce to: trimmed, scheme and host lowercased, user
 * info dropped, the scheme's default port dropped, a bare trailing slash dropped.
 *
 * Nothing a server may legitimately distinguish is touched — a path, a query, a non-default port, the case
 * of a path — and a string that is not an `scheme://` url at all is only trimmed: identity has to be total,
 * and inventing a shape for a string that has none would fuse two links that are not the same server.
 */
internal fun canonicalServerUrl(raw: String): String {
    val url = raw.trim()
    val schemeEnd = url.indexOf("://")
    if (schemeEnd <= 0) return url
    val scheme = url.substring(0, schemeEnd).lowercase()
    val rest = url.substring(schemeEnd + 3)
    val pathStart = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
    val authority = if (pathStart < 0) rest else rest.substring(0, pathStart)
    val tail = if (pathStart < 0) "" else rest.substring(pathStart)
    // Only a bare "/" is no path: a server is free to route /sync and /sync/ apart.
    return scheme + "://" + canonicalAuthority(scheme, authority) + if (tail == "/") "" else tail
}

/**
 * Case-folds the host, drops the DNS root's trailing dot on a multi-label name, drops user info, and drops
 * the port when it is the scheme's default.
 *
 * User info goes because it is not part of the request: this client authenticates with a bearer token and
 * never sends it, so `https://ada@work.test` and `https://work.test` reach the same server — and keeping
 * them apart would mean a rebuild owed to that server is not owed under a spelling that differs only by a
 * prefix nobody transmits.
 */
private fun canonicalAuthority(scheme: String, authority: String): String {
    // Whatever precedes the last '@' is user info; it is dropped, so nothing of it survives into identity.
    val hostPort = authority.substring(authority.lastIndexOf('@') + 1)
    // The last colon is a port separator only past the end of an IPv6 literal's brackets.
    val colon = hostPort.lastIndexOf(':')
    val portStart = if (colon > hostPort.lastIndexOf(']')) colon else -1
    val host = (if (portStart < 0) hostPort else hostPort.substring(0, portStart))
        .lowercase()
        // `work.test.` is `work.test` spelled from the DNS root; every resolver treats them as one host.
        // A single label is not that case: with a `search` list, `sync.` is absolute and `sync` is not,
        // so they can be two different machines.
        .let { if (it.endsWith('.') && it.dropLast(1).contains('.')) it.dropLast(1) else it }
    // Numeric, so `:0443` cannot be a second spelling of the default port.
    val port = if (portStart < 0) "" else hostPort.substring(portStart + 1).let { it.toIntOrNull()?.toString() ?: it }
    val default = when (scheme) {
        "https" -> "443"
        "http" -> "80"
        else -> ""
    }
    return host + if (port.isEmpty() || port == default) "" else ":$port"
}

/**
 * A live session together with the client it belongs to. Handed out as one value ([SyncCoordinator.currentTeamLink],
 * [SyncCoordinator.currentShareLink]) because two separate reads can straddle a connect to another server
 * and pair one server's client with the other's session — which sends the account's token to a server that
 * was never meant to see it (issue #240).
 */
data class TeamLink(
    val session: SyncSession,
    val client: TeamClient,
    /**
     * [ServerLink.cursorKey] of the link this session is on. Carried here rather than fetched separately
     * for the same reason the client is: a team space's cursor is a position in ONE server's history, and
     * a team id is echoed by whichever server answers, so filing a space's cursor under the id alone lets
     * two servers share it — issue #242, one store over.
     */
    val linkKey: String,
)

/** The same pairing for the session-sharing relay — see [TeamLink]. */
data class ShareLink(val session: SyncSession, val client: SessionShareClient)

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
     * A connect or a disconnect moved this device to another link while the dialog was open, so the
     * account this rotation was for is no longer the one the device is on. Nothing was changed —
     * rotating the captured link would have changed the password of an account the device has left,
     * and saved that link back over the live session's (issue #241).
     */
    data object LinkMoved : AccountPasswordChange

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
