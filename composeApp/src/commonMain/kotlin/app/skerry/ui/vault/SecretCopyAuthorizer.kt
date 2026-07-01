package app.skerry.ui.vault

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.skerry.shared.vault.BiometricAvailability
import app.skerry.shared.vault.BiometricConfirmResult
import app.skerry.shared.vault.BiometricPrompt
import app.skerry.shared.vault.Vault
import app.skerry.shared.vault.VaultBiometrics
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.vtail_bio_copy_cancel
import app.skerry.ui.generated.resources.vtail_bio_copy_subtitle
import app.skerry.ui.generated.resources.vtail_bio_copy_title
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString

/**
 * Повторная аутентификация перед копированием чувствительного секрета (пароля) в буфер обмена:
 * открытый vault сам по себе не должен позволять выгрести пароль чужими руками на оставленном
 * без присмотра экране. По договорённости: если биометрия включена и доступна — системный
 * биометрический промпт ([VaultBiometrics.confirm], vault не трогает); иначе — ввод мастер-пароля
 * ([Vault.verifyPassword], сверка без перевыдачи ключа). Действие выполняется только после успеха.
 *
 * Общий для desktop ([app.skerry.ui.design.VaultView]) и мобильного ([app.skerry.ui.design.MobileVaultView])
 * keychain — на desktop биометрии нет (`biometrics == null`), путь естественно сводится к паролю.
 * Состояние формы пароля держится здесь (Compose snapshot state), UI рисует его платформенно.
 * Инстанцируется через `remember(vault, biometrics, scope)`.
 *
 * [kdfDispatcher] выносит дорогую сверку пароля (Argon2id, m=64 MiB) с UI-потока — иначе сверка
 * подвешивала бы кадр; тест подменяет его виртуальным.
 */
internal class SecretCopyAuthorizer(
    private val vault: Vault?,
    private val biometrics: VaultBiometrics?,
    private val scope: CoroutineScope,
    private val kdfDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    /** Показывать ли форму ввода мастер-пароля (биометрия недоступна/отвалилась). */
    var passwordPromptVisible by mutableStateOf(false)
        private set

    /** Введённый мастер-пароль не подошёл — показать ошибку в форме (сама форма остаётся открытой). */
    var passwordError by mutableStateOf(false)
        private set

    /** Идёт сверка пароля (Argon2id) — на это время кнопка подтверждения блокируется. */
    var verifying by mutableStateOf(false)
        private set

    // Отложенное действие (копирование) ждёт подтверждения паролем; на биометрическом пути не нужно.
    private var pending: (() -> Unit)? = null

    // Биометрический промпт уже в полёте — гасим повторные тапы, иначе два промпта/два копирования.
    private var biometricInFlight = false

    /**
     * Запросить авторизацию перед [onAuthorized] (копированием). Биометрия, если включена и доступна;
     * её отмена/сбой — действие не выполняем и пароль НЕ навязываем (пользователь сам отказался).
     * Любой иной исход биометрии (не включена/недоступна/инвалидирована/ошибка железа) — откат на
     * форму пароля. Повторный вызов, пока промпт в полёте, игнорируется.
     */
    fun authorize(onAuthorized: () -> Unit) {
        val bio = biometrics
        if (bio != null && bio.isEnabled() && bio.availability() == BiometricAvailability.Available) {
            if (biometricInFlight) return
            biometricInFlight = true
            scope.launch {
                val prompt = BiometricPrompt(
                    title = getString(Res.string.vtail_bio_copy_title),
                    cancelLabel = getString(Res.string.vtail_bio_copy_cancel),
                    subtitle = getString(Res.string.vtail_bio_copy_subtitle),
                )
                // confirm зовёт платформенный BiometricPrompt — на некоторых устройствах он может
                // бросить исключение; не роняем композицию, а откатываемся на мастер-пароль.
                val result = runCatching { bio.confirm(prompt) }.getOrNull()
                biometricInFlight = false
                when (result) {
                    BiometricConfirmResult.Confirmed -> onAuthorized()
                    BiometricConfirmResult.Cancelled, BiometricConfirmResult.Failed -> Unit
                    // NotEnabled/Unavailable/Invalidated и исключение (null) — на мастер-пароль.
                    else -> requirePassword(onAuthorized)
                }
            }
        } else {
            requirePassword(onAuthorized)
        }
    }

    private fun requirePassword(onAuthorized: () -> Unit) {
        pending = onAuthorized
        passwordError = false
        passwordPromptVisible = true
    }

    /** Проверить мастер-пароль; при успехе закрыть форму и выполнить отложенное копирование, иначе — ошибка. */
    fun submitPassword(password: String) {
        if (verifying) return
        verifying = true
        scope.launch {
            // Argon2id дорогой — уводим с UI-потока, чтобы не подвесить кадр на время сверки.
            val ok = withContext(kdfDispatcher) { vault?.verifyPassword(password.toCharArray()) == true }
            verifying = false
            if (ok) {
                val run = pending
                dismiss()
                run?.invoke()
            } else {
                passwordError = true
            }
        }
    }

    /** Закрыть форму пароля и сбросить отложенное действие (Cancel/тап мимо). */
    fun dismiss() {
        passwordPromptVisible = false
        passwordError = false
        pending = null
    }
}
