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
import app.skerry.shared.vault.SecurityLog
import app.skerry.shared.vault.Vault
import app.skerry.shared.vault.VaultBiometrics
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.vtail_error_biometric_reset
import app.skerry.ui.generated.resources.vtail_error_corrupted
import app.skerry.ui.generated.resources.vtail_error_password_mismatch
import app.skerry.ui.generated.resources.vtail_error_password_too_short
import app.skerry.ui.generated.resources.vtail_error_wrong_password
import app.skerry.ui.generated.resources.vault_biometric_enable
import app.skerry.ui.generated.resources.vault_biometric_offer_subtitle
import app.skerry.ui.generated.resources.vault_biometric_offer_title
import app.skerry.ui.generated.resources.vault_cancel
import app.skerry.ui.generated.resources.vault_confirm_password_label
import app.skerry.ui.generated.resources.vault_corrupted_subtitle
import app.skerry.ui.generated.resources.vault_corrupted_title
import app.skerry.ui.generated.resources.vault_create_button
import app.skerry.ui.generated.resources.vault_create_subtitle
import app.skerry.ui.generated.resources.vault_create_title
import app.skerry.ui.generated.resources.vault_forgot_password
import app.skerry.ui.generated.resources.vault_master_password_label
import app.skerry.ui.generated.resources.vault_not_now
import app.skerry.ui.generated.resources.vault_reset_confirm_button
import app.skerry.ui.generated.resources.vault_reset_confirm_label
import app.skerry.ui.generated.resources.vault_reset_scope_everything_subtitle
import app.skerry.ui.generated.resources.vault_reset_scope_everything_title
import app.skerry.ui.generated.resources.vault_reset_scope_secrets_subtitle
import app.skerry.ui.generated.resources.vault_reset_scope_secrets_title
import app.skerry.ui.generated.resources.vault_reset_subtitle
import app.skerry.ui.generated.resources.vault_reset_vault
import app.skerry.ui.generated.resources.vault_unlock_biometric
import app.skerry.ui.generated.resources.vault_unlock_button
import app.skerry.ui.generated.resources.vault_unlock_subtitle
import app.skerry.ui.generated.resources.vault_unlock_title
import app.skerry.ui.generated.resources.vtail_bio_enable_cancel
import app.skerry.ui.generated.resources.vtail_bio_enable_subtitle
import app.skerry.ui.generated.resources.vtail_bio_enable_title
import app.skerry.ui.generated.resources.vtail_bio_unlock_cancel
import app.skerry.ui.generated.resources.vtail_bio_unlock_subtitle
import app.skerry.ui.generated.resources.vtail_bio_unlock_title
import app.skerry.ui.nav.PlatformBackHandler
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** Авто-лок по простою: бездействие дольше этого порога блокирует vault (защита оставленного экрана). */
private const val AUTO_LOCK_IDLE_MS = 5 * 60 * 1000L

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
    // Локальный журнал событий безопасности (раздел Настройки → Безопасность). Прокидывается в
    // контроллер: он пишет туда создание/смену пароля, включение/выключение биометрии, разблокировку
    // биометрией. `null` — журнал не ведётся (мок/превью).
    securityLog: SecurityLog? = null,
    // Порог автоблокировки по простою (Настройки → Безопасность). Значение из настроек: при его
    // изменении VaultGate рекомпозируется и idle-таймер перезапускается. `null` — таймер простоя
    // выключен ([AutoLockDuration.Never]); блокировка при уходе в фон остаётся (deviceMandatesAutoLock).
    autoLockIdleMs: Long? = AUTO_LOCK_IDLE_MS,
    modifier: Modifier = Modifier,
    // Внешняя чистка при сбросе (хосты/known_hosts/настройки по выбранному [ResetScope]). Вызывается
    // после стирания vault; платформенная проводка (desktop `main`) подставляет реальную реализацию.
    onReset: (ResetScope) -> Unit = {},
    // [onPairingComplete] != null — платформа умеет связать это устройство по коду прямо на экране
    // создания (быстрый паринг, вариант B): форма может показать аффорданс «у меня есть код», где
    // координатор сам создаст vault под выбранным паролем и примет ключ аккаунта; по завершении форма
    // зовёт [onPairingComplete], уводя гейт к предложению биометрии/в приложение. null — паринг с
    // экрана создания недоступен (нет sync / превью), показывается только обычное создание.
    createForm: @Composable (
        error: VaultGateError?,
        onCreate: (CharArray, CharArray) -> Unit,
        onPairingComplete: (() -> Unit)?,
    ) -> Unit =
        { error, onCreate, _ ->
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
    // Шаг подключения sync в онбординге ([VaultGateState.OfferSync]) — показывается сразу после
    // создания vault, ДО предложения биометрии. Форма сама дёргает SyncCoordinator (подключить/
    // пропустить) и вызывает onDone, когда шаг завершён. null (по умолчанию) — шаг не показывается
    // (на устройстве/в превью без sync); тогда после создания сразу биометрия/приложение.
    offerSyncForm: (@Composable (onDone: () -> Unit) -> Unit)? = null,
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
    // Разовое предложение включить биометрию сразу после создания vault. onEnable запускает промпт
    // (включает биометрию), onSkip — пропускает; оба ведут в приложение. inFlight гасит кнопки во
    // время промпта. Показывается только когда биометрия доступна (см. [VaultGateState.OfferBiometric]).
    offerBiometricForm: @Composable (
        inFlight: Boolean,
        onEnable: () -> Unit,
        onSkip: () -> Unit,
    ) -> Unit =
        { inFlight, onEnable, onSkip ->
            Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                OfferBiometricForm(inFlight, onEnable, onSkip)
            }
        },
    content: @Composable (onLock: () -> Unit) -> Unit,
) {
    // onReset не должен быть ключом remember (контроллер пересоздавать на каждой смене лямбды нельзя —
    // потерялись бы состояние/ввод). rememberUpdatedState даёт контроллеру всегда свежий колбэк, не
    // делая его ключом: иначе inline-лямбда вызывающего «застыла» бы на первой композиции.
    val currentOnReset by rememberUpdatedState(onReset)
    // Наличие формы sync не меняется в течение жизни экрана — безопасно зафиксировать на старте
    // контроллера (он решает, показывать ли шаг OfferSync).
    val offersSync = offerSyncForm != null
    val controller = remember(vault, biometrics, securityLog) {
        VaultGateController(vault, biometrics, onReset = { currentOnReset(it) }, offersSyncOnboarding = offersSync, securityLog = securityLog)
    }
    // Стабильная ссылка (не новый инстанс на каждую рекомпозицию VaultGate) — иначе createForm и его
    // поддерево (аффорданс паринга) перерисовывались бы лишний раз. null, когда паринг с экрана
    // создания недоступен (нет sync).
    val onPairingComplete: (() -> Unit)? =
        if (offersSync) remember(controller) { { controller.completePairing() } } else null
    val scope = rememberCoroutineScope()

    // Промпты биометрии резолвятся в композиции (stringResource), затем прокидываются в корутины.
    val enablePrompt = BiometricPrompt(
        title = stringResource(Res.string.vtail_bio_enable_title),
        cancelLabel = stringResource(Res.string.vtail_bio_enable_cancel),
        subtitle = stringResource(Res.string.vtail_bio_enable_subtitle),
    )
    val unlockPrompt = BiometricPrompt(
        title = stringResource(Res.string.vtail_bio_unlock_title),
        cancelLabel = stringResource(Res.string.vtail_bio_unlock_cancel),
        subtitle = stringResource(Res.string.vtail_bio_unlock_subtitle),
    )

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

    // Авто-лок по простою: delay перезапускается при изменении activityTick (касание пользователя) и
    // при смене порога [autoLockIdleMs] из настроек. null (AutoLockDuration.Never) — таймер выключен.
    if (controller.state == VaultGateState.Unlocked && autoLockIdleMs != null) {
        LaunchedEffect(controller.activityTick, autoLockIdleMs) {
            delay(autoLockIdleMs)
            controller.lock()
        }
    }

    // key по состоянию: при смене экрана Compose уничтожает и пересоздаёт поддерево формы,
    // чтобы введённый пароль не пережил переход в slot-table (например, после lock()).
    key(controller.state) {
        when (controller.state) {
            VaultGateState.NeedsCreate ->
                createForm(
                    controller.error,
                    { password, confirm -> controller.create(password, confirm) },
                    // null, когда платформа не провела sync (та же готовность, что и для OfferSync):
                    // иначе claimPairing некому исполнить. Стабильная ссылка — см. onPairingComplete выше.
                    onPairingComplete,
                )

            VaultGateState.NeedsUnlock ->
                unlockForm(
                    controller.error,
                    controller.canUnlockWithBiometric(),
                    { password -> controller.unlock(password) },
                    { scope.launch { controller.unlockWithBiometric(unlockPrompt) } },
                    { controller.beginReset() },
                )

            // Шаг sync в онбординге: форма сама подключает/пропускает sync и вызывает onDone, после
            // чего dataKey финальный и можно безопасно предлагать биометрию. offerSyncForm здесь
            // гарантированно не null — иначе контроллер не пришёл бы в OfferSync (offersSyncOnboarding).
            VaultGateState.OfferSync ->
                offerSyncForm?.invoke { controller.completeSyncOnboarding() }

            VaultGateState.Corrupted -> corruptedForm { controller.beginReset() }

            VaultGateState.Resetting -> {
                // Системный «назад» на экране подтверждения сброса = «Отмена»: возврат на разблокировку,
                // а не закрытие приложения (иначе единственный выход с danger-экрана — кнопка Cancel).
                PlatformBackHandler { controller.cancelReset() }
                resetForm({ scope -> controller.confirmReset(scope) }, { controller.cancelReset() })
            }

            // Включение/отказ оба ведут в приложение: при отказе или сбое промпта vault уже открыт,
            // биометрию можно настроить позже в разделе More. dismissBiometricOffer вызываем в любом исходе.
            VaultGateState.OfferBiometric ->
                offerBiometricForm(
                    controller.biometricInFlight,
                    { scope.launch { controller.enableBiometric(enablePrompt); controller.dismissBiometricOffer() } },
                    { controller.dismissBiometricOffer() },
                )

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
        title = stringResource(Res.string.vault_create_title),
        subtitle = stringResource(Res.string.vault_create_subtitle),
        error = error,
    ) {
        PasswordField(stringResource(Res.string.vault_master_password_label), password, ImeAction.Next) { password = it }
        PasswordField(stringResource(Res.string.vault_confirm_password_label), confirm, ImeAction.Done) { confirm = it }
        Button(
            onClick = { if (canSubmit) onCreate(password.toCharArray(), confirm.toCharArray()) },
            enabled = canSubmit,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(Res.string.vault_create_button))
        }
    }
}

/**
 * Разовое предложение включить биометрию после создания vault (Material-дефолт; мобильный слой даёт
 * свой визуал). Vault уже открыт — это необязательный шаг: «Включить» запускает промпт, «Пропустить»
 * пускает в приложение. Кнопки гаснут на время промпта ([inFlight]).
 */
@Composable
private fun OfferBiometricForm(inFlight: Boolean, onEnable: () -> Unit, onSkip: () -> Unit) {
    VaultFormScaffold(
        title = stringResource(Res.string.vault_biometric_offer_title),
        subtitle = stringResource(Res.string.vault_biometric_offer_subtitle),
        error = null,
    ) {
        Button(onClick = onEnable, enabled = !inFlight, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(Res.string.vault_biometric_enable))
        }
        TextButton(onClick = onSkip, enabled = !inFlight, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(Res.string.vault_not_now))
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
        title = stringResource(Res.string.vault_unlock_title),
        subtitle = stringResource(Res.string.vault_unlock_subtitle),
        error = error,
    ) {
        PasswordField(stringResource(Res.string.vault_master_password_label), password, ImeAction.Done) { password = it }
        Button(
            onClick = { if (canSubmit) onUnlock(password.toCharArray()) },
            enabled = canSubmit,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(Res.string.vault_unlock_button))
        }
        if (canUseBiometric) {
            OutlinedButton(onClick = onBiometric, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(Res.string.vault_unlock_biometric))
            }
        }
        TextButton(onClick = onForgotPassword, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(Res.string.vault_forgot_password))
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
        title = stringResource(Res.string.vault_corrupted_title),
        subtitle = stringResource(Res.string.vault_corrupted_subtitle),
        error = null,
    ) {
        Button(onClick = onReset, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(Res.string.vault_reset_vault))
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
        title = stringResource(Res.string.vault_reset_vault),
        subtitle = stringResource(Res.string.vault_reset_subtitle),
        error = null,
    ) {
        ResetScopeOption(
            selected = scope == ResetScope.SecretsOnly,
            title = stringResource(Res.string.vault_reset_scope_secrets_title),
            subtitle = stringResource(Res.string.vault_reset_scope_secrets_subtitle),
            onSelect = { scope = ResetScope.SecretsOnly },
        )
        ResetScopeOption(
            selected = scope == ResetScope.Everything,
            title = stringResource(Res.string.vault_reset_scope_everything_title),
            subtitle = stringResource(Res.string.vault_reset_scope_everything_subtitle),
            onSelect = { scope = ResetScope.Everything },
        )
        OutlinedTextField(
            value = confirmText,
            onValueChange = { confirmText = it },
            label = { Text(stringResource(Res.string.vault_reset_confirm_label, RESET_CONFIRM_WORD)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { if (canConfirm) onConfirm(scope) },
            enabled = canConfirm,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(Res.string.vault_reset_confirm_button))
        }
        TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(Res.string.vault_cancel))
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
@Composable
internal fun vaultGateErrorMessage(error: VaultGateError): String = when (error) {
    VaultGateError.PasswordTooShort -> stringResource(Res.string.vtail_error_password_too_short, MIN_MASTER_PASSWORD_LENGTH)
    VaultGateError.PasswordMismatch -> stringResource(Res.string.vtail_error_password_mismatch)
    VaultGateError.WrongPassword -> stringResource(Res.string.vtail_error_wrong_password)
    VaultGateError.Corrupted -> stringResource(Res.string.vtail_error_corrupted)
    VaultGateError.BiometricReset -> stringResource(Res.string.vtail_error_biometric_reset)
}
