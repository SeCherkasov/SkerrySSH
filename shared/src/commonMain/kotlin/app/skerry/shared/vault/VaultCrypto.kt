package app.skerry.shared.vault

/**
 * 256-битный ключ, выведенный из мастер-пароля через Argon2id (см. [VaultCrypto.deriveMasterKey]).
 * Существует только в памяти устройства и никогда не сериализуется — наружу уходит лишь
 * обёртка dataKey ([VaultCrypto.wrapDataKey]). Байты держатся `internal`, чтобы ключевой
 * материал не утекал в публичный API, логи или случайные сравнения; [toString] намеренно
 * не печатает содержимое.
 */
class MasterKey internal constructor(internal val bytes: ByteArray) {
    override fun toString(): String = "MasterKey(redacted)"
}

/**
 * 256-битный случайный ключ данных. Шифрует каждую запись vault (XChaCha20-Poly1305) и сам
 * хранится только в обёрнутом виде под [MasterKey]. Смена мастер-пароля переобёртывает этот
 * ключ, не перешифровывая записи — см. `docs/skerry-sync-design.md` §1.
 */
class DataKey internal constructor(internal val bytes: ByteArray) {
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
        /** AAD по умолчанию — привязки слота нет; геттер отдаёт свежий массив (без shared-mutable). */
        val EMPTY_AAD: ByteArray get() = ByteArray(0)
    }

    /** Новая случайная соль для деривации мастер-ключа (длина — требование Argon2id). */
    fun newSalt(): ByteArray

    /**
     * Argon2id(password, salt) → [MasterKey]. Намеренно дорогая операция (десятки мс и выше);
     * детерминирована по паре (password, salt). Вызывающая сторона отвечает за затирание
     * [password] после вызова.
     */
    fun deriveMasterKey(password: CharArray, salt: ByteArray): MasterKey

    /** Новый случайный [DataKey] — создаётся один раз при инициализации vault. */
    fun newDataKey(): DataKey

    /** Обернуть [dataKey] мастер-ключом для хранения на диске/сервере (видит только шифротекст). */
    fun wrapDataKey(masterKey: MasterKey, dataKey: DataKey): ByteArray

    /** Развернуть обёртку. `null` ⇒ неверный мастер-пароль или повреждённая обёртка. */
    fun unwrapDataKey(masterKey: MasterKey, wrapped: ByteArray): DataKey?

    /**
     * Зашифровать запись под [dataKey]; случайный nonce кладётся в префикс результата.
     *
     * [associatedData] аутентифицируется (AEAD), но не шифруется: вызывающая сторона должна
     * привязать сюда стабильный идентификатор слота записи (напр. `id‖type`), чтобы шифроблок
     * нельзя было бесшумно переставить в чужой слот — [open] с другим AAD вернёт `null`. По
     * умолчанию AAD пуст (привязки нет).
     */
    fun seal(dataKey: DataKey, plaintext: ByteArray, associatedData: ByteArray = EMPTY_AAD): ByteArray

    /**
     * Расшифровать запись. `null` ⇒ неверный ключ, повреждённый/подменённый шифротекст **или**
     * [associatedData], не совпавший с тем, под которым запись была запечатана.
     */
    fun open(dataKey: DataKey, ciphertext: ByteArray, associatedData: ByteArray = EMPTY_AAD): ByteArray?
}
