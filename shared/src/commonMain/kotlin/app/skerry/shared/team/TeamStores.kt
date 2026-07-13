package app.skerry.shared.team

import app.skerry.shared.vault.DataKey
import app.skerry.shared.vault.RecordType
import app.skerry.shared.vault.SharingKeyPair
import app.skerry.shared.vault.SigningKeyPair
import app.skerry.shared.vault.Vault
import app.skerry.shared.vault.VaultCrypto
import app.skerry.shared.vault.VaultRecordCodec
import kotlinx.serialization.Serializable
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString

/**
 * Team key and metadata in the member's own vault (record [RecordType.TEAM], id = teamId).
 * teamKey is stored base64 inside the payload, which is encrypted with the account dataKey and
 * synced to the account's own devices normally.
 *
 * [epoch] is the teamKey generation: it starts at 0 and increments on every key rotation (a member
 * removal/demotion re-keys the team, see [TeamsCoordinator]). The client adopts a server envelope
 * only when its epoch is newer, so a stale re-delivery of an old key can't downgrade the member.
 */
@Serializable
data class TeamKeyEntry(
    val name: String,
    val role: String,
    val teamKey: String,
    val epoch: Int = 0,
) {
    fun dataKey(): DataKey? {
        val bytes = teamKey.decodeBase64()?.toByteArray() ?: return null
        return if (bytes.size == 32) DataKey(bytes) else null
    }
}

/** Store of [RecordType.TEAM] records: one per team, record id = teamId. */
class TeamKeyStore(private val vault: Vault) {

    private val codec = VaultRecordCodec(vault, RecordType.TEAM, TeamKeyEntry.serializer())

    fun list(): Map<String, TeamKeyEntry> {
        if (!vault.isUnlocked) return emptyMap()
        return vault.records()
            .filter { it.type == RecordType.TEAM && !it.deleted }
            .mapNotNull { record -> codec.get(record.id)?.let { record.id to it } }
            .toMap()
    }

    fun get(teamId: String): TeamKeyEntry? {
        if (!vault.isUnlocked) return null
        return codec.get(teamId)
    }

    fun put(teamId: String, name: String, role: TeamRole, teamKey: DataKey, epoch: Int = 0) {
        vault.transaction {
            codec.put(
                teamId,
                TeamKeyEntry(name, role.name.lowercase(), teamKey.bytes.toByteString().base64(), epoch),
            )
        }
    }

    /** Replace the stored key and epoch on rotation, preserving name/role. No-op if the team is gone. */
    fun rekey(teamId: String, teamKey: DataKey, epoch: Int) {
        vault.transaction {
            val current = codec.get(teamId) ?: return@transaction
            codec.put(teamId, current.copy(teamKey = teamKey.bytes.toByteString().base64(), epoch = epoch))
        }
    }

    fun rename(teamId: String, name: String) {
        vault.transaction {
            val current = codec.get(teamId) ?: return@transaction
            codec.put(teamId, current.copy(name = name))
        }
    }

    fun remove(teamId: String) {
        vault.transaction { codec.remove(teamId) }
    }
}

/**
 * The account's Teams identity: an X25519 [sharing] pair (receives sealed invite/rekey envelopes)
 * and an Ed25519 [signing] pair (authors — signs — them). Both public halves are published on the
 * server; both secret halves live only in the vault.
 */
class AccountIdentity(
    val sharing: SharingKeyPair,
    val signing: SigningKeyPair,
) {
    fun zeroize() {
        sharing.zeroize()
        signing.zeroize()
    }
}

/**
 * Payload of the singleton [RecordType.TEAM_IDENTITY] record: the X25519 keypair (sharing) plus the
 * Ed25519 keypair (signing). The signing halves default to empty for records written before invite
 * signing existed — [TeamIdentityStore.ensure] upgrades such a record in place, keeping the sharing
 * pair (and thus the published key and fingerprint) stable.
 */
@Serializable
data class TeamIdentityEntry(
    val publicKey: String,
    val secretKey: String,
    val signPublicKey: String = "",
    val signSecretKey: String = "",
)

/**
 * Account identity keypairs for Teams. Created lazily on first use ([ensure]) and synced to the
 * account's other devices normally (the type is always in shouldSync). The public halves are
 * published to the server by the coordinator; this store knows nothing about the network.
 */
class TeamIdentityStore(private val vault: Vault, private val crypto: VaultCrypto) {

    private val codec = VaultRecordCodec(vault, RecordType.TEAM_IDENTITY, TeamIdentityEntry.serializer())

    fun load(): AccountIdentity? {
        if (!vault.isUnlocked) return null
        val entry = codec.get(IDENTITY_ID) ?: return null
        return decode(entry)
    }

    /**
     * Account keypairs; creates and stores new ones if none exist yet (or the record is unreadable),
     * and adds a signing pair to a legacy record that predates invite signing.
     */
    fun ensure(): AccountIdentity = vault.transaction {
        val existing = codec.get(IDENTITY_ID)
        val decoded = existing?.let { decode(it) }
        if (decoded != null) return@transaction decoded
        // Missing/corrupt sharing half → fresh identity. Present sharing but missing signing half →
        // keep the (published) sharing pair, add a signing pair.
        val sharing = existing?.let { decodeSharing(it) } ?: crypto.newSharingKeyPair()
        val signing = crypto.newSigningKeyPair()
        persist(sharing, signing)
        AccountIdentity(sharing, signing)
    }

    private fun persist(sharing: SharingKeyPair, signing: SigningKeyPair) {
        val sharingSecret = sharing.exportSecretKey()
        val signingSecret = signing.exportSecretKey()
        codec.put(
            IDENTITY_ID,
            TeamIdentityEntry(
                publicKey = sharing.publicKey.toByteString().base64(),
                secretKey = sharingSecret.toByteString().base64(),
                signPublicKey = signing.publicKey.toByteString().base64(),
                signSecretKey = signingSecret.toByteString().base64(),
            ),
        )
        sharingSecret.fill(0)
        signingSecret.fill(0)
    }

    private fun decode(entry: TeamIdentityEntry): AccountIdentity? {
        val sharing = decodeSharing(entry) ?: return null
        val signPublic = entry.signPublicKey.decodeBase64()?.toByteArray() ?: return null
        val signSecret = entry.signSecretKey.decodeBase64()?.toByteArray() ?: return null
        return try {
            AccountIdentity(sharing, crypto.signingKeyPairFromBytes(signPublic, signSecret))
        } catch (e: IllegalArgumentException) {
            null // corrupt signing half: treat as no identity; ensure() rebuilds the signing pair
        } finally {
            signSecret.fill(0)
        }
    }

    private fun decodeSharing(entry: TeamIdentityEntry): SharingKeyPair? {
        val publicKey = entry.publicKey.decodeBase64()?.toByteArray() ?: return null
        val secretKey = entry.secretKey.decodeBase64()?.toByteArray() ?: return null
        return try {
            crypto.sharingKeyPairFromBytes(publicKey, secretKey)
        } catch (e: IllegalArgumentException) {
            null
        } finally {
            secretKey.fill(0)
        }
    }

    private companion object {
        const val IDENTITY_ID = "team.identity"
    }
}
