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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.vault.VaultGateError
import app.skerry.ui.vault.vaultGateErrorMessage

/**
 * Оверлей блокировки `docs/new/Skerry.html` (мок-путь скриншота): радиальный фон, крупный логотип,
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
        LockPasswordField(pwd, { pwd = it }, "Master password", ImeAction.Done, onSubmit = submit)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton("Unlock", onClick = submit, modifier = Modifier.weight(1f))
            if (canUseBiometric) BiometricButton(onClick = onBiometric)
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
    val submit = { if (pwd.isNotEmpty() && confirm.isNotEmpty()) onCreate(pwd.toCharArray(), confirm.toCharArray()) }
    LockScaffold(
        title = "Set a master password",
        subtitle = "It encrypts this vault and never leaves the device — there is no recovery.",
        error = error,
    ) {
        LockPasswordField(pwd, { pwd = it }, "Master password", ImeAction.Next)
        LockPasswordField(confirm, { confirm = it }, "Repeat password", ImeAction.Done, onSubmit = submit)
        PrimaryButton("Create vault", onClick = submit, modifier = Modifier.fillMaxWidth())
    }
}

// ──────────────────────────────── shared scaffold ────────────────────────────────

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
) {
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
        modifier = Modifier.fillMaxWidth(),
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
