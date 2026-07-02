package app.skerry.ui.sync

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.sync.SyncCoordinator
import app.skerry.ui.sync.SyncSetupForm
import app.skerry.ui.sync.SyncStatus
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.sync_setup_title
import app.skerry.ui.generated.resources.sync_setup_dialog_desc
import app.skerry.ui.generated.resources.sync_field_server_url
import app.skerry.ui.generated.resources.sync_placeholder_server_url
import app.skerry.ui.generated.resources.sync_field_account
import app.skerry.ui.generated.resources.sync_placeholder_account
import app.skerry.ui.generated.resources.sync_field_master_password
import app.skerry.ui.generated.resources.sync_placeholder_master_password
import app.skerry.ui.generated.resources.sync_insecure_url_warning
import app.skerry.ui.generated.resources.sync_connecting
import app.skerry.ui.generated.resources.sync_zero_knowledge
import app.skerry.ui.generated.resources.sync_cancel
import app.skerry.ui.generated.resources.sync_connect
import app.skerry.ui.generated.resources.sync_keep_connected
import app.skerry.ui.generated.resources.sync_keep_connected_sub_long
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.design.D
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.PrimaryButton
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt

/**
 * Модалка-онбординг self-hosted sync (в макете её нет — макет показывает только подключённое
 * состояние): адрес сервера + accountId + мастер-пароль, одно действие «Connect» (координатор сам
 * регистрирует новый аккаунт либо входит в существующий — без выбора режима). Zero-knowledge —
 * пароль уходит в [SyncCoordinator] как CharArray и там затирается; здесь держим его строкой ровно
 * до отправки и обнуляем сразу после. Стиль — скрим + карточка, как [DesktopPasswordDialog].
 *
 * Закрывается сама, когда координатор перешёл в [SyncStatus.Online] (успешное подключение).
 */
@Composable
fun SyncSetupDialog(sync: SyncCoordinator, onDismiss: () -> Unit) {
    val noop = remember { MutableInteractionSource() }
    val status by sync.status.collectAsState()

    // Предзаполнение из сохранённой привязки (после перезапуска/Reconnect): сервер+аккаунт известны,
    // нужен только пароль.
    val saved = remember { sync.savedConfig }
    var serverUrl by remember { mutableStateOf(saved?.serverUrl ?: "") }
    var account by remember { mutableStateOf(saved?.accountId ?: "") }
    var password by remember { mutableStateOf("") }
    var keepConnected by remember { mutableStateOf(saved?.keepConnected ?: true) }

    val form = SyncSetupForm(serverUrl, account)
    val canSubmit = form.canSubmit(password.length) && status != SyncStatus.Busy

    // Закрываемся только если ИМЕННО этот диалог инициировал подключение и оно дошло до Online —
    // иначе диалог, случайно открытый при уже активной сессии, схлопнулся бы на первой композиции.
    var connecting by remember { mutableStateOf(false) }
    LaunchedEffect(status) {
        if (connecting && status is SyncStatus.Online) {
            password = ""
            onDismiss()
        }
    }

    val submit = submit@{
        if (!canSubmit) return@submit
        connecting = true
        val pw = password.toCharArray() // координатор затрёт массив
        password = ""
        val url = form.normalizedServerUrl
        val acc = form.normalizedAccountId
        // Запуск держит сам координатор (свой scope) — не привязываем к жизни этого composable.
        // Один вызов: координатор сам решит регистрировать или входить.
        sync.connect(url, acc, pw, keepConnected)
    }

    Box(
        Modifier.fillMaxSize().background(Color(0xB3060E16))
            .clickable(interactionSource = noop, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = 440.dp)
                .fillMaxWidth()
                .padding(20.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(D.surfaceDeep)
                .border(1.dp, D.cyan14, RoundedCornerShape(12.dp))
                .clickable(interactionSource = noop, indication = null, onClick = {})
                .padding(26.dp),
        ) {
            Txt(stringResource(Res.string.sync_setup_title), color = D.text, size = 16.sp, weight = FontWeight.SemiBold, letterSpacing = (-0.2).sp)
            Txt(
                stringResource(Res.string.sync_setup_dialog_desc),
                color = D.dim, size = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
            )

            FieldLabel(stringResource(Res.string.sync_field_server_url), top = 16.dp)
            SyncField(stringResource(Res.string.sync_placeholder_server_url), serverUrl, "dns", KeyboardType.Uri, ImeAction.Next) { serverUrl = it }

            FieldLabel(stringResource(Res.string.sync_field_account))
            SyncField(stringResource(Res.string.sync_placeholder_account), account, "person", KeyboardType.Text, ImeAction.Next) { account = it }

            FieldLabel(stringResource(Res.string.sync_field_master_password))
            SyncField(stringResource(Res.string.sync_placeholder_master_password), password, "key", KeyboardType.Password, ImeAction.Done, secret = true, onSubmit = { submit() }) { password = it }

            KeepConnectedRow(keepConnected) { keepConnected = it }

            // http:// разрешён (локальный тест/LAN без TLS-прокси), но беззащитен перед MITM — предупреждаем явно.
            if (form.isInsecureUrl) {
                Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Sym("warning", size = 14.sp, color = D.sunset)
                    Txt(stringResource(Res.string.sync_insecure_url_warning), color = D.sunset, size = 11.sp, lineHeight = 15.sp)
                }
            }

            val failed = status as? SyncStatus.Failed
            if (failed != null) {
                Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Sym("error", size = 14.sp, color = D.sunset)
                    Txt(failed.message, color = D.sunset, size = 11.5.sp)
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Sym("shield_lock", size = 14.sp, color = D.moss)
                    Txt(
                        if (status == SyncStatus.Busy) stringResource(Res.string.sync_connecting) else stringResource(Res.string.sync_zero_knowledge),
                        color = D.faint, size = 11.sp,
                    )
                }
                Box(Modifier.clip(RoundedCornerShape(7.dp)).clickable(onClick = onDismiss).padding(horizontal = 16.dp, vertical = 9.dp)) {
                    Txt(stringResource(Res.string.sync_cancel), color = D.dim, size = 12.5.sp)
                }
                PrimaryButton(stringResource(Res.string.sync_connect), onClick = { submit() }, enabled = canSubmit, bg = if (canSubmit) D.cyan else D.cyan.copy(alpha = 0.4f))
            }
        }
    }
}

/** Чекбокс «Keep me connected»: запоминать привязку и восстанавливать сессию без ввода пароля. */
@Composable
private fun KeepConnectedRow(checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 14.dp).clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.size(18.dp).clip(RoundedCornerShape(5.dp))
                .background(if (checked) D.cyan else Color.Transparent)
                .border(1.dp, if (checked) D.cyan else D.cyan14, RoundedCornerShape(5.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) Sym("check", size = 13.sp, color = Color(0xFF0A1A26))
        }
        Column(Modifier.weight(1f)) {
            Txt(stringResource(Res.string.sync_keep_connected), color = D.text, size = 12.5.sp, weight = FontWeight.Medium)
            Txt(stringResource(Res.string.sync_keep_connected_sub_long), color = D.faint, size = 11.sp)
        }
    }
}

@Composable
internal fun FieldLabel(text: String, top: androidx.compose.ui.unit.Dp = 12.dp) {
    Txt(text, color = D.faint, size = 10.5.sp, weight = FontWeight.SemiBold, letterSpacing = 0.6.sp, modifier = Modifier.padding(top = top, bottom = 5.dp))
}

@Composable
internal fun SyncField(
    placeholder: String,
    value: String,
    icon: String,
    keyboardType: KeyboardType,
    imeAction: ImeAction,
    secret: Boolean = false,
    onSubmit: () -> Unit = {},
    onChange: (String) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).background(D.bg).border(1.dp, D.cyan14, RoundedCornerShape(7.dp)).padding(horizontal = 11.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Sym(icon, size = 16.sp, color = D.faint)
        val ui = LocalFonts.current.ui
        val style = remember(ui) { TextStyle(color = D.text, fontSize = 13.sp, fontFamily = ui) }
        Box(Modifier.fillMaxWidth()) {
            if (value.isEmpty()) Txt(placeholder, color = D.faint, size = 13.sp)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = style,
                cursorBrush = SolidColor(D.cyan),
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(imeAction = imeAction, keyboardType = keyboardType),
                keyboardActions = KeyboardActions(onDone = { onSubmit() }, onGo = { onSubmit() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
