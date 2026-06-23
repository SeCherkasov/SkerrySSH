package app.skerry.ui.design

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.host.Host
import app.skerry.shared.vault.CredentialSecret
import app.skerry.ui.host.AuthMode
import app.skerry.ui.host.NewConnectionFormState

/**
 * Модалка «New connection» / «Edit connection»: форма профиля хоста + выбор AI-политики. С живым
 * [LocalHosts] (за гейтом vault) Save создаёт или (при [editHost] != null) обновляет профиль через
 * [app.skerry.ui.host.HostManagerController] и выделяет его в сайдбаре; без него (мок/превью) Save
 * просто закрывает модалку. В режиме правки форма предзаполняется из [editHost]
 * ([NewConnectionFormState.fromHost]), а сохранение удерживает его [Host.id]. Поля
 * jump/keep-alive/tags/AI-политика — пока визуальные заглушки (отдельные слайсы).
 */
@Composable
fun NewConnectionModal(state: DesktopDesignState, editHost: Host? = null) {
    val noop = remember { MutableInteractionSource() }
    val hosts = LocalHosts.current
    val credentials = LocalCredentials.current
    // Ключ по editHost: открытие модалки на правку (или смена цели) пересоздаёт форму из профиля.
    val form = remember(editHost) { editHost?.let { NewConnectionFormState.fromHost(it) } ?: NewConnectionFormState() }
    // Гард повторного Save (Enter/двойной клик) до закрытия модалки — иначе дубль секрета+хоста в vault.
    // Ключ по editHost вместе с form: смена цели сбрасывает гард, а не залипает на прежней.
    var submitting by remember(editHost) { mutableStateOf(false) }
    Box(
        Modifier.fillMaxSize().background(Color(0xB3060E16)).clickable(interactionSource = noop, indication = null, onClick = state::closeModal),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .padding(20.dp)
                .heightIn(max = 720.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(D.surfaceDeep)
                .border(1.dp, D.cyan14, RoundedCornerShape(12.dp))
                .clickable(interactionSource = noop, indication = null, onClick = {}),
        ) {
            Box(Modifier.fillMaxWidth().padding(start = 26.dp, end = 26.dp, top = 22.dp, bottom = 14.dp)) {
                Column {
                    Txt(if (editHost != null) "Edit connection" else "New connection", color = D.text, size = 18.sp, weight = FontWeight.SemiBold, letterSpacing = (-0.2).sp)
                    Txt(
                        if (editHost != null) "Update this skerry's profile. Credentials are encrypted with your master password."
                        else "Configure a new skerry in your archipelago. Credentials are encrypted with your master password.",
                        color = D.dim, size = 12.5.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 6.dp),
                    )
                }
                IconBtn("close", onClick = state::closeModal, modifier = Modifier.align(Alignment.TopEnd))
            }
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(start = 26.dp, end = 26.dp, top = 6.dp, bottom = 22.dp)) {
                Field("Name") { ModalTextField(form.name, { form.name = it }, "e.g. prod-web-01") }
                Spacer14()
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Field("Host address", Modifier.weight(1f)) {
                        ModalTextField(form.address, { form.address = it }, "192.168.1.45 or example.com", icon = "dns")
                    }
                    Field("Port", Modifier.width(110.dp)) {
                        ModalTextField(form.port, { form.port = it }, "22", keyboardType = KeyboardType.Number)
                    }
                }
                Spacer14()
                Field("Username") { ModalTextField(form.username, { form.username = it }, "root or username", icon = "person") }
                Spacer14()
                Field("Authentication") { AuthPicker(form) }
                Spacer14()
                Field("Group") { ModalTextField(form.group, { form.group = it }, "Production (optional)") }
                Spacer14()
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Field("Jump host (optional)", Modifier.weight(1f)) { ModalSelect("None — direct") }
                    Field("Keep-alive", Modifier.weight(1f)) { ModalSelect("Every 30s") }
                }
                Spacer14()
                Field("Tags") {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).background(D.bg).border(1.dp, D.cyan14, RoundedCornerShape(7.dp)).padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        TagPill("#prod")
                        TagPill("#docker")
                        Txt("add tag…", color = D.faint, size = 12.5.sp)
                    }
                }
                // Выбор AI-политики — фича MVP2 за фича-флагом; в MVP1 (дефолт) его в форме нет.
                if (LocalFeatures.current.ai) {
                    Spacer14()
                    Field("AI policy for this connection") {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            POLICY_OPTIONS.forEach { opt ->
                                PolicyRow(opt, selected = state.modalPolicy == opt.policy, onClick = { state.choosePolicy(opt.policy) })
                            }
                        }
                    }
                }
            }
            HLine()
            Row(
                Modifier.fillMaxWidth().background(Color(0x26000000)).padding(horizontal = 26.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Sym("shield_lock", size = 14.sp, color = D.moss)
                    Txt("Encrypted · stays on this device", color = D.faint, size = 11.sp)
                }
                Box(Modifier.clip(RoundedCornerShape(7.dp)).clickable(onClick = state::closeModal).padding(horizontal = 16.dp, vertical = 9.dp)) {
                    Txt("Cancel", color = D.dim, size = 12.5.sp)
                }
                GhostButton("Test", onClick = {})
                PrimaryButton(
                    if (editHost != null) "Save changes" else "Save",
                    onClick = {
                        if (submitting) {
                            // повторное нажатие до закрытия — игнорируем
                        } else if (hosts == null) {
                            state.closeModal() // мок/превью: сохранять некуда
                        } else if (form.canSave) {
                            // Новый секрет (пароль/ключ) запечатывается в keychain, его id напрямую
                            // привязывается к хосту; ASK/мок-путь без vault → credentialId = null.
                            submitting = true
                            // Секрет создаём только при живом keychain: иначе он осел бы в vault без
                            // ссылки на хост (orphan). За гейтом credentials всегда присутствует;
                            // гард — fail-closed на рассинхрон. В режиме правки EXISTING-привязка
                            // возвращается как есть (секрет не пересоздаётся).
                            val credentialId = form.resolveCredentialId(
                                saveCredential = { draft -> credentials?.save(draft) },
                            )
                            // editHost?.id != null → обновление существующего профиля по месту.
                            state.selectHost(hosts.save(form.toDraft(id = editHost?.id, credentialId = credentialId)))
                            state.closeModal()
                        }
                    },
                    bg = if (hosts == null || form.canSave) D.cyan else D.cyan.copy(alpha = 0.4f),
                )
            }
        }
    }
}

@Composable
private fun Spacer14() = Box(Modifier.size(14.dp))

@Composable
private fun Field(label: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier) {
        Txt(label.uppercase(), color = D.faint, size = 10.5.sp, weight = FontWeight.SemiBold, letterSpacing = 0.6.sp, modifier = Modifier.padding(bottom = 5.dp))
        content()
    }
}

/**
 * Редактируемое текстовое поле формы (опц. иконка слева): стиль макета + плейсхолдер.
 * [masked] — скрывать ввод (пароль/passphrase); [singleLine] = false + [mono] + [minHeightDp] —
 * многострочная моноширинная область для вставки приватного ключа (PEM).
 */
@Composable
private fun ModalTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    icon: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    masked: Boolean = false,
    singleLine: Boolean = true,
    mono: Boolean = false,
    minHeightDp: Int? = null,
) {
    val fonts = LocalFonts.current
    val family = if (mono) fonts.mono else fonts.ui
    val fontSize = if (mono) 11.5.sp else 13.sp
    val textStyle = remember(family, fontSize) {
        TextStyle(color = D.text, fontSize = fontSize, fontFamily = family, lineHeight = if (mono) 16.sp else 18.sp)
    }
    // Рамка/иконка — в decorationBox, чтобы клик по всей площади поля ставил каретку.
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = singleLine,
        textStyle = textStyle,
        cursorBrush = SolidColor(D.cyan),
        visualTransformation = if (masked) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (masked) KeyboardType.Password else keyboardType),
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .then(if (minHeightDp != null) Modifier.heightIn(min = minHeightDp.dp) else Modifier)
                    .clip(RoundedCornerShape(7.dp)).background(D.bg).border(1.dp, D.cyan14, RoundedCornerShape(7.dp))
                    .padding(horizontal = 11.dp, vertical = 9.dp),
                verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (icon != null) Sym(icon, size = 16.sp, color = D.faint)
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Txt(placeholder, color = D.faint, size = fontSize, font = if (mono) fonts.mono else null)
                    inner()
                }
            }
        },
    )
}

/**
 * Выбор аутентификации хоста: рабочий дропдаун (Ask every time / новый пароль / новый ключ / уже
 * сохранённые keychain-секреты из vault) + инлайн-поля под новый секрет. Список сохранённых берётся
 * из живого [LocalCredentials] (за гейтом vault); в мок-пути остаются только варианты без vault.
 */
@Composable
private fun AuthPicker(form: NewConnectionFormState) {
    val credentials = LocalCredentials.current
    val saved = credentials?.credentials ?: emptyList()
    var menuOpen by remember { mutableStateOf(false) }
    val selectedLabel = when (form.authMode) {
        AuthMode.ASK -> "Ask every time"
        AuthMode.NEW_PASSWORD -> "Password"
        AuthMode.NEW_KEY -> "Private key"
        AuthMode.EXISTING -> saved.firstOrNull { it.id == form.existingCredentialId }?.let { "${it.label} (saved)" } ?: "Select credential…"
    }
    Column {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).background(D.bg).border(1.dp, D.cyan14, RoundedCornerShape(7.dp)).clickable { menuOpen = !menuOpen }.padding(horizontal = 11.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Txt(selectedLabel, color = D.text, size = 13.sp)
            Sym(if (menuOpen) "expand_less" else "expand_more", size = 16.sp, color = D.faint)
        }
        if (menuOpen) {
            Column(Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(7.dp)).background(D.surfaceDeep).border(1.dp, D.cyan14, RoundedCornerShape(7.dp)).padding(vertical = 4.dp)) {
                AuthOption("vpn_key_off", "Ask every time", "password requested on connect", form.authMode == AuthMode.ASK) {
                    form.authMode = AuthMode.ASK; menuOpen = false
                }
                AuthOption("password", "Password…", "store a password in your vault", form.authMode == AuthMode.NEW_PASSWORD) {
                    form.authMode = AuthMode.NEW_PASSWORD; menuOpen = false
                }
                AuthOption("key", "Paste private key…", "store an SSH key in your vault", form.authMode == AuthMode.NEW_KEY) {
                    form.authMode = AuthMode.NEW_KEY; menuOpen = false
                }
                if (saved.isNotEmpty()) {
                    HLine(modifier = Modifier.padding(vertical = 4.dp))
                    saved.forEach { cred ->
                        AuthOption(cred.secret.pickerIcon(), cred.label, cred.secret.pickerTypeLabel(), form.authMode == AuthMode.EXISTING && form.existingCredentialId == cred.id) {
                            form.authMode = AuthMode.EXISTING; form.existingCredentialId = cred.id; menuOpen = false
                        }
                    }
                }
            }
        }
        when (form.authMode) {
            AuthMode.NEW_PASSWORD -> {
                Spacer14()
                ModalTextField(form.password, { form.password = it }, "password to store", icon = "key", masked = true)
            }
            AuthMode.NEW_KEY -> {
                Spacer14()
                // keyboardType=Password гасит автокоррект/подсказки IME (Android), чтобы ключ не оседал в словаре.
                ModalTextField(form.privateKeyPem, { form.privateKeyPem = it }, "-----BEGIN OPENSSH PRIVATE KEY-----", keyboardType = KeyboardType.Password, singleLine = false, mono = true, minHeightDp = 96)
                Spacer14()
                ModalTextField(form.passphrase, { form.passphrase = it }, "key passphrase (optional)", icon = "lock", masked = true)
            }
            else -> {}
        }
    }
}

/** Человеко-тип keychain-секрета для строки выбора в [AuthPicker]. */
private fun CredentialSecret.pickerTypeLabel(): String = when (this) {
    is CredentialSecret.Password -> "Password"
    is CredentialSecret.PrivateKey -> "SSH key"
    is CredentialSecret.Certificate -> "Certificate"
}

/** Material-иконка типа keychain-секрета для строки выбора в [AuthPicker]. */
private fun CredentialSecret.pickerIcon(): String = when (this) {
    is CredentialSecret.Password -> "password"
    is CredentialSecret.PrivateKey -> "key"
    is CredentialSecret.Certificate -> "workspace_premium"
}

/** Строка-вариант в дропдауне аутентификации: иконка + название + подпись + галочка выбранного. */
@Composable
private fun AuthOption(icon: String, title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(if (selected) D.cyan10 else Color.Transparent).clickable(onClick = onClick).padding(horizontal = 11.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Sym(icon, size = 16.sp, color = if (selected) D.cyanBright else D.dim)
        Column(Modifier.weight(1f)) {
            Txt(title, color = if (selected) D.cyanBright else D.text, size = 12.5.sp, weight = FontWeight.Medium)
            Txt(subtitle, color = D.faint, size = 10.5.sp)
        }
        if (selected) Sym("check", size = 15.sp, color = D.cyanBright)
    }
}

@Composable
private fun ModalSelect(value: String) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).background(D.bg).border(1.dp, D.cyan14, RoundedCornerShape(7.dp)).padding(horizontal = 11.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Txt(value, color = D.text, size = 13.sp)
        Sym("expand_more", size = 16.sp, color = D.faint)
    }
}

@Composable
private fun TagPill(text: String) {
    Box(Modifier.clip(RoundedCornerShape(20.dp)).background(D.cyan.copy(alpha = 0.12f)).padding(horizontal = 9.dp, vertical = 2.dp)) {
        Txt(text, color = D.cyanBright, size = 11.sp)
    }
}

@Composable
private fun PolicyRow(opt: PolicyOption, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) D.cyan10 else Color.Transparent)
            .border(1.dp, if (selected) D.cyan else D.cyan06, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.padding(top = 2.dp).size(16.dp).clip(CircleShape).border(1.5.dp, if (selected) D.cyan else D.faint, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Box(Modifier.size(8.dp).clip(CircleShape).background(D.cyan))
        }
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Sym(opt.icon, size = 14.sp, color = D.dim)
                Txt(opt.title, color = D.text, size = 13.sp, weight = FontWeight.Medium)
            }
            Txt(opt.desc, color = D.dim, size = 11.5.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 2.dp))
        }
    }
}
