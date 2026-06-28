package app.skerry.shared.vault

/** Исход включения биометрии для vault. */
enum class BiometricEnableResult {
    /** Биометрия включена: `vault.bio` записан. */
    Enabled,

    /** Vault заблокирован — нечего оборачивать; сперва разблокировать паролем. */
    VaultLocked,

    /** Биометрия недоступна (нет железа/не зачислена/залочена) — тумблер не должен был дойти сюда. */
    Unavailable,

    /** Пользователь отменил промпт. */
    Cancelled,

    /** Сбой биометрии/железа — не включили. */
    Failed,
}

/** Исход разблокировки vault биометрией. */
sealed interface BiometricUnlockResult {
    /** Vault разблокирован тем же `dataKey`. */
    data object Unlocked : BiometricUnlockResult

    /** Биометрия для этого vault не включена (`vault.bio` нет) — показать форму пароля. */
    data object NotEnabled : BiometricUnlockResult

    /** Биометрия недоступна сейчас (нет железа/залочена) — форма пароля. */
    data object Unavailable : BiometricUnlockResult

    /** Пользователь отменил промпт — остаться на форме пароля. */
    data object Cancelled : BiometricUnlockResult

    /** Сбой биометрии — форма пароля. */
    data object Failed : BiometricUnlockResult

    /**
     * `bioKey` инвалидирован (новый отпечаток/лицо). Биометрия **выключена** (артефакт удалён) —
     * пользователь обязан войти мастер-паролем и при желании включить биометрию заново.
     */
    data object Invalidated : BiometricUnlockResult

    /** Файл vault не читается — биометрия развернула ключ, но данные битые. */
    data object Corrupted : BiometricUnlockResult
}

/**
 * Исход биометрического подтверждения личности перед чувствительным действием в открытом vault
 * (копирование пароля). В отличие от [BiometricUnlockResult] не открывает vault — лишь доказывает
 * присутствие владельца через тот же `bioKey`.
 */
sealed interface BiometricConfirmResult {
    /** Биометрия пройдена — действие можно выполнять. */
    data object Confirmed : BiometricConfirmResult

    /** Биометрия для этого vault не включена (`vault.bio` нет) — вызывающий просит мастер-пароль. */
    data object NotEnabled : BiometricConfirmResult

    /** Биометрия недоступна сейчас (нет железа/залочена) — откат на мастер-пароль. */
    data object Unavailable : BiometricConfirmResult

    /** Пользователь отменил промпт — действие не выполняем. */
    data object Cancelled : BiometricConfirmResult

    /** Сбой биометрии — действие не выполняем. */
    data object Failed : BiometricConfirmResult

    /**
     * `bioKey` инвалидирован (новый отпечаток/лицо). Биометрия **выключена** (как в [unlock]) —
     * вызывающий откатывается на мастер-пароль.
     */
    data object Invalidated : BiometricConfirmResult
}

/**
 * Оркестрация биометрической разблокировки поверх [Vault] + [BiometricKeyStore] + [BioArtifactStore].
 * Платформо-независима (контракт — `commonMain`), поэтому покрыта TDD на фейках без железа.
 *
 * Инвариант zero-knowledge: `dataKey` достаётся из vault только через [Vault.exportDataKey]
 * (копия, затирается здесь же после обёртки) и возвращается через [Vault.unlockWithDataKey] — в
 * открытом виде наружу из `shared` не выходит. Оборачивается именно `dataKey`, поэтому смена
 * мастер-пароля ([Vault.changePassword]) **не трогает** `vault.bio` — биометрия продолжает
 * работать без перенастройки (развязка, см. дизайн-док §2).
 *
 * `alias` детерминирован по [deviceId] — один `bioKey` на устройство. `wrap`/`unwrap` зовутся
 * вне vault-локи (это `suspend`-промпты), что корректно: vault синхронизируется внутри себя.
 */
class VaultBiometrics(
    private val vault: Vault,
    private val keyStore: BiometricKeyStore,
    private val artifacts: BioArtifactStore,
    private val deviceId: String,
    private val alias: String = "skerry.vault.bio.$deviceId",
) {

    /** Доступность биометрии на устройстве — для показа/скрытия тумблера и кнопки. */
    fun availability(): BiometricAvailability = keyStore.availability()

    /** Включена ли биометрия для этого vault (есть ли `vault.bio`). */
    fun isEnabled(): Boolean = artifacts.exists()

    /**
     * Включить биометрию: vault должен быть разблокирован. Оборачивает текущий `dataKey` под
     * `bioKey` и сохраняет `vault.bio`. Экспортированную копию ключа затирает в `finally`.
     */
    suspend fun enable(prompt: BiometricPrompt): BiometricEnableResult {
        if (keyStore.availability() != BiometricAvailability.Available) return BiometricEnableResult.Unavailable
        val dataKey = vault.exportDataKey() ?: return BiometricEnableResult.VaultLocked
        try {
            if (!keyStore.ensureKey(alias)) return BiometricEnableResult.Unavailable
            return when (val wrapped = keyStore.wrap(alias, dataKey.bytes, prompt)) {
                is BiometricResult.Success -> {
                    artifacts.write(BioArtifact(FORMAT_VERSION, alias, deviceId, wrapped.value))
                    BiometricEnableResult.Enabled
                }
                BiometricResult.Cancelled -> BiometricEnableResult.Cancelled
                BiometricResult.Failed -> BiometricEnableResult.Failed
                BiometricResult.KeyInvalidated -> {
                    keyStore.deleteKey(alias) // свежесозданный ключ уже инвалидирован — не оставлять
                    BiometricEnableResult.Failed
                }
            }
        } finally {
            dataKey.bytes.fill(0)
        }
    }

    /** Выключить биометрию: удалить `bioKey` и `vault.bio`. Идемпотентно. */
    fun disable() {
        keyStore.deleteKey(alias)
        artifacts.clear()
    }

    /**
     * Разблокировать vault биометрией (холодный старт). Любой неуспех — мягкий откат на форму
     * мастер-пароля; при инвалидации ключа биометрия выключается. `dataKey` из [unwrap] передаётся
     * во [Vault.unlockWithDataKey], который им владеет (а на `Corrupted` — затирает).
     */
    suspend fun unlock(prompt: BiometricPrompt): BiometricUnlockResult {
        val artifact = artifacts.read() ?: return BiometricUnlockResult.NotEnabled
        // Артефакт с диска недоверенный: формат/alias/deviceId должны совпасть с ожидаемыми. Иначе
        // это файл другого устройства, подмена или иной формат — мягкий откат на пароль, артефакт
        // не удаляем (это не инвалидация ключа). Сверка alias заодно убирает асимметрию с disable().
        if (artifact.formatVersion != FORMAT_VERSION || artifact.alias != alias || artifact.deviceId != deviceId) {
            return BiometricUnlockResult.NotEnabled
        }
        if (keyStore.availability() != BiometricAvailability.Available) return BiometricUnlockResult.Unavailable
        return when (val unwrapped = keyStore.unwrap(alias, artifact.wrappedBio, prompt)) {
            is BiometricResult.Success -> {
                val dataKey = DataKey(unwrapped.value) // владение передаём vault (он же затирает на Corrupted)
                try {
                    when (vault.unlockWithDataKey(dataKey)) {
                        UnlockResult.Success -> BiometricUnlockResult.Unlocked
                        UnlockResult.Corrupted -> BiometricUnlockResult.Corrupted
                        // unlockWithDataKey не выводит мастер-ключ и по контракту не возвращает WrongPassword;
                        // явная ветка вместо else — чтобы новая ветка UnlockResult не утекла молча.
                        UnlockResult.WrongPassword -> error("unlockWithDataKey не сверяет пароль — WrongPassword недостижим")
                    }
                } catch (e: Throwable) {
                    dataKey.bytes.fill(0) // нештатный путь: не оставить развёрнутый ключ в памяти
                    throw e
                }
            }
            BiometricResult.Cancelled -> BiometricUnlockResult.Cancelled
            BiometricResult.Failed -> BiometricUnlockResult.Failed
            BiometricResult.KeyInvalidated -> {
                disable() // биометрия скомпрометирована сменой набора — снять и потребовать пароль
                BiometricUnlockResult.Invalidated
            }
        }
    }

    /**
     * Подтвердить личность владельца биометрией **без разблокировки** vault — для повторной
     * аутентификации перед чувствительным действием в уже открытой сессии (копирование пароля).
     * Тот же путь, что [unlock] (читает `vault.bio`, сверяет alias/deviceId, разворачивает через
     * [BiometricKeyStore.unwrap] с системным промптом), но развёрнутый ключ не присваивается vault,
     * а сразу затирается: нужен лишь факт успешной аутентификации. Инвалидация ключа выключает
     * биометрию (как в [unlock]) — вызывающий откатится на мастер-пароль. Vault не трогается.
     */
    suspend fun confirm(prompt: BiometricPrompt): BiometricConfirmResult {
        val artifact = artifacts.read() ?: return BiometricConfirmResult.NotEnabled
        if (artifact.formatVersion != FORMAT_VERSION || artifact.alias != alias || artifact.deviceId != deviceId) {
            return BiometricConfirmResult.NotEnabled
        }
        if (keyStore.availability() != BiometricAvailability.Available) return BiometricConfirmResult.Unavailable
        return when (val unwrapped = keyStore.unwrap(alias, artifact.wrappedBio, prompt)) {
            is BiometricResult.Success -> {
                unwrapped.value.fill(0) // ключ не нужен — нужен лишь факт успешной аутентификации
                BiometricConfirmResult.Confirmed
            }
            BiometricResult.Cancelled -> BiometricConfirmResult.Cancelled
            BiometricResult.Failed -> BiometricConfirmResult.Failed
            BiometricResult.KeyInvalidated -> {
                disable() // ключ скомпрометирован сменой набора — снять и потребовать пароль
                BiometricConfirmResult.Invalidated
            }
        }
    }

    private companion object {
        const val FORMAT_VERSION = 1
    }
}
