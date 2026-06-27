package app.skerry.ui.vault

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.skerry.shared.vault.BiometricAvailability
import app.skerry.shared.vault.BiometricEnableResult
import app.skerry.shared.vault.BiometricPrompt
import app.skerry.shared.vault.BiometricUnlockResult
import app.skerry.shared.vault.UnlockResult
import app.skerry.shared.vault.Vault
import app.skerry.shared.vault.VaultBiometrics

/**
 * Минимальная длина мастер-пароля. Выше типичного «8» (NIST для серверных паролей со счётчиком
 * попыток): vault-файл атакуют offline без ограничения попыток, единственный барьер — Argon2id.
 * Единственный источник правды и для валидации, и для текста ошибки в UI.
 */
const val MIN_MASTER_PASSWORD_LENGTH: Int = 12

/**
 * Слово, которое пользователь должен вписать на экране сброса (type-to-confirm), чтобы подтвердить
 * безвозвратное стирание vault. Единый источник и для UI-поля, и для проверки — как удаление репозитория
 * в GitHub: барьер от случайного клика по деструктивному действию.
 */
const val RESET_CONFIRM_WORD: String = "RESET"

/** Экран гейта мастер-пароля поверх [Vault]. */
enum class VaultGateState {
    /** Файла vault ещё нет — показываем форму создания мастер-пароля. */
    NeedsCreate,

    /** Vault существует, но заблокирован — показываем форму разблокировки. */
    NeedsUnlock,

    /** Файл vault не читается — тупик: вводить пароль бессмысленно, показываем экран сброса. */
    Corrupted,

    /** Пользователь подтверждает безвозвратный сброс (забыл пароль / битый файл). */
    Resetting,

    /**
     * Vault только что создан и уже открыт, но прежде чем пустить в приложение — разовое предложение
     * включить разблокировку биометрией. Показывается лишь когда биометрия доступна на устройстве;
     * любой исход (включил/отказался) ведёт в [Unlocked].
     */
    OfferBiometric,

    /** Vault разблокирован — пропускаем к остальному UI. */
    Unlocked,
}

/**
 * Что стирать при сбросе vault. Сам vault удаляется всегда (контракт [Vault.reset]); этот выбор
 * управляет только внешними данными, не входящими в файл vault. Решение принимает пользователь на
 * экране сброса, исполнение внешней чистки — инжектируемый колбэк `onReset` (контроллер про хосты
 * не знает: гейт остаётся над одним [Vault]).
 */
enum class ResetScope {
    /** Стереть только секреты (файл vault). Профили хостов и known_hosts остаются. */
    SecretsOnly,

    /** Заводской сброс: vault + профили хостов + known_hosts + локальные настройки. */
    Everything,
}

/**
 * Причина неуспеха последней попытки. Структурированный тип (не строка), чтобы текст
 * локализовался в UI, а тесты не зависели от формулировок.
 */
enum class VaultGateError {
    /** Пароль короче [VaultGateController.minPasswordLength]. */
    PasswordTooShort,

    /** Пароль и подтверждение не совпали. */
    PasswordMismatch,

    /** Неверный мастер-пароль при разблокировке. */
    WrongPassword,

    /** Файл vault не читается/повреждён. */
    Corrupted,

    /** Биометрия сброшена (новый отпечаток/лицо) — она снята, нужен мастер-пароль. */
    BiometricReset,
}

/**
 * Гейт мастер-пароля: блокирует доступ к остальному UI, пока [Vault] не разблокирован.
 * Стартовое состояние выбирается по [Vault.exists] — создать против разблокировать.
 *
 * [Vault] синхронный (Argon2id-деривация идёт в его реализации), поэтому контроллер, как и
 * [app.skerry.ui.host.HostManagerController], не держит корутинной scope. Пароли приходят
 * как [CharArray] и затираются здесь же: [Vault.create]/[Vault.unlock] затирают переданный
 * буфер по контракту, а подтверждение и не дошедшие до vault буферы гасит сам контроллер.
 */
@Stable
class VaultGateController(
    private val vault: Vault,
    private val biometrics: VaultBiometrics? = null,
    private val minPasswordLength: Int = MIN_MASTER_PASSWORD_LENGTH,
    /**
     * Внешняя чистка при сбросе (хосты/known_hosts/настройки по [ResetScope]). Вызывается ПОСЛЕ
     * [Vault.reset], когда vault уже стёрт. Контроллер про эти данные не знает — их предоставляет
     * платформенная проводка (desktop `main`). По умолчанию no-op (мок/превью).
     */
    private val onReset: (ResetScope) -> Unit = {},
) {
    var state: VaultGateState by mutableStateOf(
        if (vault.exists()) VaultGateState.NeedsUnlock else VaultGateState.NeedsCreate,
    )
        private set

    var error: VaultGateError? by mutableStateOf(null)
        private set

    /** Куда вернуться, если пользователь отменил экран сброса (на форму входа или экран Corrupted). */
    private var resetReturnState: VaultGateState = VaultGateState.NeedsUnlock

    /** Включена ли биометрия для этого vault (реактивно — тумблер обновляет интерфейс). */
    var biometricEnabled: Boolean by mutableStateOf(biometrics?.isEnabled() == true)
        private set

    /** Счётчик активности пользователя — авто-лок по простою перезапускается при его изменении. */
    var activityTick: Int by mutableStateOf(0)
        private set

    /**
     * Идёт ли сейчас биометрический промпт. Авто-лок при уходе в фон должен его пропускать: системный
     * промпт может слать `ON_STOP`, и блокировка посреди аутентификации привела бы к тому, что
     * пользователь успешно приложил палец, а vault остался заперт (результат уже некому принять).
     */
    var biometricInFlight: Boolean by mutableStateOf(false)
        private set

    /**
     * Создать vault, если пароль проходит валидацию и совпадает с [confirm]. Оба буфера
     * затираются в любом исходе. При ошибке валидации vault не трогается, состояние остаётся
     * [VaultGateState.NeedsCreate].
     */
    fun create(password: CharArray, confirm: CharArray) {
        try {
            error = null
            when {
                password.size < minPasswordLength -> error = VaultGateError.PasswordTooShort
                !password.contentEquals(confirm) -> error = VaultGateError.PasswordMismatch
                else -> {
                    vault.create(password)
                    // Новый vault открыт. Если устройство умеет биометрию — разовое предложение её
                    // включить (экран [VaultGateState.OfferBiometric]); иначе сразу в приложение.
                    state = if (canEnableBiometric()) VaultGateState.OfferBiometric else VaultGateState.Unlocked
                }
            }
        } finally {
            password.fill(' ')
            confirm.fill(' ')
        }
    }

    /**
     * Разблокировать существующий vault; на ошибке остаёмся на форме с [error]. Буфер пароля
     * затирается в любом исходе (как в [create]): [Vault.unlock] гасит его по контракту лишь на
     * нормальном возврате, поэтому контроллер страхует и путь с исключением.
     */
    fun unlock(password: CharArray) {
        try {
            error = null
            when (vault.unlock(password)) {
                UnlockResult.Success -> state = VaultGateState.Unlocked
                UnlockResult.WrongPassword -> error = VaultGateError.WrongPassword
                // Битый файл — не ошибка формы, а тупик: уводим на отдельный экран сброса.
                UnlockResult.Corrupted -> state = VaultGateState.Corrupted
            }
        } finally {
            password.fill(' ')
        }
    }

    /** Заблокировать vault и вернуться к форме разблокировки. */
    fun lock() {
        vault.lock()
        error = null
        state = VaultGateState.NeedsUnlock
    }

    /**
     * Открыть экран подтверждения сброса (из формы входа — «забыл пароль», или с экрана [Corrupted]).
     * Запоминает текущее состояние, чтобы [cancelReset] вернул ровно на него.
     */
    fun beginReset() {
        resetReturnState = state
        error = null
        state = VaultGateState.Resetting
    }

    /** Отменить сброс — вернуться на форму входа или экран Corrupted, откуда пришли. */
    fun cancelReset() {
        error = null
        state = resetReturnState
    }

    /**
     * Безвозвратно сбросить vault и начать заново. Стирает файл vault ([Vault.reset]), снимает
     * биометрию (`vault.bio` бесполезен без vault), затем чистит внешние данные по [scope] через
     * [onReset]. Итог — форма создания нового мастер-пароля ([VaultGateState.NeedsCreate]).
     */
    fun confirmReset(scope: ResetScope) {
        // vault.reset() уже удалил файл — что бы дальше ни упало, в Resetting застрять нельзя:
        // переход на форму создания гарантируем в finally (на холодном старте vault.exists()==false
        // и так дал бы NeedsCreate, но в этой сессии экран не должен зависнуть).
        try {
            vault.reset()
            // disable() идемпотентен; его сбой не должен срывать чистку внешних данных и переход.
            runCatching { biometrics?.disable() }
            // Чистка внешних данных — best-effort: её сбой (I/O при записи hosts.json и т.п.) не должен
            // ронять UI-обработчик клика. vault уже стёрт; в худшем случае у хостов останутся висячие
            // ссылки на секреты (коннект просто спросит пароль), но приложение не падает и не зависает.
            runCatching { onReset(scope) }
        } finally {
            biometricEnabled = false
            error = null
            state = VaultGateState.NeedsCreate
        }
    }

    /** Зафиксировать активность пользователя — перезапускает таймер авто-лока по простою. */
    fun touch() {
        activityTick++
    }

    /** Можно ли предложить разблокировку биометрией на форме входа (доступна и включена). */
    fun canUnlockWithBiometric(): Boolean =
        biometrics?.let { it.availability() == BiometricAvailability.Available && it.isEnabled() } == true

    /** Можно ли предложить включение биометрии (есть железо и зачислен фактор). */
    fun canEnableBiometric(): Boolean =
        biometrics?.let { it.availability() == BiometricAvailability.Available } == true

    /**
     * Разблокировать биометрией. Успех → [VaultGateState.Unlocked]. Инвалидация ключа снимает
     * биометрию и просит пароль ([VaultGateError.BiometricReset]). Отмена/сбой — тихо остаёмся на
     * форме пароля без ошибки. [prompt] (локализованные строки) приходит из UI.
     */
    suspend fun unlockWithBiometric(prompt: BiometricPrompt) {
        val bio = biometrics ?: return
        error = null
        biometricInFlight = true
        try {
            when (bio.unlock(prompt)) {
                BiometricUnlockResult.Unlocked -> state = VaultGateState.Unlocked
                BiometricUnlockResult.Invalidated -> {
                    biometricEnabled = false
                    error = VaultGateError.BiometricReset
                }
                BiometricUnlockResult.Corrupted -> state = VaultGateState.Corrupted
                // Cancelled / Failed / Unavailable / NotEnabled — остаёмся на форме пароля молча.
                else -> Unit
            }
        } finally {
            biometricInFlight = false
        }
    }

    /** Включить биометрию (vault уже разблокирован). `true`, если включилась. */
    suspend fun enableBiometric(prompt: BiometricPrompt): Boolean {
        val bio = biometrics ?: return false
        biometricInFlight = true
        return try {
            val enabled = bio.enable(prompt) == BiometricEnableResult.Enabled
            biometricEnabled = bio.isEnabled()
            enabled
        } finally {
            biometricInFlight = false
        }
    }

    /** Выключить биометрию (удалить ключ и `vault.bio`). */
    fun disableBiometric() {
        val bio = biometrics ?: return
        bio.disable()
        biometricEnabled = bio.isEnabled()
    }

    /**
     * Закрыть разовое предложение биометрии после создания vault ([VaultGateState.OfferBiometric]) —
     * пустить в приложение независимо от того, включил пользователь биометрию или отказался.
     */
    fun dismissBiometricOffer() {
        if (state == VaultGateState.OfferBiometric) state = VaultGateState.Unlocked
    }
}
