package app.skerry.ui.sync

import app.skerry.shared.vault.DataKey
import app.skerry.shared.vault.VaultCrypto

/**
 * Запечатывание refresh-токена sync под dataKey vault для хранения в [SyncConfig] (режим
 * «keep connected»). Токен шифруется AEAD ([VaultCrypto.seal]) с фиксированным AAD и кладётся в
 * конфиг hex-строкой шифротекста: без разблокировки vault он бесполезен, так что кража файла
 * конфигурации не даёт доступ к данным (zero-knowledge). [open] возвращает `null` на битом
 * hex/шифротексте (протухшая привязка → переавторизация паролем).
 */
internal class SealedTokenCodec(private val crypto: VaultCrypto) {
    private val tokenAad = "skerry-sync-refresh-token".encodeToByteArray()

    fun seal(dataKey: DataKey, token: String): String =
        crypto.seal(dataKey, token.encodeToByteArray(), tokenAad).toHex()

    fun open(dataKey: DataKey, hex: String): String? =
        hex.hexToBytesOrNull()?.let { crypto.open(dataKey, it, tokenAad) }?.decodeToString()
}

/** Hex-кодирование байтов (нижний регистр, два знака на байт) — конфиг/deviceId хранятся строками. */
internal fun ByteArray.toHex(): String =
    joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

/** Обратное к [toHex]; `null` на нечётной длине или не-hex символах (битый конфиг — не падаем). */
internal fun String.hexToBytesOrNull(): ByteArray? {
    if (length % 2 != 0) return null
    return runCatching { ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(16).toByte() } }.getOrNull()
}
