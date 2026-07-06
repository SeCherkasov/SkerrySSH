package app.skerry.ui.sync

import app.skerry.shared.vault.DataKey
import app.skerry.shared.vault.VaultCrypto

/**
 * Seals the sync refresh token under the vault's dataKey for storage in [SyncConfig] ("keep
 * connected" mode). The token is AEAD-encrypted ([VaultCrypto.seal]) with a fixed AAD and stored
 * in the config as a hex ciphertext string; without unlocking the vault it's useless, so stealing
 * the config file doesn't expose data (zero-knowledge). [open] returns null on malformed
 * hex/ciphertext (an expired binding triggers password reauth).
 */
internal class SealedTokenCodec(private val crypto: VaultCrypto) {
    private val tokenAad = "skerry-sync-refresh-token".encodeToByteArray()

    fun seal(dataKey: DataKey, token: String): String =
        crypto.seal(dataKey, token.encodeToByteArray(), tokenAad).toHex()

    fun open(dataKey: DataKey, hex: String): String? =
        hex.hexToBytesOrNull()?.let { crypto.open(dataKey, it, tokenAad) }?.decodeToString()
}

/** Hex-encodes bytes (lowercase, two digits per byte); config/deviceId are stored as strings. */
internal fun ByteArray.toHex(): String =
    joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

/** Inverse of [toHex]; null on odd length or non-hex characters (corrupt config, doesn't throw). */
internal fun String.hexToBytesOrNull(): ByteArray? {
    if (length % 2 != 0) return null
    return runCatching { ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(16).toByte() } }.getOrNull()
}
