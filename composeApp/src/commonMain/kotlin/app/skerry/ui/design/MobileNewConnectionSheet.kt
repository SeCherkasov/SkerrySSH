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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.host.Host
import app.skerry.ui.host.AuthMode
import app.skerry.ui.host.NewConnectionFormState
import app.skerry.ui.nav.PlatformBackHandler
import app.skerry.ui.secure.SecureScreen


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
                Txt(if (editHost != null) "Edit connection" else "New connection", color = D.text, size = 20.sp, weight = FontWeight.Bold)
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
                "Credentials are encrypted with your master password and stay on this device.",
                color = D.dim,
                size = 12.5.sp,
                lineHeight = 18.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 18.dp),
            )

            SheetField("Name") { SheetInput(form.name, { form.name = it }, "prod-web-01") }
            Spacer(Modifier.height(14.dp))
            SheetField("Host address") { SheetInput(form.address, { form.address = it }, "192.168.1.45") }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SheetField("Username", Modifier.weight(1f)) {
                    SheetInput(form.username, { form.username = it }, "root")
                }
                SheetField("Port", Modifier.width(84.dp)) {
                    SheetInput(form.port, { form.port = it }, "22", keyboardType = KeyboardType.Number)
                }
            }
            Spacer(Modifier.height(14.dp))
            SheetField("Authentication") { MobileAuthPicker(form) }
            Spacer(Modifier.height(14.dp))
            // Подсказки группы — из уже созданных хостов (паритет desktop GroupPicker); в превью список пуст.
            SheetField("Group") { MobileGroupPicker(form, hosts?.hosts ?: emptyList(), onCreateGroup = { createGroupOpen = true }) }
            Spacer(Modifier.height(14.dp))
            SheetField("Tags") {
                SheetTags(
                    tags = form.tags,
                    onRemove = { form.removeTag(it) },
                    draft = tagDraft,
                    // Запятая фиксирует тег(и) сразу; одиночный тег — по Enter (onCommit).
                    onDraftChange = { v -> if (v.contains(',')) { form.addTag(v); tagDraft = "" } else tagDraft = v },
                    onCommit = { form.addTag(tagDraft); tagDraft = "" },
                    // Подсказки — теги других хостов, ещё не добавленные сюда (паритет desktop Tags); в превью пусто.
                    allHosts = hosts?.hosts ?: emptyList(),
                    onPick = { tag -> form.addTag(tag); tagDraft = "" },
                )
            }

            if (LocalFeatures.current.ai) {
                Spacer(Modifier.height(14.dp))
                SheetField("AI policy") { AiPolicyPills() }
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
                Txt(if (editHost != null) "Save changes" else "Save connection", color = Color(0xFF0A1A26), size = 16.sp, weight = FontWeight.Bold)
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

/** Подпись поля (капс) + содержимое. */
@Composable
private fun SheetField(label: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier) {
        Txt(
            label.uppercase(),
            color = D.faint,
            size = 10.5.sp,
            weight = FontWeight.SemiBold,
            letterSpacing = 0.6.sp,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        content()
    }
}

/**
 * Текстовое поле листа в стиле макета (тёмный фон + cyan-рамка, радиус 11).
 * [masked] — скрывать ввод (пароль/passphrase); [singleLine] = false + [mono] + [minHeightDp] —
 * многострочная моноширинная область для вставки приватного ключа (PEM), как desktop `ModalTextField`.
 */
@Composable
private fun SheetInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    masked: Boolean = false,
    singleLine: Boolean = true,
    mono: Boolean = false,
    minHeightDp: Int? = null,
) {
    val fonts = LocalFonts.current
    val family = if (mono) fonts.mono else fonts.ui
    val fontSize = if (mono) 12.5.sp else 15.sp
    val textStyle = remember(family, fontSize) {
        TextStyle(color = D.text, fontSize = fontSize, fontFamily = family, lineHeight = if (mono) 17.sp else 20.sp)
    }
    // Рамка/паддинг — в decorationBox, чтобы клик по всей площади поля ставил каретку.
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
            Box(
                Modifier
                    .fillMaxWidth()
                    .then(if (minHeightDp != null) Modifier.heightIn(min = minHeightDp.dp) else Modifier)
                    .clip(RoundedCornerShape(11.dp))
                    .background(D.bg)
                    .border(1.dp, D.cyan14, RoundedCornerShape(11.dp))
                    .padding(horizontal = 14.dp, vertical = 13.dp),
            ) {
                if (value.isEmpty()) Txt(placeholder, color = D.faint, size = fontSize, font = if (mono) fonts.mono else null)
                inner()
            }
        },
    )
}

/**
 * Редактор тегов: пилюли `#tag` с крестиком + инлайн-ввод нового тега (Enter/запятая фиксирует
 * пилюлю). [tags] — каноническая форма, на экране с префиксом `#`. При фокусе поля под ним
 * раскрывается type-ahead-список [tagSuggestions] (теги других [allHosts], ещё не добавленные сюда,
 * сужённые черновиком); тап по подсказке вызывает [onPick]. Меню через [AnchoredDropdown] с
 * `focusable = false`, чтобы не отнимать фокус и не прерывать набор.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SheetTags(
    tags: List<String>,
    onRemove: (String) -> Unit,
    draft: String,
    onDraftChange: (String) -> Unit,
    onCommit: () -> Unit,
    allHosts: List<Host>,
    onPick: (String) -> Unit,
) {
    val fonts = LocalFonts.current
    val textStyle = remember(fonts.ui) { TextStyle(color = D.text, fontSize = 14.sp, fontFamily = fonts.ui) }
    var focused by remember { mutableStateOf(false) }
    val suggestions = remember(allHosts, tags, draft) { tagSuggestions(allHosts, tags, draft) }
    AnchoredDropdown(
        expanded = focused && suggestions.isNotEmpty(),
        onDismiss = { focused = false },
        focusable = false, // не красть фокус у поля ввода тега
        trigger = {
            FlowRow(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp)).background(D.bg).border(1.dp, D.cyan14, RoundedCornerShape(11.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                tags.forEach { tag ->
                    key(tag) {
                        Row(
                            Modifier.clip(RoundedCornerShape(20.dp)).background(D.cyan.copy(alpha = 0.12f)).padding(start = 10.dp, end = 5.dp, top = 3.dp, bottom = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Txt("#$tag", color = D.cyanBright, size = 12.5.sp)
                            Box(
                                Modifier.clip(CircleShape).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onRemove(tag) }.padding(2.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Sym("close", size = 14.sp, color = D.cyanBright)
                            }
                        }
                    }
                }
                BasicTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    singleLine = true,
                    textStyle = textStyle,
                    cursorBrush = SolidColor(D.cyan),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onCommit() }),
                    modifier = Modifier.widthIn(min = 90.dp).onFocusChanged { focused = it.isFocused },
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (draft.isEmpty()) Txt("add tag…", color = D.faint, size = 14.sp)
                            inner()
                        }
                    },
                )
            }
        },
        menu = { width ->
            Column(
                Modifier
                    .width(width)
                    .clip(RoundedCornerShape(11.dp))
                    .background(SheetPanel)
                    .border(1.dp, D.cyan14, RoundedCornerShape(11.dp))
                    .heightIn(max = 240.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 4.dp),
            ) {
                // Тап добавляет тег; фокус остаётся на поле — меню пересчитается без только что добавленного.
                suggestions.forEach { tag ->
                    key(tag) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onPick(tag) }
                                .padding(horizontal = 14.dp, vertical = 11.dp),
                        ) {
                            Txt("#$tag", color = D.cyanBright, size = 14.sp)
                        }
                    }
                }
            }
        },
    )
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
                    MobileAuthOption("vpn_key_off", "Ask every time", "password requested on connect", form.authMode == AuthMode.ASK) {
                        form.authMode = AuthMode.ASK; menuOpen = false
                    }
                    MobileAuthOption("password", "Password…", "store a password in your vault", form.authMode == AuthMode.NEW_PASSWORD) {
                        form.authMode = AuthMode.NEW_PASSWORD; menuOpen = false
                    }
                    MobileAuthOption("key", "Paste private key…", "store an SSH key in your vault", form.authMode == AuthMode.NEW_KEY) {
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
                SheetInput(form.password, { form.password = it }, "password to store", masked = true)
            }
            AuthMode.NEW_KEY -> {
                Spacer(Modifier.height(12.dp))
                // keyboardType=Password гасит автокоррект/подсказки IME (Android), чтобы ключ не оседал в словаре.
                // PEM показываем в открытую (не masked): маскирование многострочного поля ломает вставку
                // ключа и его визуальную проверку — осознанный trade-off, как на desktop ModalTextField.
                SheetInput(form.privateKeyPem, { form.privateKeyPem = it }, "-----BEGIN OPENSSH PRIVATE KEY-----", keyboardType = KeyboardType.Password, singleLine = false, mono = true, minHeightDp = 104)
                Spacer(Modifier.height(12.dp))
                SheetInput(form.passphrase, { form.passphrase = it }, "key passphrase (optional)", masked = true)
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
                    Txt(if (hasGroup) form.group else "No group", color = if (hasGroup) D.text else D.faint, size = 15.sp)
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
                    MobileGroupOption("No group", selected = !hasGroup) { form.group = ""; menuOpen = false }
                    groups.forEach { group ->
                        key(group) {
                            MobileGroupOption(group, selected = form.group == group) { form.group = group; menuOpen = false }
                        }
                    }
                    HLine(modifier = Modifier.padding(vertical = 4.dp))
                    MobileGroupOption("New group…", selected = false, icon = "add") { menuOpen = false; onCreateGroup() }
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

/**
 * Маленький модальный диалог создания новой группы — полноэкранный оверлей на корне листа (не в
 * скролле формы), центрируется над клавиатурой через [imePadding]: поле имени + Cancel/Create.
 * Пустое имя не создаёт (кнопка неактивна). Имя только проставляется в форму — папка появится в
 * каталоге при сохранении хоста.
 */
@Composable
private fun MobileGroupCreateDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    val canCreate = name.isNotBlank()
    val submit = { if (canCreate) onCreate(name) }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xB304080C))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
            .imePadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(horizontal = 32.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(SheetPanel)
                .border(1.dp, D.cyan14, RoundedCornerShape(18.dp))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
                .padding(20.dp),
        ) {
            Txt("New group", color = D.text, size = 18.sp, weight = FontWeight.Bold)
            Spacer(Modifier.height(14.dp))
            SheetInput(name, { name = it }, "Production")
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .border(1.dp, D.cyan14, RoundedCornerShape(12.dp))
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
                        .padding(vertical = 13.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Txt("Cancel", color = D.dim, size = 15.sp, weight = FontWeight.Medium)
                }
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (canCreate) D.cyan else D.cyan.copy(alpha = 0.4f))
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = submit)
                        .padding(vertical = 13.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Txt("Create", color = Color(0xFF0A1A26), size = 15.sp, weight = FontWeight.Bold)
                }
            }
        }
    }
}

/**
 * Диалог «Rename group» (карандаш у заголовка папки) — полноэкранный оверлей на корне над
 * клавиатурой ([imePadding]). Поле имени предзаполнено [initialName]; «Save» переименовывает
 * (хосты переезжают с группой), «Delete group» — разгруппировывает (профили целы).
 * Пустое/неизменное имя оставляет «Save» неактивной.
 */
@Composable
internal fun MobileGroupRenameDialog(
    initialName: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onDelete: () -> Unit,
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    val canSave = name.isNotBlank() && name.trim() != initialName
    // Триммим здесь, чтобы и контроллер (Host.group), и синхронизация collapsedGroups получили
    // одинаковый канонический ключ — иначе свёрнутость папки разъедется на хвостовом пробеле.
    val submit = { if (canSave) onSave(name.trim()) }
    // Системный «назад»/жест закрывает диалог (как тап по затемнению), перехватывая back до навигации каркаса.
    PlatformBackHandler(onBack = onDismiss)
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xB304080C))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
            .imePadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(horizontal = 32.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(SheetPanel)
                .border(1.dp, D.cyan14, RoundedCornerShape(18.dp))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
                .padding(20.dp),
        ) {
            Txt("Rename group", color = D.text, size = 18.sp, weight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Txt("Hosts in this group move with the new name.", color = D.dim, size = 12.5.sp)
            Spacer(Modifier.height(14.dp))
            SheetInput(name, { name = it }, "Production")
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDelete)
                        .padding(horizontal = 14.dp, vertical = 13.dp),
                ) {
                    Txt("Delete group", color = D.sunset, size = 15.sp, weight = FontWeight.Medium)
                }
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (canSave) D.cyan else D.cyan.copy(alpha = 0.4f))
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = submit)
                        .padding(horizontal = 18.dp, vertical = 13.dp),
                ) {
                    Txt("Save", color = Color(0xFF0A1A26), size = 15.sp, weight = FontWeight.Bold)
                }
            }
        }
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

/** Пилюли AI-политики (Strict/Balanced/Off) — визуальный выбор за фича-флагом. */
@Composable
private fun AiPolicyPills() {
    var selected by remember { mutableStateOf("Strict") }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("Strict", "Balanced", "Off").forEach { name ->
            val on = name == selected
            val onPick = remember(name) { { selected = name } }
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (on) D.cyan.copy(alpha = 0.1f) else Color.Transparent)
                    .border(1.dp, if (on) D.cyan else D.cyan.copy(alpha = 0.1f), RoundedCornerShape(10.dp))
                    .clickable(
                        interactionSource = remember(name) { MutableInteractionSource() },
                        indication = null,
                        onClick = onPick,
                    )
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Txt(
                    name,
                    color = if (on) D.cyanBright else D.dim,
                    size = 12.5.sp,
                    weight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}
