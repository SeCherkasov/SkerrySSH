package app.skerry.shared.vault

/**
 * 256-битный ключ, выведенный из мастер-пароля через Argon2id (см. [VaultCrypto.deriveMasterKey]).
 * Существует только в памяти устройства и никогда не сериализуется — наружу уходит лишь
 * обёртка dataKey ([VaultCrypto.wrapDataKey]). Байты держатся `internal`, чтобы ключевой
 * материал не утекал в публичный API, логи или случайные сравнения; [toString] намеренно
 * не печатает содержимое.
 */
class MasterKey internal constructor(internal val bytes: ByteArray) {
    /**
     * Затереть ключевой материал (`bytes.fill(0)`). Вызывать, как только masterKey больше не нужен:
     * это выход Argon2id, держать его в heap дольше необходимого незачем (heap-дамп/своп раскрыл бы
     * ключ). Код `shared` затирает свои masterKey сам; этот публичный метод нужен вызывающим за
     * пределами модуля (напр. `SyncCoordinator`), которым `bytes` недоступны. Идемпотентно.
     */
    fun zeroize() { bytes.fill(0) }

    override fun toString(): String = "MasterKey(redacted)"
}

/**
 * 256-битный случайный ключ данных. Шифрует каждую запись vault (XChaCha20-Poly1305) и сам
 * хранится только в обёрнутом виде под [MasterKey]. Смена мастер-пароля переобёртывает этот
 * ключ, не перешифровывая записи — см. `docs/skerry-sync-design.md` §1.
 */
class DataKey internal constructor(internal val bytes: ByteArray) {
    /**
     * Затереть ключевой материал **копии** dataKey (например полученной из [Vault.exportDataKey] или
     * [VaultCrypto.unwrapDataKey]). НЕ вызывать на ключе, которым ещё владеет живой vault — затрёшь
     * рабочий ключ. Для копий, время жизни которых вызывающий контролирует сам. Идемпотентно.
     */
    fun zeroize() { bytes.fill(0) }

    override fun toString(): String = "DataKey(redacted)"
}

/**
 * Криптопримитивы локального zero-knowledge vault. Реализация платформенная (на JVM —
 * libsodium через биндинги), поэтому интерфейс живёт в ядре, а параметры стойкости
 * (Argon2id m=64MiB/t=3/p=4) и шифр (XChaCha20-Poly1305) — детали реализации, не контракта.
 * Та же иерархия ключей переиспользуется E2E-синхронизацией в Phase 2.
 *
 * Соглашение об ошибках: [unwrapDataKey] и [open] возвращают `null` **только** при провале
 * проверки AEAD-тега (неверный ключ/мастер-пароль или повреждённый шифротекст) — это
 * ожидаемый, обрабатываемый исход. Структурно некорректный вход (неверная длина ключа,
 * усечённый блоб без места под nonce/тег) — программная ошибка и бросает исключение.
 */
interface VaultCrypto {

    companion object {
        /** Пустой AAD — привязки слота нет; геттер отдаёт свежий массив (без shared-mutable). */
        val EMPTY_AAD: ByteArray get() = ByteArray(0)
    }

    /** Новая случайная соль для деривации мастер-ключа (длина — требование Argon2id). */
    fun newSalt(): ByteArray

    /**
     * Детерминированная соль деривации masterKey для self-hosted sync — выводится из [accountId]
     * (`docs/skerry-sync-design.md` §1: «salt = accountId»). Одинакова на всех устройствах, не
     * секрет; позволяет новому устройству вывести тот же masterKey из одного мастер-пароля и
     * развернуть серверную обёртку dataKey, не получая соль с сервера. Длина = длине [newSalt].
     */
    fun deriveSyncSalt(accountId: String): ByteArray

    /**
     * Argon2id(password, salt) → [MasterKey]. Намеренно дорогая операция (десятки мс и выше);
     * детерминирована по паре (password, salt). Вызывающая сторона отвечает за затирание
     * [password] после вызова.
     */
    fun deriveMasterKey(password: CharArray, salt: ByteArray): MasterKey

    /** Новый случайный [DataKey] — создаётся один раз при инициализации vault. */
    fun newDataKey(): DataKey

    /**
     * Детерминированно выводит 256-битный authKey из [masterKey] для аутентификации в self-hosted
     * sync (`docs/skerry-sync-design.md` §1: HKDF-ветка masterKey → authKey → SRP-верификатор).
     * Отдельный домен деривации, не пересекающийся с обёрткой dataKey; сервер authKey не видит —
     * на него уходит лишь SRP-верификатор. Детерминирована по [masterKey].
     */
    fun deriveAuthKey(masterKey: MasterKey): ByteArray

    /**
     * Новый одноразовый transfer-ключ для быстрого паринга устройств (вариант B,
     * `docs/skerry-sync-design.md` §3): 32 случайных байта (длина AEAD-ключа). Уходит **только** в
     * QR/код, который пользователь переносит на новое устройство глазами/камерой — на сервер не
     * попадает, поэтому серверный шифротекст dataKey без него бесполезен. Возвращается как сырые
     * байты (а не [DataKey]), потому что должен сериализоваться в полезную нагрузку паринга.
     */
    fun newTransferKey(): ByteArray

    /**
     * Запечатать живой [dataKey] под одноразовым [transferKey] для переноса на новое устройство
     * (вариант B). Результат (nonce-prefix XChaCha20-Poly1305) кладётся на сервер в pairing-сессию;
     * развернуть его можно только тем же [transferKey], которого у сервера нет. Доменный AAD
     * отделяет конверт паринга от обёртки dataKey и записей. [transferKey] должен быть длиной
     * AEAD-ключа (см. [newTransferKey]).
     */
    fun sealDataKeyForTransfer(dataKey: DataKey, transferKey: ByteArray): ByteArray

    /**
     * Развернуть перенесённый dataKey на новом устройстве тем [transferKey], что пришёл из QR/кода.
     * `null` ⇒ неверный [transferKey] или подменённый конверт (AEAD-провал) — как [unwrapDataKey].
     */
    fun openTransferredDataKey(transferKey: ByteArray, envelope: ByteArray): DataKey?

    /** Обернуть [dataKey] мастер-ключом для хранения на диске/сервере (видит только шифротекст). */
    fun wrapDataKey(masterKey: MasterKey, dataKey: DataKey): ByteArray

    /** Развернуть обёртку. `null` ⇒ неверный мастер-пароль или повреждённая обёртка. */
    fun unwrapDataKey(masterKey: MasterKey, wrapped: ByteArray): DataKey?

    /**
     * Зашифровать запись под [dataKey]; случайный nonce кладётся в префикс результата.
     *
     * [associatedData] аутентифицируется (AEAD), но не шифруется: вызывающая сторона должна
     * привязать сюда стабильный идентификатор слота записи (напр. `id‖type`), чтобы шифроблок
     * нельзя было бесшумно переставить в чужой слот — [open] с другим AAD вернёт `null`. AAD
     * передаётся всегда явно (дефолта нет — молчаливо «без привязки» запечатать нельзя);
     * сознательное отсутствие привязки — [EMPTY_AAD].
     */
    fun seal(dataKey: DataKey, plaintext: ByteArray, associatedData: ByteArray): ByteArray

    /**
     * Расшифровать запись. `null` ⇒ неверный ключ, повреждённый/подменённый шифротекст **или**
     * [associatedData], не совпавший с тем, под которым запись была запечатана.
     */
    fun open(dataKey: DataKey, ciphertext: ByteArray, associatedData: ByteArray): ByteArray?
}
