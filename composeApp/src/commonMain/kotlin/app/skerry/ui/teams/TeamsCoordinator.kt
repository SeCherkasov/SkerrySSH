package app.skerry.ui.teams

import app.skerry.shared.sync.SyncEngine
import app.skerry.shared.sync.SyncSession
import app.skerry.shared.sync.SyncSettings
import app.skerry.shared.sync.SyncSignal
import app.skerry.shared.sync.KeyedStateStore
import app.skerry.shared.sync.SyncStateStore
import app.skerry.shared.sync.SyncException
import app.skerry.shared.team.AccountIdentity
import app.skerry.shared.team.AccountKeys
import app.skerry.shared.team.ConfirmOutcome
import app.skerry.shared.team.PeerKeys
import app.skerry.shared.team.Pin
import app.skerry.shared.team.PinNotice
import app.skerry.shared.team.pinNotice
import app.skerry.shared.team.TeamActivityEntry
import app.skerry.shared.team.TeamClient
import app.skerry.shared.team.TeamIdentityStore
import app.skerry.shared.team.TeamInviteCodec
import app.skerry.shared.team.TeamInvitePayload
import app.skerry.shared.team.TeamKeyStore
import app.skerry.shared.team.TeamMember
import app.skerry.shared.team.TeamMemberStatus
import app.skerry.shared.team.TeamPeerStore
import app.skerry.shared.team.TeamRole
import app.skerry.shared.team.TeamScopeRef
import app.skerry.shared.team.TeamScopedSyncClient
import app.skerry.shared.team.TeamSessionKind
import app.skerry.shared.team.TeamSummary
import app.skerry.shared.team.TeamVaults
import app.skerry.shared.team.accountKeyFingerprint
import app.skerry.shared.team.checkPinned
import app.skerry.shared.team.fetchPinned
import app.skerry.shared.team.stripShareFields
import app.skerry.shared.vault.DataKey
import app.skerry.shared.vault.RecordType
import app.skerry.shared.vault.Vault
import app.skerry.shared.vault.VaultCrypto
import app.skerry.ui.sync.TeamLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import app.skerry.shared.terminal.epochMillis
import app.skerry.shared.host.VaultHostStore
import app.skerry.shared.runbook.VaultRunbookStore
import app.skerry.shared.snippet.VaultSnippetStore

/** Typed cause of a Teams operation failure (text in the UI layer, syncFailureText style). */
enum class TeamsFailure {
    NotConnected, VaultLocked, NoRecipientKey, RecipientKeyChanged, AlreadyInvited, NoSuchAccount,
    KeyMissing, Network, Protocol, Forbidden, VaultUnreadable,
    TooManyRequests, ServerError, AlreadyShared, ScopesUnsupported,

    /**
     * A peer's published key is not the fingerprint pinned for them — nothing was sealed to it
     * (#319). Either the key moved, or the pin itself can no longer be read on this device.
     */
    PeerKeyUnconfirmed,

    /**
     * A team or scope key arrived signed by an identity that is not the pinned one, and was ignored
     * (#319). Said once per pass over the keys, however many of them one pass steps over — and again
     * on the next pass, because the condition stands until the key is confirmed or withdrawn.
     */
    UnconfirmedKeyIgnored,

    /** Accept was reached without the inviter's fingerprint having been shown and confirmed (#319). */
    InviteUnverified,

    /**
     * The fingerprint the user just confirmed could not be recorded on this device, so nothing was
     * sealed to the key it belongs to (#319). Its own value rather than [PeerKeyUnconfirmed]: that
     * one tells a manager to re-invite the member, which is not something an invitee can do.
     */
    PinNotRecorded,

    /**
     * The record for the account moved while its fingerprint was on screen, so the confirmation was
     * not written (#323). Its own value rather than [PinNotRecorded]: nothing is wrong with this
     * device, the question simply has to be asked again against what is on record now — a pin that
     * moved costs an acknowledgement, and a gate decided against a record that has since changed is
     * no gate.
     */
    PinMovedMeanwhile,

    /**
     * This device cannot read the Teams identity an invite is sealed to — the record is gone (a
     * reactivation reconcile drops it and the re-pull has not landed) or no longer decrypts. A
     * separate value because the alternative is telling the user their colleague sent something
     * forged when nothing is wrong with the invite (#319).
     */
    IdentityUnreadable,
}

/**
 * Sync-client error → team-level failure. Same server as sync, so its rate limiter and its 5xx are
 * named here too instead of landing in the generic protocol bucket. A null kind (any non-sync
 * exception) is a protocol failure.
 */
internal fun SyncException.Kind?.toTeamsFailure(): TeamsFailure = when (this) {
    SyncException.Kind.NETWORK -> TeamsFailure.Network
    SyncException.Kind.UNAUTHORIZED, SyncException.Kind.FORBIDDEN -> TeamsFailure.Forbidden
    SyncException.Kind.NOT_FOUND -> TeamsFailure.NoSuchAccount
    SyncException.Kind.CONFLICT -> TeamsFailure.AlreadyInvited
    SyncException.Kind.TOO_MANY_REQUESTS -> TeamsFailure.TooManyRequests
    SyncException.Kind.SERVER_ERROR -> TeamsFailure.ServerError
    // GONE is a pairing-code state with no team-level meaning; PROTOCOL and null stay generic.
    SyncException.Kind.GONE, SyncException.Kind.PROTOCOL, null -> TeamsFailure.Protocol
}

/** A team as the UI sees it: server metadata + local key (the name lives in its vault / envelope). */
data class TeamUi(
    val id: String,
    val name: String,
    val ownerAccountId: String,
    val role: TeamRole,
    val status: TeamMemberStatus,
    val memberCount: Int,
    /** false for an active team = the key didn't arrive (or the envelope didn't open) — records inaccessible. */
    val hasKey: Boolean,
    /** Scopes of this team we may see: everything for a manager, our grants otherwise. */
    val scopes: List<TeamScopeUi> = emptyList(),
)

/**
 * Invite confirmation data: the invitee's key fingerprint is verified over voice/chat. Passing it
 * back to [TeamsCoordinator.invite] is what binds the send to the key that was read out loud — the
 * type exists so an invite cannot be sent without one.
 */
data class InvitePreview(
    val accountId: String,
    val fingerprint: String,
    /**
     * What this account already held for [accountId] when the fingerprint was read. Carried whole
     * rather than as a flag: whether the record on the other side of the comparison was confirmed by
     * a human or merely seen first decides what a screen may call it (#323).
     *
     * Required, not defaulted: the write this preview is passed to holds itself to this value, and
     * [Pin.None] is a positive claim that the ceremony saw an empty record. Defaulting to it would
     * let a caller that never read a pin pass the very gate that read exists for.
     */
    val pinned: Pin,
) {
    /**
     * The record does not simply agree with [fingerprint], so confirming replaces it and costs a
     * second, deliberate gesture. An identity that moved is either an honest rotation or the server
     * trying its luck, and only the person on the other end of the trusted channel can tell; a pin
     * this device cannot read asks the same question, having nothing to compare against.
     */
    val keyChanged: Boolean get() = pinNotice(pinned, fingerprint) != PinNotice.NOTHING
}

/** What a peer-key lookup answered: the fingerprint to confirm, or why it could not be fetched. */
sealed interface PeerKeyVerdict {
    /** The account's published key, fingerprinted, under whatever this device already holds for it. */
    data class Ready(val preview: InvitePreview) : PeerKeyVerdict

    /** The key could not be read — offline, no published key, a server that errored. Worth retrying. */
    data class Failed(val reason: TeamsFailure) : PeerKeyVerdict
}

/** What [TeamsCoordinator.acceptPreview] could establish about an invite. */
sealed interface InviteVerdict {
    /** Opened and verified: [preview] is the inviter and the fingerprint to confirm out of band. */
    data class Verified(val preview: InvitePreview) : InviteVerdict

    /** No envelope, or one that does not verify — forged, tampered with, or not addressed to us. */
    data object Unverified : InviteVerdict

    /**
     * The check could not be made — [reason] says why (offline, locked vault, server error). Worth
     * retrying, and carried on the verdict rather than written to [TeamsCoordinator.lastError]: the
     * banner is the one voice for this, and the error line is a second live region on the same screen.
     */
    data class Failed(val reason: TeamsFailure) : InviteVerdict
}

/**
 * Teams coordinator: ties [TeamClient] (network), the account vault (team keys and identity), per-team
 * vaults, and a team-scoped [SyncEngine]. All operations report [TeamsFailure] in [lastError] instead
 * of throwing (except CancellationException). Concurrency conventions as in SyncCoordinator: one
 * [opMutex] for mutations, [syncMutex] for sync cycles.
 *
 * A team's records live in **share spaces** ([TeamScopeRef]): the team itself, plus one per scope for
 * granular sharing. Everything about their keys and vaults is in [TeamSpaces]; this class
 * orchestrates.
 */
class TeamsCoordinator(
    /**
     * The live session and the team client it belongs to, as ONE value: two suppliers can be read either
     * side of a connect to another server, pairing one server's client with the other's session (#240).
     */
    private val live: () -> TeamLink?,
    private val vault: Vault,
    private val crypto: VaultCrypto,
    private val teamVaults: TeamVaults,
    private val teamState: SyncStateStore,
    /** Generator of client-side ids (teams and scopes) — a UUID in production. */
    private val newId: () -> String,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val onTeamsChanged: () -> Unit = {},
) {

    private val keyStore = TeamKeyStore(vault)
    private val identityStore = TeamIdentityStore(vault, crypto)
    private val peerStore = TeamPeerStore(vault)
    private val inviteCodec = TeamInviteCodec(crypto)

    private val spaces = TeamSpaces(
        keyStore = keyStore,
        teamVaults = teamVaults,
        crypto = crypto,
        inviteCodec = inviteCodec,
        accountVaultUnlocked = { vault.isUnlocked },
        markError = { markError(it) },
        syncSpace = { syncSpace(it) },
    )

    /** Which colleagues a lookup refused, and what has already been said about them (#326). */
    private val refusals = PeerRefusals()

    /**
     * The rows whose published key is not the one on record for them — what the member list draws
     * its refused mark from. A mark stands until the account's own key is looked at again, not until
     * the next operation empties the error slot: a rotation can refuse several colleagues at once,
     * and dealing with one of them says nothing about the others (#326).
     */
    val refusedPeers: StateFlow<Set<String>> get() = refusals.accounts

    /**
     * Every seal to another account resolves its keys through here: the fingerprint pinned for that
     * account, refusing a key that is not the confirmed one instead of trusting the server's latest
     * answer (#319). What to do with a refusal is the caller's — it answers something the user asked
     * for, and each caller knows which of its steps it belongs to.
     */
    private val sealingKeys: PeerKeyLookup = { s, c, accountId -> refusals.record(accountId, peerStore.fetchPinned(s, c, accountId)) }

    /**
     * The same check for keys arriving from the other side — a team rekey envelope, a scope grant.
     * Two differences, both because the account id here is one the server chose rather than one a
     * user typed: nothing is pinned on first sight (a pin written from here would let the server fix
     * its own key as the account's confirmed one), and each account+fingerprint is reported once per
     * pass — a team with a dozen scopes granted to the same refused account has one thing to say
     * about it, not a dozen.
     */
    private val adoptingKeys: PeerKeyLookup = { s, c, accountId ->
        refusals.record(accountId, peerStore.checkPinned(s, c, accountId)).also {
            if (it is PeerKeys.Unconfirmed && refusals.announcing(it)) markError(TeamsFailure.UnconfirmedKeyIgnored)
        }
    }

    private val opMutex = Mutex()
    private val syncMutex = Mutex()

    // Verified invites cached between acceptPreview (the banner) and accept (the button) so accepting
    // doesn't re-run the listTeams + fetchPublicKey round-trips openVerifiedInvite already did. Reusing
    // the *verified* payload is sound (its signature was checked) — indeed it's the exact envelope whose
    // fingerprint the user confirmed. A StateFlow (atomic updates) rather than a mutex-guarded map:
    // acceptPreview runs outside opMutex and lock() (not suspend) must clear it without racing.
    private val verifiedInvites = MutableStateFlow<Map<String, VerifiedInvite>>(emptyMap())

    private val _teams = MutableStateFlow<List<TeamUi>>(emptyList())
    val teams: StateFlow<List<TeamUi>> = _teams

    /**
     * Monotonic counter bumped on every actual change to a team space's contents: a pull brought
     * remote records ([syncSpace] with `pulled > 0`) or we shared/unshared a record
     * ([shareRecord]/[unshareRecord]). The shared-host UI sections read the team vault imperatively (not
     * via a records StateFlow), so without this signal a live-sync that pulled new records wouldn't
     * repaint the list: [_teams] doesn't change and the personal catalog (which the sections were tied
     * to indirectly) stays the same — Compose would skip recomposition. Sections key `remember` on this.
     *
     * Bump only on actual changes (not every [syncSpace]): [syncAll] on each Online transition runs all
     * spaces, and an unconditional ++ would invalidate the sections' `remember` (→ recompute
     * `VaultHostStore.all()` for all teams) even on an empty delta.
     */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    /**
     * When each team last completed a sync cycle, `teamId -> epoch millis`; absent until one does.
     * Per team rather than one stamp for the account: [syncAll] runs every team, and one of them
     * failing must not let the others' freshness vouch for it in the header pill.
     */
    private val _lastSyncedAt = MutableStateFlow<Map<String, Long>>(emptyMap())
    val lastSyncedAt: StateFlow<Map<String, Long>> = _lastSyncedAt

    private val _lastError = MutableStateFlow<TeamsFailure?>(null)
    val lastError: StateFlow<TeamsFailure?> = _lastError

    /**
     * Keys the last removal failed to rotate — the team key, plus one per scope the removed member
     * held (#324). [lastError] is one value and a removal can fail twice over, so the reason is
     * reported there and the extent here: the count is what says a second key the member still
     * holds also stayed put. Zero for a rotation that committed while stepping over a recipient —
     * that key did rotate.
     */
    private val _unrotatedKeys = MutableStateFlow(0)
    val unrotatedKeys: StateFlow<Int> = _unrotatedKeys

    /**
     * Empties the error slot — and with it the memory of what was already reported into it. The dedup
     * in [adoptingKeys] exists only so one refusal doesn't re-fire for every scope of one pass; the
     * slot is emptied at the start of every operation, and from there the warning has to be earnable
     * again, or the next pass over the same unconfirmed key would say nothing.
     */
    private fun resetError() {
        _lastError.value = null
        _unrotatedKeys.value = 0
        refusals.forgetAnnounced()
    }

    /**
     * Ask the account sync for a recovery full re-pull ([SyncCoordinator.recoverFullPull]): an active
     * team without a key means the TEAM record is lost to delta sync (an old client without Teams
     * skipped the unknown type while advancing the cursor — it won't come again). Late-bound like
     * teamsForSync: sync is created before teams.
     */
    var onKeyMissing: (() -> Unit)? = null

    /**
     * Whether this device may tell a team about our sessions on its shared hosts (Settings →
     * Security). Late-bound like [onKeyMissing]: the setting lives in the platform's UI state, which
     * is built after this coordinator.
     *
     * The gate belongs here rather than at each call site so it cannot drift between the platforms or
     * between the connect and the recording path — and so it sits beside the other privacy rule, that
     * a host of our own is reported nowhere at all ([spaceHoldingHost]).
     */
    var reportSessionsEnabled: () -> Boolean = { true }

    // Recover a key once per team per process: if it's also missing on the server, every refresh would
    // otherwise run a full re-pull for nothing.
    private val recoveryRequested = mutableSetOf<String>()

    /**
     * The connected server predates scopes: it answers 404 on `/teams/{id}/scopes` even for a team
     * we're an active member of. Remembered so scope operations can say "update the server" instead
     * of failing as "no such account" — and cleared as soon as a listing succeeds, so it can't stick
     * after the server is upgraded mid-session.
     */
    private var scopesUnsupported = false

    /**
     * The team-wide key held on this device, or `null` when it hasn't arrived (or the vault is
     * locked). Session sharing seals its frames with it — the relay never sees the plaintext.
     */
    fun teamKey(teamId: String): DataKey? = spaces.key(TeamScopeRef(teamId))

    /**
     * A team's directory of live shared sessions changed (someone started or stopped sharing).
     * Wired by the platform to the share directory controller, which re-reads the list — the
     * coordinator itself owns no share state.
     */
    var onSharesChanged: ((String) -> Unit)? = null

    /** Wire to SyncCoordinator's WS signals (`sync.onTeamSignal = teams::onSignal`). */
    fun onSignal(signal: SyncSignal) {
        when (signal) {
            is SyncSignal.Team -> scope.launch {
                // Cursor guard, like the account watch: our own echo doesn't run a redundant cycle.
                // The cursor is the team's, shared by all its spaces, so any space lagging behind it
                // has something to fetch.
                // One read of the link for the whole guard, like every other place that pairs a session
                // with the server it is on.
                val key = live()?.linkKey
                if (spacesOf(signal.teamId).any { signal.cursor > teamState.cursor(teamCursorKey(key, it)) }) syncTeam(signal.teamId)
            }
            is SyncSignal.Shares -> onSharesChanged?.invoke(signal.teamId)
            SyncSignal.Membership -> scope.launch { refresh() }
            is SyncSignal.Account -> Unit // the account channel is handled by SyncCoordinator
        }
    }

    /**
     * Call after an account sync cycle ([SyncCoordinator.onSynced]): TEAM records may have just arrived
     * in the personal vault (a team created/accepted on another device of this account). Without this,
     * "team key hasn't arrived" lingers until the screen is reopened, even when the key is already in
     * the vault. No-op while the UI shows no keyless team — don't hit the network on every live-sync cycle.
     */
    fun onAccountSynced() {
        if (!vault.isUnlocked) return
        val keyless = _teams.value.filter { !it.hasKey }
        if (keyless.isEmpty()) return
        val keys = keyStore.list()
        if (keyless.none { keys.containsKey(it.id) }) return
        scope.launch {
            refresh()
            syncAll() // freshly opened teams need their shared records pulled right away
        }
    }

    /** Fingerprint of the own identity (both public halves) — shown in the UI for verification. */
    fun ownFingerprint(): String? {
        if (!vault.isUnlocked) return null
        return runCatching {
            identityStore.ensure().let { accountKeyFingerprint(it.sharing.publicKey, it.signing.publicKey) }
        }.getOrNull()
    }

    /** Reread teams from the server, open active teams' vaults, publish identity on first login. */
    suspend fun refresh() {
        val (s, c) = live() ?: return markError(TeamsFailure.NotConnected)
        if (!vault.isUnlocked) return markError(TeamsFailure.VaultLocked)
        op {
            // Publish identity idempotently: without it we can't be invited to a team.
            val identity = publishIdentity(s, c)
            val remote = c.listTeams(s)
            adoptRotatedKeys(s, c, remote, identity)
            publishTeams(s, c, remote, identity)
            // Keys of teams we were removed from (or that were deleted) are no longer needed.
            val liveIds = remote.map { it.id }.toSet()
            keyStore.list().keys.filter { it !in liveIds }.forEach { gone -> forgetTeamLocally(gone) }
            // A cached invite of a vanished team holds teamKey material — drop it with the team.
            verifiedInvites.update { cached -> cached.filterKeys { it in liveIds } }
            onTeamsChanged()
            maybeRecoverKeys()
        }
    }

    suspend fun members(teamId: String): List<TeamMember> {
        val (s, c) = live() ?: return emptyList()
        return try {
            c.members(s, teamId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            markError(e.toFailure())
            emptyList()
        }
    }

    /** Create a team: id is client-side, teamKey is local; the server learns only the id. */
    suspend fun createTeam(name: String) {
        val (s, c) = live() ?: return markError(TeamsFailure.NotConnected)
        if (!vault.isUnlocked) return markError(TeamsFailure.VaultLocked)
        op {
            publishIdentity(s, c)
            val teamId = newId()
            c.createTeam(s, teamId)
            keyStore.put(teamId, name.ifBlank { teamId }, TeamRole.OWNER, crypto.newDataKey(), epoch = 0)
            refreshUnlocked(s, c)
        }
    }

    /**
     * The account's published key and what this device holds for it — the lookup behind both
     * ceremonies that show a fingerprint to a human: the invite dialog's first step, and the member
     * list's own confirmation of a colleague this account never invited (#323).
     *
     * The reason a lookup failed is the answer rather than a write to [lastError], because the
     * confirm dialog is a modal with a line of its own to say it on: routing it through the error
     * slot as well would make a screen reader say the same failure twice, on two live regions, the
     * way the invite banner used to (WCAG 4.1.3).
     */
    suspend fun peerKey(accountId: String): PeerKeyVerdict {
        val (s, c) = live() ?: return PeerKeyVerdict.Failed(TeamsFailure.NotConnected)
        return try {
            val keys = c.fetchPublicKey(s, accountId)
                ?: return PeerKeyVerdict.Failed(TeamsFailure.NoRecipientKey)
            val fingerprint = accountKeyFingerprint(keys.sharing, keys.signing)
            val pinned = peerStore.pins(listOf(accountId))[accountId] ?: Pin.None
            PeerKeyVerdict.Ready(InvitePreview(accountId, fingerprint, pinned))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PeerKeyVerdict.Failed(e.toFailure())
        }
    }

    /**
     * [peerKey] for the invite dialog, which has no line of its own and reports through [lastError]
     * like the rest of its steps.
     */
    suspend fun previewPeerKey(accountId: String): InvitePreview? = when (val verdict = peerKey(accountId)) {
        is PeerKeyVerdict.Ready -> verdict.preview
        is PeerKeyVerdict.Failed -> {
            markError(verdict.reason)
            null
        }
    }

    /**
     * Invite step 2: seal+sign teamKey+name to the key [verified] was taken from and create an
     * invite membership with role [role].
     *
     * The key is fetched again here (the preview is a UI state that may be minutes old), so it is
     * re-fingerprinted and compared against the one the user confirmed out of band. The server owns
     * the key table: answering the lookup with the invitee's real key and the send with one of its
     * own would otherwise seal the team key to the server, with the user having verified a
     * fingerprint that was never used (#316). A mismatch stops the send with
     * [TeamsFailure.RecipientKeyChanged] — which is also the right answer for the honest case, an
     * invitee who rotated their identity between the two steps.
     */
    suspend fun invite(teamId: String, verified: InvitePreview, role: TeamRole) {
        val (s, c) = live() ?: return markError(TeamsFailure.NotConnected)
        val entry = keyStore.get(teamId) ?: return markError(TeamsFailure.KeyMissing)
        val teamKey = entry.dataKey() ?: return markError(TeamsFailure.KeyMissing)
        val accountId = verified.accountId
        op {
            val identity = identityStore.ensure()
            val recipient = c.fetchPublicKey(s, accountId)
                ?: return@op markError(TeamsFailure.NoRecipientKey)
            if (accountKeyFingerprint(recipient.sharing, recipient.signing) != verified.fingerprint) {
                return@op markError(TeamsFailure.RecipientKeyChanged)
            }
            // Sign the envelope with our identity and bind it to (teamId, inviter=self, invitee, epoch):
            // a malicious server can neither forge the invite nor retarget it to another team/invitee.
            val envelope = inviteCodec.seal(
                recipientPublicKey = recipient.sharing,
                inviter = identity.signing,
                inviterId = s.accountId,
                inviteeId = accountId,
                teamId = teamId,
                teamKey = teamKey,
                teamName = entry.name,
                epoch = entry.epoch,
            )
            // The fingerprint was read out loud and the envelope is sealed to the key it belongs to:
            // pin it, so every later seal to this colleague (a scope grant, a rotation) is held to
            // the same key instead of trusting the server's next answer (#319).
            // Written before the send, not after it: a pin this device cannot write — the id is one
            // the server names, and another record may already hold it — must stop the seal rather
            // than surface once the team key has left.
            if (!vault.isUnlocked) return@op markError(TeamsFailure.VaultLocked)
            confirmShown(accountId, verified)?.let { return@op markError(it) }
            c.invite(s, teamId, accountId, role, envelope)
            refreshUnlocked(s, c)
        }
    }

    /**
     * Record [verified] as a fingerprint a human read out loud — the same ceremony the invite ends
     * with, for a colleague this account never invited (#323).
     *
     * Without it the only way to confirm an account was to invite it: a scope grant and a rotation
     * pin whatever the server answered on the first sight and show no fingerprint at all, so the
     * record could never say more than "this is what we saw first". Promoting one is what lets the
     * screens use the word "confirmed" about anything.
     *
     * The key is fetched again and re-fingerprinted here, exactly as [invite] does and for the same
     * reason: the preview is a UI state that may be minutes old, and the key table is the server's —
     * answering the lookup with the colleague's real key and this call with one of its own would
     * write a confirmation for a key nobody read (#316).
     */
    suspend fun confirmPeer(verified: InvitePreview) {
        val (s, c) = live() ?: return markError(TeamsFailure.NotConnected)
        if (!vault.isUnlocked) return markError(TeamsFailure.VaultLocked)
        op {
            val keys = c.fetchPublicKey(s, verified.accountId) ?: return@op markError(TeamsFailure.NoRecipientKey)
            if (accountKeyFingerprint(keys.sharing, keys.signing) != verified.fingerprint) {
                return@op markError(TeamsFailure.RecipientKeyChanged)
            }
            if (!vault.isUnlocked) return@op markError(TeamsFailure.VaultLocked)
            confirmShown(verified.accountId, verified)?.let { markError(it) }
        }
    }

    /**
     * Record the fingerprint [shown] carries as confirmed, held to the pin the ceremony was drawn
     * against. Answers the failure to report, or null when it is on record.
     *
     * One place because all three ceremonies — the invite send, the invite accept, the member list's
     * own confirm — end in the same write and owe the same two refusals. And it is the one write
     * that retires a refusal mark: the pin now names the key the server publishes.
     */
    private fun confirmShown(accountId: String, shown: InvitePreview): TeamsFailure? =
        when (peerStore.confirm(accountId, shown.fingerprint, shown.pinned)) {
            ConfirmOutcome.RECORDED -> null.also { refusals.settled(accountId) }
            ConfirmOutcome.MOVED -> TeamsFailure.PinMovedMeanwhile
            ConfirmOutcome.REFUSED -> TeamsFailure.PinNotRecorded
        }

    /**
     * What this account holds for each of [accountIds] — how the member table tells a fingerprint a
     * human confirmed from one the server was simply first to answer with (#323). One vault pass, so
     * every row of a paint agrees.
     */
    suspend fun peerPins(accountIds: Collection<String>): Map<String, Pin> = peerStore.pins(accountIds)

    /** Change a member's role (owner/admin; the server enforces anti-escalation). */
    suspend fun changeRole(teamId: String, accountId: String, role: TeamRole) {
        val (s, c) = live() ?: return markError(TeamsFailure.NotConnected)
        op {
            c.changeRole(s, teamId, accountId, role)
            refreshUnlocked(s, c)
        }
    }

    /** Team audit log (owner/admin); on error — [lastError] and an empty list. */
    suspend fun teamActivity(teamId: String): List<TeamActivityEntry> {
        val (s, c) = live() ?: return emptyList()
        return try {
            c.teamActivity(s, teamId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            markError(e.toFailure())
            emptyList()
        }
    }

    /**
     * Reports to the owning team that a session on shared host [hostId] just started — the session
     * half of the activity feed (see [TeamClient.reportSessionEvent]).
     *
     * Fire-and-forget and deliberately silent: a connection is already under way, the user asked for
     * nothing here, and the server may be older than the endpoint. Nothing at all is sent for a host
     * that isn't shared with a team — connecting to one's own hosts is private, and the feature would
     * be indefensible otherwise.
     */
    fun reportSessionOpened(hostId: String) = reportSession(hostId, TeamSessionKind.OPEN, null)

    /** Reports that a recording of a session on shared host [hostId] was saved. See [reportSessionOpened]. */
    fun reportSessionRecorded(hostId: String, durationSec: Long) =
        reportSession(hostId, TeamSessionKind.RECORD, durationSec.coerceAtLeast(0))

    private fun reportSession(hostId: String, kind: TeamSessionKind, durationSec: Long?) {
        val (s, c) = live() ?: return
        if (!reportSessionsEnabled()) return
        // Everything past this point runs off the caller's thread. This is called straight from a
        // Connect click, and finding the owning space means decrypting the account's team records and
        // opening each space's vault — on the UI thread that is a stutter on desktop and an ANR risk
        // on Android, the more so while a background sync holds the same vault's lock.
        scope.launch {
            if (!vault.isUnlocked) return@launch
            val ref = spaceHoldingHost(hostId) ?: return@launch
            try {
                c.reportSessionEvent(s, ref.teamId, hostId, kind, durationSec)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Best-effort by contract: never surfaced in lastError, never retried. A report that
                // didn't land leaves a gap in the feed, which is why the UI presents session events
                // as reported rather than as an authoritative record.
            }
        }
    }

    /**
     * The share space whose vault holds [hostId] as a live host, across every team we hold a key
     * for — or null when the host is ours alone. Read from the key store rather than [teams] so a
     * report works before the first refresh and while offline.
     */
    private fun spaceHoldingHost(hostId: String): TeamScopeRef? =
        keyStore.list().keys.asSequence()
            .flatMap { teamId -> spacesOf(teamId).asSequence() }
            .firstOrNull { ref ->
                spaces.vault(ref)?.records()?.any { it.id == hostId && it.type == RecordType.HOST && !it.deleted } == true
            }

    /** The signed-in account, for marking our own actions in the activity feed. */
    fun selfAccountId(): String? = live()?.session?.accountId

    /**
     * Names of the records shared in this team, per share space: `scopeId -> recordId -> name`
     * (team-wide space under an empty key). This is what makes the activity feed readable — the
     * server logs record ids and never learns a name, so the reader's own copy of each space is the
     * only place one can come from.
     *
     * A space we hold no key for contributes nothing, and neither does a record that has since been
     * unshared (its tombstone carries no payload) — the feed falls back to a short id for those.
     */
    fun sharedRecordNames(teamId: String): Map<String, Map<String, String>> =
        spacesOf(teamId).mapNotNull { ref ->
            val vault = spaces.vault(ref) ?: return@mapNotNull null
            val names = buildMap {
                VaultHostStore(vault).all().forEach { put(it.id, it.label) }
                VaultSnippetStore(vault).all().forEach { put(it.id, it.label) }
                VaultRunbookStore(vault).all().forEach { put(it.id, it.label) }
            }
            if (names.isEmpty()) null else ref.scopeId to names
        }.toMap()

    /**
     * Invite step (invitee side): open+verify the envelope and return the **verified inviter's**
     * account + fingerprint for out-of-band confirmation before accepting.
     *
     * The three answers are kept apart. [InviteVerdict.Unverified] is a statement about the envelope
     * — missing, forged, wrong team, not addressed to us — and it is permanent until a new one is
     * sent. [InviteVerdict.Failed] means the check did not happen: no connection, a locked vault, a
     * server that errored. Collapsing the second into the first tells the user their colleague sent
     * something forged because their Wi-Fi dropped; both refuse Accept, but only one is worth
     * retrying.
     *
     * The verdict IS the report — nothing here writes [lastError]. The banner that asked for it draws
     * the answer and announces it, and [TeamsErrorLine] is a second live region on the same screen:
     * routing the same event through both makes a screen reader say two unrelated sentences about one
     * thing, on the ordinary offline path (WCAG 4.1.3).
     */
    suspend fun acceptPreview(teamId: String): InviteVerdict {
        val (s, c) = live() ?: return InviteVerdict.Failed(TeamsFailure.NotConnected)
        if (!vault.isUnlocked) return InviteVerdict.Failed(TeamsFailure.VaultLocked)
        // Before the envelope, because the envelope cannot be opened without it: an identity this
        // device cannot read is a local condition, and folding it into the verdict below would
        // accuse the inviter of forging what they sent correctly.
        if (identityStore.load() == null) return InviteVerdict.Failed(TeamsFailure.IdentityUnreadable)
        return try {
            val verified = openVerifiedInvite(s, c, teamId) ?: return InviteVerdict.Unverified
            // The keys the signature was checked against, not a second fetch of the same account: the
            // server owns the key table, and answering the check with a key it forged the invite
            // under and the fingerprint with the real colleague's key is the whole attack (#319).
            InviteVerdict.Verified(verified.preview())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            InviteVerdict.Failed(e.toFailure())
        }
    }

    /** Accept an invite: open+verify the signed envelope, save the key at its epoch, pull records. */
    suspend fun accept(teamId: String) {
        val (s, c) = live() ?: return markError(TeamsFailure.NotConnected)
        if (!vault.isUnlocked) return markError(TeamsFailure.VaultLocked)
        op {
            // Only the invite the banner opened, verified and *showed*. Accepting without it used to
            // fall back to a fresh open whose only check is a signature against a server-supplied key,
            // which is no ceremony at all — and the button was live while the banner still resolved
            // and after it came back unverifiable (#319).
            val verified = cachedInvite(teamId) ?: return@op markError(TeamsFailure.InviteUnverified)
            val invite = verified.payload
            // The fingerprint on the banner was confirmed over a trusted channel: pin the inviter, so
            // the rotation envelopes they send later are held to the same identity. First, and the
            // join is abandoned if it cannot be written (another record holds the id the server chose
            // for this account): a membership whose later envelopes nothing guards is the state this
            // whole change exists to prevent.
            if (!vault.isUnlocked) return@op markError(TeamsFailure.VaultLocked)
            confirmShown(invite.inviterAccountId, verified.preview())?.let { return@op markError(it) }
            // Placeholder role: the server returns the actual role at refreshUnlocked (listTeams).
            keyStore.put(teamId, invite.teamName, TeamRole.VIEWER, invite.teamKey, invite.epoch)
            c.accept(s, teamId)
            verifiedInvites.update { it - teamId }
            refreshUnlocked(s, c)
        }
        syncTeam(teamId)
    }

    /** Decline an invite = remove own membership (the server envelope vanishes with it). */
    suspend fun decline(teamId: String) = leave(teamId)

    suspend fun leave(teamId: String) {
        val self = live()?.session?.accountId ?: return markError(TeamsFailure.NotConnected)
        removeMember(teamId, self)
    }

    suspend fun removeMember(teamId: String, accountId: String) {
        val (sess, c) = live() ?: return markError(TeamsFailure.NotConnected)
        op {
            // Which scopes the member holds has to be read before the removal: the server drops their
            // grants along with the membership, and afterwards there is no way to tell which keys they
            // walked away with.
            val heldScopes = if (accountId == sess.accountId) emptyList() else scopesHeldBy(sess, c, teamId, accountId)
            c.removeMember(sess, teamId, accountId)
            var teamRotation: TeamsFailure? = null
            var scopeRotation: TeamsFailure? = null
            // Keys the removed member walked away with because their rotation did not commit. Kept
            // beside the verdicts because the single error slot cannot hold two of them (#324).
            var unrotated = 0
            if (accountId == sess.accountId) {
                // Voluntary leave/decline: we can't rotate (we're gone). A remaining manager rotates.
                forgetTeamLocally(teamId)
            } else {
                // Removing someone else revokes their server ACL but not their copy of the keys. Rotate
                // so records shared after removal are encrypted under keys the removed member lacks
                // (forward secrecy against a leaked backup / compromised server). Best-effort: a rotation
                // failure still leaves the member removed — surfaced via lastError.
                // Guarded like the scopes below, and for the same reason: a failed team rotation
                // must not skip them. Unguarded it unwound past the loop, so one 5xx on the rekey
                // left the removed member holding every scope key they had — the exact hole the
                // rotation exists to close — with nothing to retry it.
                // A rotation also fails without throwing: a recipient whose key is not the confirmed
                // one is skipped while the rest of it commits. That verdict comes back as a return
                // value rather than through `lastError` — the slot is written by suspend functions
                // that hold no lock (the member and grant listings a screen keeps refreshing), so
                // reading it back here would report whichever of them landed last.
                try {
                    teamRotation = rotateTeamKey(sess, c, teamId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    teamRotation = e.toFailure()
                }
                if (teamRotation.keptByTheMember()) unrotated++
                // Each scope rotates independently: one failing must not leave the rest un-rotated,
                // since every skipped scope is a key the removed member still holds.
                heldScopes.forEach { scopeId ->
                    val failure = try {
                        rotateScopeKey(sess, c, TeamScopeRef(teamId, scopeId))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        e.toFailure()
                    }
                    if (failure.keptByTheMember()) unrotated++
                    scopeRotation = moreSerious(scopeRotation, failure)
                }
            }
            // Caught rather than allowed to unwind: the reread runs over the same network the
            // rotations just failed on, and a throw here would leave `op` reporting the reread while
            // the verdict below — and the count of keys the removed member kept — were never
            // published at all.
            val reread = failureOf { refreshUnlocked(sess, c) }
            // Reported after the reread, not before it: refreshUnlocked writes `lastError` of its own
            // (an unconfirmed key it steps over), so a verdict published first would be replaced by
            // the milder one. And folded rather than written twice: the team key is usually the more
            // serious of the two, but not when its own verdict is a rotation that committed while
            // skipping a recipient — a scope that did not rotate at all outranks that.
            val verdict = moreSerious(moreSerious(teamRotation, scopeRotation), reread)
            verdict?.let { markError(it, unrotated) }
        }
    }

    suspend fun deleteTeam(teamId: String) {
        val (sess, c) = live() ?: return markError(TeamsFailure.NotConnected)
        op {
            c.deleteTeam(sess, teamId)
            forgetTeamLocally(teamId)
            refreshUnlocked(sess, c)
        }
    }

    // --- scopes ---

    /** Create a scope with its own key: what is shared into it stays unreadable outside its grants. */
    suspend fun createScope(teamId: String, name: String) {
        val (s, c) = live() ?: return markError(TeamsFailure.NotConnected)
        if (!vault.isUnlocked) return markError(TeamsFailure.VaultLocked)
        if (scopesUnsupported) return markError(TeamsFailure.ScopesUnsupported)
        op {
            val identity = identityStore.ensure()
            val scopeId = newId()
            spaces.createScope(s, c, teamId, scopeId, name.ifBlank { scopeId }, identity)
            refreshUnlocked(s, c)
        }
    }

    suspend fun deleteScope(teamId: String, scopeId: String) {
        val (s, c) = live() ?: return markError(TeamsFailure.NotConnected)
        op {
            spaces.deleteScope(s, c, teamId, scopeId)
            refreshUnlocked(s, c)
        }
    }

    /** Give a team member access to a scope: its current key, sealed and signed to them. */
    suspend fun grantScope(teamId: String, scopeId: String, accountId: String) {
        val (s, c) = live() ?: return markError(TeamsFailure.NotConnected)
        if (!vault.isUnlocked) return markError(TeamsFailure.VaultLocked)
        if (scopesUnsupported) return markError(TeamsFailure.ScopesUnsupported)
        op {
            val recipient = when (val fetched = sealingKeys(s, c, accountId)) {
                is PeerKeys.Pinned -> TeamRecipient(accountId, fetched.keys)
                PeerKeys.Unpublished -> return@op markError(TeamsFailure.NoRecipientKey)
                // Nothing on this screen shows a fingerprint, so there is nothing for the user to
                // contradict: a member whose key moved is re-invited, and that ceremony confirms it.
                is PeerKeys.Unconfirmed -> return@op markError(TeamsFailure.PeerKeyUnconfirmed)
            }
            spaces.grantScope(s, c, TeamScopeRef(teamId, scopeId), recipient, identityStore.ensure())
            refreshUnlocked(s, c)
        }
    }

    /**
     * Take a member's scope access away and rotate the scope key: the ACL row is gone, but they keep
     * their copy of the old key, so anything shared afterwards must be under a new one.
     */
    suspend fun revokeScope(teamId: String, scopeId: String, accountId: String) {
        val (s, c) = live() ?: return markError(TeamsFailure.NotConnected)
        if (!vault.isUnlocked) return markError(TeamsFailure.VaultLocked)
        op {
            c.revokeScope(s, teamId, scopeId, accountId)
            var rotation: TeamsFailure? = null
            if (accountId == s.accountId) {
                spaces.forgetScope(teamId, scopeId) // gave up our own access: the local copy must go
            } else {
                rotation = rotateScopeKey(s, c, TeamScopeRef(teamId, scopeId))
            }
            refreshUnlocked(s, c)
            // After the reread, for the reason spelled out in removeMember: it reports on its own.
            rotation?.let { markError(it) }
        }
    }

    /**
     * Accounts holding a grant on the scope (managers only), or **null** when the list couldn't be
     * read — the failure is also surfaced in [lastError]. Null rather than an empty list on purpose:
     * "nobody has access" and "we don't know who has access" are different answers, and a screen that
     * renders the second as the first tells a manager the scope is unshared when it may not be.
     */
    suspend fun scopeGrants(teamId: String, scopeId: String): List<String>? {
        val (s, c) = live() ?: return null
        return try {
            c.scopeGrants(s, teamId, scopeId).map { it.accountId }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            markError(e.toFailure())
            null
        }
    }

    // --- records ---

    /** Vault of one share space (for shared-record stores in the UI); null — no key/vault locked. */
    fun spaceVault(ref: TeamScopeRef): Vault? = spaces.vault(ref)

    /**
     * Ids of records already shared into **any** space of the team, by type. A record belongs to
     * exactly one space (the server refuses to move one, see TeamRecordRepository.upsert), so the
     * share picker has to hide what is already shared elsewhere in the team — offering it again would
     * write a local copy the server then silently declines.
     */
    fun sharedRecordIds(teamId: String, type: RecordType): Set<String> =
        spacesOf(teamId).mapNotNull { spaces.vault(it) }
            .flatMapTo(mutableSetOf()) { space ->
                space.records().filter { it.type == type && !it.deleted }.map { it.id }
            }

    /**
     * Share an account-vault record with a team space: a copy of the decrypted payload is placed in
     * that space's vault under the same id. [stripFields] are fields meaningless outside the personal
     * workspace (e.g. a host's `groupId`). Returns false if the vault/record is inaccessible, or if
     * the record is already shared into another space of this team — the server keeps a record in the
     * space it was first shared into, so writing it here would be a local copy nobody else ever sees.
     * Moving a record between spaces is an unshare followed by a share.
     */
    suspend fun shareRecord(
        ref: TeamScopeRef,
        recordId: String,
        type: RecordType,
        stripFields: Set<String> = emptySet(),
    ): Boolean {
        val target = spaceVault(ref) ?: run { markError(TeamsFailure.KeyMissing); return false }
        val elsewhere = spacesOf(ref.teamId).filter { it != ref }
            .any { space -> spaces.vault(space)?.records()?.any { it.id == recordId && !it.deleted } == true }
        if (elsewhere) {
            markError(TeamsFailure.AlreadyShared)
            return false
        }
        val payload = runCatching { vault.openPayload(recordId) }.getOrNull() ?: return false
        val cleaned = stripShareFields(payload, stripFields)
        target.put(recordId, type, cleaned)
        _revision.value++ // local mutation: syncSpace below yields pulled==0 on our own record
        syncSpace(ref)
        return true
    }

    /** Remove a record from a team space (the tombstone reaches everyone holding that space's key). */
    suspend fun unshareRecord(ref: TeamScopeRef, recordId: String) {
        spaceVault(ref)?.remove(recordId) ?: return
        _revision.value++ // local mutation: syncSpace below yields pulled==0 on our own tombstone
        syncSpace(ref)
    }

    /** Sync one share space (scoped pull+push via the shared SyncEngine). */
    suspend fun syncSpace(ref: TeamScopeRef) {
        // The link is read ONCE, with the session and the client it belongs to: the cursor this cycle
        // advances has to be the one belonging to the server this cycle talked to. Re-reading it after the
        // lock — which suspends — files server A's progress under server B's key when a connect landed in
        // between, and B then skips records it never received (the shape of issues #240 and #242).
        val link = live() ?: return
        val (s, c) = link
        val spaceVault = spaces.vaultResettingStale(ref) ?: return
        syncMutex.withLock {
            try {
                // No device-local filter (issue #174): a space vault cannot hold a credential at
                // all — the share picker offers hosts, snippets and runbooks, and HOST_SHARE_STRIP
                // drops `credentialId` on the way in. Offering credentials to a team would have to
                // pass DeviceLocalRecords(spaceVault) here, or a file-backed ref would reach every
                // member's device.
                val engine = SyncEngine(
                    TeamScopedSyncClient(c, ref),
                    spaceVault,
                    KeyedStateStore(teamState, teamCursorKey(link.linkKey, ref)),
                    settings = { SyncSettings() },
                )
                val outcome = engine.sync(s)
                // Wake the shared-host UI sections (which read the vault imperatively, see [revision])
                // only when a pull actually brought remote records. Our own push doesn't count here:
                // local share/unshare bump revision explicitly, and a push-all with no incoming delta
                // doesn't change section contents.
                if (outcome.pulled > 0) _revision.value++
                _lastSyncedAt.update { it + (ref.teamId to epochMillis()) }
                onTeamsChanged()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                markError(e.toFailure())
            }
        }
    }

    /** Sync every space of one team: the team itself plus each scope we hold a key for. */
    suspend fun syncTeam(teamId: String) = spacesOf(teamId).forEach { syncSpace(it) }

    suspend fun syncAll() {
        _teams.value.filter { it.status == TeamMemberStatus.ACTIVE && it.hasKey }
            .forEach { syncTeam(it.id) }
    }

    /** Lock team vaults and drop what this account held (called on vault reset — another account). */
    fun lock() {
        teamVaults.lockAll()
        _teams.value = emptyList()
        verifiedInvites.value = emptyMap() // drop cached invite payloads (they hold teamKey material)
        // The refusal marks go with them: they are read off the flow rather than re-derived from a
        // vault pass, and the member listing of the next account is a single round trip that lands
        // before anything would empty them.
        refusals.clear()
    }

    // --- internals ---

    /** Spaces of a team whose key we hold: the team itself and each granted scope. */
    private fun spacesOf(teamId: String): List<TeamScopeRef> =
        listOf(TeamScopeRef(teamId)) + keyStore.scopes(teamId).keys.map { TeamScopeRef(teamId, it) }

    private fun forgetTeamLocally(teamId: String) {
        // Read the spaces first: removing the TEAM record takes the nested scope keys with it, and
        // their cursors would then never be cleared (a re-join would resume mid-stream and miss records).
        // Every link the space was ever synced on, not just the one live now: the removal that leads here
        // follows a network round trip, so the session can be gone or on another server by the time it
        // runs — and a tip left standing is one a later re-join resumes from, missing everything below it.
        val suffixes = spacesOf(teamId).map { it.key }
        val spaceKeys = teamState.keys().filter { key -> suffixes.any { key == it || key.endsWith("\u0000$it") } }
        keyStore.remove(teamId)
        teamVaults.resetTeam(teamId) // the team's vault and every scope vault under it
        spaceKeys.forEach { teamState.setCursor(it, 0) }
        verifiedInvites.update { it - teamId } // decline/leave: drop any cached invite for this team
        _lastSyncedAt.update { it - teamId }
    }

    /** refresh() without re-acquiring [opMutex] — for calls from inside op{} blocks. */
    private suspend fun refreshUnlocked(s: SyncSession, c: TeamClient) {
        val identity = identityStore.load()
        val remote = c.listTeams(s)
        if (identity != null) adoptRotatedKeys(s, c, remote, identity)
        publishTeams(s, c, remote, identity)
        onTeamsChanged()
        maybeRecoverKeys()
    }

    /** Publish own identity (both public halves) and return it (creating it if needed). */
    private suspend fun publishIdentity(s: SyncSession, c: TeamClient): AccountIdentity {
        val identity = identityStore.ensure()
        c.publishKey(s, identity.sharing.publicKey, identity.signing.publicKey)
        return identity
    }

    /** Map server summaries to [TeamUi]; the display name comes from the local key or the invite envelope. */
    private suspend fun publishTeams(s: SyncSession, c: TeamClient, remote: List<TeamSummary>, identity: AccountIdentity?) {
        val keys = keyStore.list()
        _teams.value = remote.map { t ->
            val entry = keys[t.id]
            val name = entry?.name ?: t.envelope?.let { env ->
                identity?.let { inviteCodec.open(it.sharing, env)?.teamName }
            } ?: t.id
            val scopes = if (t.status == TeamMemberStatus.ACTIVE) refreshScopes(s, c, t.id, identity) else emptyList()
            TeamUi(t.id, name, t.ownerAccountId, t.role, t.status, t.memberCount, entry != null, scopes)
        }
    }

    /**
     * Scopes of one team, tolerating a server that doesn't know about them: a self-hosted deployment
     * older than granular sharing answers 404, and losing the whole team list over an optional feature
     * would be a far worse trade (same reasoning as the trash-record push batch in SyncEngine).
     *
     * The two failure modes are kept apart. A 404 means "this server has no scopes" — the list is
     * genuinely empty and [scopesUnsupported] is raised so a later create/grant can explain itself.
     * Anything else (network blip, 5xx) says nothing about whether scopes exist, so the ones we
     * already know are kept on screen rather than blinking out, and the failure is surfaced.
     */
    private suspend fun refreshScopes(s: SyncSession, c: TeamClient, teamId: String, identity: AccountIdentity?): List<TeamScopeUi> =
        try {
            spaces.refreshScopes(s, c, teamId, identity?.let { SealingIdentity(it, adoptingKeys) })
                .also { scopesUnsupported = false }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if ((e as? SyncException)?.kind == SyncException.Kind.NOT_FOUND) {
                scopesUnsupported = true
                emptyList()
            } else {
                markError(e.toFailure())
                _teams.value.firstOrNull { it.id == teamId }?.scopes ?: emptyList()
            }
        }

    /**
     * Scope ids [accountId] holds in the team, read **before** the removal — the server drops their
     * grants along with the membership, and afterwards there is no way to tell which keys they walked
     * away with.
     *
     * Fails safe: if the grant lists can't be read, every scope we hold a key for is rotated instead
     * of none. Over-rotating costs the other members a re-pull; under-rotating would leave the removed
     * member with a live key, which is the thing this exists to prevent. Best-effort against one race:
     * a grant handed out from another device between this read and the removal isn't covered.
     */
    private suspend fun scopesHeldBy(s: SyncSession, c: TeamClient, teamId: String, accountId: String): List<String> =
        try {
            c.listScopes(s, teamId)
                .filter { scope -> c.scopeGrants(s, teamId, scope.scopeId).any { it.accountId == accountId } }
                .map { it.scopeId }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            keyStore.scopes(teamId).keys.toList()
        }

    /**
     * Adopt a rotated teamKey delivered by the server ([TeamSummary.keyEnvelope]): open+verify the
     * signed rekey envelope and, if its epoch is newer than the locally stored key, replace the key.
     * The stale local team-vault file (still under the old key) is dropped so the next sync re-pulls
     * the re-encrypted records. A forged/unverifiable envelope is ignored (the old key is kept).
     */
    private suspend fun adoptRotatedKeys(s: SyncSession, c: TeamClient, remote: List<TeamSummary>, identity: AccountIdentity) {
        val adopted = mutableListOf<String>()
        for (summary in remote) {
            val envelope = summary.keyEnvelope ?: continue
            val local = keyStore.get(summary.id) ?: continue
            val payload = inviteCodec.open(identity.sharing, envelope) ?: continue
            if (payload.teamId != summary.id || payload.inviteeAccountId != s.accountId) continue
            if (payload.epoch <= local.epoch) continue
            // Held to the pin: the envelope is signed with whatever key the server publishes for the
            // rotator, so an account whose fingerprint was verified once cannot be impersonated by a
            // key the server swapped in afterwards (#319).
            val rotatorKeys = when (val fetched = adoptingKeys(s, c, payload.inviterAccountId)) {
                is PeerKeys.Pinned -> fetched.keys
                PeerKeys.Unpublished -> continue
                is PeerKeys.Unconfirmed -> continue // reported by the lookup
            }
            if (!inviteCodec.verify(payload, rotatorKeys.signing)) continue
            keyStore.rekey(summary.id, payload.teamKey, payload.epoch)
            teamVaults.reset(TeamScopeRef(summary.id)) // old-key file is unreadable under the new key
            adopted += summary.id
        }
        // Re-pull the re-encrypted records under the freshly adopted key (the reset dropped the stale file).
        adopted.forEach { syncSpace(TeamScopeRef(it)) }
    }

    /** Rotate the team key (member removal). See [TeamSpaces.rotate] for the fail-closed contract. */
    private suspend fun rotateTeamKey(s: SyncSession, c: TeamClient, teamId: String): TeamsFailure? {
        val identity = identityStore.ensure()
        return spaces.rotate(
            s, c,
            RotationTarget(
                ref = TeamScopeRef(teamId),
                sealing = SealingIdentity(identity, sealingKeys),
                serverEpoch = { sess, cl -> cl.listTeams(sess).firstOrNull { it.id == teamId }?.keyEpoch },
                recipients = { sess, cl -> cl.members(sess, teamId).map { it.accountId } },
                commit = { sess, cl, epoch, envelopes -> cl.rekey(sess, teamId, epoch, envelopes) },
            ),
        )
    }

    /** Rotate one scope's key (grant revoked, or its holder removed from the team). */
    private suspend fun rotateScopeKey(s: SyncSession, c: TeamClient, ref: TeamScopeRef): TeamsFailure? {
        val identity = identityStore.ensure()
        return spaces.rotate(
            s, c,
            RotationTarget(
                ref = ref,
                sealing = SealingIdentity(identity, sealingKeys),
                serverEpoch = { sess, cl -> cl.listScopes(sess, ref.teamId).firstOrNull { it.scopeId == ref.scopeId }?.keyEpoch },
                recipients = { sess, cl -> cl.scopeGrants(sess, ref.teamId, ref.scopeId).map { it.accountId } },
                commit = { sess, cl, epoch, envelopes -> cl.rekeyScope(sess, ref.teamId, ref.scopeId, epoch, envelopes) },
            ),
        )
    }

    /**
     * A pending invite whose signature was checked, with the very keys it was checked against and
     * what was pinned for the inviter when the banner drew its fingerprint. [pinned] is read here
     * rather than at accept time: it is what decided whether the banner demanded an acknowledgement,
     * and the write that follows is held to it.
     */
    internal class VerifiedInvite(
        val payload: TeamInvitePayload,
        val inviterKeys: AccountKeys,
        val pinned: Pin,
    ) {
        fun preview() = InvitePreview(
            payload.inviterAccountId,
            accountKeyFingerprint(inviterKeys.sharing, inviterKeys.signing),
            pinned,
        )
    }

    /**
     * Open the invite envelope for [teamId] and verify the inviter's signature and binding. Returns
     * null if there's no pending envelope, it isn't ours, the team/invitee binding is wrong, or the
     * signature doesn't match the inviter's published key.
     */
    private suspend fun openVerifiedInvite(s: SyncSession, c: TeamClient, teamId: String): VerifiedInvite? {
        val summary = c.listTeams(s).firstOrNull { it.id == teamId } ?: return null
        val envelope = summary.envelope ?: return null
        val identity = identityStore.load() ?: return null
        val payload = inviteCodec.open(identity.sharing, envelope) ?: return null
        if (payload.teamId != teamId || payload.inviteeAccountId != s.accountId) return null
        val inviterKeys = c.fetchPublicKey(s, payload.inviterAccountId) ?: return null
        if (!inviteCodec.verify(payload, inviterKeys.signing)) return null
        // The pin is read here so the banner's fingerprint and the acknowledgement it may demand are
        // decided against one state, and the accept that follows is held to that same state.
        val pinned = peerStore.pins(listOf(payload.inviterAccountId))[payload.inviterAccountId] ?: Pin.None
        return VerifiedInvite(payload, inviterKeys, pinned).also { verified ->
            verifiedInvites.update { it + (teamId to verified) }
        }
    }

    internal fun cachedInvite(teamId: String): VerifiedInvite? = verifiedInvites.value[teamId]

    /**
     * Active team without a key → ask the account sync for a full re-pull once (per team per process):
     * the key may have been lost to delta sync permanently (see [onKeyMissing]). After the pull
     * [onAccountSynced] notices the arrived key and rereads teams.
     */
    private fun maybeRecoverKeys() {
        val lost = _teams.value.filter {
            it.status == TeamMemberStatus.ACTIVE && !it.hasKey && recoveryRequested.add(it.id)
        }
        if (lost.isNotEmpty()) onKeyMissing?.invoke()
    }

    private suspend fun op(block: suspend () -> Unit) {
        opMutex.withLock {
            _busy.value = true
            try {
                resetError()
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                markError(e.toFailure())
            } finally {
                _busy.value = false
            }
        }
    }

    /**
     * The more serious of two rotation verdicts, for the single error slot. A rotation that did not
     * commit outranks one that committed while leaving a recipient out: the first is the removed
     * member possibly still holding a live key, the second is one colleague to re-confirm.
     */
    private fun moreSerious(current: TeamsFailure?, next: TeamsFailure?): TeamsFailure? = when {
        current == null -> next
        next == null -> current
        current == TeamsFailure.PeerKeyUnconfirmed -> next
        else -> current
    }

    /**
     * Writes the error slot, and with it how many of the removed member's keys stayed put (#324 —
     * zero for everything that is not a removal). One writer for both, and the count first: the two
     * are read as one line, so a screen reader that catches the state between them would otherwise
     * announce the failure once without the count and again with it. Every path through here clears
     * a count from an earlier operation, including the guard clauses that never reach [op].
     */
    private fun markError(reason: TeamsFailure, unrotatedKeys: Int = 0) {
        _unrotatedKeys.value = unrotatedKeys
        _lastError.value = reason
    }

}

/**
 * Whether this verdict means the key never rotated, so the removed member still holds it.
 * [TeamsFailure.PeerKeyUnconfirmed] does not: that rotation committed, having stepped over a
 * recipient whose published key is not the pinned one — a colleague to re-confirm, not a key
 * that stayed put.
 */
private fun TeamsFailure?.keptByTheMember(): Boolean = this != null && this != TeamsFailure.PeerKeyUnconfirmed

private fun Exception.toFailure(): TeamsFailure = (this as? SyncException)?.kind.toTeamsFailure()

/**
 * Runs [block] and hands back what went wrong instead of throwing it — for the steps whose failure
 * is a verdict to report next to another one, not a reason to abandon what has already been decided.
 * Cancellation is not a failure and still propagates.
 */
private suspend fun failureOf(block: suspend () -> Unit): TeamsFailure? = try {
    block()
    null
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    e.toFailure()
}

/**
 * Where a team space's delta cursor is filed: the link this device is on, then the space.
 *
 * The space id alone is a key two servers can share — a team id is echoed by whichever server answers, so
 * two of them would share one cursor and the second one's first pull would start at the first one's tip
 * (issue #242, in the team store). With no live session there is nothing to sync anyway; the empty prefix
 * keeps this total and cannot collide with a real link key, which starts with the url's length.
 */
internal fun teamCursorKey(linkKey: String?, ref: TeamScopeRef): String = "${linkKey.orEmpty()}\u0000${ref.key}"
