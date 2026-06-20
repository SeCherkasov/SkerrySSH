package app.skerry.ui.vault

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.skerry.shared.vault.BiometricAvailability
import app.skerry.shared.vault.BiometricEnableResult
import app.skerry.shared.vault.BiometricPrompt
import app.skerry.shared.vault.Vault
import app.skerry.shared.vault.VaultBiometrics
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Авто-лок по простою: бездействие дольше этого порога блокирует vault (защита оставленного экрана). */
private const val AUTO_LOCK_IDLE_MS = 5 * 60 * 1000L

/** Промпт включения биометрии (vault уже открыт). */
private val ENABLE_PROMPT = BiometricPrompt(
    title = "Включить биометрию",
    cancelLabel = "Отмена",
    subtitle = "Подтвердите биометрию, чтобы привязать разблокировку хранилища",
)

/** Промпт разблокировки биометрией (холодный старт). */
private val UNLOCK_PROMPT = BiometricPrompt(
    title = "Разблокировать Skerry",
    cancelLabel = "Ввести пароль",
    subtitle = "Подтвердите биометрию, чтобы открыть хранилище",
)

/**
 * Нужно ли запирать vault при уходе приложения в фон (`ON_STOP`) — платформенная политика.
 * Desktop: всегда (как раньше). Android: только если устройство реально заблокировано (keyguard) —
 * переключение на системный пикер/шторку фон vault не запирает, иначе выбор файла рвал бы сессию.
 * Простой по таймеру ([AUTO_LOCK_IDLE_MS]) и завершение процесса (свежий старт всегда заперт)
 * закрывают остальные случаи.
 */
expect fun deviceMandatesAutoLock(): Boolean

/**
 * Гейт мастер-пароля: пока [Vault] заблокирован, показывает форму создания или разблокировки;
 * после разблокировки рендерит [content] (остальной UI приложения). Контроллер живёт на время
 * композиции (привязан к идентичности [vault]/[biometrics]).
 *
 * Если передан [biometrics], форма входа предлагает разблокировку биометрией (промпт вызывается
 * автоматически при наличии включённой биометрии). Авто-лок: при уходе в фон (lifecycle `ON_STOP`)
 * и по простою ([AUTO_LOCK_IDLE_MS], таймер перезапускается касаниями) vault блокируется.
 *
 * Поля ввода Compose оперируют [String], поэтому пароль конвертируется в [CharArray] только на
 * сабмите и сразу затирается контроллером; сам строковый буфер поля живёт до рекомпозиции —
 * известное ограничение; секьюрный ввод без String — отдельный шаг.
 */
@Composable
fun VaultGate(
    vault: Vault,
    biometrics: VaultBiometrics? = null,
    modifier: Modifier = Modifier,
    content: @Composable (onLock: () -> Unit) -> Unit,
) {
    val controller = remember(vault, biometrics) { VaultGateController(vault, biometrics) }
    val scope = rememberCoroutineScope()

    // Авто-лок при уходе приложения в фон: чужие руки на разблокированном устройстве не должны
    // получить открытый vault после сворачивания.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, controller) {
        val observer = LifecycleEventObserver { _, event ->
            // Не блокировать во время биометрического промпта: он может прислать ON_STOP, а блокировка
            // посреди аутентификации потеряла бы её успешный результат (см. biometricInFlight).
            if (event == Lifecycle.Event.ON_STOP &&
                controller.state == VaultGateState.Unlocked &&
                !controller.biometricInFlight &&
                deviceMandatesAutoLock()
            ) {
                controller.lock()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Авто-лок по простою: delay перезапускается при изменении activityTick (касание пользователя).
    if (controller.state == VaultGateState.Unlocked) {
        LaunchedEffect(controller.activityTick) {
            delay(AUTO_LOCK_IDLE_MS)
            controller.lock()
        }
    }

    // key по состоянию: при смене экрана Compose уничтожает и пересоздаёт поддерево формы,
    // чтобы введённый пароль не пережил переход в slot-table (например, после lock()).
    key(controller.state) {
        when (controller.state) {
            VaultGateState.NeedsCreate -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CreateVaultForm(controller.error) { password, confirm -> controller.create(password, confirm) }
            }

            VaultGateState.NeedsUnlock -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                UnlockVaultForm(
                    error = controller.error,
                    canUseBiometric = controller.canUnlockWithBiometric(),
                    onUnlock = { password -> controller.unlock(password) },
                    onBiometric = { scope.launch { controller.unlockWithBiometric(UNLOCK_PROMPT) } },
                )
            }

            // lock() переводит гейт в NeedsUnlock; key(state) рушит поддерево content, чей
            // DisposableEffect рвёт живую SSH-сессию — блокировка заодно закрывает сессии.
            VaultGateState.Unlocked -> Box(
                Modifier.fillMaxSize().pointerInput(Unit) {
                    // наблюдаем нажатия на Initial-проходе, НЕ потребляя — дети получают события,
                    // а таймер простоя перезапускается при каждом касании.
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.type == PointerEventType.Press) controller.touch()
                        }
                    }
                },
            ) {
                content { controller.lock() }
            }
        }
    }
}

@Composable
private fun CreateVaultForm(error: VaultGateError?, onCreate: (CharArray, CharArray) -> Unit) {
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val canSubmit = password.isNotEmpty() && confirm.isNotEmpty()

    VaultFormScaffold(
        title = "Создать мастер-пароль",
        subtitle = "Им шифруется локальное хранилище. Пароль не покидает устройство и не " +
            "восстанавливается — забыли его, и данные не расшифровать.",
        error = error,
    ) {
        PasswordField("Мастер-пароль", password, ImeAction.Next) { password = it }
        PasswordField("Повторите пароль", confirm, ImeAction.Done) { confirm = it }
        Button(
            onClick = { if (canSubmit) onCreate(password.toCharArray(), confirm.toCharArray()) },
            enabled = canSubmit,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Создать хранилище")
        }
    }
}

@Composable
private fun UnlockVaultForm(
    error: VaultGateError?,
    canUseBiometric: Boolean,
    onUnlock: (CharArray) -> Unit,
    onBiometric: () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    val canSubmit = password.isNotEmpty()

    // Если биометрия доступна и включена — вызываем промпт сразу при входе на форму (один раз).
    if (canUseBiometric) {
        LaunchedEffect(Unit) { onBiometric() }
    }

    VaultFormScaffold(
        title = "Разблокировать хранилище",
        subtitle = "Введите мастер-пароль, чтобы открыть хосты, ключи и сессии.",
        error = error,
    ) {
        PasswordField("Мастер-пароль", password, ImeAction.Done) { password = it }
        Button(
            onClick = { if (canSubmit) onUnlock(password.toCharArray()) },
            enabled = canSubmit,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Разблокировать")
        }
        if (canUseBiometric) {
            OutlinedButton(onClick = onBiometric, modifier = Modifier.fillMaxWidth()) {
                Text("Разблокировать биометрией")
            }
        }
    }
}

/**
 * Настройки биометрии за гейтом (vault разблокирован). Тумблер включает/выключает разблокировку
 * биометрией; включение требует биометрического подтверждения (оборачивает `dataKey`). Кнопка
 * блокировки — ручной lock. Скрывается целиком, если биометрия недоступна на устройстве.
 */
@Composable
fun VaultBiometricSettings(
    biometrics: VaultBiometrics,
    onLock: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    if (biometrics.availability() != BiometricAvailability.Available) {
        // Биометрия недоступна — показываем только ручной lock (если он есть).
        if (onLock != null) {
            Column(modifier.padding(24.dp)) {
                TextButton(onClick = onLock) { Text("Заблокировать хранилище") }
            }
        }
        return
    }

    var enabled by remember { mutableStateOf(biometrics.isEnabled()) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = modifier.widthIn(max = 360.dp).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Разблокировка биометрией",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Switch(
                checked = enabled,
                enabled = !busy,
                onCheckedChange = { wantOn ->
                    if (busy) return@Switch
                    busy = true
                    scope.launch {
                        try {
                            if (wantOn) {
                                if (enableBiometric(biometrics)) enabled = true
                            } else {
                                biometrics.disable()
                                enabled = false
                            }
                        } finally {
                            busy = false
                        }
                    }
                },
            )
        }
        if (onLock != null) {
            TextButton(onClick = onLock) { Text("Заблокировать хранилище") }
        }
    }
}

/** Включить биометрию с промптом; вынесено, чтобы держать строки промпта рядом с гейтом. */
private suspend fun enableBiometric(biometrics: VaultBiometrics): Boolean =
    biometrics.enable(ENABLE_PROMPT) == BiometricEnableResult.Enabled

@Composable
private fun VaultFormScaffold(
    title: String,
    subtitle: String,
    error: VaultGateError?,
    fields: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier.widthIn(max = 360.dp).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        fields()
        if (error != null) {
            Text(error.message(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun PasswordField(label: String, value: String, imeAction: ImeAction, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(imeAction = imeAction, keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun VaultGateError.message(): String = when (this) {
    VaultGateError.PasswordTooShort -> "Пароль слишком короткий — минимум $MIN_MASTER_PASSWORD_LENGTH символов."
    VaultGateError.PasswordMismatch -> "Пароли не совпадают."
    VaultGateError.WrongPassword -> "Неверный мастер-пароль."
    VaultGateError.Corrupted -> "Файл хранилища повреждён или не читается."
    VaultGateError.BiometricReset -> "Биометрия сброшена — войдите мастер-паролем."
}
