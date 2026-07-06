package app.skerry.ui.teams

import app.skerry.shared.sync.SyncEngine
import app.skerry.shared.sync.SyncSession
import app.skerry.shared.sync.SyncSettings
import app.skerry.shared.sync.SyncSignal
import app.skerry.shared.sync.SyncStateStore
import app.skerry.shared.sync.SyncException
import app.skerry.shared.team.TeamActivityEntry
import app.skerry.shared.team.TeamClient
import app.skerry.shared.team.TeamInviteCodec
import app.skerry.shared.team.TeamKeyStore
import app.skerry.shared.team.TeamIdentityStore
import app.skerry.shared.team.TeamMember
import app.skerry.shared.team.TeamMemberStatus
import app.skerry.shared.team.TeamRole
import app.skerry.shared.team.TeamScopedSyncClient
import app.skerry.shared.team.TeamVaults
import app.skerry.shared.team.sharingKeyFingerprint
import app.skerry.shared.vault.RecordType
import app.skerry.shared.vault.Vault
import app.skerry.shared.vault.VaultCrypto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import app.skerry.shared.team.stripShareFields

/** Typed cause of a Teams operation failure (text in the UI layer, syncFailureText style). */
enum class TeamsFailure {
    NotConnected, VaultLocked, NoRecipientKey, AlreadyInvited, NoSuchAccount,
    KeyMissing, Network, Protocol, Forbidden,
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

    /** Fingerprint of the own public half — shown in the UI for verification when inviting. */
    fun ownFingerprint(): String? {
        if (!vault.isUnlocked) return null
        return runCatching { sharingKeyFingerprint(identityStore.ensure().publicKey) }.getOrNull()
    }

    /** Reread teams from the server, open active teams' vaults, publish identity on first login. */
    suspend fun refresh() {
        val s = session() ?: return markError(TeamsFailure.NotConnected)
        val c = client() ?: return markError(TeamsFailure.NotConnected)
        if (!vault.isUnlocked) return markError(TeamsFailure.VaultLocked)
        op {
            // Publish identity idempotently: without it we can't be invited to a team.
            c.publishKey(s, identityStore.ensure().publicKey)
            val remote = c.listTeams(s)
            val keys = keyStore.list()
            _teams.value = remote.map { t ->
                val entry = keys[t.id]
                val name = entry?.name ?: t.envelope?.let { env ->
                    identityStore.load()?.let { id -> inviteCodec.open(id, env)?.teamName }
                } ?: t.id
                TeamUi(t.id, name, t.ownerAccountId, t.role, t.status, t.memberCount, entry != null)
            }
            // Keys of teams we were removed from (or that were deleted) are no longer needed.
            val liveIds = remote.map { it.id }.toSet()
            keys.keys.filter { it !in liveIds }.forEach { gone ->
                keyStore.remove(gone)
                teamVaults.reset(gone)
            }
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
            c.publishKey(s, identityStore.ensure().publicKey)
            val teamId = newTeamId()
            c.createTeam(s, teamId)
            keyStore.put(teamId, name.ifBlank { teamId }, TeamRole.OWNER, crypto.newDataKey())
            refreshUnlocked(s, c)
        }
    }

    /** Invite step 1: the invitee's key + fingerprint for verification over a trusted channel. */
    suspend fun previewInvite(accountId: String): InvitePreview? {
        val s = session() ?: run { markError(TeamsFailure.NotConnected); return null }
        val c = client() ?: run { markError(TeamsFailure.NotConnected); return null }
        return try {
            val key = c.fetchPublicKey(s, accountId)
            if (key == null) {
                markError(TeamsFailure.NoRecipientKey)
                null
            } else {
                InvitePreview(accountId, sharingKeyFingerprint(key))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            markError(e.toFailure())
            null
        }
    }

    /** Invite step 2: seal teamKey+name to the invitee's key and create an invite membership with role [role]. */
    suspend fun invite(teamId: String, accountId: String, role: TeamRole) {
        val s = session() ?: return markError(TeamsFailure.NotConnected)
        val c = client() ?: return markError(TeamsFailure.NotConnected)
        val entry = keyStore.get(teamId) ?: return markError(TeamsFailure.KeyMissing)
        val teamKey = entry.dataKey() ?: return markError(TeamsFailure.KeyMissing)
        op {
            val recipientKey = c.fetchPublicKey(s, accountId)
                ?: return@op markError(TeamsFailure.NoRecipientKey)
            c.invite(s, teamId, accountId, role, inviteCodec.seal(recipientKey, teamKey, entry.name))
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

    /** Accept an invite: open the envelope with own identity, save the key, pull records. */
    suspend fun accept(teamId: String) {
        val s = session() ?: return markError(TeamsFailure.NotConnected)
        val c = client() ?: return markError(TeamsFailure.NotConnected)
        if (!vault.isUnlocked) return markError(TeamsFailure.VaultLocked)
        op {
            val summary = c.listTeams(s).firstOrNull { it.id == teamId }
                ?: return@op markError(TeamsFailure.Protocol)
            val envelope = summary.envelope ?: return@op markError(TeamsFailure.KeyMissing)
            val identity = identityStore.load() ?: return@op markError(TeamsFailure.KeyMissing)
            val invite = inviteCodec.open(identity, envelope)
                ?: return@op markError(TeamsFailure.KeyMissing)
            // Placeholder role: the server returns the actual role at refreshUnlocked (listTeams).
            keyStore.put(teamId, invite.teamName, TeamRole.VIEWER, invite.teamKey)
            c.accept(s, teamId)
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
            if (accountId == sess.accountId) forgetTeamLocally(teamId)
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
        val teamVault = teamVault(teamId) ?: return
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
    }

    // --- internals ---

    private fun forgetTeamLocally(teamId: String) {
        keyStore.remove(teamId)
        teamVaults.reset(teamId)
        teamState.setCursor(cursorKey(teamId), 0)
    }

    /** refresh() without re-acquiring [opMutex] — for calls from inside op{} blocks. */
    private suspend fun refreshUnlocked(s: SyncSession, c: TeamClient) {
        val remote = c.listTeams(s)
        val keys = keyStore.list()
        _teams.value = remote.map { t ->
            val entry = keys[t.id]
            val name = entry?.name ?: t.envelope?.let { env ->
                identityStore.load()?.let { id -> inviteCodec.open(id, env)?.teamName }
            } ?: t.id
            TeamUi(t.id, name, t.ownerAccountId, t.role, t.status, t.memberCount, entry != null)
        }
        onTeamsChanged()
        maybeRecoverKeys()
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

