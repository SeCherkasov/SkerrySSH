package app.skerry.shared.sync

import kotlinx.coroutines.flow.Flow

/**
 * Идентификация устройства в аккаунте (id — стабильный, генерируется при первом входе).
 * [platform] — открытая метка (напр. «Android 34», «Linux») для админ-консоли; `null` означает
 * «не сообщать» (сервер не затирает уже известное значение).
 */
data class DeviceInfo(val id: String, val name: String, val platform: String? = null)

/** Активная сессия с sync-сервером: accountId и пара токенов (см. §4 дизайна). */
data class SyncSession(val accountId: String, val accessToken: String, val refreshToken: String)

/**
 * Зашифрованная запись «как на проводе»: метаданные открыты, [blob] — шифротекст
 * XChaCha20-Poly1305 (см. `VaultRecord`). Доменная модель клиента; сериализация в JSON/base64 —
 * деталь сетевой реализации. `equals`/`hashCode` дефолтные (ByteArray по ссылке) — не ключ коллекции.
 */
data class RemoteRecord(
    val id: String,
    val type: String,
    val version: Long,
    val updatedAt: String,
    val deviceId: String,
    val deleted: Boolean,
    val blob: ByteArray,
)

/**
 * Страница дельты: записи + новый курсор синхронизации (`lastSyncVersion`). [compactedIds] — id
 * надгробий, которые сервер считает полностью распространёнными (все устройства их дочитали):
 * клиент физически забывает их ([Vault.compact]) и перестаёт пушить, иначе re-push воскрешал бы их
 * после серверного purge. Пусто для старого сервера (поле опционально на проводе).
 */
data class RecordPage(val records: List<RemoteRecord>, val cursor: Long, val compactedIds: List<String> = emptyList())

data class RemoteDevice(
    val id: String,
    val name: String,
    val createdAt: Long,
    val lastSeenAt: Long,
    val revoked: Boolean,
    val current: Boolean,
)

/** Тикет быстрого паринга (вариант B): код для QR и срок жизни. */
data class PairingTicket(val code: String, val expiresAt: Long)

/** Результат claim'а паринга на новом устройстве: зашифрованный dataKey + готовая сессия. */
data class PairingResult(val accountId: String, val encryptedDataKey: ByteArray, val session: SyncSession)

/**
 * Клиент self-hosted sync-сервера (`docs/skerry-sync-design.md` §3). Контракт в ядре,
 * реализация платформенная (JVM: Ktor client + Nimbus SRP). Zero-knowledge: наружу уходят
 * только [authKey]-производный SRP-верификатор и шифроблобы; пароль/masterKey/dataKey остаются
 * на устройстве. [authKey] вычисляет вызывающая сторона через `VaultCrypto.deriveAuthKey`.
 *
 * Ошибки сети/протокола сигнализируются исключением [SyncException]; ожидаемый «неверный
 * пароль/нет аккаунта» — тоже [SyncException] с соответствующим [SyncException.Kind].
 */
interface SyncClient {

    /** Регистрация нового аккаунта: клиент сам считает SRP-соль/верификатор из [authKey]. */
    suspend fun register(
        accountId: String,
        authKey: ByteArray,
        wrappedDataKey: ByteArray,
        device: DeviceInfo,
    ): SyncSession

    /** Вход по SRP (без передачи пароля): challenge → доказательство → токены. */
    suspend fun login(accountId: String, authKey: ByteArray, device: DeviceInfo): SyncSession

    /** Обёртка dataKey для расшифровки на этом устройстве (вариант A паринга). */
    suspend fun fetchWrappedDataKey(session: SyncSession): ByteArray

    /** Дельта записей с серверным курсором `since`. */
    suspend fun pull(session: SyncSession, since: Long): RecordPage

    /** Batch upsert; ответ — победившее по LWW состояние и новый курсор. */
    suspend fun push(session: SyncSession, records: List<RemoteRecord>): RecordPage

    suspend fun listDevices(session: SyncSession): List<RemoteDevice>

    suspend fun revokeDevice(session: SyncSession, deviceId: String): Boolean

    /** Ротация токенов по refresh. */
    suspend fun refresh(session: SyncSession): SyncSession

    /** Старт паринга на вошедшем устройстве: кладёт зашифрованный transferKey dataKey, отдаёт код. */
    suspend fun startPairing(session: SyncSession, encryptedDataKey: ByteArray): PairingTicket

    /** Claim паринга на новом устройстве по коду (без входа). */
    suspend fun claimPairing(code: String, device: DeviceInfo): PairingResult

    /** Поток сигналов «появились изменения» (WS push). Завершается при закрытии соединения. */
    fun changes(session: SyncSession): Flow<SyncSignal>

    /**
     * Лёгкая проверка доступности сервера (health-пробник `GET /healthz`, без аутентификации):
     * `true`, если сервер ответил успехом. Намеренно НЕ бросает — любой сетевой/протокольный сбой
     * даёт `false`. Питает индикатор «сервер доступен», который должен работать и при заблокированном
     * vault (активной сессии может не быть).
     */
    suspend fun ping(): Boolean

    /** Освобождает сетевые ресурсы (HTTP/WS клиент). */
    suspend fun close()
}

/**
 * Сигнал live-sync из WS-канала `/sync`. Кадры не несут содержимого — только «что перечитать»:
 * `{cursor}` → [Account]; `team:{id}:{cursor}` → [Team]; `teams` → [Membership].
 */
sealed interface SyncSignal {
    /** В аккаунтном vault появились изменения до [cursor] — сделать дельта-pull. */
    data class Account(val cursor: Long) : SyncSignal

    /** В команде [teamId] появились записи до [cursor] — синкнуть team-vault. */
    data class Team(val teamId: String, val cursor: Long) : SyncSignal

    /** Изменился состав команд/приглашения аккаунта — перечитать список команд. */
    data object Membership : SyncSignal
}

/** Ошибка sync-клиента: сетевая, протокольная или ожидаемая (нет аккаунта / неверный пароль). */
class SyncException(val kind: Kind, message: String, cause: Throwable? = null) : Exception(message, cause) {
    enum class Kind { NETWORK, UNAUTHORIZED, CONFLICT, NOT_FOUND, GONE, PROTOCOL }
}
