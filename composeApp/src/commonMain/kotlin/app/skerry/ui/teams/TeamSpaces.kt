package app.skerry.ui.teams

import app.skerry.shared.sync.SyncException
import app.skerry.shared.sync.SyncSession
import app.skerry.shared.team.AccountIdentity
import app.skerry.shared.team.AccountKeys
import app.skerry.shared.team.PeerKeys
import app.skerry.shared.team.TeamClient
import app.skerry.shared.team.TeamInviteCodec
import app.skerry.shared.team.TeamKeyStore
import app.skerry.shared.team.TeamScopeRef
import app.skerry.shared.team.TeamScopeSummary
import app.skerry.shared.team.TeamVaults
import app.skerry.shared.vault.DataKey
import app.skerry.shared.vault.Vault
import app.skerry.shared.vault.VaultCrypto

/**
 * How an account's published keys are resolved: through the fingerprint pinned for that account (see
 * [app.skerry.shared.team.fetchPinned]), so a key the server moved after the user verified it is
 * refused instead of trusted (#319). Refusals are reported by the lookup itself, since only the
 * coordinator knows whether the caller is a user's own action or a background adoption. The pin
 * store stays with the coordinator that owns the vault — this is the one thing spaces need from it.
 */
internal typealias PeerKeyLookup = suspend (SyncSession, TeamClient, String) -> PeerKeys

/**
 * What sealing to another account takes: our own identity to sign with, and the lookup that resolves
 * theirs under the pin. The two travel together everywhere a space key is handed over — an identity
 * without the lookup is the hole #319 closed.
 */
internal class SealingIdentity(val own: AccountIdentity, val peerKeys: PeerKeyLookup)

/** An account a space key is being sealed to, with the keys it was resolved under. */
internal class TeamRecipient(val accountId: String, val keys: AccountKeys)

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
    suspend fun refreshScopes(s: SyncSession, c: TeamClient, teamId: String, sealing: SealingIdentity?): List<TeamScopeUi> {
        val remote = c.listScopes(s, teamId)
        val adopted = remote.filter { adoptScopeKey(s, c, teamId, it, sealing) }
            .map { TeamScopeRef(teamId, it.scopeId) }
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
     * Adopts one scope's sealed key when the envelope is ours, newer than what we hold, and signed by
     * an identity that still matches its pin. Returns whether the local key moved, so the caller can
     * re-pull that space.
     *
     * The granter's key comes from the server like every other, so it goes through the same pin as a
     * team rekey envelope: a signature is worth exactly what the key it is checked against is (#319).
     * The granter is named inside an envelope anyone can seal, so the lookup handed in here is the
     * one that pins nothing — a first sight on this path would be the server's key, written down as
     * confirmed.
     */
    private suspend fun adoptScopeKey(
        s: SyncSession,
        c: TeamClient,
        teamId: String,
        scope: TeamScopeSummary,
        sealing: SealingIdentity?,
    ): Boolean {
        if (sealing == null) return false
        val envelope = scope.envelope ?: return false
        val payload = inviteCodec.open(sealing.own.sharing, envelope) ?: return false
        if (payload.teamId != teamId || payload.scopeId != scope.scopeId) return false
        if (payload.inviteeAccountId != s.accountId) return false
        val local = keyStore.scope(teamId, scope.scopeId)
        if (local != null && payload.epoch <= local.epoch) return false
        val granterKeys = when (val fetched = sealing.peerKeys(s, c, payload.inviterAccountId)) {
            is PeerKeys.Pinned -> fetched.keys
            PeerKeys.Unpublished -> return false
            is PeerKeys.Unconfirmed -> return false // reported by the lookup
        }
        // The scope id is part of the signed binding: an envelope filed under another scope by the
        // server doesn't verify here (see TeamInviteCodec).
        if (!inviteCodec.verify(payload, granterKeys.signing, scopeId = scope.scopeId)) return false
        if (local == null) {
            keyStore.putScope(teamId, scope.scopeId, payload.teamName, payload.teamKey, payload.epoch)
        } else {
            keyStore.rekeyScope(teamId, scope.scopeId, payload.teamKey, payload.epoch)
        }
        teamVaults.reset(TeamScopeRef(teamId, scope.scopeId)) // the file is under the previous key
        return true
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

    /**
     * Grants a member the scope: its current key, sealed and signed to them.
     *
     * There is no fingerprint on screen here and nothing for the user to contradict, so the key is
     * taken through the pin rather than trusted as the server answers it (#319). A member whose key
     * honestly moved has to be re-invited — that ceremony is where a human confirms the new one.
     */
    suspend fun grantScope(s: SyncSession, c: TeamClient, ref: TeamScopeRef, recipient: TeamRecipient, identity: AccountIdentity) {
        val entry = keyStore.scope(ref.teamId, ref.scopeId) ?: return markError(TeamsFailure.KeyMissing)
        val scopeKey = entry.dataKey() ?: return markError(TeamsFailure.KeyMissing)
        val envelope = seal(
            recipient.keys.sharing, identity, s.accountId, recipient.accountId,
            ref.teamId, ref.scopeId, scopeKey, entry.name, entry.epoch,
        )
        c.grantScope(s, ref.teamId, ref.scopeId, recipient.accountId, envelope)
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
    suspend fun rotate(s: SyncSession, c: TeamClient, target: RotationTarget): TeamsFailure? {
        val ref = target.ref
        val currentKey = key(ref) ?: return TeamsFailure.KeyMissing
        val vault = openUnder(ref, currentKey) ?: return TeamsFailure.KeyMissing
        var attempt = 0
        while (true) {
            val serverEpoch = target.serverEpoch(s, c) ?: return TeamsFailure.KeyMissing
            val newEpoch = (serverEpoch + 1).toInt()
            val newKey = crypto.newDataKey()
            val resealed = resealTo(s, c, target, newKey, newEpoch)
            try {
                target.commit(s, c, newEpoch.toLong(), resealed.envelopes)
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
            return if (resealed.skippedUnconfirmed) TeamsFailure.PeerKeyUnconfirmed else null
        }
    }

    /**
     * The new key sealed to every remaining recipient of the space, each under the fingerprint pinned
     * for their account.
     *
     * A recipient whose published key is not the pinned one gets **no envelope**: they lose access
     * until a human confirms the new fingerprint, which is the point (#319). The rotation still goes
     * through — it exists to take the key away from the member that was just removed, and refusing it
     * wholesale would leave that member holding it — so the skip is carried out to the caller instead.
     */
    private suspend fun resealTo(
        s: SyncSession,
        c: TeamClient,
        target: RotationTarget,
        newKey: DataKey,
        newEpoch: Int,
    ): Resealed {
        val spaceName = name(target.ref)
        val envelopes = mutableMapOf<String, ByteArray>()
        var skipped = false
        for (accountId in target.recipients(s, c)) {
            if (accountId == s.accountId) continue // we adopt the key locally, no self-envelope
            val keys = when (val fetched = target.sealing.peerKeys(s, c, accountId)) {
                is PeerKeys.Pinned -> fetched.keys
                PeerKeys.Unpublished -> continue // unpublished key: can't re-seal
                is PeerKeys.Unconfirmed -> { skipped = true; continue }
            }
            envelopes[accountId] = seal(
                keys.sharing, target.sealing.own, s.accountId, accountId,
                target.ref.teamId, target.ref.scopeId, newKey, spaceName, newEpoch,
            )
        }
        return Resealed(envelopes, skipped)
    }

    /** What [resealTo] produced: the envelopes to commit, and whether a recipient was left out. */
    private class Resealed(val envelopes: Map<String, ByteArray>, val skippedUnconfirmed: Boolean)

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
    /** Who signs the new envelopes, and how each recipient's keys are resolved (under their pin). */
    val sealing: SealingIdentity,
    val serverEpoch: suspend (SyncSession, TeamClient) -> Long?,
    val recipients: suspend (SyncSession, TeamClient) -> List<String>,
    val commit: suspend (SyncSession, TeamClient, Long, Map<String, ByteArray>) -> Unit,
)
