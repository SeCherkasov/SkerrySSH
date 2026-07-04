package app.skerry.shared.team

import app.skerry.shared.vault.DataKey
import app.skerry.shared.vault.SharingKeyPair
import app.skerry.shared.vault.VaultCrypto
import com.ionspin.kotlin.crypto.generichash.GenericHash
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString

/** Что расшифровывает приглашённый: ключ команды и её имя (сервер не видит ни того, ни другого). */
class TeamInvitePayload(
    val teamKey: DataKey,
    val teamName: String,
)

/**
 * Конверт приглашения: JSON `{v, teamKey, name}` → sealed-конверт (crypto_box_seal) на публичный
 * ключ приглашённого. Версия внутри payload — доменная привязка: конверт от чужого контекста
 * (не-приглашение) отбрасывается декодером.
 */
class TeamInviteCodec(private val crypto: VaultCrypto) {

    @Serializable
    private data class WireInvite(val v: Int, val teamKey: String, val name: String)

    private val json = Json { ignoreUnknownKeys = true }

    fun seal(recipientPublicKey: ByteArray, teamKey: DataKey, teamName: String): ByteArray {
        val plain = json.encodeToString(
            WireInvite.serializer(),
            WireInvite(VERSION, teamKey.bytes.toByteString().base64(), teamName),
        ).encodeToByteArray()
        return crypto.sealForRecipient(recipientPublicKey, plain).also { plain.fill(0) }
    }

    /** null — конверт не наш, повреждён или неожиданной версии (недоверенный вход с сервера). */
    fun open(keyPair: SharingKeyPair, envelope: ByteArray): TeamInvitePayload? {
        val plain = crypto.openSealedEnvelope(keyPair, envelope) ?: return null
        return try {
            val wire = json.decodeFromString(WireInvite.serializer(), plain.decodeToString())
            if (wire.v != VERSION) return null
            val keyBytes = wire.teamKey.decodeBase64()?.toByteArray() ?: return null
            if (keyBytes.size != 32) return null
            TeamInvitePayload(DataKey(keyBytes), wire.name)
        } catch (e: kotlinx.serialization.SerializationException) {
            null
        } finally {
            plain.fill(0)
        }
    }

    private companion object {
        const val VERSION = 1
    }
}

/**
 * Короткий фингерпринт публичного ключа (BLAKE2b-8), формат `ab12-cd34-ef56-7890`.
 * Стороны сверяют его по доверенному каналу (голос/чат) — защита от подмены ключа сервером.
 */
fun sharingKeyFingerprint(publicKey: ByteArray): String {
    @OptIn(ExperimentalUnsignedTypes::class)
    val digest = GenericHash.genericHash(publicKey.toUByteArray(), 8).toByteArray()
    return digest.toList().chunked(2).joinToString("-") { pair ->
        pair.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    }
}
