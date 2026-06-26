package app.skerry.ui.vault

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.runtime.rememberUpdatedState
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
    // Внешняя чистка при сбросе (хосты/known_hosts/настройки по выбранному [ResetScope]). Вызывается
    // после стирания vault; платформенная проводка (desktop `main`) подставляет реальную реализацию.
    onReset: (ResetScope) -> Unit = {},
    createForm: @Composable (error: VaultGateError?, onCreate: (CharArray, CharArray) -> Unit) -> Unit =
        { error, onCreate ->
            Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CreateVaultForm(error, onCreate) }
        },
    unlockForm: @Composable (
        error: VaultGateError?,
        canUseBiometric: Boolean,
        onUnlock: (CharArray) -> Unit,
        onBiometric: () -> Unit,
        onForgotPassword: () -> Unit,
    ) -> Unit =
        { error, canUseBiometric, onUnlock, onBiometric, onForgotPassword ->
            Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                UnlockVaultForm(error, canUseBiometric, onUnlock, onBiometric, onForgotPassword)
            }
        },
    // Экран повреждённого файла: единственное действие — уйти на подтверждение сброса ([onReset]).
    corruptedForm: @Composable (onReset: () -> Unit) -> Unit =
        { onResetClick ->
            Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CorruptedVaultForm(onResetClick) }
        },
    // Экран подтверждения сброса: выбор объёма + явное подтверждение, затем onConfirm/onCancel.
    resetForm: @Composable (onConfirm: (ResetScope) -> Unit, onCancel: () -> Unit) -> Unit =
        { onConfirm, onCancel ->
            Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { ResetVaultForm(onConfirm, onCancel) }
        },
    content: @Composable (onLock: () -> Unit) -> Unit,
) {
    // onReset не должен быть ключом remember (контроллер пересоздавать на каждой смене лямбды нельзя —
    // потерялись бы состояние/ввод). rememberUpdatedState даёт контроллеру всегда свежий колбэк, не
    // делая его ключом: иначе inline-лямбда вызывающего «застыла» бы на первой композиции.
    val currentOnReset by rememberUpdatedState(onReset)
    val controller = remember(vault, biometrics) {
        VaultGateController(vault, biometrics, onReset = { currentOnReset(it) })
    }
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
            VaultGateState.NeedsCreate ->
                createForm(controller.error) { password, confirm -> controller.create(password, confirm) }

            VaultGateState.NeedsUnlock ->
                unlockForm(
                    controller.error,
                    controller.canUnlockWithBiometric(),
                    { password -> controller.unlock(password) },
                    { scope.launch { controller.unlockWithBiometric(UNLOCK_PROMPT) } },
                    { controller.beginReset() },
                )

            VaultGateState.Corrupted -> corruptedForm { controller.beginReset() }

            VaultGateState.Resetting ->
                resetForm({ scope -> controller.confirmReset(scope) }, { controller.cancelReset() })

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
    onForgotPassword: () -> Unit,
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
        TextButton(onClick = onForgotPassword, modifier = Modifier.fillMaxWidth()) {
            Text("Забыли мастер-пароль?")
        }
    }
}

/**
 * Экран повреждённого файла vault (Material-дефолт). Тупик: пароль ввести нельзя, единственный выход —
 * безвозвратный сброс. Кнопка лишь уводит на экран подтверждения [ResetVaultForm].
 */
@Composable
private fun CorruptedVaultForm(onReset: () -> Unit) {
    VaultFormScaffold(
        title = "Хранилище повреждено",
        subtitle = "Файл хранилища не читается, и расшифровать его нельзя. Чтобы пользоваться " +
            "приложением, придётся сбросить хранилище и начать заново.",
        error = null,
    ) {
        Button(onClick = onReset, modifier = Modifier.fillMaxWidth()) {
            Text("Сбросить хранилище")
        }
    }
}

/**
 * Экран подтверждения безвозвратного сброса (Material-дефолт): выбор объёма ([ResetScope]) и
 * type-to-confirm — кнопка активна только когда вписано слово `RESET`. Список потерь — в подзаголовке.
 */
@Composable
private fun ResetVaultForm(onConfirm: (ResetScope) -> Unit, onCancel: () -> Unit) {
    var scope by remember { mutableStateOf(ResetScope.SecretsOnly) }
    var confirmText by remember { mutableStateOf("") }
    val canConfirm = confirmText.trim() == RESET_CONFIRM_WORD

    VaultFormScaffold(
        title = "Сбросить хранилище",
        subtitle = "Это необратимо. Сохранённые пароли, ключи и учётные данные будут стёрты без " +
            "возможности восстановления — мастер-пароль их не защищает, а пересоздаёт.",
        error = null,
    ) {
        ResetScopeOption(
            selected = scope == ResetScope.SecretsOnly,
            title = "Только секреты",
            subtitle = "Профили хостов и known_hosts останутся.",
            onSelect = { scope = ResetScope.SecretsOnly },
        )
        ResetScopeOption(
            selected = scope == ResetScope.Everything,
            title = "Стереть всё",
            subtitle = "Также удалить профили хостов, known_hosts и настройки.",
            onSelect = { scope = ResetScope.Everything },
        )
        OutlinedTextField(
            value = confirmText,
            onValueChange = { confirmText = it },
            label = { Text("Впишите $RESET_CONFIRM_WORD для подтверждения") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { if (canConfirm) onConfirm(scope) },
            enabled = canConfirm,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Сбросить безвозвратно")
        }
        TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
            Text("Отмена")
        }
    }
}

@Composable
private fun ResetScopeOption(selected: Boolean, title: String, subtitle: String, onSelect: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().selectable(selected = selected, onClick = onSelect),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(modifier = Modifier.padding(top = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    vault: Vault,
    biometrics: VaultBiometrics,
    onLock: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    // Тумблер ходит через тот же контроллер, что и разблокировка: включение/выключение и
    // реактивное состояние биометрии живут в VaultGateController, UI их только отображает.
    val controller = remember(vault, biometrics) { VaultGateController(vault, biometrics) }
    if (!controller.canEnableBiometric()) {
        // Биометрия недоступна — показываем только ручной lock (если он есть).
        if (onLock != null) {
            Column(modifier.padding(24.dp)) {
                TextButton(onClick = onLock) { Text("Заблокировать хранилище") }
            }
        }
        return
    }

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
                checked = controller.biometricEnabled,
                enabled = !controller.biometricInFlight,
                onCheckedChange = { wantOn ->
                    if (controller.biometricInFlight) return@Switch
                    scope.launch {
                        if (wantOn) controller.enableBiometric(ENABLE_PROMPT) else controller.disableBiometric()
                    }
                },
            )
        }
        if (onLock != null) {
            TextButton(onClick = onLock) { Text("Заблокировать хранилище") }
        }
    }
}

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
            Text(vaultGateErrorMessage(error), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
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

/**
 * Локализованное сообщение об ошибке гейта. `internal` (не `private`), потому что переиспользуется
 * дизайн-слоем (`ui/design`) поверх того же [VaultGateController]; зафиксировано
 * [app.skerry.ui.vault.VaultGateErrorMessageTest].
 */
internal fun vaultGateErrorMessage(error: VaultGateError): String = when (error) {
    VaultGateError.PasswordTooShort -> "Пароль слишком короткий — минимум $MIN_MASTER_PASSWORD_LENGTH символов."
    VaultGateError.PasswordMismatch -> "Пароли не совпадают."
    VaultGateError.WrongPassword -> "Неверный мастер-пароль."
    VaultGateError.Corrupted -> "Файл хранилища повреждён или не читается."
    VaultGateError.BiometricReset -> "Биометрия сброшена — войдите мастер-паролем."
}
