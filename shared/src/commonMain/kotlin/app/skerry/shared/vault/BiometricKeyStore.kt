package app.skerry.shared.vault

import kotlinx.serialization.Serializable

/**
 * Доступность биометрии на устройстве в момент опроса. Опрашивается перед каждой операцией:
 * пользователь мог добавить/убрать отпечаток или временно залочить сенсор между запусками.
 * `NoHardware` — штатное состояние desktop (единого биометрического API нет), там тумблер скрыт.
 */
enum class BiometricAvailability {
    /** Железо есть и хотя бы один биометрический фактор зачислен — можно включать/разблокировать. */
    Available,

    /** Сенсора нет (или платформа без биометрии — desktop MVP). */
    NoHardware,

    /** Железо есть, но отпечаток/лицо не настроены — предложить настроить в системе. */
    NotEnrolled,

    /** Временно заблокировано после серии неудачных попыток — только пароль до разлока системой. */
    LockedOut,
}

/**
 * Исход биометрической операции, огороженной системным промптом. Параметризован полезной
 * нагрузкой ([Success.value]) — для `wrap`/`unwrap` это `ByteArray`. Неуспехи разделены, чтобы
 * оркестрация различала «молча падаем на пароль» (`Cancelled`/`Failed`) и «ключ инвалидирован,
 * биометрию надо пересоздать» (`KeyInvalidated`, напр. добавлен новый отпечаток — см. модель угроз).
 */
sealed interface BiometricResult<out T> {
    data class Success<T>(val value: T) : BiometricResult<T>

    /** Пользователь отменил промпт или истёк таймаут — не ошибка, просто нет результата. */
    data object Cancelled : BiometricResult<Nothing>

    /** Биометрия не распозналась / сбой сенсора — откат на мастер-пароль. */
    data object Failed : BiometricResult<Nothing>

    /**
     * `bioKey` безвозвратно инвалидирован платформой (изменился набор биометрии). Оркестрация
     * обязана удалить артефакт `vault.bio` и потребовать мастер-пароль. См. `setInvalidatedBy
     * BiometricEnrollment` (Android) / `.biometryCurrentSet` (iOS) в `docs/skerry-biometric-design.md`.
     */
    data object KeyInvalidated : BiometricResult<Nothing>
}

/**
 * Тексты системного биометрического промпта. UI-строки (локализованные) приходят сверху —
 * `commonMain` их не зашивает. `cancelLabel` обязателен: на Android это negative button промпта.
 */
data class BiometricPrompt(
    val title: String,
    val cancelLabel: String,
    val subtitle: String? = null,
)

/**
 * Платформенное защищённое биометрией хранилище ключа `bioKey`. Реализация платформенная
 * (Android Keystore + `androidx.biometric`; iOS Keychain + Secure Enclave + LocalAuthentication;
 * desktop — `NoHardware`-заглушка), поэтому контракт живёт в ядре, а железо за интерфейсом
 * (по `architecture-discipline`). `bioKey` неизвлекаем: наружу выходит только обёртка `dataKey`.
 *
 * `wrap`/`unwrap` — `suspend`, потому что показывают системный промпт и ждут пользователя; их
 * **нельзя** звать под `synchronized`-локой [Vault]. Вызывающая сторона отвечает за затирание
 * переданного `plaintext` после [wrap]; реализация его не удерживает.
 */
interface BiometricKeyStore {

    /** Текущая доступность биометрии; опрашивать перед каждой операцией. */
    fun availability(): BiometricAvailability

    /**
     * Идемпотентно создать неизвлекаемый `bioKey` под [alias] в secure storage. `false`, если
     * создать нельзя (нет железа/не зачислена биометрия) — вызывающий не продолжает включение.
     */
    suspend fun ensureKey(alias: String): Boolean

    /** Показать промпт и при успехе обернуть [plaintext] ключом [alias]. */
    suspend fun wrap(alias: String, plaintext: ByteArray, prompt: BiometricPrompt): BiometricResult<ByteArray>

    /** Показать промпт и при успехе развернуть [wrapped] ключом [alias]. */
    suspend fun unwrap(alias: String, wrapped: ByteArray, prompt: BiometricPrompt): BiometricResult<ByteArray>

    /** Удалить `bioKey` (выключение биометрии, паника, смена устройства). Неизвестный alias — no-op. */
    fun deleteKey(alias: String)
}

/**
 * Открытый артефакт `vault.bio`: обёртка `dataKey` под `bioKey` плюс метаданные для разворота.
 * Лежит рядом с `vault.json`; `dataKey` один и тот же, поэтому смена мастер-пароля артефакт не
 * трогает (см. дизайн-док §2). Сам по себе бесполезен без `bioKey` из secure-enclave устройства.
 */
@Serializable
data class BioArtifact(
    val formatVersion: Int,
    val alias: String,
    val deviceId: String,
    val wrappedBio: ByteArray,
) {
    // ByteArray ломает автогенерацию структурного equals/hashCode — реализованы вручную. При
    // добавлении поля обновить обе функции И toString (компилятор об этом не предупредит).
    override fun toString(): String =
        "BioArtifact(formatVersion=$formatVersion, alias=$alias, deviceId=$deviceId, wrappedBio=<redacted>)"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BioArtifact) return false
        return formatVersion == other.formatVersion &&
            alias == other.alias &&
            deviceId == other.deviceId &&
            wrappedBio.contentEquals(other.wrappedBio)
    }

    override fun hashCode(): Int {
        var result = formatVersion
        result = 31 * result + alias.hashCode()
        result = 31 * result + deviceId.hashCode()
        result = 31 * result + wrappedBio.contentHashCode()
        return result
    }
}

/**
 * Персистентность артефакта `vault.bio`. Отдельный контракт (как [Vault] над файлом), чтобы
 * оркестрацию [VaultBiometrics] можно было тестировать на `FakeFileSystem` без реального железа.
 */
interface BioArtifactStore {
    /** Есть ли сохранённый артефакт (включена ли биометрия для этого vault). */
    fun exists(): Boolean

    /** Прочитать артефакт; `null`, если файла нет или он не парсится. */
    fun read(): BioArtifact?

    /** Записать/перезаписать артефакт атомарно. */
    fun write(artifact: BioArtifact)

    /** Удалить артефакт (выключение биометрии). Отсутствие файла — no-op. */
    fun clear()
}
