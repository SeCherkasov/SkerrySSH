package app.skerry.shared.vault

/** Результат разблокировки [Vault]: успех, неверный пароль или нечитаемый файл. */
sealed interface UnlockResult {
    data object Success : UnlockResult
    data object WrongPassword : UnlockResult
    data object Corrupted : UnlockResult
}

/**
 * Локальное зашифрованное хранилище записей (хосты/ключи/identity). Иерархия ключей и формат
 * — `docs/skerry-sync-design.md` (Argon2id → masterKey → dataKey, XChaCha20-Poly1305), крипто
 * за [VaultCrypto]. В отличие от [app.skerry.shared.host.HostStore], у vault есть жизненный
 * цикл: пока он заблокирован, `dataKey` не в памяти и любой CRUD бросает [IllegalStateException].
 *
 * `dataKey` наружу не отдаётся: CRUD работает с открытым payload, шифрование/расшифровка с
 * привязкой AAD к `id‖type` происходят внутри. Платформенная реализация — файловая
 * ([app.skerry.shared.vault.FileVault] на desktop). Переданные пароли реализация затирает.
 */
interface Vault {

    /** Существует ли уже файл vault (для выбора экрана «создать» против «разблокировать»). */
    fun exists(): Boolean

    /** Разблокирован ли vault (`dataKey` в памяти). */
    val isUnlocked: Boolean

    /**
     * Создать новый vault с нуля (salt + случайный dataKey + обёртка под мастер-паролем),
     * записать пустой файл. После вызова vault разблокирован. Перезаписывает существующий
     * файл — вызывающий проверяет [exists] заранее.
     */
    fun create(password: CharArray)

    /** Разблокировать существующий vault; см. [UnlockResult]. */
    fun unlock(password: CharArray): UnlockResult

    /** Заблокировать: затереть `dataKey` из памяти. После — [isUnlocked] == false. */
    fun lock()

    /** Метаданные всех записей, включая tombstone (`deleted=true`); вызывающий фильтрует сам. */
    fun records(): List<VaultRecord>

    /** Расшифрованный payload записи; `null` если записи нет, она удалена (tombstone) или blob не проходит AEAD. */
    fun openPayload(id: String): ByteArray?

    /**
     * Upsert: запечатать [payload] под `dataKey` (AAD = `id‖type`) и сохранить запись с этим
     * [id]/[type], увеличив `version` и обновив `updatedAt`.
     */
    fun put(id: String, type: RecordType, payload: ByteArray)

    /** Мягко удалить запись (tombstone): `deleted=true`, `version++`. Неизвестный id — no-op. */
    fun remove(id: String)

    /**
     * Сменить мастер-пароль: переобернуть тот же `dataKey` под новым паролем (записи не
     * перешифровываются). `false`, если [oldPassword] неверен. Требует разблокированного vault.
     */
    fun changePassword(oldPassword: CharArray, newPassword: CharArray): Boolean
}
