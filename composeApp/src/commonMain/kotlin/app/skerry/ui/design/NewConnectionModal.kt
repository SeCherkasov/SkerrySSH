package app.skerry.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.host.Host
import app.skerry.shared.ssh.SshAuth
import app.skerry.shared.ssh.SshTarget
import app.skerry.ui.connection.ConnectionTestController
import app.skerry.ui.connection.ConnectionTestStatus
import app.skerry.ui.connection.toSshAuth
import app.skerry.ui.host.AuthMode
import app.skerry.ui.host.NewConnectionFormState

/**
 * Модалка «New connection» / «Edit connection»: форма профиля хоста + выбор AI-политики. С живым
 * [LocalHosts] (за гейтом vault) Save создаёт или (при [editHost] != null) обновляет профиль через
 * [app.skerry.ui.host.HostManagerController] и выделяет его в сайдбаре; без него (мок/превью) Save
 * просто закрывает модалку. В режиме правки форма предзаполняется из [editHost]
 * ([NewConnectionFormState.fromHost]), а сохранение удерживает его [Host.id]. Теги редактируемы
 * (пилюли + инлайн-ввод, проводка к [NewConnectionFormState]); jump/keep-alive/AI-политика — пока
 * визуальные заглушки (отдельные слайсы).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NewConnectionModal(state: DesktopDesignState, editHost: Host? = null) {
    val noop = remember { MutableInteractionSource() }
    val hosts = LocalHosts.current
    // Уже созданные хосты — источник подсказок для пикеров Group/Tags (в мок/превью список пуст).
    val allHosts = hosts?.hosts ?: emptyList()
    val credentials = LocalCredentials.current
    // Ключ по editHost: открытие модалки на правку (или смена цели) пересоздаёт форму из профиля.
    val form = remember(editHost) { editHost?.let { NewConnectionFormState.fromHost(it) } ?: NewConnectionFormState() }
    // Гард повторного Save (Enter/двойной клик) до закрытия модалки — иначе дубль секрета+хоста в vault.
    // Ключ по editHost вместе с form: смена цели сбрасывает гард, а не залипает на прежней.
    var submitting by remember(editHost) { mutableStateOf(false) }
    // Незакоммиченный ввод тега (пилюля ещё не создана). Хоистится сюда, чтобы Save мог его дофиксировать.
    var tagDraft by remember(editHost) { mutableStateOf("") }
    // «Test connection»: разовый коннект без открытия сессии. Только при живом транспорте (за гейтом vault);
    // в мок/превью tester == null и кнопка недоступна.
    val transport = LocalTestTransport.current
    val testScope = rememberCoroutineScope()
    val tester = remember(transport, testScope) { transport?.let { ConnectionTestController(it, testScope) } }
    // При смене транспорта (новый tester) или закрытии модалки отменяем in-flight проверку прежнего
    // tester — иначе осиротевший коннект-пробник тянул бы сеть до своего таймаута.
    DisposableEffect(tester) { onDispose { tester?.reset() } }
    // Готов ли способ аутентификации для теста — БЕЗ материализации секрета (его собираем только в onClick,
    // чтобы копия пароля/ключа жила лишь на время коннекта, а не весь срок открытой модалки).
    val hasTestSecret = when (form.authMode) {
        AuthMode.NEW_PASSWORD -> form.password.isNotEmpty()
        AuthMode.NEW_KEY -> form.privateKeyPem.isNotBlank()
        AuthMode.EXISTING -> credentials?.credentials?.any { it.id == form.existingCredentialId } == true
        AuthMode.ASK -> false
    }
    val canTest = tester != null && hasTestSecret &&
        form.address.isNotBlank() && form.username.isNotBlank() && form.portOrNull != null
    // Правка полей коннекта/аутентификации обнуляет прежний результат теста — он больше не релевантен.
    LaunchedEffect(form.address, form.username, form.port, form.authMode, form.existingCredentialId, form.password, form.privateKeyPem, form.passphrase) {
        tester?.reset()
    }
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
                Field("Group") { GroupPicker(form, allHosts) }
                Spacer14()
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Field("Jump host (optional)", Modifier.weight(1f)) { ModalSelect("None — direct") }
                    Field("Keep-alive", Modifier.weight(1f)) { ModalSelect("Every 30s") }
                }
                Spacer14()
                Field("Tags") {
                    // Подсказки = теги других хостов, ещё не добавленные сюда, сужённые набранным черновиком.
                    var tagFocused by remember(editHost) { mutableStateOf(false) }
                    val tagSugs = remember(allHosts, form.tags, tagDraft) { tagSuggestions(allHosts, form.tags, tagDraft) }
                    AnchoredDropdown(
                        expanded = tagFocused && tagSugs.isNotEmpty(),
                        onDismiss = { tagFocused = false },
                        focusable = false, // не красть фокус у поля ввода тега
                        trigger = {
                            FlowRow(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).background(D.bg).border(1.dp, D.cyan14, RoundedCornerShape(7.dp)).padding(horizontal = 10.dp, vertical = 7.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                form.tags.forEach { tag -> key(tag) { RemovableTagPill(tag) { form.removeTag(tag) } } }
                                TagInput(
                                    value = tagDraft,
                                    // Запятая фиксирует тег(и) сразу; одиночный тег — по Enter (onCommit).
                                    onValueChange = { v -> if (v.contains(',')) { form.addTag(v); tagDraft = "" } else tagDraft = v },
                                    onCommit = { form.addTag(tagDraft); tagDraft = "" },
                                    onFocusChanged = { tagFocused = it },
                                )
                            }
                        },
                        menu = { width ->
                            SuggestionMenu(width) {
                                // Клик по подсказке добавляет тег; фокус остаётся на поле — можно набирать дальше,
                                // меню пересчитается без только что добавленного.
                                tagSugs.forEach { tag -> key(tag) { SuggestionRow("#$tag") { form.addTag(tag); tagDraft = "" } } }
                            }
                        },
                    )
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
                    // Пока тест не запускался — подсказка о шифровании; иначе её место занимает статус теста.
                    val status = tester?.status ?: ConnectionTestStatus.Idle
                    if (status == ConnectionTestStatus.Idle) {
                        Sym("shield_lock", size = 14.sp, color = D.moss)
                        Txt("Encrypted · stays on this device", color = D.faint, size = 11.sp)
                    } else {
                        TestStatusLabel(status)
                    }
                }
                Box(Modifier.clip(RoundedCornerShape(7.dp)).clickable(onClick = state::closeModal).padding(horizontal = 16.dp, vertical = 9.dp)) {
                    Txt("Cancel", color = D.dim, size = 12.5.sp)
                }
                GhostButton(
                    "Test",
                    onClick = {
                        // Секрет материализуем здесь (только на время коннекта), цель — из полей формы.
                        val auth = when (form.authMode) {
                            AuthMode.NEW_PASSWORD -> form.password.takeIf { it.isNotEmpty() }?.let { SshAuth.Password(it) }
                            AuthMode.NEW_KEY -> form.privateKeyPem.takeIf { it.isNotBlank() }?.let { SshAuth.PublicKey(it, form.passphrase.ifBlank { null }) }
                            AuthMode.EXISTING -> credentials?.credentials?.firstOrNull { it.id == form.existingCredentialId }?.toSshAuth()
                            AuthMode.ASK -> null
                        }
                        if (canTest && auth != null) {
                            tester?.test(SshTarget(form.address.trim(), form.portOrNull ?: 22, form.username.trim()), auth)
                        }
                    },
                    fg = if (canTest) D.text else D.faint,
                    border = if (canTest) D.lineStrong else D.line,
                )
                PrimaryButton(
                    if (editHost != null) "Save changes" else "Save",
                    onClick = {
                        if (submitting) {
                            // повторное нажатие до закрытия — игнорируем
                        } else if (hosts == null) {
                            state.closeModal() // мок/превью: сохранять некуда
                        } else if (form.canSave) {
                            // Дофиксировать незакоммиченный ввод тега, чтобы он не потерялся при Save.
                            if (tagDraft.isNotBlank()) { form.addTag(tagDraft); tagDraft = "" }
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
                            // Секрет уже запечатан в vault — снимаем ссылки на него из state формы, сокращая
                            // окно жизни ключа/пароля в куче (String на JVM не занулить на месте, но ссылку
                            // убираем). Тот же приём, что в мобильном MobileNewConnectionSheet.
                            form.password = ""; form.privateKeyPem = ""; form.passphrase = ""
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
    trailing: (@Composable () -> Unit)? = null,
    onFocusChanged: ((Boolean) -> Unit)? = null,
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
        modifier = Modifier.fillMaxWidth().onFocusChanged { onFocusChanged?.invoke(it.isFocused) },
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
                if (trailing != null) trailing()
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
        AnchoredDropdown(
            expanded = menuOpen,
            onDismiss = { menuOpen = false },
            trigger = {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).background(D.bg).border(1.dp, D.cyan14, RoundedCornerShape(7.dp)).clickable { menuOpen = !menuOpen }.padding(horizontal = 11.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Txt(selectedLabel, color = D.text, size = 13.sp)
                    Sym(if (menuOpen) "expand_less" else "expand_more", size = 16.sp, color = D.faint)
                }
            },
            menu = { width ->
                // Меню всплывает НАД формой (Popup), а не раздвигает её; ширина = ширине триггера, скролл при переполнении.
                Column(Modifier.width(width).clip(RoundedCornerShape(7.dp)).background(D.surfaceDeep).border(1.dp, D.cyan14, RoundedCornerShape(7.dp)).heightIn(max = 320.dp).verticalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
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
            },
        )
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

/**
 * Поле «Group»: свободный ввод + выпадающий список уже созданных групп ([groupSuggestions]). Меню —
 * type-ahead (focusable=false, чтобы не прерывать набор), показывается пока поле в фокусе и есть что
 * предложить; выбор пункта заполняет поле и снимает фокус (меню закрывается). Скрываем подсказку,
 * дословно совпадающую с введённым, — выбирать в ней нечего.
 */
@Composable
private fun GroupPicker(form: NewConnectionFormState, allHosts: List<Host>) {
    val focusManager = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    val suggestions = remember(allHosts, form.group) {
        groupSuggestions(allHosts, form.group)
            .filterNot { it.equals(form.group.trim(), ignoreCase = true) }
    }
    AnchoredDropdown(
        expanded = focused && suggestions.isNotEmpty(),
        onDismiss = { focused = false },
        focusable = false,
        trigger = {
            ModalTextField(
                form.group, { form.group = it }, "Production (optional)",
                onFocusChanged = { focused = it },
                trailing = { Sym("expand_more", size = 16.sp, color = D.faint) },
            )
        },
        menu = { width ->
            SuggestionMenu(width) {
                suggestions.forEach { group -> key(group) { SuggestionRow(group) { form.group = group; focusManager.clearFocus() } } }
            }
        },
    )
}

/** Контейнер выпадающих подсказок (group/tags): ширина триггера, скролл при переполнении, стиль меню. */
@Composable
private fun SuggestionMenu(width: Dp, content: @Composable () -> Unit) {
    Column(
        Modifier.width(width).clip(RoundedCornerShape(7.dp)).background(D.surfaceDeep).border(1.dp, D.cyan14, RoundedCornerShape(7.dp))
            .heightIn(max = 240.dp).verticalScroll(rememberScrollState()).padding(vertical = 4.dp),
    ) { content() }
}

/** Строка-подсказка в выпадающем списке: одна метка, клик — выбор. */
@Composable
private fun SuggestionRow(label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 11.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Txt(label, color = D.text, size = 12.5.sp)
    }
}

/** Пилюля тега с крестиком удаления; [tag] — каноническая форма, на экране с префиксом `#`. */
@Composable
private fun RemovableTagPill(tag: String, onRemove: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(20.dp)).background(D.cyan.copy(alpha = 0.12f)).padding(start = 9.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Txt("#$tag", color = D.cyanBright, size = 11.sp)
        Box(Modifier.clip(CircleShape).clickable(onClick = onRemove).padding(2.dp), contentAlignment = Alignment.Center) {
            Sym("close", size = 12.sp, color = D.cyanBright)
        }
    }
}

/** Статус «Test connection» в футере модалки: проверка/успех (с RTT)/ошибка с причиной. */
@Composable
private fun TestStatusLabel(status: ConnectionTestStatus) {
    when (status) {
        ConnectionTestStatus.Idle -> {}
        ConnectionTestStatus.Checking -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Sym("progress_activity", size = 14.sp, color = D.dim)
            Txt("Testing connection…", color = D.dim, size = 11.5.sp)
        }
        is ConnectionTestStatus.Success -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Sym("check_circle", size = 14.sp, color = D.moss)
            Txt(
                "Connected" + (status.roundTripMillis?.let { " · $it ms" } ?: ""),
                color = D.moss, size = 11.5.sp,
            )
        }
        is ConnectionTestStatus.Failure -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Sym("error", size = 14.sp, color = D.storm)
            Txt(status.message, color = D.storm, size = 11.5.sp)
        }
    }
}

/** Инлайн-ввод нового тега внутри блока Tags: Enter ([onCommit]) или запятая фиксирует пилюлю. */
@Composable
private fun TagInput(value: String, onValueChange: (String) -> Unit, onCommit: () -> Unit, onFocusChanged: ((Boolean) -> Unit)? = null) {
    val fonts = LocalFonts.current
    val textStyle = remember(fonts.ui) { TextStyle(color = D.text, fontSize = 12.5.sp, fontFamily = fonts.ui) }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = textStyle,
        cursorBrush = SolidColor(D.cyan),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onCommit() }),
        modifier = Modifier.widthIn(min = 72.dp).onFocusChanged { onFocusChanged?.invoke(it.isFocused) },
        decorationBox = { inner ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) Txt("add tag…", color = D.faint, size = 12.5.sp)
                inner()
            }
        },
    )
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
