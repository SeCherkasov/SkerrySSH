package app.skerry.ui.sync

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Полезная нагрузка быстрого паринга (вариант B, `docs/skerry-sync-design.md` §3), которую вошедшее
 * устройство показывает в QR/коде, а новое — считывает камерой или вставляет вручную. Несёт ровно то,
 * что новому устройству нужно, чтобы забрать сессию и расшифровать ключ аккаунта **в обход сервера**:
 *
 *  - [serverUrl] — куда идти за claim'ом (новое устройство ещё не знает адрес сервера);
 *  - [code]      — одноразовый claim-код pairing-сессии (его проверяет сервер);
 *  - [transferKey] — одноразовый ключ, которым на сервере запечатан dataKey ([VaultCrypto.newTransferKey]).
 *    Именно поэтому он едет в QR, а НЕ на сервер: сервер хранит лишь конверт и без этого ключа бесполезен.
 *
 * Формат: `"sk1"` + три поля, каждое в base64url-без-паддинга, через `.` — точка не входит в алфавит
 * base64url (`A-Za-z0-9-_`), поэтому `split('.')` однозначен даже когда [serverUrl]/[code] сами содержат
 * `:`/`/`/`-`/`_`. Версионный префикс `sk1` отбивает мусор/чужие QR на входе. Формат компактен (для QR
 * важна плотность) и не зависит от kotlinx.serialization.
 */
class PairingPayload(
    val serverUrl: String,
    val code: String,
    val transferKey: ByteArray,
) {
    @OptIn(ExperimentalEncodingApi::class)
    fun encode(): String {
        val b64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
        return listOf(
            PREFIX,
            b64.encode(serverUrl.encodeToByteArray()),
            b64.encode(code.encodeToByteArray()),
            b64.encode(transferKey),
        ).joinToString(SEP)
    }

    // Обычный class (не data): авто-copy() data-класса делил бы мутабельный transferKey по ссылке —
    // затирание одной копии молча обнулило бы другую (security-ревью). equals/hashCode по содержимому
    // (structural) пишем руками — нужно тестам round-trip'а.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PairingPayload) return false
        return serverUrl == other.serverUrl && code == other.code && transferKey.contentEquals(other.transferKey)
    }

    override fun hashCode(): Int =
        (serverUrl.hashCode() * 31 + code.hashCode()) * 31 + transferKey.contentHashCode()

    override fun toString(): String = "PairingPayload(serverUrl=$serverUrl, code=***, transferKey=***)"

    companion object {
        private const val PREFIX = "sk1"
        private const val SEP = "."

        /** Длина transferKey (= AEAD-ключ XChaCha20). Проверяется при decode — см. ниже. */
        private const val TRANSFER_KEY_SIZE = 32

        /**
         * Разобрать строку из QR/ручного ввода. `null` — не наша строка/битый формат (чужой QR, обрезка,
         * неверный префикс, не-base64): вызывающий показывает «не похоже на код связывания», а не падает.
         * Пробелы по краям срезаются (ручная вставка/перенос строки из камеры).
         */
        @OptIn(ExperimentalEncodingApi::class)
        fun decode(raw: String): PairingPayload? {
            val parts = raw.trim().split(SEP)
            if (parts.size != 4 || parts[0] != PREFIX) return null
            val b64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
            return runCatching {
                val serverUrl = b64.decode(parts[1]).decodeToString()
                val code = b64.decode(parts[2]).decodeToString()
                val transferKey = b64.decode(parts[3])
                // Структурная валидация ДО любого сетевого вызова: иначе claimPairing сжёг бы одноразовый
                // код на обрезанном QR (security-ревью). Неверная длина ключа или дикая схема URL
                // (подменённый QR) — это decode-провал, а не сожжённый код.
                if (transferKey.size != TRANSFER_KEY_SIZE) return@runCatching null
                if (!serverUrl.startsWith("http://") && !serverUrl.startsWith("https://")) return@runCatching null
                PairingPayload(serverUrl, code, transferKey)
            }.getOrNull()
        }
    }
}
