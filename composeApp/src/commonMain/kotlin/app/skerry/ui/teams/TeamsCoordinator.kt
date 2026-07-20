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
import app.skerry.shared.team.TeamScopedSyncClient
import app.skerry.shared.team.TeamSummary
import app.skerry.shared.team.TeamVaults
import app.skerry.shared.team.accountKeyFingerprint
import app.skerry.shared.vault.DataKey
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
)

/** Invite confirmation data: the invitee's key fingerprint is verified over voice/chat. */
data class InvitePreview(val accountId: String, val fingerprint: String)

/**
 * Teams coordinator: ties [TeamClient] (network), the account vault (team keys and identity), per-team
 * vaults, and a team-scoped [SyncEngine]. All operations report [TeamsFailure] in [lastError] instead
 * of throwing (except CancellationException). Concurrency conventions as in SyncCoordinator: one
 * [opMutex] for mutations, [syncMutex] for sync cycles.
 */
class TeamsCoordinator(
    private val session: () -> SyncSession?,
    private val client: () -> TeamClient?,
    private val vault: Vault,
    private val crypto: VaultCrypto,
    private val teamVaults: TeamVaults,
    private val teamState: SyncStateStore,
    private val newTeamId: () -> String,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val onTeamsChanged: () -> Unit = {},
) {

    private val keyStore = TeamKeyStore(vault)
    private val identityStore = TeamIdentityStore(vault, crypto)
    private val inviteCodec = TeamInviteCodec(crypto)

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
     * Monotonic counter bumped on every actual change to team-vault contents: a pull brought remote
     * records ([syncTeam] with `pulled > 0`) or we shared/unshared a record
     * ([shareRecord]/[unshareRecord]). The shared-host UI sections read the team vault imperatively (not
     * via a records StateFlow), so without this signal a live-sync that pulled new records wouldn't
     * repaint the list: [_teams] doesn't change and the personal catalog (which the sections were tied
     * to indirectly) stays the same — Compose would skip recomposition. Sections key `remember` on this.
     *
     * Bump only on actual changes (not every [syncTeam]): [syncAll] on each Online transition runs all
     * teams, and an unconditional ++ would invalidate the sections' `remember` (→ recompute
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

    /** Wire to SyncCoordinator's WS signals (`sync.onTeamSignal = teams::onSignal`). */
    fun onSignal(signal: SyncSignal) {
        when (signal) {
            is SyncSignal.Team -> scope.launch {
                // Cursor guard, like the account watch: our own echo doesn't run a redundant cycle.
                if (signal.cursor > teamState.cursor(cursorKey(signal.teamId))) syncTeam(signal.teamId)
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
            publishTeams(remote, identity)
            // Keys of teams we were removed from (or that were deleted) are no longer needed.
            val liveIds = remote.map { it.id }.toSet()
            keyStore.list().keys.filter { it !in liveIds }.forEach { gone ->
                keyStore.remove(gone)
                teamVaults.reset(gone)
            }
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
            val teamId = newTeamId()
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
            c.removeMember(sess, teamId, accountId)
            if (accountId == sess.accountId) {
                // Voluntary leave/decline: we can't rotate (we're gone). A remaining manager rotates.
                forgetTeamLocally(teamId)
            } else {
                // Removing someone else revokes their server ACL but not their copy of teamKey. Rotate
                // so records shared after removal are encrypted under a key the removed member lacks
                // (forward secrecy against a leaked backup / compromised server). Best-effort: a rotation
                // failure still leaves the member removed — surfaced via lastError.
                rotateTeamKey(sess, c, teamId)
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

    /** Team vault (for shared-record stores in the UI); null — no key/not active/vault locked. */
    fun teamVault(teamId: String): Vault? {
        if (!vault.isUnlocked) return null
        val key = keyStore.get(teamId)?.dataKey() ?: return null
        return teamVaults.open(teamId, key)
    }

    /**
     * Share an account-vault record with a team: a copy of the decrypted payload is placed in the team
     * vault under the same id. [stripFields] are fields meaningless outside the personal workspace
     * (e.g. a host's `groupId`). Returns false if the vault/record is inaccessible.
     */
    suspend fun shareRecord(
        teamId: String,
        recordId: String,
        type: RecordType,
        stripFields: Set<String> = emptySet(),
    ): Boolean {
        val target = teamVault(teamId) ?: run { markError(TeamsFailure.KeyMissing); return false }
        val payload = runCatching { vault.openPayload(recordId) }.getOrNull() ?: return false
        val cleaned = stripShareFields(payload, stripFields)
        target.put(recordId, type, cleaned)
        _revision.value++ // local mutation: syncTeam below yields pulled==0 on our own record
        syncTeam(teamId)
        return true
    }

    /** Remove a record from a team (the tombstone reaches all members). */
    suspend fun unshareRecord(teamId: String, recordId: String) {
        teamVault(teamId)?.remove(recordId) ?: return
        _revision.value++ // local mutation: syncTeam below yields pulled==0 on our own tombstone
        syncTeam(teamId)
    }

    /** Sync one team (team-scoped pull+push via the shared SyncEngine). */
    suspend fun syncTeam(teamId: String) {
        val s = session() ?: return
        val c = client() ?: return
        val teamVault = openTeamVaultResettingStale(teamId) ?: return
        syncMutex.withLock {
            try {
                val engine = SyncEngine(
                    TeamScopedSyncClient(c, teamId),
                    teamVault,
                    KeyedStateStore(teamState, cursorKey(teamId)),
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

    private fun forgetTeamLocally(teamId: String) {
        keyStore.remove(teamId)
        teamVaults.reset(teamId)
        teamState.setCursor(cursorKey(teamId), 0)
        verifiedInvites.update { it - teamId } // decline/leave: drop any cached invite for this team
    }

    /** refresh() without re-acquiring [opMutex] — for calls from inside op{} blocks. */
    private suspend fun refreshUnlocked(s: SyncSession, c: TeamClient) {
        val identity = identityStore.load()
        val remote = c.listTeams(s)
        if (identity != null) adoptRotatedKeys(s, c, remote, identity)
        publishTeams(remote, identity)
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
    private fun publishTeams(remote: List<TeamSummary>, identity: AccountIdentity?) {
        val keys = keyStore.list()
        _teams.value = remote.map { t ->
            val entry = keys[t.id]
            val name = entry?.name ?: t.envelope?.let { env ->
                identity?.let { inviteCodec.open(it.sharing, env)?.teamName }
            } ?: t.id
            TeamUi(t.id, name, t.ownerAccountId, t.role, t.status, t.memberCount, entry != null)
        }
    }

    /**
     * Adopt a rotated teamKey delivered by the server ([TeamSummary.keyEnvelope]): open+verify the
     * signed rekey envelope and, if its epoch is newer than the locally stored key, replace the key.
     * The stale local team-vault file (still under the old key) is dropped so [syncTeam] re-pulls the
     * re-encrypted records. A forged/unverifiable envelope is ignored (the old key is kept).
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
            teamVaults.reset(summary.id) // old-key file is unreadable under the new key — rebuild on pull
            adopted += summary.id
        }
        // Re-pull the re-encrypted records under the freshly adopted key (the reset dropped the stale file).
        adopted.forEach { syncTeam(it) }
    }

    /**
     * Rotate the teamKey after a member removal: generate a fresh key, re-seal it (signed) to every
     * remaining member, bump the server epoch, then re-encrypt local records under the new key so they
     * win LWW and overwrite the server's old-key copies.
     *
     * Fails closed: re-encrypting local records is mandatory (otherwise the server keeps old-key blobs
     * while members adopt the new key, leaving every shared record unreadable for everyone). So the
     * team vault is opened under the CURRENT key *before* the server epoch is bumped — if it can't be
     * opened (vault locked, or the on-disk file is already under a superseded key), rotation aborts
     * before touching the server: the old key stays authoritative and records stay readable. The next
     * epoch is derived from the server's epoch, not the (possibly stale) local one, so a lagging device
     * doesn't 409 while the member is already removed; a genuine concurrent rotation is retried a
     * bounded number of times.
     */
    private suspend fun rotateTeamKey(s: SyncSession, c: TeamClient, teamId: String) {
        val entry = keyStore.get(teamId) ?: return
        val oldKey = entry.dataKey() ?: return markError(TeamsFailure.KeyMissing)
        // Open under the current key up front (before keyStore.rekey swaps it) — the returned vault is
        // what we re-encrypt after the server accepts the rotation. Abort here means nothing changed.
        val teamVault = openTeamVault(teamId, oldKey) ?: return markError(TeamsFailure.KeyMissing)
        val identity = identityStore.ensure()
        var attempt = 0
        while (true) {
            val serverEpoch = c.listTeams(s).firstOrNull { it.id == teamId }?.keyEpoch
                ?: return markError(TeamsFailure.KeyMissing)
            val newEpoch = (serverEpoch + 1).toInt()
            val newKey = crypto.newDataKey()
            val envelopes = mutableMapOf<String, ByteArray>()
            for (member in c.members(s, teamId)) {
                if (member.accountId == s.accountId) continue // we adopt the key locally, no self-envelope
                val keys = c.fetchPublicKey(s, member.accountId) ?: continue // unpublished key: can't re-seal
                envelopes[member.accountId] = inviteCodec.seal(
                    recipientPublicKey = keys.sharing,
                    inviter = identity.signing,
                    inviterId = s.accountId,
                    inviteeId = member.accountId,
                    teamId = teamId,
                    teamKey = newKey,
                    teamName = entry.name,
                    epoch = newEpoch,
                )
            }
            try {
                c.rekey(s, teamId, newEpoch.toLong(), envelopes)
            } catch (e: SyncException) {
                newKey.zeroize() // rotation didn't commit — don't leave the unused key dangling
                // A stale-epoch conflict means someone else rotated meanwhile: refetch the epoch and
                // retry (their rotation may already cover the removal, but ours re-encrypts our local
                // records). Give up after a few tries and surface — the member stays removed regardless.
                if (e.kind == SyncException.Kind.CONFLICT && attempt++ < REKEY_MAX_ATTEMPTS) continue
                throw e
            }
            // Server bumped and envelopes delivered. Commit locally: store the new key (keyStore.rekey
            // base64-copies it) and re-encrypt records (version+1 wins LWW, overwriting the server's
            // old-key copies), then push. rekeyRecords takes ownership of newKey.
            keyStore.rekey(teamId, newKey, newEpoch)
            teamVault.rekeyRecords(newKey)
            syncTeam(teamId)
            return
        }
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

    /** Open the team vault under [key] (no stale-file handling — used where the exact key is known). */
    private fun openTeamVault(teamId: String, key: DataKey): Vault? {
        if (!vault.isUnlocked) return null
        return teamVaults.open(teamId, key)
    }

    /**
     * Open the team vault under the current key, rebuilding it ONLY if the on-disk file is under a
     * superseded key (a rotation adopted on another device left the local file stale). A structurally
     * unreadable file is left in place and surfaced — resetting it would silently drop any local
     * records that were never pushed. Used by sync paths, not by UI reads.
     */
    private fun openTeamVaultResettingStale(teamId: String): Vault? {
        if (!vault.isUnlocked) return null
        val key = keyStore.get(teamId)?.dataKey() ?: return null
        return when (val r = teamVaults.openOrClassify(teamId, key)) {
            is TeamVaults.OpenResult.Opened -> r.vault
            TeamVaults.OpenResult.StaleKey -> {
                teamVaults.reset(teamId) // old-key file → drop it and rebuild empty; syncTeam re-pulls
                teamVaults.open(teamId, key)
            }
            TeamVaults.OpenResult.Unreadable -> {
                markError(TeamsFailure.VaultUnreadable) // don't destroy a corrupt-but-maybe-unpushed file
                null
            }
        }
    }

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

    private fun Exception.toFailure(): TeamsFailure = when ((this as? SyncException)?.kind) {
        SyncException.Kind.NETWORK -> TeamsFailure.Network
        SyncException.Kind.UNAUTHORIZED -> TeamsFailure.Forbidden
        SyncException.Kind.NOT_FOUND -> TeamsFailure.NoSuchAccount
        SyncException.Kind.CONFLICT -> TeamsFailure.AlreadyInvited
        else -> TeamsFailure.Protocol
    }

    private companion object {
        /** Bounded retries when a rotation loses the epoch race to a concurrent rotation. */
        const val REKEY_MAX_ATTEMPTS = 3
        fun cursorKey(teamId: String) = "team:$teamId"
    }
}

/** [SyncStateStore] with a fixed key — so SyncEngine keeps a per-team cursor. */
private class KeyedStateStore(
    private val backing: SyncStateStore,
    private val key: String,
) : SyncStateStore {
    override fun cursor(accountId: String): Long = backing.cursor(key)
    override fun setCursor(accountId: String, cursor: Long) = backing.setCursor(key, cursor)
}

