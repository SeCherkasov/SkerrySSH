package app.skerry.ui.mobile

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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.host.Host
import app.skerry.shared.ssh.ConnectionType
import app.skerry.ui.host.AuthMode
import app.skerry.ui.host.NewConnectionFormState
import app.skerry.ui.nav.PlatformBackHandler
import app.skerry.ui.secure.SecureScreen
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
import app.skerry.ui.generated.resources.conn_field_ai_policy_short
import app.skerry.ui.generated.resources.conn_field_authentication
import app.skerry.ui.generated.resources.conn_field_baud
import app.skerry.ui.generated.resources.conn_field_device
import app.skerry.ui.generated.resources.conn_field_group
import app.skerry.ui.generated.resources.conn_field_host_address
import app.skerry.ui.generated.resources.conn_field_name
import app.skerry.ui.generated.resources.conn_field_port
import app.skerry.ui.generated.resources.conn_field_protocol
import app.skerry.ui.generated.resources.conn_field_tags
import app.skerry.ui.generated.resources.conn_field_username
import app.skerry.ui.generated.resources.conn_group_delete
import app.skerry.ui.generated.resources.conn_group_new
import app.skerry.ui.generated.resources.conn_group_new_title
import app.skerry.ui.generated.resources.conn_group_none
import app.skerry.ui.generated.resources.conn_group_rename_hint
import app.skerry.ui.generated.resources.conn_group_rename_title
import app.skerry.ui.generated.resources.conn_protocol_serial
import app.skerry.ui.generated.resources.conn_protocol_ssh
import app.skerry.ui.generated.resources.conn_protocol_telnet
import app.skerry.ui.generated.resources.conn_save
import app.skerry.ui.generated.resources.conn_save_changes
import app.skerry.ui.generated.resources.conn_save_connection
import app.skerry.ui.generated.resources.conn_subtitle_mobile
import app.skerry.ui.generated.resources.conn_tag_add_placeholder
import app.skerry.ui.generated.resources.conn_title_edit
import app.skerry.ui.generated.resources.conn_title_new
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.app.AiPolicy
import app.skerry.ui.design.AnchoredDropdown
import app.skerry.ui.design.D
import app.skerry.ui.design.HLine
import app.skerry.ui.app.LocalAi
import app.skerry.ui.app.LocalCredentials
import app.skerry.ui.app.LocalFeatures
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.app.LocalHosts
import app.skerry.ui.app.MobileDesignState
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.host.groupSuggestions
import app.skerry.ui.i18n.label
import app.skerry.ui.host.listSerialPorts
import app.skerry.ui.host.tagSuggestions
import app.skerry.ui.host.pickerIcon
import app.skerry.ui.host.pickerTypeLabel


/**
 * Нижний лист «New connection»: затемнение + панель снизу с формой профиля хоста. С живым
 * [LocalHosts] (за гейтом vault) Save создаёт профиль через
 * [app.skerry.ui.host.HostManagerController] и закрывает лист; без него (превью) Save просто
 * закрывает. Переиспользует общий [NewConnectionFormState].
 *
 * Authentication — живой [MobileAuthPicker]: Ask / новый пароль / новый ключ / уже сохранённый
 * keychain-секрет из [LocalCredentials]. Новый секрет запечатывается в открытый vault и
 * привязывается к хосту через [NewConnectionFormState.resolveCredentialId]; AI-политика спрятана
 * за фича-флагом [FeatureFlags.ai].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MobileNewConnectionSheet(state: MobileDesignState) {
    // Форма может содержать вводимые секреты (новый пароль/приватный ключ/passphrase) — защита окна
    // от снимков экрана/превью в Recent Apps пока лист открыт (Android; desktop — no-op).
    SecureScreen()
    val hosts = LocalHosts.current
    val credentials = LocalCredentials.current
    // Режим правки: лист предзаполняется из профиля и удерживает его id (паритет desktop NewConnectionModal).
    val editHost = state.editingHost
    // Ключ по editHost: открытие листа на правку (или смена цели) пересоздаёт форму из профиля.
    val form = remember(editHost) { editHost?.let { NewConnectionFormState.fromHost(it) } ?: NewConnectionFormState() }
    val canSave = hosts == null || form.canSave
    // Гард повторного Save (двойной тап) до закрытия листа — иначе дубль секрета+хоста в vault (как desktop).
    // Ключ по editHost вместе с form: смена цели сбрасывает гард, а не залипает на прежней.
    var submitting by remember(editHost) { mutableStateOf(false) }
    // Незакоммиченный ввод тега (пилюля ещё не создана); Save дофиксирует его, чтобы не потерялся.
    var tagDraft by remember(editHost) { mutableStateOf("") }
    // Открыт ли диалог «New group» — держим на уровне листа, чтобы оверлей рисовался на корне (не в
    // скролле формы) и корректно поднимался над клавиатурой.
    var createGroupOpen by remember(editHost) { mutableStateOf(false) }
    val onSave = {
        if (submitting) {
            // повторное нажатие до закрытия — игнорируем
        } else if (hosts == null) {
            state.closeSheet() // мок/превью: сохранять некуда
        } else if (form.canSave) {
            submitting = true
            if (tagDraft.isNotBlank()) { form.addTag(tagDraft); tagDraft = "" }
            // Новый секрет создаём только при живом keychain (иначе он осел бы в vault без ссылки на хост);
            // ASK/мок-путь → credentialId = null. EXISTING-привязка возвращается как есть (не пересоздаём).
            // В режиме правки EXISTING-привязка возвращается как есть (секрет не пересоздаётся).
            val credentialId = form.resolveCredentialId(saveCredential = { draft -> credentials?.save(draft) })
            // editHost?.id != null → обновление существующего профиля по месту, иначе создание нового.
            hosts.save(form.toDraft(id = editHost?.id, credentialId = credentialId))
            // Секрет уже запечатан в vault — снимаем ссылки на него из state формы, сокращая окно
            // жизни ключа/пароля в куче (String на JVM не занулить на месте, но ссылку убираем).
            form.password = ""; form.privateKeyPem = ""; form.passphrase = ""
            state.closeSheet()
        }
    }

    // Панель фиксированной высоты (0.92 экрана), скроллится; общая обвязка листа — в MobileBottomSheet.
    MobileBottomSheet(
        onDismiss = state::closeSheet,
        panelModifier = Modifier
            .fillMaxHeight(0.92f)
            .verticalScroll(rememberScrollState())
            .padding(start = 22.dp, end = 22.dp, bottom = 30.dp),
    ) {
        Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Txt(if (editHost != null) stringResource(Res.string.conn_title_edit) else stringResource(Res.string.conn_title_new), color = D.text, size = 20.sp, weight = FontWeight.Bold)
                Sym(
                    "close",
                    size = 24.sp,
                    color = D.dim,
                    modifier = Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = state::closeSheet,
                    ),
                )
            }
            Txt(
                stringResource(Res.string.conn_subtitle_mobile),
                color = D.dim,
                size = 12.5.sp,
                lineHeight = 18.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 18.dp),
            )

            MobileFormField(stringResource(Res.string.conn_field_name)) { MobileFormInput(form.name, { form.name = it }, "prod-web-01") }
            Spacer(Modifier.height(14.dp))
            MobileFormField(stringResource(Res.string.conn_field_protocol)) { MobileProtocolPicker(form) }
            Spacer(Modifier.height(14.dp))
            val serial = form.connectionType == ConnectionType.SERIAL
            MobileFormField(if (serial) stringResource(Res.string.conn_field_device) else stringResource(Res.string.conn_field_host_address)) {
                MobileFormInput(form.address, { form.address = it }, if (serial) "/dev/ttyUSB0 or COM3" else "192.168.1.45")
            }
            // Пикер обнаруженных портов (Android — USB-OTG): тап заполняет Device. Пусто — только ручной ввод.
            if (serial) MobileSerialPortPicker(form)
            Spacer(Modifier.height(14.dp))
            if (form.connectionType == ConnectionType.SSH) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MobileFormField(stringResource(Res.string.conn_field_username), Modifier.weight(1f)) {
                        MobileFormInput(form.username, { form.username = it }, "root")
                    }
                    MobileFormField(stringResource(Res.string.conn_field_port), Modifier.width(84.dp)) {
                        MobileFormInput(form.port, { form.port = it }, "22", keyboardType = KeyboardType.Number)
                    }
                }
                Spacer(Modifier.height(14.dp))
                MobileFormField(stringResource(Res.string.conn_field_authentication)) { MobileAuthPicker(form) }
                Spacer(Modifier.height(14.dp))
            } else {
                // Telnet/Serial: аутентификации нет; показываем только порт/скорость.
                MobileFormField(if (serial) stringResource(Res.string.conn_field_baud) else stringResource(Res.string.conn_field_port), Modifier.width(120.dp)) {
                    MobileFormInput(form.port, { form.port = it }, if (serial) "9600" else "23", keyboardType = KeyboardType.Number)
                }
                Spacer(Modifier.height(14.dp))
            }
            // Подсказки группы — из уже созданных хостов (паритет desktop GroupPicker); в превью список пуст.
            MobileFormField(stringResource(Res.string.conn_field_group)) { MobileGroupPicker(form, hosts?.hosts ?: emptyList(), onCreateGroup = { createGroupOpen = true }) }
            Spacer(Modifier.height(14.dp))
            MobileFormField(stringResource(Res.string.conn_field_tags)) {
                // Подсказки — теги других хостов, ещё не добавленные сюда (паритет desktop Tags); в превью пусто.
                val allHosts = hosts?.hosts ?: emptyList()
                val suggestions = remember(allHosts, form.tags, tagDraft) { tagSuggestions(allHosts, form.tags, tagDraft) }
                MobileTagsEditor(
                    tags = form.tags,
                    onRemove = { form.removeTag(it) },
                    draft = tagDraft,
                    // Запятая фиксирует тег(и) сразу; одиночный тег — по Enter (onCommit).
                    onDraftChange = { v -> if (v.contains(',')) { form.addTag(v); tagDraft = "" } else tagDraft = v },
                    onCommit = { form.addTag(tagDraft); tagDraft = "" },
                    suggestions = suggestions,
                    placeholder = stringResource(Res.string.conn_tag_add_placeholder),
                    onPick = { tag -> form.addTag(tag); tagDraft = "" },
                    menuBackground = SheetPanel,
                )
            }

            if (LocalFeatures.current.ai || LocalAi.current != null) {
                Spacer(Modifier.height(14.dp))
                MobileFormField(stringResource(Res.string.conn_field_ai_policy_short)) { AiPolicyPills(form) }
            }

            Spacer(Modifier.height(22.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (canSave) D.cyan else D.cyan.copy(alpha = 0.4f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onSave)
                    .padding(15.dp),
                contentAlignment = Alignment.Center,
            ) {
                Txt(if (editHost != null) stringResource(Res.string.conn_save_changes) else stringResource(Res.string.conn_save_connection), color = D.ink, size = 16.sp, weight = FontWeight.Bold)
            }
    }
    // Оверлей «New group» — сиблингом над листом (свой полноэкранный скрим), чтобы корректно подниматься над клавиатурой.
    if (createGroupOpen) {
        MobileGroupCreateDialog(
            onDismiss = { createGroupOpen = false },
            onCreate = { name -> form.group = name.trim(); createGroupOpen = false },
        )
    }
}

/**
 * Сегментированный выбор транспорта (SSH / Telnet / Serial) на телефоне — паритет desktop
 * ProtocolPicker. Пишет тип через [NewConnectionFormState.chooseConnectionType] (подставляет
 * дефолтный порт/скорость) и перестраивает форму.
 */
@Composable
private fun MobileSerialPortPicker(form: NewConnectionFormState) {
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
                        .padding(horizontal = 10.dp, vertical = 7.dp),
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
private fun MobileProtocolPicker(form: NewConnectionFormState) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp)).background(D.bg).border(1.dp, D.cyan14, RoundedCornerShape(11.dp)).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        MobileProtocolSegment(stringResource(Res.string.conn_protocol_ssh), form.connectionType == ConnectionType.SSH, Modifier.weight(1f)) { form.chooseConnectionType(ConnectionType.SSH) }
        MobileProtocolSegment(stringResource(Res.string.conn_protocol_telnet), form.connectionType == ConnectionType.TELNET, Modifier.weight(1f)) { form.chooseConnectionType(ConnectionType.TELNET) }
        MobileProtocolSegment(stringResource(Res.string.conn_protocol_serial), form.connectionType == ConnectionType.SERIAL, Modifier.weight(1f)) { form.chooseConnectionType(ConnectionType.SERIAL) }
    }
}

@Composable
private fun MobileProtocolSegment(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) D.cyan10 else Color.Transparent)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Txt(label, color = if (selected) D.cyanBright else D.dim, size = 14.sp, weight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

/**
 * Выбор аутентификации хоста в стиле мобильного листа: селект-триггер раскрывает варианты — Ask
 * every time / новый пароль / новый ключ / уже сохранённые keychain-секреты из живого
 * [LocalCredentials] — плюс инлайн-поля под новый секрет. В мок-пути (без vault) остаются только
 * варианты без сохранения.
 */
@Composable
private fun MobileAuthPicker(form: NewConnectionFormState) {
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
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(11.dp))
                        .background(D.bg)
                        .border(1.dp, D.cyan14, RoundedCornerShape(11.dp))
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { menuOpen = !menuOpen }
                        .padding(horizontal = 14.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Txt(selectedLabel, color = D.text, size = 15.sp)
                    Sym(if (menuOpen) "expand_less" else "expand_more", size = 20.sp, color = D.faint)
                }
            },
            menu = { width ->
                // Настоящий dropdown поверх листа (Popup), а не раздвигание формы; ширина = триггеру, скролл при переполнении.
                Column(
                    Modifier
                        .width(width)
                        .clip(RoundedCornerShape(11.dp))
                        .background(SheetPanel)
                        .border(1.dp, D.cyan14, RoundedCornerShape(11.dp))
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 4.dp),
                ) {
                    MobileAuthOption("vpn_key_off", stringResource(Res.string.conn_auth_ask), stringResource(Res.string.conn_auth_ask_desc), form.authMode == AuthMode.ASK) {
                        form.authMode = AuthMode.ASK; menuOpen = false
                    }
                    MobileAuthOption("password", stringResource(Res.string.conn_auth_password_option), stringResource(Res.string.conn_auth_password_desc), form.authMode == AuthMode.NEW_PASSWORD) {
                        form.authMode = AuthMode.NEW_PASSWORD; menuOpen = false
                    }
                    MobileAuthOption("key", stringResource(Res.string.conn_auth_key_option), stringResource(Res.string.conn_auth_key_desc), form.authMode == AuthMode.NEW_KEY) {
                        form.authMode = AuthMode.NEW_KEY; menuOpen = false
                    }
                    // Разделитель перед сохранёнными секретами (паритет desktop AuthPicker).
                    if (saved.isNotEmpty()) {
                        HLine(modifier = Modifier.padding(vertical = 4.dp))
                        saved.forEach { cred ->
                            MobileAuthOption(cred.secret.pickerIcon(), cred.label, cred.secret.pickerTypeLabel(), form.authMode == AuthMode.EXISTING && form.existingCredentialId == cred.id) {
                                form.authMode = AuthMode.EXISTING; form.existingCredentialId = cred.id; menuOpen = false
                            }
                        }
                    }
                }
            },
        )
        when (form.authMode) {
            AuthMode.NEW_PASSWORD -> {
                Spacer(Modifier.height(12.dp))
                MobileFormInput(form.password, { form.password = it }, stringResource(Res.string.conn_auth_password_placeholder), masked = true)
            }
            AuthMode.NEW_KEY -> {
                Spacer(Modifier.height(12.dp))
                // keyboardType=Password гасит автокоррект/подсказки IME (Android), чтобы ключ не оседал в словаре.
                // PEM показываем в открытую (не masked): маскирование многострочного поля ломает вставку
                // ключа и его визуальную проверку — осознанный trade-off, как на desktop ModalTextField.
                MobileFormInput(form.privateKeyPem, { form.privateKeyPem = it }, "-----BEGIN OPENSSH PRIVATE KEY-----", keyboardType = KeyboardType.Password, singleLine = false, mono = true, minHeightDp = 104)
                Spacer(Modifier.height(12.dp))
                MobileFormInput(form.passphrase, { form.passphrase = it }, stringResource(Res.string.conn_auth_passphrase_placeholder), masked = true)
            }
            else -> {}
        }
    }
}

/**
 * Поле «Group» листа: выпадающий селект — пункт «No group», уже созданные группы каталога
 * ([groupSuggestions]) и «New group…», открывающий диалог создания. Выбранная группа хранится в
 * [NewConnectionFormState.group]; создание новой просто проставляет её имя (профиль заведёт папку
 * при сохранении). Свободного ввода в самом поле нет — только список + явное создание, чтобы не
 * плодить опечатки-дубли групп на телефоне.
 */
@Composable
private fun MobileGroupPicker(form: NewConnectionFormState, allHosts: List<Host>, onCreateGroup: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    val groups = remember(allHosts) { groupSuggestions(allHosts) }
    val hasGroup = form.group.isNotBlank()
    Column {
        AnchoredDropdown(
            expanded = menuOpen,
            onDismiss = { menuOpen = false },
            trigger = {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(11.dp))
                        .background(D.bg)
                        .border(1.dp, D.cyan14, RoundedCornerShape(11.dp))
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { menuOpen = !menuOpen }
                        .padding(horizontal = 14.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Txt(if (hasGroup) form.group else stringResource(Res.string.conn_group_none), color = if (hasGroup) D.text else D.faint, size = 15.sp)
                    Sym(if (menuOpen) "expand_less" else "expand_more", size = 20.sp, color = D.faint)
                }
            },
            menu = { width ->
                Column(
                    Modifier
                        .width(width)
                        .clip(RoundedCornerShape(11.dp))
                        .background(SheetPanel)
                        .border(1.dp, D.cyan14, RoundedCornerShape(11.dp))
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 4.dp),
                ) {
                    MobileGroupOption(stringResource(Res.string.conn_group_none), selected = !hasGroup) { form.group = ""; menuOpen = false }
                    groups.forEach { group ->
                        key(group) {
                            MobileGroupOption(group, selected = form.group == group) { form.group = group; menuOpen = false }
                        }
                    }
                    HLine(modifier = Modifier.padding(vertical = 4.dp))
                    MobileGroupOption(stringResource(Res.string.conn_group_new), selected = false, icon = "add") { menuOpen = false; onCreateGroup() }
                }
            },
        )
    }
}

/** Строка-вариант селекта группы: опц. иконка + название + галочка выбранного. */
@Composable
private fun MobileGroupOption(title: String, selected: Boolean, icon: String? = null, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) D.cyan10 else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        if (icon != null) Sym(icon, size = 18.sp, color = D.cyanBright)
        Txt(title, color = if (selected) D.cyanBright else D.text, size = 14.sp, weight = if (selected) FontWeight.Medium else FontWeight.Normal, modifier = Modifier.weight(1f))
        if (selected) Sym("check", size = 17.sp, color = D.cyanBright)
    }
}

/** Строка-вариант в дропдауне аутентификации: иконка + название + подпись + галочка выбранного. */
@Composable
private fun MobileAuthOption(icon: String, title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) D.cyan10 else Color.Transparent)
            // Без явного interactionSource (паритет desktop AuthOption): remember в forEach позиционен —
            // при смене порядка saved слот сдвинулся бы на чужую строку.
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Sym(icon, size = 18.sp, color = if (selected) D.cyanBright else D.dim)
        Column(Modifier.weight(1f)) {
            Txt(title, color = if (selected) D.cyanBright else D.text, size = 14.sp, weight = FontWeight.Medium)
            Txt(subtitle, color = D.faint, size = 11.sp)
        }
        if (selected) Sym("check", size = 17.sp, color = D.cyanBright)
    }
}

/** Пилюли AI-политики (все 4 значения [AiPolicy]) — выбор пишется в форму (Host.aiPolicy). */
@Composable
private fun AiPolicyPills(form: NewConnectionFormState) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        AiPolicy.entries.forEach { policy ->
            val on = form.aiPolicy == policy
            val onPick = remember(policy) { { form.aiPolicy = policy } }
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (on) D.cyan.copy(alpha = 0.1f) else Color.Transparent)
                    .border(1.dp, if (on) D.cyan else D.cyan.copy(alpha = 0.1f), RoundedCornerShape(10.dp))
                    .clickable(
                        interactionSource = remember(policy) { MutableInteractionSource() },
                        indication = null,
                        onClick = onPick,
                    )
                    .padding(vertical = 9.dp, horizontal = 2.dp),
                contentAlignment = Alignment.Center,
            ) {
                Txt(
                    policy.name,
                    color = if (on) D.cyanBright else D.dim,
                    size = 11.sp,
                    weight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}
