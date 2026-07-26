package app.skerry.ui.teams

import app.skerry.shared.sync.SyncEngine
import app.skerry.shared.sync.SyncSession
import app.skerry.shared.sync.SyncSettings
import app.skerry.shared.sync.SyncSignal
import app.skerry.shared.sync.SyncStateStore
import app.skerry.shared.sync.SyncException
import app.skerry.shared.team.AccountIdentity
import app.skerry.shared.team.TeamActivityEntry
import app.skerry.shared.team.TeamClient
import app.skerry.shared.team.TeamInviteCodec
import app.skerry.shared.team.TeamInvitePayload
import app.skerry.shared.team.TeamKeyStore
import app.skerry.shared.team.TeamIdentityStore
import app.skerry.shared.team.TeamMember
import app.skerry.shared.team.TeamMemberStatus
import app.skerry.shared.team.TeamRole
import app.skerry.shared.team.TeamScopeRef
import app.skerry.shared.team.TeamScopedSyncClient
import app.skerry.shared.team.TeamSummary
import app.skerry.shared.team.TeamVaults
import app.skerry.shared.team.accountKeyFingerprint
import app.skerry.shared.vault.RecordType
import app.skerry.shared.vault.Vault
import app.skerry.shared.vault.VaultCrypto
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
import app.skerry.shared.team.stripShareFields

/** Typed cause of a Teams operation failure (text in the UI layer, syncFailureText style). */
enum class TeamsFailure {
    NotConnected, VaultLocked, NoRecipientKey, AlreadyInvited, NoSuchAccount,
    KeyMissing, Network, Protocol, Forbidden, VaultUnreadable,
    TooManyRequests, ServerError, AlreadyShared, ScopesUnsupported,
}

/**
 * Sync-client error → team-level failure. Same server as sync, so its rate limiter and its 5xx are
 * named here too instead of landing in the generic protocol bucket. A null kind (any non-sync
 * exception) is a protocol failure.
 */
internal fun SyncException.Kind?.toTeamsFailure(): TeamsFailure = when (this) {
    SyncException.Kind.NETWORK -> TeamsFailure.Network
    SyncException.Kind.UNAUTHORIZED -> TeamsFailure.Forbidden
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

/** Invite confirmation data: the invitee's key fingerprint is verified over voice/chat. */
data class InvitePreview(val accountId: String, val fingerprint: String)

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
    private val session: () -> SyncSession?,
    private val client: () -> TeamClient?,
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

    private val _lastError = MutableStateFlow<TeamsFailure?>(null)
    val lastError: StateFlow<TeamsFailure?> = _lastError

    fun clearError() {
        _lastError.value = null
    }

    /**
     * Ask the account sync for a recovery full re-pull ([SyncCoordinator.recoverFullPull]): an active
     * team without a key means the TEAM record is lost to delta sync (an old client without Teams
     * skipped the unknown type while advancing the cursor — it won't come again). Late-bound like
     * teamsForSync: sync is created before teams.
     */
    var onKeyMissing: (() -> Unit)? = null

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

    /** Wire to SyncCoordinator's WS signals (`sync.onTeamSignal = teams::onSignal`). */
    fun onSignal(signal: SyncSignal) {
        when (signal) {
            is SyncSignal.Team -> scope.launch {
                // Cursor guard, like the account watch: our own echo doesn't run a redundant cycle.
                // The cursor is the team's, shared by all its spaces, so any space lagging behind it
                // has something to fetch.
                if (spacesOf(signal.teamId).any { signal.cursor > teamState.cursor(it.key) }) syncTeam(signal.teamId)
            }
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
        val s = session() ?: return markError(TeamsFailure.NotConnected)
        val c = client() ?: return markError(TeamsFailure.NotConnected)
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
        val s = session() ?: return emptyList()
        val c = client() ?: return emptyList()
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
        val s = session() ?: return markError(TeamsFailure.NotConnected)
        val c = client() ?: return markError(TeamsFailure.NotConnected)
        if (!vault.isUnlocked) return markError(TeamsFailure.VaultLocked)
        op {
            publishIdentity(s, c)
            val teamId = newId()
            c.createTeam(s, teamId)
            keyStore.put(teamId, name.ifBlank { teamId }, TeamRole.OWNER, crypto.newDataKey(), epoch = 0)
            refreshUnlocked(s, c)
        }
    }

    /** Invite step 1: the invitee's key + fingerprint for verification over a trusted channel. */
    suspend fun previewInvite(accountId: String): InvitePreview? {
        val s = session() ?: run { markError(TeamsFailure.NotConnected); return null }
        val c = client() ?: run { markError(TeamsFailure.NotConnected); return null }
        return try {
            val keys = c.fetchPublicKey(s, accountId)
            if (keys == null) {
                markError(TeamsFailure.NoRecipientKey)
                null
            } else {
                InvitePreview(accountId, accountKeyFingerprint(keys.sharing, keys.signing))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            markError(e.toFailure())
            null
        }
    }

    /** Invite step 2: seal+sign teamKey+name to the invitee's key and create an invite membership with role [role]. */
    suspend fun invite(teamId: String, accountId: String, role: TeamRole) {
        val s = session() ?: return markError(TeamsFailure.NotConnected)
        val c = client() ?: return markError(TeamsFailure.NotConnected)
        val entry = keyStore.get(teamId) ?: return markError(TeamsFailure.KeyMissing)
        val teamKey = entry.dataKey() ?: return markError(TeamsFailure.KeyMissing)
        op {
            val identity = identityStore.ensure()
            val recipient = c.fetchPublicKey(s, accountId)
                ?: return@op markError(TeamsFailure.NoRecipientKey)
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
            c.invite(s, teamId, accountId, role, envelope)
            refreshUnlocked(s, c)
        }
    }

    /** Change a member's role (owner/admin; the server enforces anti-escalation). */
    suspend fun changeRole(teamId: String, accountId: String, role: TeamRole) {
        val s = session() ?: return markError(TeamsFailure.NotConnected)
        val c = client() ?: return markError(TeamsFailure.NotConnected)
        op {
            c.changeRole(s, teamId, accountId, role)
            refreshUnlocked(s, c)
        }
    }

    /** Team audit log (owner/admin); on error — [lastError] and an empty list. */
    suspend fun teamActivity(teamId: String): List<TeamActivityEntry> {
        val s = session() ?: return emptyList()
        val c = client() ?: return emptyList()
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
     * Invite step (invitee side): open+verify the envelope and return the **verified inviter's**
     * account + fingerprint for out-of-band confirmation before accepting. null if the envelope is
     * missing/forged (signature invalid, wrong team, or not addressed to us).
     */
    suspend fun acceptPreview(teamId: String): InvitePreview? {
        val s = session() ?: run { markError(TeamsFailure.NotConnected); return null }
        val c = client() ?: run { markError(TeamsFailure.NotConnected); return null }
        if (!vault.isUnlocked) { markError(TeamsFailure.VaultLocked); return null }
        return try {
            val verified = openVerifiedInvite(s, c, teamId) ?: run { markError(TeamsFailure.KeyMissing); return null }
            val inviterKeys = c.fetchPublicKey(s, verified.payload.inviterAccountId)
                ?: run { markError(TeamsFailure.NoRecipientKey); return null }
            InvitePreview(verified.payload.inviterAccountId, accountKeyFingerprint(inviterKeys.sharing, inviterKeys.signing))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            markError(e.toFailure())
            null
        }
    }

    /** Accept an invite: open+verify the signed envelope, save the key at its epoch, pull records. */
    suspend fun accept(teamId: String) {
        val s = session() ?: return markError(TeamsFailure.NotConnected)
        val c = client() ?: return markError(TeamsFailure.NotConnected)
        if (!vault.isUnlocked) return markError(TeamsFailure.VaultLocked)
        op {
            // Reuse the invite acceptPreview already opened+verified (no second listTeams/fetchPublicKey),
            // falling back to a fresh open if the banner didn't run. Either way the signature was checked:
            // a server-fabricated invite to a fake team is rejected even if the user skipped the fingerprint.
            val verified = cachedInvite(teamId) ?: openVerifiedInvite(s, c, teamId)
                ?: return@op markError(TeamsFailure.Forbidden)
            val invite = verified.payload
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
        val self = session()?.accountId ?: return markError(TeamsFailure.NotConnected)
        removeMember(teamId, self)
    }

    suspend fun removeMember(teamId: String, accountId: String) {
        val sess = session() ?: return markError(TeamsFailure.NotConnected)
        val c = client() ?: return markError(TeamsFailure.NotConnected)
        op {
            // Which scopes the member holds has to be read before the removal: the server drops their
            // grants along with the membership, and afterwards there is no way to tell which keys they
            // walked away with.
            val heldScopes = if (accountId == sess.accountId) emptyList() else scopesHeldBy(sess, c, teamId, accountId)
            c.removeMember(sess, teamId, accountId)
            if (accountId == sess.accountId) {
                // Voluntary leave/decline: we can't rotate (we're gone). A remaining manager rotates.
                forgetTeamLocally(teamId)
            } else {
                // Removing someone else revokes their server ACL but not their copy of the keys. Rotate
                // so records shared after removal are encrypted under keys the removed member lacks
                // (forward secrecy against a leaked backup / compromised server). Best-effort: a rotation
                // failure still leaves the member removed — surfaced via lastError.
                rotateTeamKey(sess, c, teamId)
                // Each scope rotates independently: one failing must not leave the rest un-rotated,
                // since every skipped scope is a key the removed member still holds.
                heldScopes.forEach { scopeId ->
                    try {
                        rotateScopeKey(sess, c, TeamScopeRef(teamId, scopeId))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        markError(e.toFailure())
                    }
                }
            }
            refreshUnlocked(sess, c)
        }
    }

    suspend fun deleteTeam(teamId: String) {
        val sess = session() ?: return markError(TeamsFailure.NotConnected)
        val c = client() ?: return markError(TeamsFailure.NotConnected)
        op {
            c.deleteTeam(sess, teamId)
            forgetTeamLocally(teamId)
            refreshUnlocked(sess, c)
        }
    }

    // --- scopes ---

    /** Create a scope with its own key: what is shared into it stays unreadable outside its grants. */
    suspend fun createScope(teamId: String, name: String) {
        val s = session() ?: return markError(TeamsFailure.NotConnected)
        val c = client() ?: return markError(TeamsFailure.NotConnected)
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
        val s = session() ?: return markError(TeamsFailure.NotConnected)
        val c = client() ?: return markError(TeamsFailure.NotConnected)
        op {
            spaces.deleteScope(s, c, teamId, scopeId)
            refreshUnlocked(s, c)
        }
    }

    /** Give a team member access to a scope: its current key, sealed and signed to them. */
    suspend fun grantScope(teamId: String, scopeId: String, accountId: String) {
        val s = session() ?: return markError(TeamsFailure.NotConnected)
        val c = client() ?: return markError(TeamsFailure.NotConnected)
        if (!vault.isUnlocked) return markError(TeamsFailure.VaultLocked)
        if (scopesUnsupported) return markError(TeamsFailure.ScopesUnsupported)
        op {
            spaces.grantScope(s, c, teamId, scopeId, accountId, identityStore.ensure())
            refreshUnlocked(s, c)
        }
    }

    /**
     * Take a member's scope access away and rotate the scope key: the ACL row is gone, but they keep
     * their copy of the old key, so anything shared afterwards must be under a new one.
     */
    suspend fun revokeScope(teamId: String, scopeId: String, accountId: String) {
        val s = session() ?: return markError(TeamsFailure.NotConnected)
        val c = client() ?: return markError(TeamsFailure.NotConnected)
        if (!vault.isUnlocked) return markError(TeamsFailure.VaultLocked)
        op {
            c.revokeScope(s, teamId, scopeId, accountId)
            if (accountId == s.accountId) {
                spaces.forgetScope(teamId, scopeId) // gave up our own access: the local copy must go
            } else {
                rotateScopeKey(s, c, TeamScopeRef(teamId, scopeId))
            }
            refreshUnlocked(s, c)
        }
    }

    /** Accounts holding a grant on the scope (managers only); empty list plus [lastError] on failure. */
    suspend fun scopeGrants(teamId: String, scopeId: String): List<String> {
        val s = session() ?: return emptyList()
        val c = client() ?: return emptyList()
        return try {
            c.scopeGrants(s, teamId, scopeId).map { it.accountId }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            markError(e.toFailure())
            emptyList()
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
        val s = session() ?: return
        val c = client() ?: return
        val spaceVault = spaces.vaultResettingStale(ref) ?: return
        syncMutex.withLock {
            try {
                val engine = SyncEngine(
                    TeamScopedSyncClient(c, ref),
                    spaceVault,
                    KeyedStateStore(teamState, ref.key),
                    settings = { SyncSettings() },
                )
                val outcome = engine.sync(s)
                // Wake the shared-host UI sections (which read the vault imperatively, see [revision])
                // only when a pull actually brought remote records. Our own push doesn't count here:
                // local share/unshare bump revision explicitly, and a push-all with no incoming delta
                // doesn't change section contents.
                if (outcome.pulled > 0) _revision.value++
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

    /** Lock team vaults (called when the account vault locks — team keys become inaccessible). */
    fun lock() {
        teamVaults.lockAll()
        _teams.value = emptyList()
        verifiedInvites.value = emptyMap() // drop cached invite payloads (they hold teamKey material)
    }

    // --- internals ---

    /** Spaces of a team whose key we hold: the team itself and each granted scope. */
    private fun spacesOf(teamId: String): List<TeamScopeRef> =
        listOf(TeamScopeRef(teamId)) + keyStore.scopes(teamId).keys.map { TeamScopeRef(teamId, it) }

    private fun forgetTeamLocally(teamId: String) {
        // Read the spaces first: removing the TEAM record takes the nested scope keys with it, and
        // their cursors would then never be cleared (a re-join would resume mid-stream and miss records).
        val spaceKeys = spacesOf(teamId).map { it.key }
        keyStore.remove(teamId)
        teamVaults.resetTeam(teamId) // the team's vault and every scope vault under it
        spaceKeys.forEach { teamState.setCursor(it, 0) }
        verifiedInvites.update { it - teamId } // decline/leave: drop any cached invite for this team
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
            spaces.refreshScopes(s, c, teamId, identity).also { scopesUnsupported = false }
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
            val rotatorKeys = c.fetchPublicKey(s, payload.inviterAccountId) ?: continue
            if (!inviteCodec.verify(payload, rotatorKeys.signing)) continue
            keyStore.rekey(summary.id, payload.teamKey, payload.epoch)
            teamVaults.reset(TeamScopeRef(summary.id)) // old-key file is unreadable under the new key
            adopted += summary.id
        }
        // Re-pull the re-encrypted records under the freshly adopted key (the reset dropped the stale file).
        adopted.forEach { syncSpace(TeamScopeRef(it)) }
    }

    /** Rotate the team key (member removal). See [TeamSpaces.rotate] for the fail-closed contract. */
    private suspend fun rotateTeamKey(s: SyncSession, c: TeamClient, teamId: String) {
        val identity = identityStore.ensure()
        spaces.rotate(
            s, c,
            RotationTarget(
                ref = TeamScopeRef(teamId),
                identity = identity,
                serverEpoch = { sess, cl -> cl.listTeams(sess).firstOrNull { it.id == teamId }?.keyEpoch },
                recipients = { sess, cl -> cl.members(sess, teamId).map { it.accountId } },
                commit = { sess, cl, epoch, envelopes -> cl.rekey(sess, teamId, epoch, envelopes) },
            ),
        )
    }

    /** Rotate one scope's key (grant revoked, or its holder removed from the team). */
    private suspend fun rotateScopeKey(s: SyncSession, c: TeamClient, ref: TeamScopeRef) {
        val identity = identityStore.ensure()
        spaces.rotate(
            s, c,
            RotationTarget(
                ref = ref,
                identity = identity,
                serverEpoch = { sess, cl -> cl.listScopes(sess, ref.teamId).firstOrNull { it.scopeId == ref.scopeId }?.keyEpoch },
                recipients = { sess, cl -> cl.scopeGrants(sess, ref.teamId, ref.scopeId).map { it.accountId } },
                commit = { sess, cl, epoch, envelopes -> cl.rekeyScope(sess, ref.teamId, ref.scopeId, epoch, envelopes) },
            ),
        )
    }

    internal class VerifiedInvite(val payload: TeamInvitePayload)

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
        return VerifiedInvite(payload).also { verified ->
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
                _lastError.value = null
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

    private fun markError(reason: TeamsFailure) {
        _lastError.value = reason
    }

    private fun Exception.toFailure(): TeamsFailure = (this as? SyncException)?.kind.toTeamsFailure()
}

/** [SyncStateStore] with a fixed key — so SyncEngine keeps a per-space cursor. */
private class KeyedStateStore(
    private val backing: SyncStateStore,
    private val key: String,
) : SyncStateStore {
    override fun cursor(accountId: String): Long = backing.cursor(key)
    override fun setCursor(accountId: String, cursor: Long) = backing.setCursor(key, cursor)
}
