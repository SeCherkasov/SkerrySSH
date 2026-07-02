package app.skerry.ui.host

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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import app.skerry.shared.host.Host
import app.skerry.shared.ssh.ConnectionType
import app.skerry.shared.ssh.SshAuth
import app.skerry.shared.ssh.SshTarget
import app.skerry.ui.connection.ConnectionTestController
import app.skerry.ui.connection.ConnectionTestStatus
import app.skerry.ui.connection.toSshAuth
import app.skerry.ui.host.AuthMode
import app.skerry.ui.host.NewConnectionFormState
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.conn_auth_ask
import app.skerry.ui.generated.resources.conn_auth_ask_desc
import app.skerry.ui.generated.resources.conn_auth_existing_saved
import app.skerry.ui.generated.resources.conn_auth_key_desc
import app.skerry.ui.generated.resources.conn_auth_key_option
import app.skerry.ui.generated.resources.conn_auth_passphrase_placeholder
import app.skerry.ui.generated.resources.conn_auth_password
import app.skerry.ui.generated.resources.conn_auth_password_desc
import app.skerry.ui.generated.resources.conn_auth_password_option
import app.skerry.ui.generated.resources.conn_auth_password_placeholder
import app.skerry.ui.generated.resources.conn_auth_private_key
import app.skerry.ui.generated.resources.conn_auth_select_credential
import app.skerry.ui.generated.resources.conn_cancel
import app.skerry.ui.generated.resources.conn_create
import app.skerry.ui.generated.resources.conn_field_ai_policy
import app.skerry.ui.generated.resources.conn_field_authentication
import app.skerry.ui.generated.resources.conn_field_baud
import app.skerry.ui.generated.resources.conn_field_device
import app.skerry.ui.generated.resources.conn_field_group
import app.skerry.ui.generated.resources.conn_field_host_address
import app.skerry.ui.generated.resources.conn_field_jump_host
import app.skerry.ui.generated.resources.conn_field_keep_alive
import app.skerry.ui.generated.resources.conn_field_name
import app.skerry.ui.generated.resources.conn_field_port
import app.skerry.ui.generated.resources.conn_field_protocol
import app.skerry.ui.generated.resources.conn_field_tags
import app.skerry.ui.generated.resources.conn_field_username
import app.skerry.ui.generated.resources.conn_footer_encrypted
import app.skerry.ui.generated.resources.conn_group_new
import app.skerry.ui.generated.resources.conn_group_new_title
import app.skerry.ui.generated.resources.conn_group_none
import app.skerry.ui.generated.resources.conn_jump_none
import app.skerry.ui.generated.resources.conn_keepalive_30s
import app.skerry.ui.generated.resources.conn_protocol_serial
import app.skerry.ui.generated.resources.conn_protocol_ssh
import app.skerry.ui.generated.resources.conn_protocol_telnet
import app.skerry.ui.generated.resources.conn_save
import app.skerry.ui.generated.resources.conn_save_changes
import app.skerry.ui.generated.resources.conn_subtitle_edit
import app.skerry.ui.generated.resources.conn_subtitle_new
import app.skerry.ui.generated.resources.conn_tag_add_placeholder
import app.skerry.ui.generated.resources.conn_test
import app.skerry.ui.generated.resources.conn_test_checking
import app.skerry.ui.generated.resources.conn_test_connected
import app.skerry.ui.generated.resources.conn_test_connected_ms
import app.skerry.ui.generated.resources.conn_title_edit
import app.skerry.ui.generated.resources.conn_title_new
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.design.AnchoredDropdown
import app.skerry.ui.design.D
import app.skerry.ui.app.DesktopDesignState
import app.skerry.ui.design.GhostButton
import app.skerry.ui.design.HLine
import app.skerry.ui.design.IconBtn
import app.skerry.ui.app.LocalAi
import app.skerry.ui.app.LocalCredentials
import app.skerry.ui.app.LocalFeatures
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.app.LocalHosts
import app.skerry.ui.app.LocalTestTransport
import app.skerry.ui.ai.POLICY_OPTIONS
import app.skerry.ui.ai.PolicyOption
import app.skerry.ui.design.PrimaryButton
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.i18n.label
import app.skerry.ui.vault.title

/**
 * Модалка «New connection» / «Edit connection»: форма профиля хоста + выбор AI-политики. С живым
 * [LocalHosts] (за гейтом vault) Save создаёт или (при [editHost] != null) обновляет профиль через
 * [app.skerry.ui.host.HostManagerController] и выделяет его в сайдбаре; без него (мок/превью) Save
 * просто закрывает модалку. В режиме правки форма предзаполняется из [editHost]
 * ([NewConnectionFormState.fromHost]), а сохранение удерживает его [Host.id]. Теги редактируемы
 * (пилюли + инлайн-ввод, проводка к [NewConnectionFormState]); jump/keep-alive/AI-политика — пока
 * визуальные заглушки.
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
    // «Test connection» — только для SSH (у Telnet/Serial аутентификации/пробы шифра нет).
    val canTest = tester != null && form.connectionType == ConnectionType.SSH && hasTestSecret &&
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
                    Txt(if (editHost != null) stringResource(Res.string.conn_title_edit) else stringResource(Res.string.conn_title_new), color = D.text, size = 18.sp, weight = FontWeight.SemiBold, letterSpacing = (-0.2).sp)
                    Txt(
                        if (editHost != null) stringResource(Res.string.conn_subtitle_edit)
                        else stringResource(Res.string.conn_subtitle_new),
                        color = D.dim, size = 12.5.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 6.dp),
                    )
                }
                IconBtn("close", onClick = state::closeModal, modifier = Modifier.align(Alignment.TopEnd))
            }
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(start = 26.dp, end = 26.dp, top = 6.dp, bottom = 22.dp)) {
                Field(stringResource(Res.string.conn_field_name)) { ModalTextField(form.name, { form.name = it }, "e.g. prod-web-01") }
                Spacer14()
                Field(stringResource(Res.string.conn_field_protocol)) { ProtocolPicker(form) }
                Spacer14()
                val serial = form.connectionType == ConnectionType.SERIAL
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Field(if (serial) stringResource(Res.string.conn_field_device) else stringResource(Res.string.conn_field_host_address), Modifier.weight(1f)) {
                        ModalTextField(
                            form.address, { form.address = it },
                            if (serial) "/dev/ttyUSB0 or COM3" else "192.168.1.45 or example.com",
                            icon = if (serial) "usb" else "dns",
                        )
                    }
                    Field(if (serial) stringResource(Res.string.conn_field_baud) else stringResource(Res.string.conn_field_port), Modifier.width(110.dp)) {
                        ModalTextField(form.port, { form.port = it }, if (serial) "9600" else "22", keyboardType = KeyboardType.Number)
                    }
                }
                // Пикер обнаруженных портов (desktop — jSerialComm, Android — USB-OTG): тап заполняет
                // поле Device. Пусто (нет адаптера / нет USB Host) — чипсов нет, остаётся ручной ввод.
                if (serial) SerialPortPicker(form)
                // Аутентификация — только SSH: у Telnet логин/пароль вводятся в самом терминале,
                // у Serial аутентификации нет вовсе.
                if (form.connectionType == ConnectionType.SSH) {
                    Spacer14()
                    Field(stringResource(Res.string.conn_field_username)) { ModalTextField(form.username, { form.username = it }, "root or username", icon = "person") }
                    Spacer14()
                    Field(stringResource(Res.string.conn_field_authentication)) { AuthPicker(form) }
                }
                Spacer14()
                Field(stringResource(Res.string.conn_field_group)) { GroupPicker(form, allHosts) }
                if (form.connectionType == ConnectionType.SSH) {
                    Spacer14()
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Field(stringResource(Res.string.conn_field_jump_host), Modifier.weight(1f)) { ModalSelect(stringResource(Res.string.conn_jump_none)) }
                        Field(stringResource(Res.string.conn_field_keep_alive), Modifier.weight(1f)) { ModalSelect(stringResource(Res.string.conn_keepalive_30s)) }
                    }
                }
                Spacer14()
                Field(stringResource(Res.string.conn_field_tags)) {
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
                // Выбор AI-политики виден, когда AI реально доступен (живой контроллер или фича-флаг).
                // Пишется прямо в форму → профиль хоста (Host.aiPolicy).
                if (LocalFeatures.current.ai || LocalAi.current != null) {
                    Spacer14()
                    Field(stringResource(Res.string.conn_field_ai_policy)) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            POLICY_OPTIONS.forEach { opt ->
                                PolicyRow(opt, selected = form.aiPolicy == opt.policy, onClick = { form.aiPolicy = opt.policy })
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
                        Txt(stringResource(Res.string.conn_footer_encrypted), color = D.faint, size = 11.sp)
                    } else {
                        TestStatusLabel(status)
                    }
                }
                Box(Modifier.clip(RoundedCornerShape(7.dp)).clickable(onClick = state::closeModal).padding(horizontal = 16.dp, vertical = 9.dp)) {
                    Txt(stringResource(Res.string.conn_cancel), color = D.dim, size = 12.5.sp)
                }
                GhostButton(
                    stringResource(Res.string.conn_test),
                    onClick = {
                        // Секрет материализуем здесь (только на время коннекта), цель — из полей формы.
                        val auth = when (form.authMode) {
                            AuthMode.NEW_PASSWORD -> form.password.takeIf { it.isNotEmpty() }?.let { SshAuth.Password(it) }
                            AuthMode.NEW_KEY -> form.privateKeyPem.takeIf { it.isNotBlank() }?.let { SshAuth.PublicKey(it, form.passphrase.ifBlank { null }) }
                            AuthMode.EXISTING -> credentials?.credentials?.firstOrNull { it.id == form.existingCredentialId }?.toSshAuth()
                            AuthMode.ASK -> null
                        }
                        if (canTest && auth != null) {
                            tester.test(SshTarget(form.address.trim(), form.portOrNull ?: 22, form.username.trim()), auth)
                        }
                    },
                    fg = if (canTest) D.text else D.faint,
                    border = if (canTest) D.lineStrong else D.line,
                )
                PrimaryButton(
                    if (editHost != null) stringResource(Res.string.conn_save_changes) else stringResource(Res.string.conn_save),
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
 * Сегментированный выбор транспорта (SSH / Telnet / Serial): пишет [NewConnectionFormState.connectionType]
 * через [NewConnectionFormState.chooseConnectionType] (тот подставляет дефолтный порт/скорость). Смена
 * типа перестраивает форму (скрывает аутентификацию, меняет подписи адрес/порт).
 */
@Composable
private fun SerialPortPicker(form: NewConnectionFormState) {
    // Перечисляем один раз при открытии формы (enumerate дёшев и без разрешения). Пусто — ничего не рисуем.
    val ports = remember { listSerialPorts() }
    if (ports.isEmpty()) return
    FlowRow(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ports.forEach { port ->
            key(port.systemName) {
                val selected = form.address == port.systemName
                Row(
                    Modifier
                        .clip(RoundedCornerShape(7.dp))
                        .background(if (selected) D.cyan14 else D.bg)
                        .border(1.dp, if (selected) D.cyan else D.cyan14, RoundedCornerShape(7.dp))
                        .clickable { form.address = port.systemName }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Sym("usb", size = 14.sp, color = if (selected) D.cyanBright else D.faint)
                    Txt(port.description, color = if (selected) D.text else D.dim, size = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun ProtocolPicker(form: NewConnectionFormState) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).background(D.bg).border(1.dp, D.cyan14, RoundedCornerShape(7.dp)).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        ProtocolSegment(stringResource(Res.string.conn_protocol_ssh), "lan", form.connectionType == ConnectionType.SSH, Modifier.weight(1f)) { form.chooseConnectionType(ConnectionType.SSH) }
        ProtocolSegment(stringResource(Res.string.conn_protocol_telnet), "terminal", form.connectionType == ConnectionType.TELNET, Modifier.weight(1f)) { form.chooseConnectionType(ConnectionType.TELNET) }
        ProtocolSegment(stringResource(Res.string.conn_protocol_serial), "cable", form.connectionType == ConnectionType.SERIAL, Modifier.weight(1f)) { form.chooseConnectionType(ConnectionType.SERIAL) }
    }
}

/** Одна пилюля сегментированного выбора протокола: активная — на cyan-подложке. */
@Composable
private fun ProtocolSegment(label: String, icon: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier.clip(RoundedCornerShape(5.dp)).background(if (selected) D.cyan10 else Color.Transparent).clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        Sym(icon, size = 15.sp, color = if (selected) D.cyanBright else D.faint)
        Txt(label, color = if (selected) D.cyanBright else D.dim, size = 12.5.sp, weight = if (selected) FontWeight.Medium else FontWeight.Normal)
    }
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
        AuthMode.ASK -> stringResource(Res.string.conn_auth_ask)
        AuthMode.NEW_PASSWORD -> stringResource(Res.string.conn_auth_password)
        AuthMode.NEW_KEY -> stringResource(Res.string.conn_auth_private_key)
        AuthMode.EXISTING -> saved.firstOrNull { it.id == form.existingCredentialId }?.let { stringResource(Res.string.conn_auth_existing_saved, it.label) } ?: stringResource(Res.string.conn_auth_select_credential)
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
                    AuthOption("vpn_key_off", stringResource(Res.string.conn_auth_ask), stringResource(Res.string.conn_auth_ask_desc), form.authMode == AuthMode.ASK) {
                        form.authMode = AuthMode.ASK; menuOpen = false
                    }
                    AuthOption("password", stringResource(Res.string.conn_auth_password_option), stringResource(Res.string.conn_auth_password_desc), form.authMode == AuthMode.NEW_PASSWORD) {
                        form.authMode = AuthMode.NEW_PASSWORD; menuOpen = false
                    }
                    AuthOption("key", stringResource(Res.string.conn_auth_key_option), stringResource(Res.string.conn_auth_key_desc), form.authMode == AuthMode.NEW_KEY) {
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
                ModalTextField(form.password, { form.password = it }, stringResource(Res.string.conn_auth_password_placeholder), icon = "key", masked = true)
            }
            AuthMode.NEW_KEY -> {
                Spacer14()
                // keyboardType=Password гасит автокоррект/подсказки IME (Android), чтобы ключ не оседал в словаре.
                ModalTextField(form.privateKeyPem, { form.privateKeyPem = it }, "-----BEGIN OPENSSH PRIVATE KEY-----", keyboardType = KeyboardType.Password, singleLine = false, mono = true, minHeightDp = 96)
                Spacer14()
                ModalTextField(form.passphrase, { form.passphrase = it }, stringResource(Res.string.conn_auth_passphrase_placeholder), icon = "lock", masked = true)
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
 * Поле «Group»: выпадающий селект (как [AuthPicker]) — «No group», уже созданные группы каталога
 * ([groupSuggestions]) и «New group…», открывающий диалог создания. Выбранная группа хранится в
 * [NewConnectionFormState.group]; создание новой просто проставляет её имя (профиль заведёт папку при
 * сохранении). Свободного ввода в самом поле нет — только список + явное создание, чтобы не плодить
 * опечатки-дубли групп.
 */
@Composable
private fun GroupPicker(form: NewConnectionFormState, allHosts: List<Host>) {
    var menuOpen by remember { mutableStateOf(false) }
    var createOpen by remember { mutableStateOf(false) }
    val groups = remember(allHosts) { groupSuggestions(allHosts) }
    val hasGroup = form.group.isNotBlank()
    AnchoredDropdown(
        expanded = menuOpen,
        onDismiss = { menuOpen = false },
        trigger = {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).background(D.bg).border(1.dp, D.cyan14, RoundedCornerShape(7.dp)).clickable { menuOpen = !menuOpen }.padding(horizontal = 11.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Txt(if (hasGroup) form.group else stringResource(Res.string.conn_group_none), color = if (hasGroup) D.text else D.faint, size = 13.sp)
                Sym(if (menuOpen) "expand_less" else "expand_more", size = 16.sp, color = D.faint)
            }
        },
        menu = { width ->
            SuggestionMenu(width) {
                GroupOption(stringResource(Res.string.conn_group_none), selected = !hasGroup) { form.group = ""; menuOpen = false }
                groups.forEach { group ->
                    key(group) { GroupOption(group, selected = form.group == group) { form.group = group; menuOpen = false } }
                }
                HLine(modifier = Modifier.padding(vertical = 4.dp))
                GroupOption(stringResource(Res.string.conn_group_new), selected = false, icon = "add") { menuOpen = false; createOpen = true }
            }
        },
    )
    if (createOpen) {
        GroupCreateDialog(onDismiss = { createOpen = false }, onCreate = { name -> form.group = name.trim(); createOpen = false })
    }
}

/** Строка-вариант селекта группы: опц. иконка + название + галочка выбранного. */
@Composable
private fun GroupOption(title: String, selected: Boolean, icon: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(if (selected) D.cyan10 else Color.Transparent).clickable(onClick = onClick).padding(horizontal = 11.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        if (icon != null) Sym(icon, size = 15.sp, color = D.cyanBright)
        Txt(title, color = if (selected) D.cyanBright else D.text, size = 12.5.sp, weight = if (selected) FontWeight.Medium else FontWeight.Normal, modifier = Modifier.weight(1f))
        if (selected) Sym("check", size = 15.sp, color = D.cyanBright)
    }
}

/**
 * Модальный диалог создания новой группы (Popup поверх модалки коннекта): поле имени + Cancel/Create.
 * Пустое имя не создаёт (кнопка неактивна). Имя только проставляется в форму — папка появится в
 * каталоге при сохранении хоста.
 */
@Composable
private fun GroupCreateDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    val canCreate = name.isNotBlank()
    Popup(alignment = Alignment.Center, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        Box(
            Modifier.fillMaxSize().background(Color(0xB3060E16)).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier
                    .widthIn(max = 360.dp)
                    .fillMaxWidth()
                    .padding(20.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(D.surfaceDeep)
                    .border(1.dp, D.cyan14, RoundedCornerShape(12.dp))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
                    .padding(22.dp),
            ) {
                Txt(stringResource(Res.string.conn_group_new_title), color = D.text, size = 16.sp, weight = FontWeight.SemiBold)
                Spacer14()
                ModalTextField(name, { name = it }, "Production")
                Spacer14()
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                    Box(Modifier.clip(RoundedCornerShape(7.dp)).clickable(onClick = onDismiss).padding(horizontal = 16.dp, vertical = 9.dp)) {
                        Txt(stringResource(Res.string.conn_cancel), color = D.dim, size = 12.5.sp)
                    }
                    PrimaryButton(stringResource(Res.string.conn_create), onClick = { if (canCreate) onCreate(name) }, bg = if (canCreate) D.cyan else D.cyan.copy(alpha = 0.4f))
                }
            }
        }
    }
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
            Txt(stringResource(Res.string.conn_test_checking), color = D.dim, size = 11.5.sp)
        }
        is ConnectionTestStatus.Success -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Sym("check_circle", size = 14.sp, color = D.moss)
            Txt(
                status.roundTripMillis?.let { stringResource(Res.string.conn_test_connected_ms, it) } ?: stringResource(Res.string.conn_test_connected),
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
                if (value.isEmpty()) Txt(stringResource(Res.string.conn_tag_add_placeholder), color = D.faint, size = 12.5.sp)
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
