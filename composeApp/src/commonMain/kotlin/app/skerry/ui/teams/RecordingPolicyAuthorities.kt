package app.skerry.ui.teams

import app.skerry.shared.team.SignedRecordingPolicy
import app.skerry.shared.team.CachedRecordingPolicy
import app.skerry.shared.team.RecordingPolicyAuthority
import app.skerry.shared.team.RecordingPolicyException
import app.skerry.shared.team.RecordingPolicy
import app.skerry.shared.team.RecordingMode
import app.skerry.shared.team.TeamScopeRef
import app.skerry.shared.team.TeamRecordingCrypto
import app.skerry.shared.team.Pin
import app.skerry.shared.team.PinOrigin
import app.skerry.shared.team.PeerKeys
import app.skerry.shared.team.fetchPinned
import app.skerry.shared.team.cached
import app.skerry.shared.vault.DataKey
import app.skerry.ui.sync.TeamLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.ByteString.Companion.toByteString

/** A confirmed peer pin authenticates a key, not server-selected ownership. Remote-owner
 * bootstrap and subsequent signer changes require explicit approval of the captured authority.
 * A policy signed by our own identity can bootstrap locally. A never-observed policy defaults
 * to OFF; that absence is the trust-discovery boundary, not proof of an owner requirement.
 * Existing server APIs provide no independently authenticated ownership lineage at bootstrap.
 */
internal class RecordingPolicyAuthorities(
    private val live: () -> TeamLink?,
    stores: RecordingStores,
    private val spaces: TeamSpaces,
    private val opMutex: Mutex,
) {
    private val vault = stores.vault
    private val keyStore = stores.keyStore
    private val identityStore = stores.identityStore
    private val peerStore = stores.peerStore
    private val recordingCrypto = TeamRecordingCrypto(stores.crypto)

    suspend fun verifiedPolicy(link: TeamLink, ref: TeamScopeRef, key: DataKey, epoch: Long): RecordingPolicy? {
        val api = link.recordings ?: error("Teams recording API unavailable")
        val cached = keyStore.recordingPolicy(ref, link.linkKey)
        val envelope = api.recordingPolicy(link.session, ref) ?: run {
            check(cached == null) { "recording policy missing after prior observation" }
            return null
        }
        val owner = link.client.listTeams(link.session).firstOrNull { it.id == ref.teamId }?.ownerAccountId
            ?: error("team owner missing")
        val authority = authorityFor(link, owner)
        if (requiresAuthorityApproval(cached, authority, link.session.accountId)) {
            val localTransfer = owner == link.session.accountId && cached?.authority != null
            val policy = validateApprovalCandidate(key, authority, envelope, ref, cached, localTransfer)
            check(policy.keyEpoch == epoch) { "recording policy key epoch mismatch" }
            throw RecordingAuthorityRequired(RecordingAuthorityApproval(ref, link.linkKey, cached, authority,
                envelope.cached(policy.mode, authority), policy, localTransfer))
        }
        val policy = recordingCrypto.openPolicy(key, authority.publicKey(), envelope, ref, cached?.revision ?: 0)
        check(policy.keyEpoch == epoch) { "recording policy key epoch mismatch" }
        keyStore.rememberRecordingPolicy(ref, envelope.cached(policy.mode, authority), link.linkKey)
        return policy
    }

    private fun requiresAuthorityApproval(
        cached: CachedRecordingPolicy?, authority: RecordingPolicyAuthority, localAccountId: String,
    ): Boolean {
        if (cached == null) return authority.accountId != localAccountId
        return cached.authority != authority
    }

    private fun validateApprovalCandidate(
        key: DataKey, authority: RecordingPolicyAuthority, envelope: SignedRecordingPolicy,
        ref: TeamScopeRef, cached: CachedRecordingPolicy?, localTransfer: Boolean = false,
    ): RecordingPolicy {
        val policy = if (localTransfer) {
            try { recordingCrypto.openPolicy(key, authority.publicKey(), envelope, ref, cached?.revision ?: 0) }
            catch (_: RecordingPolicyException) {
                val previous = checkNotNull(cached)
                val approved = checkNotNull(previous.authority)
                recordingCrypto.openPolicy(key, approved.publicKey(), envelope, ref, previous.revision)
            }
        } else recordingCrypto.openPolicy(key, authority.publicKey(), envelope, ref, cached?.revision ?: 0)
        val candidate = envelope.cached(policy.mode, authority)
        if (cached != null && cached.revision == candidate.revision) {
            check(candidate.copy(authority = cached.authority, mode = cached.mode) == cached) {
                "recording policy equivocation"
            }
        }
        return policy
    }

    suspend fun authorityFor(link: TeamLink, owner: String): RecordingPolicyAuthority {
        val signing = if (owner == link.session.accountId) {
            identityStore.load()?.signing?.publicKey ?: error("owner identity missing")
        } else {
            val keys = (peerStore.fetchPinned(link.session, link.client, owner) as? PeerKeys.Pinned)?.keys
                ?: throw RecordingOwnerKeysRequired()
            val pin = peerStore.pin(owner)
            if (pin !is Pin.Known || pin.origin != PinOrigin.CONFIRMED)
                throw RecordingOwnerKeysRequired()
            keys.signing
        }
        return RecordingPolicyAuthority(owner, signing.toByteString().base64())
    }

    /** Explicit local-owner bootstrap when no previously approved signer is available.
     * The user chooses this device as authority and establishes REQUIRED/30 days before editing.
     * No peer identity selected by the server is trusted by this path.
     */
    suspend fun requestOwnRecordingAuthority(ref: TeamScopeRef): RecordingAuthorityApproval =
        withContext(Dispatchers.Default) {
            opMutex.withLock {
                check(vault.isUnlocked)
                val link = live() ?: error("Teams sync is offline")
                val previous = keyStore.recordingPolicy(ref, link.linkKey)
                check(previous == null) { "Approve the captured policy authority transition instead" }
                val owner = link.client.listTeams(link.session).firstOrNull { it.id == ref.teamId }?.ownerAccountId
                check(owner == link.session.accountId) { "Local account is not the advertised owner" }
                val authority = authorityFor(link, link.session.accountId)
                val remote = (link.recordings ?: error("Teams recording API unavailable")).recordingPolicy(link.session, ref)
                val policy = RecordingPolicy(ref, RecordingMode.REQUIRED, maxOf(1, remote?.revision ?: 0),
                    spaces.epoch(ref).toLong(), 30)
                RecordingAuthorityApproval(ref, link.linkKey, null, authority,
                    remote?.cached(policy.mode, authority), policy, resign = true)
            }
        }

    suspend fun approveRecordingAuthority(decision: RecordingAuthorityApproval) = withContext(Dispatchers.Default) {
        opMutex.withLock {
            check(vault.isUnlocked)
            val link = live() ?: error("Teams sync is offline")
            check(link.linkKey == decision.linkKey)
            check(keyStore.recordingPolicy(decision.ref, link.linkKey) == decision.previous)
            val owner = link.client.listTeams(link.session).firstOrNull { it.id == decision.ref.teamId }?.ownerAccountId
            check(owner == decision.authority.accountId && authorityFor(link, checkNotNull(owner)) == decision.authority)
            val api = link.recordings ?: error("Teams recording API unavailable")
            val remote = api.recordingPolicy(link.session, decision.ref)
            check(remote?.cached(decision.policy.mode, decision.authority) == decision.candidate)
            val key = spaces.key(decision.ref) ?: error("team recording key missing")
            try {
                check(spaces.epoch(decision.ref).toLong() == decision.policy.keyEpoch)
                val accepted = if (decision.resign) {
                    val signer = identityStore.load()?.signing ?: error("owner identity missing")
                    check(signer.publicKey.contentEquals(decision.authority.publicKey()))
                    val policy = decision.policy.copy(revision = decision.policy.revision + 1)
                    val sealed = recordingCrypto.sealPolicy(key, signer, policy)
                    recordingCrypto.openPolicy(key, signer.publicKey, sealed, decision.ref, policy.revision)
                    api.putRecordingPolicy(link.session, decision.ref, sealed)
                    sealed.cached(policy.mode, decision.authority)
                } else {
                    recordingCrypto.openPolicy(key, decision.authority.publicKey(), checkNotNull(decision.candidate).envelope(),
                        decision.ref, decision.previous?.revision ?: 0)
                    checkNotNull(decision.candidate)
                }
                keyStore.approveRecordingAuthority(decision.ref, decision.previous, accepted, link.linkKey)
            } finally { key.zeroize() }
        }
    }

}

/** Immutable captured approval; never resolve a different owner from UI state on confirmation. */
data class RecordingAuthorityApproval internal constructor(
    val ref: TeamScopeRef,
    val linkKey: String,
    val previous: CachedRecordingPolicy?,
    val authority: RecordingPolicyAuthority,
    internal val candidate: CachedRecordingPolicy?,
    internal val policy: RecordingPolicy,
    internal val resign: Boolean,
)

class RecordingAuthorityRequired(val decision: RecordingAuthorityApproval) :
    RecordingPolicyException("Recording signing authority requires local approval")

/** The member-list confirmation ceremony must authenticate this key before policy discovery. */
class RecordingOwnerKeysRequired : RecordingPolicyException("Owner keys require member-list confirmation")
