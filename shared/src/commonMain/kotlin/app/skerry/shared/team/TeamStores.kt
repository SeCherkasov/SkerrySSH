package app.skerry.shared.team

import app.skerry.shared.vault.DataKey
import app.skerry.shared.vault.RecordType
import app.skerry.shared.vault.SharingKeyPair
import app.skerry.shared.vault.Vault
import app.skerry.shared.vault.VaultCrypto
import app.skerry.shared.vault.VaultRecordCodec
import kotlinx.serialization.Serializable
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString

/**
 * Ключ и метаданные команды в СОБСТВЕННОМ vault участника (запись [RecordType.TEAM], id = teamId).
 * teamKey хранится base64 внутри payload — payload целиком зашифрован dataKey'ем аккаунта и едет
 * на свои устройства обычным синком.
 */
@Serializable
data class TeamKeyEntry(
    val name: String,
    val role: String,
    val teamKey: String,
) {
    fun dataKey(): DataKey? {
        val bytes = teamKey.decodeBase64()?.toByteArray() ?: return null
        return if (bytes.size == 32) DataKey(bytes) else null
    }
}

/** Стор записей [RecordType.TEAM]: по одной на команду, id записи = teamId. */
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

    fun put(teamId: String, name: String, role: TeamRole, teamKey: DataKey) {
        vault.transaction {
            codec.put(teamId, TeamKeyEntry(name, role.name.lowercase(), teamKey.bytes.toByteString().base64()))
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

/** Payload singleton-записи [RecordType.TEAM_IDENTITY]: X25519-пара для приёма приглашений. */
@Serializable
data class TeamIdentityEntry(
    val publicKey: String,
    val secretKey: String,
)

/**
 * Identity-пара аккаунта для Teams. Создаётся лениво при первом использовании ([ensure]) и едет
 * на другие устройства аккаунта обычным синком (тип всегда в shouldSync). Публичную половину
 * публикует на сервере координатор — стор о сети не знает.
 */
class TeamIdentityStore(private val vault: Vault, private val crypto: VaultCrypto) {

    private val codec = VaultRecordCodec(vault, RecordType.TEAM_IDENTITY, TeamIdentityEntry.serializer())

    fun load(): SharingKeyPair? {
        if (!vault.isUnlocked) return null
        val entry = codec.get(IDENTITY_ID) ?: return null
        val publicKey = entry.publicKey.decodeBase64()?.toByteArray() ?: return null
        val secretKey = entry.secretKey.decodeBase64()?.toByteArray() ?: return null
        return try {
            crypto.sharingKeyPairFromBytes(publicKey, secretKey)
        } catch (e: IllegalArgumentException) {
            null // повреждённая запись — считаем, что identity нет; ensure() создаст новую
        } finally {
            secretKey.fill(0)
        }
    }

    /** Пара аккаунта; создаёт и сохраняет новую, если записи ещё нет (или она нечитаема). */
    fun ensure(): SharingKeyPair = vault.transaction {
        load() ?: run {
            val pair = crypto.newSharingKeyPair()
            val secret = pair.exportSecretKey()
            codec.put(
                IDENTITY_ID,
                TeamIdentityEntry(pair.publicKey.toByteString().base64(), secret.toByteString().base64()),
            )
            secret.fill(0)
            pair
        }
    }

    private companion object {
        const val IDENTITY_ID = "team.identity"
    }
}
