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

    /**
     * Разблокировать тем же `dataKey`, минуя мастер-пароль — путь биометрии. `dataKey` обычно
     * приходит из [BiometricKeyStore.unwrap] (обёртка `vault.bio`), обёрнутый под `bioKey`
     * устройства. Реализация **присваивает** переданный [dataKey] (вызывающий его не затирает) и
     * грузит записи из файла; [UnlockResult.Corrupted], если файл не читается. Метод намеренно
     * не проверяет, что `dataKey` верен (нет мастер-ключа для сверки): неверный ключ просто не
     * откроет записи (AEAD-провал в [openPayload]). Использовать только из доверенного пути
     * биометрии — см. `docs/skerry-biometric-design.md` §4.
     */
    fun unlockWithDataKey(dataKey: DataKey): UnlockResult

    /**
     * Выгрузить **копию** текущего `dataKey` для включения биометрии (его обернут под `bioKey` и
     * сохранят в `vault.bio`). `null`, если vault заблокирован. Возвращается [DataKey], чьи байты
     * `internal` — UI их не прочитает; копия, чтобы вызывающий мог затереть её после обёртки, не
     * затронув живой ключ. Единственный санкционированный способ достать `dataKey` наружу; держать
     * результат минимально и сразу затирать (`bytes.fill(0)` доступен только коду `shared`).
     */
    fun exportDataKey(): DataKey?

    /** Заблокировать: затереть `dataKey` из памяти. После — [isUnlocked] == false. */
    fun lock()

    /**
     * Безвозвратно сбросить vault: затереть `dataKey`/метаданные/записи из памяти и **удалить файл**
     * с диска. После вызова [exists] == false и [isUnlocked] == false — vault возвращается в исходное
     * состояние «ещё не создан».
     *
     * Это аварийный выход для забытого мастер-пароля или повреждённого файла: zero-knowledge не
     * допускает восстановления, поэтому единственная альтернатива тупику — стереть всё и начать заново
     * (модель Bitwarden/1Password; удаление необратимо, бэкап не оставляется). Сохранённые секреты
     * теряются; внешние данные, не входящие в файл vault (профили хостов, known_hosts), за пределами
     * этого контракта — их чистит вызывающий. Биометрию (`vault.bio`) тоже снимает вызывающий: vault
     * про неё не знает. Идемпотентно: повторный вызов / отсутствие файла — no-op.
     */
    fun reset()

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
