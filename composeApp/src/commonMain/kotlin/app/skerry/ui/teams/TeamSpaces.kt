package app.skerry.ui.teams

import app.skerry.shared.sync.SyncException
import app.skerry.shared.sync.SyncSession
import app.skerry.shared.team.AccountIdentity
import app.skerry.shared.team.TeamClient
import app.skerry.shared.team.TeamInviteCodec
import app.skerry.shared.team.TeamKeyStore
import app.skerry.shared.team.TeamScopeRef
import app.skerry.shared.team.TeamVaults
import app.skerry.shared.vault.DataKey
import app.skerry.shared.vault.Vault
import app.skerry.shared.vault.VaultCrypto

/** A scope as the UI sees it: the server's metadata plus whether its key actually reached us. */
data class TeamScopeUi(
    val id: String,
    val name: String,
    val memberCount: Int,
    /** false = we're listed as a manager but hold no key, so the scope's records stay unreadable. */
    val hasKey: Boolean,
)

/**
 * Everything about a team's **share spaces** — the team itself and each of its scopes: where their
 * keys live, how their vaults open, how a key is rotated, and how a scope's sealed key is adopted
 * from the server.
 *
 * The team and a scope are the same thing at this level (see [TeamScopeRef]), which is the point:
 * key rotation and envelope verification exist once and are parameterized by the space, instead of
 * a scope growing a parallel copy of the team's machinery.
 */
internal class TeamSpaces(
    private val keyStore: TeamKeyStore,
    private val teamVaults: TeamVaults,
    private val crypto: VaultCrypto,
    private val inviteCodec: TeamInviteCodec,
    private val accountVaultUnlocked: () -> Boolean,
    private val markError: (TeamsFailure) -> Unit,
    private val syncSpace: suspend (TeamScopeRef) -> Unit,
) {

    // --- keys ---

    /** The space's key, or null if we don't hold it (never granted, or the local record lost it). */
    fun key(ref: TeamScopeRef): DataKey? =
        if (ref.isTeamWide) keyStore.get(ref.teamId)?.dataKey()
        else keyStore.scope(ref.teamId, ref.scopeId)?.dataKey()

    fun epoch(ref: TeamScopeRef): Int =
        if (ref.isTeamWide) keyStore.get(ref.teamId)?.epoch ?: 0
        else keyStore.scope(ref.teamId, ref.scopeId)?.epoch ?: 0

    fun name(ref: TeamScopeRef): String =
        (if (ref.isTeamWide) keyStore.get(ref.teamId)?.name else keyStore.scope(ref.teamId, ref.scopeId)?.name)
            ?: ref.key

    private fun storeKey(ref: TeamScopeRef, key: DataKey, epoch: Int) {
        if (ref.isTeamWide) keyStore.rekey(ref.teamId, key, epoch)
        else keyStore.rekeyScope(ref.teamId, ref.scopeId, key, epoch)
    }

    // --- vaults ---

    /** The space's vault; null when the account vault is locked or the key isn't here. */
    fun vault(ref: TeamScopeRef): Vault? {
        if (!accountVaultUnlocked()) return null
        val key = key(ref) ?: return null
        return teamVaults.open(ref, key)
    }

    /**
     * Like [vault] but rebuilds the file if it is under a **superseded** key (a rotation adopted on
     * another device). A structurally unreadable file is left alone and surfaced — resetting it
     * would silently drop local records that were never pushed. Used by sync paths, not UI reads.
     */
    fun vaultResettingStale(ref: TeamScopeRef): Vault? {
        if (!accountVaultUnlocked()) return null
        val key = key(ref) ?: return null
        return when (val r = teamVaults.openOrClassify(ref, key)) {
            is TeamVaults.OpenResult.Opened -> r.vault
            TeamVaults.OpenResult.StaleKey -> {
                teamVaults.reset(ref)
                teamVaults.open(ref, key)
            }
            TeamVaults.OpenResult.Unreadable -> {
                markError(TeamsFailure.VaultUnreadable)
                null
            }
        }
    }

    // --- scopes ---

    /**
     * Reconciles the server's scope list into local state and returns what the UI should show.
     * Adopts keys we've been granted (or re-keyed), drops the ones we've lost. [canManage] members
     * also see scopes they hold no key for — they can delete such a scope but not hand it out, since
     * sealing an envelope needs the key itself.
     */
    suspend fun refreshScopes(s: SyncSession, c: TeamClient, teamId: String, identity: AccountIdentity?): List<TeamScopeUi> {
        val remote = c.listScopes(s, teamId)
        val adopted = mutableListOf<TeamScopeRef>()
        for (scope in remote) {
            val ref = TeamScopeRef(teamId, scope.scopeId)
            val envelope = scope.envelope ?: continue
            if (identity == null) continue
            val payload = inviteCodec.open(identity.sharing, envelope) ?: continue
            if (payload.teamId != teamId || payload.scopeId != scope.scopeId) continue
            if (payload.inviteeAccountId != s.accountId) continue
            val local = keyStore.scope(teamId, scope.scopeId)
            if (local != null && payload.epoch <= local.epoch) continue
            val granterKeys = c.fetchPublicKey(s, payload.inviterAccountId) ?: continue
            // The scope id is part of the signed binding: an envelope filed under another scope by
            // the server doesn't verify here (see TeamInviteCodec).
            if (!inviteCodec.verify(payload, granterKeys.signing, scopeId = scope.scopeId)) continue
            if (local == null) {
                keyStore.putScope(teamId, scope.scopeId, payload.teamName, payload.teamKey, payload.epoch)
            } else {
                keyStore.rekeyScope(teamId, scope.scopeId, payload.teamKey, payload.epoch)
            }
            teamVaults.reset(ref) // the file (if any) is under the previous key — rebuild on pull
            adopted += ref
        }
        // Scopes we no longer appear in: the key is useless and the local copy has no right to exist.
        val liveIds = remote.map { it.scopeId }.toSet()
        keyStore.scopes(teamId).keys.filter { it !in liveIds }.forEach { gone ->
            keyStore.removeScope(teamId, gone)
            teamVaults.reset(TeamScopeRef(teamId, gone))
        }
        adopted.forEach { syncSpace(it) }
        val keys = keyStore.scopes(teamId)
        return remote.map { scope ->
            TeamScopeUi(
                id = scope.scopeId,
                name = keys[scope.scopeId]?.name ?: scope.scopeId,
                memberCount = scope.memberCount,
                hasKey = keys.containsKey(scope.scopeId),
            )
        }
    }

    /**
     * Creates a scope with a fresh key, sealed to ourselves: that envelope is what the server hands
     * back to our other devices (and to this one if the local record ever loses the key).
     *
     * The recipient key is our **local** public half, never the copy the server publishes for us —
     * asking the server for our own key would let it answer with someone else's and have us seal the
     * scope key to them.
     */
    suspend fun createScope(s: SyncSession, c: TeamClient, teamId: String, scopeId: String, name: String, identity: AccountIdentity) {
        val scopeKey = crypto.newDataKey()
        val envelope = seal(identity.sharing.publicKey, identity, s.accountId, s.accountId, teamId, scopeId, scopeKey, name, epoch = 0)
        c.createScope(s, teamId, scopeId, envelope)
        keyStore.putScope(teamId, scopeId, name, scopeKey, epoch = 0)
    }

    /** Grants a member the scope: its current key, sealed and signed to them. */
    suspend fun grantScope(s: SyncSession, c: TeamClient, teamId: String, scopeId: String, accountId: String, identity: AccountIdentity) {
        val entry = keyStore.scope(teamId, scopeId) ?: return markError(TeamsFailure.KeyMissing)
        val scopeKey = entry.dataKey() ?: return markError(TeamsFailure.KeyMissing)
        val recipient = c.fetchPublicKey(s, accountId) ?: return markError(TeamsFailure.NoRecipientKey)
        val envelope = seal(
            recipient.sharing, identity, s.accountId, accountId, teamId, scopeId, scopeKey, entry.name, entry.epoch,
        )
        c.grantScope(s, teamId, scopeId, accountId, envelope)
    }

    /** Deletes a scope on the server and forgets it locally (key and vault file). */
    suspend fun deleteScope(s: SyncSession, c: TeamClient, teamId: String, scopeId: String) {
        c.deleteScope(s, teamId, scopeId)
        forgetScope(teamId, scopeId)
    }

    fun forgetScope(teamId: String, scopeId: String) {
        keyStore.removeScope(teamId, scopeId)
        teamVaults.reset(TeamScopeRef(teamId, scopeId))
    }

    // --- rotation ---

    /**
     * Rotates a share space's key: a fresh key is re-sealed (signed) to every remaining recipient,
     * the server epoch is bumped, and only then are the local records re-encrypted so they win LWW
     * over the server's old-key copies.
     *
     * Fails closed. The vault is opened under the CURRENT key *before* the server is touched: if it
     * can't be opened (locked, or already under a superseded key), nothing happens at all and the old
     * key stays authoritative — better than a server holding blobs nobody can read. The next epoch
     * comes from the server, not from a possibly stale local one, and a lost race is retried a
     * bounded number of times.
     *
     * The team key and a scope key rotate through this one implementation; [target] is what differs.
     */
    suspend fun rotate(s: SyncSession, c: TeamClient, target: RotationTarget) {
        val ref = target.ref
        val currentKey = key(ref) ?: return markError(TeamsFailure.KeyMissing)
        val spaceName = name(ref)
        val vault = openUnder(ref, currentKey) ?: return markError(TeamsFailure.KeyMissing)
        val identity = target.identity
        var attempt = 0
        while (true) {
            val serverEpoch = target.serverEpoch(s, c) ?: return markError(TeamsFailure.KeyMissing)
            val newEpoch = (serverEpoch + 1).toInt()
            val newKey = crypto.newDataKey()
            val envelopes = mutableMapOf<String, ByteArray>()
            for (accountId in target.recipients(s, c)) {
                if (accountId == s.accountId) continue // we adopt the key locally, no self-envelope
                val keys = c.fetchPublicKey(s, accountId) ?: continue // unpublished key: can't re-seal
                envelopes[accountId] = seal(
                    keys.sharing, identity, s.accountId, accountId, ref.teamId, ref.scopeId, newKey, spaceName, newEpoch,
                )
            }
            try {
                target.commit(s, c, newEpoch.toLong(), envelopes)
            } catch (e: SyncException) {
                newKey.zeroize() // rotation didn't commit — don't leave the unused key dangling
                // A stale-epoch conflict means someone rotated meanwhile: refetch the epoch and retry.
                // Give up after a few tries and surface it — the revocation stands regardless.
                if (e.kind == SyncException.Kind.CONFLICT && attempt++ < REKEY_MAX_ATTEMPTS) continue
                throw e
            }
            // Committed: store the new key (the store base64-copies it) and re-encrypt the records
            // (version+1 wins LWW over the server's old-key copies), then push. rekeyRecords takes
            // ownership of newKey.
            storeKey(ref, newKey, newEpoch)
            vault.rekeyRecords(newKey)
            syncSpace(ref)
            return
        }
    }

    /** Open the space's vault under a known key (no stale-file handling — the key is the current one). */
    private fun openUnder(ref: TeamScopeRef, key: DataKey): Vault? {
        if (!accountVaultUnlocked()) return null
        return teamVaults.open(ref, key)
    }

    private fun seal(
        recipientPublicKey: ByteArray,
        identity: AccountIdentity,
        senderId: String,
        recipientId: String,
        teamId: String,
        scopeId: String,
        key: DataKey,
        name: String,
        epoch: Int,
    ): ByteArray = inviteCodec.seal(
        recipientPublicKey = recipientPublicKey,
        inviter = identity.signing,
        inviterId = senderId,
        inviteeId = recipientId,
        teamId = teamId,
        teamKey = key,
        teamName = name,
        epoch = epoch,
        scopeId = scopeId,
    )

    private companion object {
        /** Bounded retries when a rotation loses the epoch race to a concurrent rotation. */
        const val REKEY_MAX_ATTEMPTS = 3
    }
}

/**
 * What differs between rotating the team key and rotating a scope key: where the authoritative
 * epoch is read, who must receive the new key, and which endpoint commits the bump.
 */
internal class RotationTarget(
    val ref: TeamScopeRef,
    val identity: AccountIdentity,
    val serverEpoch: suspend (SyncSession, TeamClient) -> Long?,
    val recipients: suspend (SyncSession, TeamClient) -> List<String>,
    val commit: suspend (SyncSession, TeamClient, Long, Map<String, ByteArray>) -> Unit,
)
