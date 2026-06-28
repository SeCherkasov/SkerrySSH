package app.skerry.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.vault.MIN_MASTER_PASSWORD_LENGTH
import app.skerry.ui.vault.RESET_CONFIRM_WORD
import app.skerry.ui.vault.ResetScope
import app.skerry.ui.vault.VaultGateError
import app.skerry.ui.vault.vaultGateErrorMessage

/**
 * Оверлей блокировки (мок-путь): радиальный фон, крупный логотип,
 * поле мастер-пароля и кнопки. Разблокировка здесь — заглушка ([DesktopDesignState.unlock]);
 * живой гейт поверх `VaultGateController` рендерит [DesktopUnlockScreen]/[DesktopCreateScreen].
 */
@Composable
fun LockScreen(state: DesktopDesignState) {
    var pwd by remember { mutableStateOf("") }
    LockScaffold(
        title = "Skerry is locked",
        subtitle = "Enter your master password to unlock the harbor",
    ) {
        LockPasswordField(pwd, { pwd = it }, "Master password", ImeAction.Done, onSubmit = state::unlock)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton("Unlock", onClick = state::unlock, modifier = Modifier.weight(1f))
            BiometricButton(onClick = state::unlock)
        }
    }
}

/**
 * Живая форма разблокировки: тот же визуал, что у [LockScreen], но поверх [VaultGateController]
 * (через слот `unlockForm` в [app.skerry.ui.vault.VaultGate]). На сабмите пароль уходит в
 * [onUnlock] как [CharArray] и затирается контроллером; [error] локализуется
 * [vaultGateErrorMessage]. Кнопка биометрии видна только при [canUseBiometric].
 */
@Composable
fun DesktopUnlockScreen(
    error: VaultGateError?,
    canUseBiometric: Boolean,
    onUnlock: (CharArray) -> Unit,
    onBiometric: () -> Unit,
    onForgotPassword: () -> Unit,
) {
    var pwd by remember { mutableStateOf("") }
    val submit = { if (pwd.isNotEmpty()) onUnlock(pwd.toCharArray()) }
    // Биометрия включена — вызываем системный промпт сразу при входе на форму (один раз),
    // как делала Material-форма; кнопка отпечатка остаётся для повторной попытки.
    if (canUseBiometric) {
        LaunchedEffect(Unit) { onBiometric() }
    }
    LockScaffold(
        title = "Skerry is locked",
        subtitle = "Enter your master password to unlock the harbor",
        error = error,
    ) {
        LockPasswordField(pwd, { pwd = it }, "Master password", ImeAction.Done, onSubmit = submit, autoFocus = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton("Unlock", onClick = submit, modifier = Modifier.weight(1f))
            if (canUseBiometric) BiometricButton(onClick = onBiometric)
        }
        // Тупик забытого пароля расшивается только сбросом (zero-knowledge): ненавязчивая ссылка
        // под кнопкой ведёт на экран подтверждения.
        Txt(
            "Forgot your master password?",
            color = D.faint,
            size = 12.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClick = onForgotPassword)
                .padding(vertical = 4.dp),
        )
    }
}

/**
 * Экран повреждённого файла vault (стиль макета). Файл не читается → пароль ввести нельзя; единственное
 * действие — уйти на подтверждение сброса ([onReset]). Иконка-предупреждение amber (lighthouse-момент).
 */
@Composable
fun DesktopCorruptedScreen(onReset: () -> Unit) {
    LockScaffold(
        title = "Storage is damaged",
        subtitle = "The vault file can't be read or decrypted. To use Skerry again you'll need to reset it.",
    ) {
        PrimaryButton("Reset Skerry", onClick = onReset, modifier = Modifier.fillMaxWidth())
    }
}

/**
 * Экран подтверждения безвозвратного сброса (стиль макета): выбор объёма ([ResetScope]) +
 * type-to-confirm — кнопка сброса активна только когда вписано [RESET_CONFIRM_WORD]. Удаление
 * необратимо (модель Bitwarden/1Password), поэтому барьер от случайного клика жёсткий.
 */
@Composable
fun DesktopResetScreen(onConfirm: (ResetScope) -> Unit, onCancel: () -> Unit) {
    var scope by remember { mutableStateOf(ResetScope.SecretsOnly) }
    var confirmText by remember { mutableStateOf("") }
    val canConfirm = confirmText.trim() == RESET_CONFIRM_WORD
    LockScaffold(
        title = "Reset Skerry",
        subtitle = "This is permanent. Saved passwords, keys and identities are erased — there is no recovery.",
    ) {
        ResetScopeRow(
            selected = scope == ResetScope.SecretsOnly,
            title = "Secrets only",
            subtitle = "Keep host profiles and known_hosts.",
            onSelect = { scope = ResetScope.SecretsOnly },
        )
        ResetScopeRow(
            selected = scope == ResetScope.Everything,
            title = "Erase everything",
            subtitle = "Also remove host profiles, known_hosts and settings.",
            onSelect = { scope = ResetScope.Everything },
        )
        LockTextField(confirmText, { confirmText = it }, "Type $RESET_CONFIRM_WORD to confirm", ImeAction.Done) {
            if (canConfirm) onConfirm(scope)
        }
        PrimaryButton(
            "Reset permanently",
            onClick = { if (canConfirm) onConfirm(scope) },
            modifier = Modifier.fillMaxWidth(),
            bg = if (canConfirm) D.storm else Color(0x14FFFFFF),
            fg = if (canConfirm) Color(0xFF0A1A26) else D.faint,
            enabled = canConfirm,
        )
        Txt(
            "Cancel",
            color = D.dim,
            size = 12.sp,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClick = onCancel)
                .padding(vertical = 6.dp),
        )
    }
}

/** Строка выбора объёма сброса: радио-точка + заголовок/подзаголовок, кликабельна целиком. */
@Composable
private fun ResetScopeRow(selected: Boolean, title: String, subtitle: String, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(7.dp))
            .border(1.dp, if (selected) D.cyan else D.line, RoundedCornerShape(7.dp))
            .clickable(onClick = onSelect)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier.size(16.dp).clip(RoundedCornerShape(8.dp))
                .border(1.dp, if (selected) D.cyan else D.faint, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(D.cyan))
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Txt(title, color = D.text, size = 13.sp, weight = FontWeight.Medium)
            Txt(subtitle, color = D.dim, size = 11.5.sp, lineHeight = 15.sp)
        }
    }
}

/**
 * Живая форма создания мастер-пароля при первом запуске (слот `createForm`): два поля + кнопка,
 * валидация (длина/совпадение) — в [VaultGateController]; сюда оба буфера уходят как [CharArray]
 * и затираются там же. Тот же «night sea»-визуал, что у [DesktopUnlockScreen].
 */
@Composable
fun DesktopCreateScreen(
    error: VaultGateError?,
    onCreate: (CharArray, CharArray) -> Unit,
) {
    var pwd by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    // Утрата мастер-пароля = безвозвратная утрата vault (zero-knowledge): требуем явного признания
    // этого до создания, чтобы «нет восстановления» не осталось незамеченным мелким текстом.
    var acknowledged by remember { mutableStateOf(false) }
    // Минимум длины — тот же, что форсирует VaultGateController, чтобы кнопка не была активна до отказа.
    val canCreate = pwd.length >= MIN_MASTER_PASSWORD_LENGTH && confirm.isNotEmpty() && acknowledged
    val submit = { if (canCreate) onCreate(pwd.toCharArray(), confirm.toCharArray()) }
    LockScaffold(
        title = "Set a master password",
        subtitle = "It encrypts this vault and never leaves the device — there is no recovery.",
        error = error,
    ) {
        LockPasswordField(pwd, { pwd = it }, "Master password", ImeAction.Next, autoFocus = true)
        passwordStrength(pwd)?.let { PasswordStrengthMeter(it) }
        LockPasswordField(confirm, { confirm = it }, "Repeat password", ImeAction.Done, onSubmit = submit)
        NoRecoveryAcknowledge(acknowledged) { acknowledged = !acknowledged }
        PrimaryButton(
            "Create vault",
            onClick = submit,
            modifier = Modifier.fillMaxWidth(),
            bg = if (canCreate) D.cyan else Color(0x14FFFFFF),
            fg = if (canCreate) Color(0xFF0A1A26) else D.faint,
            enabled = canCreate,
        )
    }
}

/** Полоска силы мастер-пароля (4 сегмента) + подпись; цвет по [PasswordStrength]. */
@Composable
private fun PasswordStrengthMeter(strength: PasswordStrength) {
    val (filled, color, label) = when (strength) {
        PasswordStrength.Weak -> Triple(1, D.storm, "Weak")
        PasswordStrength.Fair -> Triple(2, D.amber, "Fair")
        PasswordStrength.Good -> Triple(3, D.cyan, "Good")
        PasswordStrength.Strong -> Triple(4, D.moss, "Strong")
    }
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(4) { i ->
                Box(
                    Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp))
                        .background(if (i < filled) color else D.cyan14),
                )
            }
        }
        Txt("Password strength: $label", color = color, size = 11.sp)
    }
}

/** Чекбокс-подтверждение «пароль невосстановим», гейтит создание vault. */
@Composable
private fun NoRecoveryAcknowledge(checked: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp))
            .toggleable(value = checked, onValueChange = { onToggle() }, role = Role.Checkbox)
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier.size(18.dp).clip(RoundedCornerShape(4.dp))
                .background(if (checked) D.cyan.copy(alpha = 0.15f) else Color.Transparent)
                .border(1.dp, if (checked) D.cyan else D.faint, RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) Sym("check", size = 13.sp, color = D.cyan)
        }
        Txt(
            "I understand my master password can't be recovered or reset. If I lose it, this vault is gone.",
            color = D.dim, size = 11.5.sp, lineHeight = 16.sp, modifier = Modifier.weight(1f),
        )
    }
}

// Общий каркас экранов блокировки.

/** Общий каркас экрана блокировки: радиальный фон, логотип, заголовок/подзаголовок, [fields], футер. */
@Composable
private fun LockScaffold(
    title: String,
    subtitle: String,
    error: VaultGateError? = null,
    fields: @Composable () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Brush.radialGradient(colors = listOf(Color(0xFF122332), D.bg)))
            .clickable(enabled = false) {}
            .padding(40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .size(88.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color(0xFF142634), Color(0xFF0A141B), Color(0xFF05090D)),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            BrandMark(size = 88.dp)
        }
        Box(Modifier.height(22.dp))
        Txt(title, color = D.text, size = 22.sp, weight = FontWeight.SemiBold, letterSpacing = (-0.3).sp)
        Box(Modifier.height(6.dp))
        Txt(subtitle, color = D.dim, size = 13.sp)
        Box(Modifier.height(32.dp))
        Column(Modifier.width(320.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            fields()
            if (error != null) {
                Txt(vaultGateErrorMessage(error), color = D.storm, size = 12.sp)
            }
        }
        Box(Modifier.height(28.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Sym("shield_lock", size = 14.sp, color = D.faint)
            Txt("Master password never leaves this device", color = D.faint, size = 11.sp)
        }
    }
}

/** Поле мастер-пароля макета: иконка-замок + скрытый ввод; Enter (Done) вызывает [onSubmit]. */
@Composable
private fun LockPasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    imeAction: ImeAction,
    onSubmit: () -> Unit = {},
    autoFocus: Boolean = false,
) {
    // Автофокус на старте экрана (запрос пользователя): курсор сразу в поле пароля без клика.
    val focusRequester = remember { FocusRequester() }
    if (autoFocus) {
        LaunchedEffect(Unit) { focusRequester.requestFocus() }
    }
    // Рамка/паддинг/иконка живут в decorationBox самого поля, поэтому зона ввода покрывает
    // всю видимую рамку целиком — клик в любую её точку (паддинги, край, иконка) ставит каретку.
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        textStyle = TextStyle(color = D.text, fontSize = 14.sp, fontFamily = LocalFonts.current.ui),
        cursorBrush = SolidColor(D.cyan),
        keyboardOptions = KeyboardOptions(imeAction = imeAction, keyboardType = KeyboardType.Password),
        keyboardActions = KeyboardActions(onDone = { onSubmit() }, onGo = { onSubmit() }),
        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
        decorationBox = { innerTextField ->
            Row(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(D.surface2)
                    .border(1.dp, D.cyan14, RoundedCornerShape(8.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Sym("lock", size = 18.sp, color = D.faint)
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Txt(placeholder, color = D.faint, size = 14.sp)
                    innerTextField()
                }
            }
        },
    )
}

/** Плоское текстовое поле макета (без маскирования) — для type-to-confirm на экране сброса. */
@Composable
private fun LockTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    imeAction: ImeAction,
    onSubmit: () -> Unit = {},
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(color = D.text, fontSize = 14.sp, fontFamily = LocalFonts.current.ui),
        cursorBrush = SolidColor(D.cyan),
        keyboardOptions = KeyboardOptions(imeAction = imeAction),
        keyboardActions = KeyboardActions(onDone = { onSubmit() }, onGo = { onSubmit() }),
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { innerTextField ->
            Row(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(D.surface2)
                    .border(1.dp, D.cyan14, RoundedCornerShape(8.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Txt(placeholder, color = D.faint, size = 14.sp)
                    innerTextField()
                }
            }
        },
    )
}

/** Контурная квадратная кнопка биометрии (отпечаток). */
@Composable
private fun BiometricButton(onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, D.cyan14, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Sym("fingerprint", size = 16.sp, color = D.dim)
    }
}
